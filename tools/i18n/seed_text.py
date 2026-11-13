#!/usr/bin/env python3
"""Stdlib-only text pipeline for the community-translation seed.

Owns (docs/i18n-phase4a-seed-translations.md, Part 2):
  - extracting the six ``values/strings*.xml`` source files into ordered, per-file units
  - tokenizing ``<xliff:g>`` elements into opaque, collision-checked placeholders and
    detokenizing them back byte-exact, with strict multiset parity
  - decoding source escape syntax (XML entities, ``\\uXXXX``, Android backslash escapes,
    ``%%``) before any text is sent to a translation model
  - escaping model-authored text back into valid Android XML, in the documented order
  - emitting Android resource XML by hand, preserving source key order
  - offline validation of a staged locale against the current source tree, stricter than
    ``validate_strings.py``'s CI report because a missing key here is a failure
  - atomic, same-filesystem promotion of a staged locale into ``core/src/main/res``

This module must not import ``anthropic`` or perform any network I/O. It is exercised by
the offline tests in ``test_i18n_tools.py`` in environments where the SDK is not
installed, and by ``seed_translations.py`` for everything except the actual API calls.
"""
from __future__ import annotations

import hashlib
import re
import shutil
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "core" / "src" / "main" / "res"

# --- source extraction ---------------------------------------------------------

_ELEMENT_RE = re.compile(
    r'(?:<!--\s*Translators:\s*(?P<comment>.*?)\s*-->\s*)?'
    r'<(?P<tag>string|plurals)\s+(?P<attrs>[^>]*)>(?P<body>.*?)</(?P=tag)>',
    re.DOTALL,
)
_ITEM_RE = re.compile(r'<item\s+quantity="(?P<q>[^"]*)"[^>]*>(?P<body>.*?)</item>', re.DOTALL)
_NAME_RE = re.compile(r'name="([^"]*)"')
_TRANSLATABLE_FALSE_RE = re.compile(r'translatable\s*=\s*["\']false["\']')

# Keys carrying an intentional source whitespace envelope that the model's own output
# must never supply (docs/i18n-phase4a-seed-translations.md, "Escape and emit").
_SPACING_SUFFIXES = ("_separator", "_metadata_year")


def is_spacing_key(name: str) -> bool:
    return name.endswith(_SPACING_SUFFIXES)


@dataclass(frozen=True)
class StringSource:
    filename: str
    order: int
    key: str
    comment: str | None
    text: str  # decoded, still carries opaque xliff tokens
    tokens: tuple[str, ...]  # original byte-exact <xliff:g> XML, indexed by token id
    envelope: tuple[str, str] | None  # (leading, trailing) whitespace for spacing keys


@dataclass(frozen=True)
class PluralSource:
    filename: str
    order: int
    key: str
    comment: str | None
    quantities: dict[str, str]  # quantity -> decoded text with opaque tokens
    tokens: dict[str, tuple[str, ...]]  # quantity -> original xliff XML


class SeedTextError(Exception):
    """Base class for pipeline failures that must stop a seed run before it writes."""


class TokenCollisionError(SeedTextError):
    pass


class TokenParityError(SeedTextError):
    pass


class SeedValidationError(SeedTextError):
    def __init__(self, summary: str, errors: list[str]):
        super().__init__(summary)
        self.errors = errors


# --- tokenize / detokenize -----------------------------------------------------

_XLIFF_RE = re.compile(r"<xliff:g\b[^>]*>.*?</xliff:g>", re.DOTALL)
# A Unicode Private Use Area character brackets each token. Real translator or model
# text should never contain it; if it does, tokenize() raises rather than trusting it.
_TOKEN_MARKER = ""
_TOKEN_RE = re.compile(_TOKEN_MARKER + r"XLF(\d+)" + _TOKEN_MARKER)


def tokenize(raw: str) -> tuple[str, list[str]]:
    """Replace every ``<xliff:g>`` element with an opaque token.

    Returns (tokenized_text, originals) where originals[i] is the exact source XML of
    the i-th xliff element in document order. Raises if the token marker already
    appears in the source text (a naturally colliding token must never be trusted).
    """
    if _TOKEN_MARKER in raw:
        raise TokenCollisionError("source text already contains the token marker byte")
    originals: list[str] = []

    def _replace(m: re.Match) -> str:
        originals.append(m.group(0))
        return f"{_TOKEN_MARKER}XLF{len(originals) - 1}{_TOKEN_MARKER}"

    tokenized = _XLIFF_RE.sub(_replace, raw)
    return tokenized, originals


def detokenize(text: str, originals: list[str]) -> str:
    """Substitute each token with its original byte-exact xliff XML."""

    def _replace(m: re.Match) -> str:
        return originals[int(m.group(1))]

    return _TOKEN_RE.sub(_replace, text)


def check_token_parity(text: str, originals: list[str]) -> None:
    """Require the exact token multiset: reordering is fine, loss or duplication is not."""
    found = [int(m.group(1)) for m in _TOKEN_RE.finditer(text)]
    expected = list(range(len(originals)))
    if sorted(found) == sorted(expected):
        return
    missing = sorted(set(expected) - set(found))
    extra = sorted(set(found) - set(expected))
    duplicated = sorted({i for i in found if found.count(i) > 1})
    unknown = sorted(i for i in extra if i < 0 or i >= len(originals))
    raise TokenParityError(
        f"token parity failed: missing={missing} extra={extra} "
        f"duplicated={duplicated} unknown={unknown}"
    )


# --- decode source escape syntax ------------------------------------------------

_ENTITY_RE = re.compile(r"&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);")
_NAMED_ENTITIES = {"amp": "&", "lt": "<", "gt": ">", "apos": "'", "quot": '"'}
_HEX4_RE = re.compile(r"[0-9A-Fa-f]{4}")


def decode_xml_entities(s: str) -> str:
    def _repl(m: re.Match) -> str:
        body = m.group(1)
        if body[:2] in ("#x", "#X"):
            return chr(int(body[2:], 16))
        if body.startswith("#"):
            return chr(int(body[1:]))
        return _NAMED_ENTITIES.get(body, m.group(0))

    return _ENTITY_RE.sub(_repl, s)


def decode_source_text(s: str) -> str:
    """Decode \\uXXXX, Android backslash escapes (\\' \\" \\n \\t \\\\), and %% -> %.

    Single left-to-right pass so an escaped backslash followed by a literal "u1234"
    is not mistaken for a \\uXXXX escape.
    """
    out: list[str] = []
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c == "\\" and i + 1 < n:
            nxt = s[i + 1]
            if nxt == "u" and i + 6 <= n and _HEX4_RE.match(s[i + 2:i + 6]):
                out.append(chr(int(s[i + 2:i + 6], 16)))
                i += 6
                continue
            simple = {"'": "'", '"': '"', "n": "\n", "t": "\t", "\\": "\\"}
            if nxt in simple:
                out.append(simple[nxt])
                i += 2
                continue
        if c == "%" and i + 1 < n and s[i + 1] == "%":
            out.append("%")
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def decode_for_prompt(raw_body: str) -> tuple[str, list[str]]:
    """Full decode pipeline for one raw XML body: tokenize xliff, then decode escapes."""
    tokenized, originals = tokenize(raw_body)
    decoded = decode_source_text(decode_xml_entities(tokenized))
    return decoded, originals


# --- extract the source tree ----------------------------------------------------

def extract_source(res_dir: Path = RES / "values") -> tuple[dict[str, object], list[str]]:
    """Parse the six source strings*.xml files (skip donottranslate.xml and
    translatable=false entries). Returns (units_by_suffixed_key, ordered_keys)."""
    units: dict[str, object] = {}
    order: list[str] = []
    idx = 0
    for f in sorted(res_dir.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        text = f.read_text(encoding="utf-8")
        for m in _ELEMENT_RE.finditer(text):
            attrs = m.group("attrs")
            if _TRANSLATABLE_FALSE_RE.search(attrs):
                continue
            name_m = _NAME_RE.search(attrs)
            if not name_m:
                continue
            name = name_m.group(1)
            comment = (m.group("comment") or "").strip() or None
            body = m.group("body")
            if m.group("tag") == "string":
                decoded, originals = decode_for_prompt(body)
                envelope = None
                if is_spacing_key(name):
                    core = decoded.strip()
                    lead = decoded[: len(decoded) - len(decoded.lstrip())]
                    trail = decoded[len(decoded.rstrip()):] if decoded.rstrip() else decoded
                    envelope = (lead, trail)
                key = name
                units[key] = StringSource(f.name, idx, name, comment, decoded, tuple(originals), envelope)
            else:
                qtys: dict[str, str] = {}
                qtok: dict[str, tuple[str, ...]] = {}
                for im in _ITEM_RE.finditer(body):
                    decoded, originals = decode_for_prompt(im.group("body"))
                    qtys[im.group("q")] = decoded
                    qtok[im.group("q")] = tuple(originals)
                key = name + "#"
                units[key] = PluralSource(f.name, idx, name, comment, qtys, qtok)
            order.append(key)
            idx += 1
    return units, order


def chunk_by_file(units: dict[str, object], order: list[str], max_per_chunk: int = 40) -> dict[str, list[list[str]]]:
    """Group ordered keys by owning filename, then split each file into <= max_per_chunk
    chunks, deterministically (source order)."""
    by_file: dict[str, list[str]] = {}
    for key in order:
        by_file.setdefault(units[key].filename, []).append(key)
    return {
        fname: [keys[i:i + max_per_chunk] for i in range(0, len(keys), max_per_chunk)]
        for fname, keys in by_file.items()
    }


def custom_id(locale: str, filename: str, seq: int) -> str:
    stem = filename.rsplit(".", 1)[0]
    return f"{locale}__{stem}__{seq:03d}"


def glossary_custom_id(locale: str) -> str:
    return f"{locale}__glossary"


def plural_source_text_for_quantity(source: PluralSource, quantity: str) -> tuple[str, tuple[str, ...]]:
    """Locale-specific quantities absent from English translate from English `other`
    (docs/i18n-phase4a-seed-translations.md, "Extract and tokenize")."""
    if quantity in source.quantities:
        return source.quantities[quantity], source.tokens[quantity]
    return source.quantities["other"], source.tokens["other"]


# --- escape and emit -------------------------------------------------------------

_CONTROL_CHAR_RE = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F]")


def escape_for_emit(core: str) -> str:
    """Escape model-authored text into a valid Android XML resource body.

    Order matters: injecting xliff tokens happens after this (by the caller), which is
    what keeps a %1$d inside a preserved xliff element from becoming %%1$d.
    """
    if _CONTROL_CHAR_RE.search(core):
        raise SeedTextError("model output contains an XML-illegal control character")
    out = core.replace("\\", "\\\\")
    out = out.replace("&", "&amp;").replace("<", "&lt;")
    out = out.replace("'", "\\'").replace('"', '\\"')
    out = out.replace("%", "%%")
    out = out.replace("\n", "\\n").replace("\t", "\\t")
    if out.startswith("@"):
        out = "\\@" + out[1:]
    elif out.startswith("?"):
        out = "\\?" + out[1:]
    return out


def finalize_translation(model_text: str, originals: list[str], name: str,
                          source_envelope: tuple[str, str] | None = None) -> str:
    """Full model-output -> Android-XML-body pipeline for one key/quantity.

    Validates token parity, trims the model's own whitespace, escapes, detokenizes
    (restoring the exact source xliff XML), and restores the source's own whitespace
    envelope for `_separator` / `_metadata_year` keys instead of the model's.
    """
    check_token_parity(model_text, originals)
    core = model_text.strip()
    escaped = escape_for_emit(core)
    detok = detokenize(escaped, originals)
    if is_spacing_key(name) and source_envelope is not None:
        lead, trail = source_envelope
        detok = lead + detok + trail
    return detok


# --- emit Android XML by hand ------------------------------------------------------

_XML_HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">'
)


def emit_locale_file(entries: list[tuple[str, str, object]]) -> str:
    """entries: ordered (kind, name, payload) triples in source order. payload is the
    finished Android-XML body (str) for "string", or a quantity->body dict for "plurals".
    """
    lines = [_XML_HEADER]
    for kind, name, payload in entries:
        if kind == "string":
            lines.append(f'    <string name="{name}">{payload}</string>')
        else:
            lines.append(f'    <plurals name="{name}">')
            for q in ("zero", "one", "two", "few", "many", "other"):
                if q in payload:
                    lines.append(f'        <item quantity="{q}">{payload[q]}</item>')
            lines.append('    </plurals>')
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def _keys_in_file(path: Path) -> set[str]:
    keys: set[str] = set()
    root = ET.parse(path).getroot()
    for el in root:
        name = el.get("name")
        if not name:
            continue
        if el.tag == "string":
            keys.add(name)
        elif el.tag == "plurals":
            keys.add(name + "#")
    return keys


def resource_directory_hash(path: Path) -> str:
    """Bind a missing-only run to the exact localized resource snapshot it extends."""
    if not path.is_dir():
        raise FileNotFoundError(f"localized resource directory does not exist: {path}")
    digest = hashlib.sha256()
    for child in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        digest.update(child.relative_to(path).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(child.read_bytes())
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def append_locale_entries(path: Path, entries: list[tuple[str, str, object]]) -> None:
    """Append validated missing entries without rewriting existing translator-authored XML."""
    if not entries:
        return
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(emit_locale_file(entries), encoding="utf-8")
        return

    existing_keys = _keys_in_file(path)
    duplicate_keys = [name + ("#" if kind == "plurals" else "")
                      for kind, name, _ in entries
                      if name + ("#" if kind == "plurals" else "") in existing_keys]
    if duplicate_keys:
        raise SeedTextError(f"refusing to append existing key(s) to {path}: {duplicate_keys[:5]}")

    rendered_lines = emit_locale_file(entries).splitlines()
    body = "\n".join(rendered_lines[2:-1])
    current = path.read_text(encoding="utf-8")
    closing = "</resources>"
    closing_at = current.rfind(closing)
    if closing_at < 0:
        raise SeedTextError(f"{path}: missing </resources> closing tag")
    updated = current[:closing_at].rstrip() + "\n" + body + "\n" + current[closing_at:]
    path.write_text(updated, encoding="utf-8")


# --- offline validation ------------------------------------------------------------

def validate_staged_locale(locale_tag: str, staged_dir: Path, source_dir: Path = RES / "values") -> list[str]:
    """Offline, stricter-than-CI validation of one staged locale.

    Reuses validate_strings.py's structural checks (the single source of truth for what
    makes a present localized value valid) and adds the seed's completeness requirement:
    every translatable source key must be present, not merely valid-if-present.
    """
    try:
        from tools.i18n import validate_strings as vs
    except ModuleNotFoundError:
        import validate_strings as vs  # direct invocation from tools/i18n

    errors: list[str] = []
    source_filenames = {f.name for f in source_dir.glob("strings*.xml") if f.name != "donottranslate.xml"}
    staged_filenames = {f.name for f in staged_dir.glob("*.xml")} if staged_dir.is_dir() else set()
    if staged_filenames != source_filenames:
        errors.append(f"{locale_tag}: filename set {sorted(staged_filenames)} != source {sorted(source_filenames)}")
        return errors

    if (staged_dir / "donottranslate.xml").is_file():
        errors.append(f"{locale_tag}: staged donottranslate.xml is never valid")

    for fname in sorted(source_filenames):
        src_keys = _keys_in_file(source_dir / fname)
        loc_keys = _keys_in_file(staged_dir / fname)
        missing = sorted(src_keys - loc_keys)
        extra = sorted(loc_keys - src_keys)
        if missing:
            errors.append(f"{locale_tag} {fname}: missing key(s) {missing[:5]}")
        if extra:
            errors.append(f"{locale_tag} {fname}: key(s) owned by another file {extra[:5]}")

    src, src_errs = vs._parse_dir(source_dir)
    errors.extend(f"{locale_tag} source: {e}" for e in src_errs)
    loc, loc_errs = vs._parse_dir(staged_dir)
    errors.extend(loc_errs)
    for f in sorted(staged_dir.glob("strings*.xml")):
        errors.extend(vs._check_escaping(f))
        errors.extend(vs._check_translatable_false_placement(f))

    rule = vs._PLURAL_RULES.get(locale_tag)
    valid_quantities = {"zero", "one", "two", "few", "many", "other"}
    for skey, psrc in src.items():
        if not psrc.get("translatable", True):
            continue
        ploc = loc.get(skey)
        if ploc is None:
            errors.append(f"{locale_tag} {skey.rstrip('#[]')}: missing (seed requires complete coverage)")
            continue
        if ploc.get("translatable") is False:
            errors.append(f"{locale_tag} {skey.rstrip('#[]')}: translatable='false' must not appear")
        if psrc["kind"] == "plurals":
            qloc = set(ploc.get("plurals", {}).keys())
            if "other" not in qloc:
                errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}: mandatory 'other' missing")
            if rule:
                for q in rule:
                    if q not in qloc:
                        errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}: missing required quantity {q}")
            for q in qloc - valid_quantities:
                errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}: invalid quantity name '{q}'")
            for q, ltext in ploc.get("plurals", {}).items():
                sp = vs._placeholders(psrc["plurals"][q]) if q in psrc["plurals"] else vs._placeholders(psrc["plurals"].get("other", ""))
                lp = vs._placeholders(ltext)
                if sorted(sp) != sorted(lp):
                    errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}/{q}: placeholder mismatch src {sp} vs loc {lp}")
                if vs._has_bare(ltext):
                    errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}/{q}: bare placeholder")
                if ltext.strip() == "":
                    errors.append(f"{locale_tag} plural {skey.rstrip('#[]')}/{q}: empty translation")
                spacing = vs._fragment_spacing_error(skey.rstrip("#[]"), ltext, f"{locale_tag} plural {skey.rstrip('#[]')}/{q}")
                if spacing:
                    errors.append(spacing)
        elif psrc["kind"] == "string":
            stext = psrc["text"]
            ltext = ploc.get("text", "")
            sp = vs._placeholders(stext)
            lp = vs._placeholders(ltext)
            if sorted(sp) != sorted(lp):
                errors.append(f"{locale_tag} {skey}: placeholder mismatch src {sp} vs loc {lp}")
            if vs._has_bare(ltext):
                errors.append(f"{locale_tag} {skey}: bare placeholder")
            if ltext.strip() == "":
                errors.append(f"{locale_tag} {skey}: empty translation")
            spacing = vs._fragment_spacing_error(skey, ltext, f"{locale_tag} {skey}")
            if spacing:
                errors.append(spacing)

    for lkey in loc:
        if lkey not in src:
            errors.append(f"{locale_tag}: translation-only key '{lkey.rstrip('#[]')}' has no source counterpart")

    return errors


def promote_locale(locale_tag: str, staged_dir: Path, final_dir: Path,
                    source_dir: Path = RES / "values", *, force: bool = False,
                    replace_existing: bool = False,
                    expected_existing_hash: str | None = None) -> None:
    """Validate a staged locale and promote it with a same-filesystem directory swap.

    A failed validation leaves the staged and final directories untouched. Missing-only updates
    additionally bind replacement to the exact existing-directory hash captured at preparation.
    """
    errors = validate_staged_locale(locale_tag, staged_dir, source_dir)
    if errors:
        raise SeedValidationError(f"{locale_tag}: {len(errors)} validation error(s)", errors)
    if final_dir.exists() and replace_existing:
        if not expected_existing_hash:
            raise FileExistsError("replace_existing requires expected_existing_hash")
        actual_hash = resource_directory_hash(final_dir)
        if actual_hash != expected_existing_hash:
            raise FileExistsError(
                f"{final_dir} changed since missing-only preparation; expected "
                f"{expected_existing_hash}, found {actual_hash}"
            )
        backup = final_dir.with_name(f".{final_dir.name}.seed-backup")
        if backup.exists():
            raise FileExistsError(
                f"recovery backup already exists at {backup}; inspect it before retrying"
            )
        final_dir.rename(backup)
        try:
            staged_dir.rename(final_dir)
        except BaseException:
            backup.rename(final_dir)
            raise
        shutil.rmtree(backup)
        return
    if final_dir.exists():
        if not force:
            raise FileExistsError(
                f"{final_dir} already exists; pass force=True only to intentionally recover a run"
            )
        shutil.rmtree(final_dir)
    final_dir.parent.mkdir(parents=True, exist_ok=True)
    staged_dir.rename(final_dir)


# --- offline CLI: check already-generated locale files against current source -----

LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"


def _resource_directory_for(tag: str) -> str | None:
    """Look up the Android resource directory for a runtime tag from locales.json.

    Never assume `values-<languageTag>` — several locales diverge (pt-BR -> values-pt,
    zh-CN -> values-zh-rCN, es-US -> values-es-rUS, ...).
    """
    import json
    data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    for entry in data:
        if entry.get("languageTag") == tag:
            return entry.get("resourceDirectory")
    return None


def cmd_check(locales: list[str]) -> int:
    source_keys = set(extract_source()[1])
    rc = 0
    for tag in locales:
        resdir = _resource_directory_for(tag)
        if resdir is None:
            print(f"{tag}: not found in tools/i18n/locales.json")
            rc = 1
            continue
        loc_dir = RES / resdir
        if not loc_dir.is_dir():
            print(f"{tag}: no resource directory at {loc_dir}")
            rc = 1
            continue
        errors = validate_staged_locale(tag, loc_dir)
        if errors:
            print(f"{tag}: FAILED ({len(errors)} error(s))")
            for e in errors[:20]:
                print("  " + e)
            rc = 1
        else:
            print(f"{tag}: OK ({len(source_keys)} keys)")
    return rc


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    check_parser = sub.add_parser("check", help="offline-validate already-generated locale dirs")
    check_parser.add_argument("--locales", required=True, help="comma-separated language tags")
    args = parser.parse_args()
    if args.cmd == "check":
        return cmd_check([t.strip() for t in args.locales.split(",") if t.strip()])
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
