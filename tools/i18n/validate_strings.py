#!/usr/bin/env python3
"""Validate Android string resources and the locales.json catalogue.

Owns (docs/internationalization.md 0d / 4c / 4d, docs/i18n-phase4a-seed-translations.md):
  - locales.json schema: required fields, type checks, id/tag/qualifier uniqueness, qualifier
    validity, directory/qualifier correspondence, pickerVisible ⇒ packaged, stored coverage
    rejection, exact Tier 1 membership.
  - placeholder positional-index parity of every translation against the source (strings, plurals,
    string-array items — all of them), correctly reading placeholders wrapped in ``<xliff:g>``.
    Recognises full printf format specifiers including flags/width/precision (``%1$.2f``).
  - XML escaping checked on the RAW XML source (not ElementTree-decoded text) so valid entities
    like ``&amp;`` and ``&lt;`` are not false-positive'd.
  - duplicate keys (strings, plurals, arrays — all detected BEFORE overwrite).
  - non-translatable leakage into translation files, and translatable="false" placement: a
    false-marked string inside strings.xml (not donottranslate.xml) is rejected; every source
    donottranslate.xml entry must be false-marked and its keys cannot collide with strings*.xml.
  - empty translations for packaged locales.
  - translation-only keys (keys in a translation file that don't exist in source, including leaked
    donottranslate keys).
  - ``<plurals>`` validity, mandatory ``other``, per-locale CLDR plural-quantity completeness, and
    placeholder parity for EVERY translation quantity (including locale-specific forms like Arabic
    zero/two/few/many that don't exist in the source). Source English must carry its own required
    quantities (one, other).

English in ``values/`` is the only language OwnTV guarantees complete. A missing localized key is
valid Android fallback behaviour: it is reported (see ``--report``) but never fails the build. A
localized value that *is* present must still be structurally safe — invalid XML, broken
placeholders, invalid plurals, empty values and similar defects fail regardless of coverage, because
those entries override the English fallback rather than deferring to it.

Coverage is **computed** here, never read from a stored field, and uses the same suffixed
string/plurals/array key sets as ``gen_supported_locales.py`` so the CI report and the picker badge
can never disagree.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"
COMMUNITY_CONFIG = ROOT / "tools" / "i18n" / "community.json"
# All translatable resources live in :core, so one app shell or another can be built on the same
# strings. :app keeps only colours and themes, which have nothing to validate.
RES = ROOT / "core" / "src" / "main" / "res"

# --- resource parsing ---------------------------------------------------------

# Formatter parsing is finite and isolated from XML/catalogue policy.
try:
    from tools.i18n.format_specs import (
        has_bare_placeholder as _has_bare,
        invalid_format_snippets as _invalid_format_snippets,
        placeholders as _placeholders,
        strip_valid_formats as _strip_valid_formats,
    )
except ModuleNotFoundError:  # Direct invocation from tools/i18n rather than repository root.
    from format_specs import (
        has_bare_placeholder as _has_bare,
        invalid_format_snippets as _invalid_format_snippets,
        placeholders as _placeholders,
        strip_valid_formats as _strip_valid_formats,
    )
# Valid XML entity: &amp; &lt; &#123; &#x1F; ...
_ENTITY = re.compile(r"&(?:[a-zA-Z]+|#x?[0-9]+);")
# xliff:g child tags inside string bodies (the only child element allowed in Android string resources).
_XLIFF_TAG = re.compile(r"</?xliff:g[^>]*>")
# Raw element body extractor: <string ...>BODY</string> or <item ...>BODY</item>, non-greedy, DOTALL.
_RAW_BODY = re.compile(r"<(string|item)\b([^>]*)>(.*?)</\1>", re.DOTALL)


def _flatten_text(el: ET.Element) -> str:
    """Decoded text of a <string>/<item> for placeholder and coverage checks (entities resolved)."""
    raw = "".join(el.itertext())
    raw = _XLIFF_TAG.sub("", raw)
    return raw


def _parse_dir(directory: Path) -> tuple[dict[str, dict], list[str]]:
    """Return (entries, errors) for a res dir.

    entries maps a SUFFIXED key → {"text":..., "plurals": {qty: text}, "array": [...],
    "translatable": bool, "kind": "string"|"plurals"|"array"}.
    Suffixes: ``""`` for <string>, ``#`` for <plurals>, ``[]`` for <string-array>.
    Duplicates are reported in [errors] rather than silently overwriting.
    """
    out: dict[str, dict] = {}
    errs: list[str] = []
    if not directory.is_dir():
        return out, errs
    for f in sorted(directory.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            errs.append(f"{directory.name}/{f.name}: XML parse error: {e}")
            continue
        for el in root:
            name = el.get("name")
            if not name:
                continue
            if el.tag == "string":
                key = name
            elif el.tag == "plurals":
                key = name + "#"
            elif el.tag == "string-array":
                key = name + "[]"
            else:
                continue
            if key in out:
                errs.append(f"{directory.name}/{f.name}: duplicate key '{name}' ({el.tag})")
                continue  # keep first definition; do NOT overwrite
            if el.tag == "string":
                out[key] = {"text": _flatten_text(el), "translatable": el.get("translatable") != "false",
                            "kind": "string"}
            elif el.tag == "plurals":
                qtys: dict[str, str] = {}
                dup_q: set[str] = set()
                for item in el.findall("item"):
                    q = item.get("quantity", "")
                    if q in qtys:
                        dup_q.add(q)
                        continue
                    qtys[q] = _flatten_text(item)
                for q in dup_q:
                    errs.append(f"{directory.name}/{f.name}: plural '{name}' duplicate quantity '{q}'")
                out[key] = {
                    "plurals": qtys,
                    "kind": "plurals",
                    "translatable": el.get("translatable") != "false",
                }
            elif el.tag == "string-array":
                out[key] = {
                    "array": [_flatten_text(it) for it in el.findall("item")],
                    "kind": "array",
                    "translatable": el.get("translatable") != "false",
                }
    return out, errs


def _parse_donottranslate_file(file_path: Path, tag: str) -> tuple[dict[str, dict], list[str]]:
    """Parse the source constants file and require every entry to be explicitly non-translatable.

    ``donottranslate.xml`` is intentionally outside the normal source map: its keys must never be
    covered or exported. It still needs structural validation, and its key namespace must not collide
    with any ``strings*.xml`` source key.
    """
    out: dict[str, dict] = {}
    errs: list[str] = []
    if not file_path.is_file():
        return out, errs
    try:
        root = ET.parse(file_path).getroot()
    except ET.ParseError as e:
        return out, [f"{tag} {file_path.name}: XML parse error: {e}"]
    for el in root:
        name = el.get("name")
        if not name:
            errs.append(f"{tag} {file_path.name}: every resource entry needs a name")
            continue
        if el.tag == "string":
            key = name
        elif el.tag == "plurals":
            key = name + "#"
        elif el.tag == "string-array":
            key = name + "[]"
        else:
            errs.append(f"{tag} {file_path.name}: unsupported resource element <{el.tag}>")
            continue
        if key in out:
            errs.append(f"{tag} {file_path.name}: duplicate key '{name}' ({el.tag})")
            continue
        if el.get("translatable") != "false":
            errs.append(
                f"{tag} {file_path.name}: '{name}' must declare translatable=\"false\""
            )
        out[key] = {"kind": el.tag, "translatable": el.get("translatable") == "false"}
    return out, errs


# --- escaping (checked on RAW XML, not decoded text) --------------------------

def _check_escaping(file_path: Path) -> list[str]:
    """Check raw XML bodies for unescaped characters that ElementTree would decode and hide.

    ElementTree's ``itertext()`` resolves ``&amp;``→``&`` and ``&lt;``→``<`` before any check can
    run, so a valid ``Fish &amp; Chips &lt;3`` would look like ``Fish & Chips <3`` and be wrongly
    rejected. This function reads the raw file text and checks the body *between the tags*, where
    entities are still encoded. ``&`` and ``<`` as bare characters in the raw body would have caused
    an XML parse error (caught by ET.parse above), so they are not re-checked here; the remaining
    Android-specific escapes are apostrophe (``'``→``\\'``) and percent (``%``→``%%`` or a format spec).
    """
    errs: list[str] = []
    text = file_path.read_text(encoding="utf-8")
    for m in _RAW_BODY.finditer(text):
        tag = m.group(1)
        attrs = m.group(2)
        body = m.group(3)
        name_m = re.search(r'name="([^"]*)"', attrs)
        name = name_m.group(1) if name_m else "?"
        qty_m = re.search(r'quantity="([^"]*)"', attrs)
        label = f"{name}/{qty_m.group(1)}" if qty_m else name
        # Strip xliff:g child tags (valid markup inside string bodies).
        b = _XLIFF_TAG.sub("", body)
        # Strip valid XML entities so their & is not flagged.
        b = _ENTITY.sub("", b)
        # Strip only semantically valid Java/Android format specifiers (including %%). A broad
        # regex would accept things such as %1$#s, %1$.2d or %1$0tY even though Formatter rejects
        # them at runtime.
        invalid_formats = _invalid_format_snippets(b)
        b = _strip_valid_formats(b)
        # Strip escaped apostrophes and quotes.
        b = b.replace("\\'", "").replace('\\"', "")
        # Android allows a bare apostrophe if the ENTIRE string is wrapped in double quotes
        # ("This'll work") — the outer quotes are part of the value and escape the inner '.
        # ElementTree strips the outer quotes, so check the raw body: if it starts and ends with
        # ", the inner apostrophes are valid. Strip the quotes and remove inner ' before checking.
        if len(body) >= 2 and body[0] == '"' and body[-1] == '"':
            b = b.replace("'", "")
        # Any remaining ', % is an unescaped character that will break the Android build.
        bad = []
        if "'" in b:
            bad.append("unescaped apostrophe (use \\' or &apos;)")
        if "%" in b:
            bad.append("unescaped percent (use %% or a format specifier)")
        if invalid_formats:
            bad.append("invalid Java/Android format placeholder(s): " + ", ".join(repr(s) for s in invalid_formats[:3]))
        if bad:
            errs.append(f"{file_path.parent.name}/{file_path.name} {label}: {'; '.join(bad)} in {body.strip()[:60]!r}")
    return errs


def _check_translatable_false_placement(file_path: Path) -> list[str]:
    """Reject translatable=\"false\" on a string outside ``donottranslate.xml``.

    Parse XML attributes instead of matching one quote style in raw text: XML permits both single
    and double quotes, and attribute order is immaterial. ``donottranslate.xml`` is never passed to
    this function, so the only accepted home for a false-marked string is that file.
    """
    errs: list[str] = []
    try:
        root = ET.parse(file_path).getroot()
    except ET.ParseError:
        # The normal parser reports the XML error; avoid duplicating it here.
        return errs
    for el in root:
        if el.tag not in {"string", "plurals", "string-array"} or el.get("translatable") != "false":
            continue
        name = el.get("name", "?")
        errs.append(
            f"{file_path.parent.name}/{file_path.name}: translatable='false' on '{name}' must be "
            f"in donottranslate.xml, not in a translatable strings file")
    return errs


def _is_metadata_spacing_key(name: str) -> bool:
    """Allow whitespace only where it is an intentional presentation separator/metadata token."""
    return name.endswith("_separator") or name.endswith("_metadata_year")


def _fragment_spacing_error(name: str, text: str, location: str) -> str | None:
    if text and text != text.strip() and not _is_metadata_spacing_key(name):
        return f"{location}: leading/trailing whitespace makes a sentence fragment; use a full template"
    return None


def _check_donottranslate_file(file_path: Path, tag: str) -> list[str]:
    """Reject a localized ``donottranslate.xml`` and report its leaked keys explicitly."""
    if not file_path.is_file():
        return []
    errs: list[str] = []
    try:
        root = ET.parse(file_path).getroot()
    except ET.ParseError as e:
        return [f"{tag} {file_path.name}: XML parse error: {e}"]
    for el in root:
        name = el.get("name")
        if name:
            errs.append(f"{tag}: donottranslate.xml leaked key '{name}' into a translation directory")
    return errs


# --- plural rules -------------------------------------------------------------

# CLDR plural rules per locale. "other" is mandatory for every <plurals>; the rest are the
# quantities the locale's ICU rule actually selects. Updated to current CLDR: Czech includes
# decimal `many`; French, Italian, Portuguese and Spanish select "one"/"many"/"other" (the
# "many" form is used for large round numbers in fr/it/pt/es per CLDR 42+).
_PLURAL_RULES = {
    "en": ["one", "other"], "en-US": ["one", "other"], "en-GB": ["one", "other"],
    "ar": ["zero", "one", "two", "few", "many", "other"],
    "cs": ["one", "few", "many", "other"], "da": ["one", "other"], "nl": ["one", "other"],
    "fr": ["one", "many", "other"], "de": ["one", "other"], "it": ["one", "many", "other"],
    "ja": ["other"], "ko": ["other"], "nb": ["one", "other"], "sv": ["one", "other"],
    "pl": ["one", "few", "many", "other"], "ru": ["one", "few", "many", "other"],
    "pt": ["one", "many", "other"], "pt-BR": ["one", "many", "other"], "pt-PT": ["one", "many", "other"],
    "zh-CN": ["other"], "zh-TW": ["other"], "es-US": ["one", "many", "other"], "es-ES": ["one", "many", "other"],
    "tr": ["one", "other"], "ml": ["one", "other"], "hi": ["one", "other"],
    "bn": ["one", "other"],
    "bg": ["one", "other"], "hr": ["one", "few", "other"],
    "et": ["one", "other"], "fa": ["one", "other"], "fi": ["one", "other"],
    "el": ["one", "other"], "he": ["one", "two", "many", "other"],
    "hu": ["one", "other"], "id": ["other"], "lv": ["zero", "one", "other"],
    "lt": ["one", "few", "many", "other"], "ms": ["other"],
    "ro": ["one", "few", "other"], "sr": ["one", "few", "other"],
    "sk": ["one", "few", "many", "other"], "sl": ["one", "two", "few", "other"],
    "th": ["other"], "uk": ["one", "few", "many", "other"], "vi": ["other"],
    "eu": ["one", "other"],
}


# --- locales.json catalogue validation ----------------------------------------

_REQUIRED_FIELDS = {"id", "languageTag", "resourceQualifier", "resourceDirectory", "weblateCode",
                    "englishName", "endonym", "script", "rtl", "tier", "packaged", "pickerVisible"}
_CATALOGUE_STRING_FIELDS = ("id", "languageTag", "resourceQualifier", "resourceDirectory", "weblateCode",
                            "englishName", "endonym", "script")
_VALID_TIERS = (0, 1, 2)
_RUNTIME_TAG_RE = re.compile(r"^[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?$")
_SCRIPT_RE = re.compile(r"^[A-Z][a-z]{3}$")
_WEBLATE_RE = re.compile(r"^[a-z]{2,3}(?:_(?:[A-Z][a-z]{3}|[A-Z]{2}|[0-9]{3}))?$")
_DIRECTORY_RE = re.compile(r"^values(?:-[A-Za-z0-9+_-]+)*$")

# Exact set of Tier 1 language tags the catalogue must contain (docs/internationalization.md 4d).
_EXPECTED_TIER1_TAGS = {
    "en-US", "ar", "pt-BR", "pt-PT", "zh-CN", "zh-TW", "cs", "da", "nl", "fr", "de", "it",
    "ja", "ko", "nb", "pl", "ru", "es-US", "es-ES", "sv", "tr", "ml", "hi", "bn",
}

_EXPECTED_CATALOGUE_ONLY_TAGS = {
    "bg", "hr", "et", "fa", "fi", "el", "he", "hu", "id", "lv", "lt", "ms", "ro", "sr",
    "sk", "sl", "th", "uk", "vi", "eu",
}

# Valid Android resource qualifier forms for locales:
#   xx              — language only (en, de, ar)
#   xx-rYY          — language + region (en-rGB, pt-rPT)
#   b+xx+Script     — the Android b+ form with a script subtag (b+sr+Latn)
#   b+xx+419        — the Android b+ form with a UN M.49 numeric region (b+es+419)
# Script-qualified resources MUST use b+ syntax: aapt2 rejects the tempting ``sr-Latn`` folder.
# Bare b+xx and b+xx+YY forms are intentionally rejected; the catalogue uses the canonical plain
# language/xx-rYY forms for languages/regions and b+ only where Android requires script/numeric form.
# Script subtags are 4 letters, title case (Latn, Hans, Hant, Cyrl); numeric regions are three digits.
_QUAL_RE = re.compile(
    r"^(?:[a-z]{2,3}(?:-r[A-Z]{2})?"
    r"|b\+[a-z]{2,3}\+(?:[A-Z][a-z]{3}|[0-9]{3}))$"
)

# Canonical Weblate code mappings — pinned so a typo (pt_BR where pt_PT was meant, or default es
# swapped for es_419) is caught at catalogue-validation time. Every entry in the catalogue is pinned here so
# a non-matching code (e.g. German's weblateCode changed from 'de' to 'fr') is always caught, not just
# the selected special cases. The key is the resourceQualifier; the value is the required weblateCode.
_CANONICAL_WEBLATE = {
    "en": "en", "en-rGB": "en_GB",
    "ar": "ar",
    "pt": "pt_BR", "pt-rPT": "pt_PT",
    "zh-rCN": "zh_Hans", "zh-rTW": "zh_Hant",
    "es": "es", "es-rUS": "es_419",
    "nb": "nb_NO",
    "bg": "bg", "hr": "hr", "et": "et", "fa": "fa", "fi": "fi", "el": "el",
    "iw": "he", "hu": "hu", "in": "id", "lv": "lv", "lt": "lt", "ms": "ms",
    "ro": "ro", "sr": "sr", "sk": "sk", "sl": "sl", "th": "th", "uk": "uk", "vi": "vi", "eu": "eu",
    "cs": "cs", "da": "da", "nl": "nl", "fr": "fr", "de": "de", "it": "it",
    "ja": "ja", "ko": "ko", "pl": "pl", "ru": "ru", "sv": "sv", "tr": "tr", "ml": "ml",
    "hi": "hi", "bn": "bn",
}


def _validate_catalogue(data: list) -> list[str]:
    fails: list[str] = []
    if not isinstance(data, list):
        return ["locales.json root must be an array of locale objects"]
    ids: set[str] = set()
    tags: set[str] = set()
    qualifiers: set[str] = set()
    dirs: set[str] = set()
    tier1_tags: set[str] = set()
    for index, e in enumerate(data):
        if not isinstance(e, dict):
            fails.append(f"locales.json entry {index}: must be an object")
            continue
        eid = e.get("id", "?")
        missing = _REQUIRED_FIELDS - e.keys()
        if missing:
            fails.append(f"locales.json entry {eid}: missing fields: {sorted(missing)}")
        # Schema string fields are required, typed and non-blank. Checking this before regexes keeps
        # malformed JSON from becoming a Python TypeError in the validator itself.
        for fld in _CATALOGUE_STRING_FIELDS:
            value = e.get(fld)
            if not isinstance(value, str) or not value.strip():
                fails.append(f"locales.json {eid}: {fld} must be a non-blank string")

        if isinstance(eid, str) and eid.strip():
            if eid in ids:
                fails.append(f"locales.json duplicate id: {eid}")
            ids.add(eid)
        tag = e.get("languageTag") if isinstance(e.get("languageTag"), str) else ""
        if tag:
            if not _RUNTIME_TAG_RE.fullmatch(tag):
                fails.append(f"locales.json {eid}: invalid languageTag '{tag}'")
            if tag in tags:
                fails.append(f"locales.json duplicate languageTag: {tag}")
            tags.add(tag)
        q = e.get("resourceQualifier") if isinstance(e.get("resourceQualifier"), str) else ""
        if q:
            if not _QUAL_RE.fullmatch(q):
                fails.append(f"locales.json {eid}: invalid resourceQualifier '{q}'")
            if q in qualifiers:
                fails.append(f"locales.json duplicate resourceQualifier '{q}'")
            qualifiers.add(q)
        d = e.get("resourceDirectory") if isinstance(e.get("resourceDirectory"), str) else ""
        if d:
            if not _DIRECTORY_RE.fullmatch(d):
                fails.append(f"locales.json {eid}: invalid resourceDirectory '{d}'")
            if d in dirs:
                fails.append(f"locales.json duplicate resourceDirectory '{d}'")
            dirs.add(d)
            # Directory/qualifier correspondence: values-<qualifier> (or values for the en source).
            expected = "values" if q == "en" else "values-" + q
            if d != expected:
                fails.append(f"locales.json {eid}: resourceDirectory '{d}' should be '{expected}'")

        # Stored coverage is forbidden — coverage is always computed.
        if "coverage" in e:
            fails.append(f"locales.json {eid}: 'coverage' field must not be stored (it is computed)")
        rtl = e.get("rtl")
        packaged = e.get("packaged")
        picker_visible = e.get("pickerVisible")
        for fld, value in (("rtl", rtl), ("packaged", packaged), ("pickerVisible", picker_visible)):
            if type(value) is not bool:
                fails.append(f"locales.json {eid}: {fld} must be boolean")
        if picker_visible is True and packaged is not True:
            fails.append(f"locales.json {eid}: pickerVisible=true requires packaged=true")
        tier = e.get("tier")
        valid_tier = type(tier) is int and tier in _VALID_TIERS
        if not valid_tier:
            fails.append(f"locales.json {eid}: tier must be one of {_VALID_TIERS} (got {tier!r})")
        script = e.get("script") if isinstance(e.get("script"), str) else ""
        if script and not _SCRIPT_RE.fullmatch(script):
            fails.append(f"locales.json {eid}: invalid script '{script}'")
        wc = e.get("weblateCode") if isinstance(e.get("weblateCode"), str) else ""
        if wc and not _WEBLATE_RE.fullmatch(wc):
            fails.append(f"locales.json {eid}: invalid weblateCode '{wc}'")
        # Canonical mapping: for every committed qualifier with a pinned weblateCode, the stored
        # value must match exactly — a swapped code pairs the wrong Weblate component.
        expected_wc = _CANONICAL_WEBLATE.get(q)
        if expected_wc is not None and wc != expected_wc:
            fails.append(f"locales.json {eid}: weblateCode '{wc}' should be '{expected_wc}' for qualifier '{q}'")
        if valid_tier and tier == 1 and tag:
            tier1_tags.add(tag)

    # The established release set is mandatory. Catalogue-only additions may later move from tier 2
    # to tier 1 without adding a parallel code mapping here; the catalogue remains authoritative.
    missing_tier1 = _EXPECTED_TIER1_TAGS - tier1_tags
    unexpected_tier1 = tier1_tags - _EXPECTED_TIER1_TAGS - _EXPECTED_CATALOGUE_ONLY_TAGS
    if missing_tier1:
        fails.append(f"locales.json: missing Tier 1 languages: {sorted(missing_tier1)}")
    if unexpected_tier1:
        fails.append(f"locales.json: unexpected Tier 1 languages: {sorted(unexpected_tier1)}")
    requested_tags = {
        e.get("languageTag") for e in data
        if isinstance(e, dict) and e.get("languageTag") in _EXPECTED_CATALOGUE_ONLY_TAGS
    }
    if requested_tags != _EXPECTED_CATALOGUE_ONLY_TAGS:
        fails.append(
            "locales.json: requested community catalogue mismatch: "
            f"missing={sorted(_EXPECTED_CATALOGUE_ONLY_TAGS - requested_tags)}"
        )
    for e in data:
        if not isinstance(e, dict) or e.get("tier") != 2:
            continue
        if e.get("packaged") is not False or e.get("pickerVisible") is not False:
            fails.append(
                f"locales.json {e.get('id', '?')}: catalogue-only tier 2 locales must remain "
                "packaged=false and pickerVisible=false until promoted to tier 1"
            )

    return fails


def _validate_source_entries(src: dict[str, dict]) -> list[str]:
    """Validate source keys independently so translation directories are still checked when empty."""
    fails: list[str] = []
    for key, payload in src.items():
        if payload.get("translatable") is False:
            continue
        name = key.rstrip("#[]")
        kind = payload["kind"]
        if kind == "plurals":
            qtys = set(payload["plurals"].keys())
            if "other" not in qtys:
                fails.append(f"source plural {name}: mandatory `other` quantity missing")
            # Source English must carry the quantities English's own CLDR rule requires (one, other).
            for q in _PLURAL_RULES.get("en", []):
                if q not in qtys:
                    fails.append(f"source plural {name}: missing required English quantity `{q}`")
            for q, text in payload["plurals"].items():
                if _has_bare(text):
                    fails.append(f"source plural {name}/{q}: bare placeholder (use %1$s etc.): {text!r}")
                if text.strip() == "":
                    fails.append(f"source plural {name}/{q}: empty string")
                spacing = _fragment_spacing_error(name, text, f"source plural {name}/{q}")
                if spacing:
                    fails.append(spacing)
        elif kind == "array":
            for i, text in enumerate(payload["array"]):
                if _has_bare(text):
                    fails.append(f"source array {name}[{i}]: bare placeholder (use %1$s etc.): {text!r}")
                if text.strip() == "":
                    fails.append(f"source array {name}[{i}]: empty string")
                spacing = _fragment_spacing_error(name, text, f"source array {name}[{i}]")
                if spacing:
                    fails.append(spacing)
        elif kind == "string":
            text = payload["text"]
            if _has_bare(text):
                fails.append(f"source {name}: bare placeholder (use %1$s etc.): {text!r}")
            if payload.get("translatable", True) and text.strip() == "":
                fails.append(f"source {name}: empty translatable string")
            spacing = _fragment_spacing_error(name, text, f"source {name}")
            if spacing:
                fails.append(spacing)
    return fails


# --- main ---------------------------------------------------------------------

def _format_text_report(source_keys: int, coverage_rows: list[dict]) -> str:
    """Deterministic, catalogue-ordered coverage report (docs/i18n-phase4a-seed-translations.md)."""
    lines = ["Translation coverage:"]
    for row in coverage_rows:
        pct = f"{row['coveragePercent']:.1f}%"
        lines.append(
            "  " + row["languageTag"].ljust(9)
            + f"{row['translatedKeys']:>4}"
            + f" / {source_keys} "
            + f"{pct:>7}"
            + f"{row['missingKeys']:>6} missing"
        )
    return "\n".join(lines)


def main(report: str = "text") -> int:
    """Validate resources; structural errors always gate, coverage is informational only.

    ``report`` selects the informational coverage report format: ``text`` (default), ``json``, or
    ``none``. In ``json`` mode stdout carries only the JSON document; every diagnostic goes to
    stderr instead, so a caller can pipe stdout straight into a JSON parser even when the process
    exits non-zero.
    """
    diagnostics = sys.stderr if report == "json" else sys.stdout
    fails: list[str] = []

    # --- locales.json ----------------------------------------------------------
    if not LOCALES_JSON.is_file():
        print("error: tools/i18n/locales.json missing", file=diagnostics)
        return 1
    try:
        data = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        print(f"error: locales.json is not valid JSON: {e}", file=diagnostics)
        return 1
    fails.extend(_validate_catalogue(data))
    if not isinstance(data, list):
        data = []  # Keep reporting schema errors instead of crashing while walking malformed input.

    # --- source English --------------------------------------------------------
    source_values = RES / "values"
    src, src_errs = _parse_dir(source_values)
    fails.extend(src_errs)
    source_constants, constant_errs = _parse_donottranslate_file(
        source_values / "donottranslate.xml", "source")
    fails.extend(constant_errs)
    # Constants have a separate namespace and are never translatable. A duplicate between the
    # constants file and a strings*.xml file is still a source-key collision and must fail.
    for key in sorted(set(src) & set(source_constants)):
        fails.append(
            f"source: duplicate key '{key.rstrip('#[]')}' appears in donottranslate.xml and strings*.xml"
        )
    # Escaping checks run on raw XML for every source resource file, including donottranslate.xml.
    for f in sorted(source_values.glob("strings*.xml")):
        if f.name == "donottranslate.xml":
            continue
        fails.extend(_check_escaping(f))
        # translatable="false" must live in donottranslate.xml, not in strings*.xml. A false-marked
        # string in strings.xml will be picked up by Weblate as a translatable key and pollutes the
        # translation component. Check the raw XML for the attribute on <string> elements.
        fails.extend(_check_translatable_false_placement(f))
    if (source_values / "donottranslate.xml").is_file():
        fails.extend(_check_escaping(source_values / "donottranslate.xml"))

    fails.extend(_validate_source_entries(src))

    # --- per-locale ------------------------------------------------------------
    # Coverage denominator: the same suffixed translatable source key set gen_supported_locales.py
    # intersects against, so the CI report and the picker badge can never disagree.
    source_translatable_keys = {k for k, v in src.items() if v.get("translatable", True)}
    coverage_rows: list[dict] = []
    try:
        community_config = json.loads(COMMUNITY_CONFIG.read_text(encoding="utf-8"))
        threshold = community_config["translationReadinessThresholdPercent"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        print(f"error: invalid tools/i18n/community.json: {error}", file=diagnostics)
        return 1
    if type(threshold) is not int:
        print("error: translationReadinessThresholdPercent must be an integer", file=diagnostics)
        return 1

    for e in data:
        if not isinstance(e, dict):
            continue
        resdir = e.get("resourceDirectory")
        tag = e.get("languageTag")
        if not isinstance(resdir, str) or not isinstance(tag, str) or resdir == "values":
            continue
        loc_dir = RES / resdir
        if e.get("tier") == 2 and loc_dir.exists():
            fails.append(
                f"{tag}: catalogue-only locale must not have a resource directory or seed files "
                f"({resdir})"
            )
        loc_keys, loc_errs = _parse_dir(loc_dir)
        fails.extend(loc_errs)
        for f in sorted(loc_dir.glob("strings*.xml")):
            if f.name == "donottranslate.xml":
                continue
            fails.extend(_check_escaping(f))
            fails.extend(_check_translatable_false_placement(f))
        # A localized donottranslate.xml is never a valid translation component. Report its keys
        # instead of silently skipping the file as _parse_dir does for the source constants file.
        fails.extend(_check_donottranslate_file(loc_dir / "donottranslate.xml", tag))
        rule = _PLURAL_RULES.get(tag)
        is_packaged = e.get("packaged") is True
        is_tier1 = type(e.get("tier")) is int and e.get("tier") == 1

        # Translation-only keys: keys in the translation that don't exist in source (including leaked
        # donottranslate keys). Every translation key must have a source counterpart.
        for lkey in loc_keys:
            if lkey not in src:
                lname = lkey.rstrip("#[]")
                fails.append(f"{tag}: translation-only key '{lname}' has no source counterpart")

        translated = 0
        for skey, psrc in src.items():
            # Skip source keys marked translatable="false" — they must NOT appear in translations.
            if not psrc.get("translatable", True):
                continue
            ploc = loc_keys.get(skey)
            if ploc is None:
                # Missing is valid Android fallback behaviour: informational only, never a failure.
                continue
            # translatable="false" must NOT leak into translation files. gen_supported_locales.py's
            # _string_keys excludes such entries from its locale key set entirely, so a leaked entry
            # must not count as translated here either — otherwise the two tools' coverage numerators
            # would disagree on this (already-failing) case.
            if ploc.get("translatable") is False:
                fails.append(f"{tag} {skey.rstrip('#[]')}: translatable='false' must not appear in a translation file")
            else:
                translated += 1
            if psrc["kind"] == "plurals":
                qloc = set(ploc.get("plurals", {}).keys())
                if "other" not in qloc:
                    fails.append(f"{tag} plural {skey.rstrip('#[]')}: mandatory `other` missing")
                if rule:
                    for q in rule:
                        if q not in qloc:
                            fails.append(f"{tag} plural {skey.rstrip('#[]')}: missing required quantity {q}")
                # Placeholder parity for EVERY quantity in the translation. For a quantity that
                # exists in the source, compare against the source placeholders for THAT quantity.
                # For a locale-specific quantity (Arabic zero/two/few/many) not in the source, compare
                # against the source `other` quantity. A quantity present with ZERO placeholders must
                # match a source quantity that also has zero — the `or` fallback previously conflated
                # "quantity absent" (empty list) with "quantity present with no placeholders".
                for q, ltext in ploc.get("plurals", {}).items():
                    if q in psrc["plurals"]:
                        sp = _placeholders(psrc["plurals"][q])
                    else:
                        sp = _placeholders(psrc["plurals"].get("other", ""))
                    lp = _placeholders(ltext)
                    if sorted(sp) != sorted(lp):
                        fails.append(f"{tag} plural {skey.rstrip('#[]')}/{q}: placeholder mismatch src {sp} vs loc {lp}")
                    # Translations must not introduce bare placeholders (translators must use
                    # positional so reordering is possible). Source already forbids them; a
                    # translation adding %s where the source has none is a translator mistake.
                    if _has_bare(ltext):
                        fails.append(f"{tag} plural {skey.rstrip('#[]')}/{q}: bare placeholder in translation (use %1$s etc.): {ltext!r}")
                    if ltext.strip() == "":
                        fails.append(f"{tag} plural {skey.rstrip('#[]')}/{q}: empty translation")
                    spacing = _fragment_spacing_error(skey.rstrip("#[]"), ltext, f"{tag} plural {skey.rstrip('#[]')}/{q}")
                    if spacing:
                        fails.append(spacing)
            elif psrc["kind"] == "array":
                sarr = psrc["array"]
                larr = ploc.get("array", [])
                if len(sarr) != len(larr):
                    fails.append(f"{tag} array {skey.rstrip('[]')}: length mismatch src {len(sarr)} vs loc {len(larr)}")
                else:
                    for i, stext in enumerate(sarr):
                        ltext = larr[i]
                        sp = _placeholders(stext)
                        lp = _placeholders(ltext)
                        if sorted(sp) != sorted(lp):
                            fails.append(f"{tag} array {skey.rstrip('[]')}[{i}]: placeholder mismatch src {sp} vs loc {lp}")
                        if _has_bare(ltext):
                            fails.append(f"{tag} array {skey.rstrip('[]')}[{i}]: bare placeholder in translation (use %1$s etc.): {ltext!r}")
                        if ltext.strip() == "":
                            fails.append(f"{tag} array {skey.rstrip('[]')}[{i}]: empty translation")
                        spacing = _fragment_spacing_error(skey.rstrip("[]"), ltext, f"{tag} array {skey.rstrip('[]')}[{i}]")
                        if spacing:
                            fails.append(spacing)
            elif psrc["kind"] == "string":
                stext = psrc["text"]
                ltext = ploc.get("text", "")
                sp = _placeholders(stext)
                lp = _placeholders(ltext)
                if sorted(sp) != sorted(lp):
                    fails.append(f"{tag} {skey}: placeholder mismatch src {sp} vs loc {lp}")
                # Translations must not introduce bare placeholders. Source already forbids them;
                # a translation adding %s where the source has none is a translator mistake.
                if _has_bare(ltext):
                    fails.append(f"{tag} {skey}: bare placeholder in translation (use %1$s etc.): {ltext!r}")
                spacing = _fragment_spacing_error(skey, ltext, f"{tag} {skey}")
                if spacing:
                    fails.append(spacing)
                if ltext.strip() == "":
                    if is_packaged:
                        fails.append(f"{tag} {skey}: empty translation for a packaged locale")
                    else:
                        fails.append(f"{tag} {skey}: empty translation")

        if tag != "en-GB":
            total = len(source_translatable_keys)
            missing = total - translated
            percent = round(100 * translated / total, 1) if total else 0.0
            coverage_rows.append({
                "languageTag": tag,
                "translatedKeys": translated,
                "missingKeys": missing,
                "coveragePercent": percent,
            })
            if (is_packaged or e.get("pickerVisible") is True) and percent < threshold:
                fails.append(
                    f"{tag}: {percent:.1f}% is below the {threshold}% translation readiness "
                    "threshold; keep packaged=false and pickerVisible=false"
                )

    if fails:
        print("i18n validation FAILED:", file=diagnostics)
        for f in fails[:200]:
            print("  " + f, file=diagnostics)
        if len(fails) > 200:
            print(f"  ... and {len(fails) - 200} more", file=diagnostics)
    elif not src:
        print("i18n validate: no translatable source keys yet (empty split files); catalogue OK.",
              file=diagnostics)
    else:
        print("i18n validation OK", file=diagnostics)

    if report == "text":
        print(_format_text_report(len(source_translatable_keys), coverage_rows), file=diagnostics)
    elif report == "json":
        payload = {
            "schemaVersion": 1,
            "sourceKeys": len(source_translatable_keys),
            "locales": coverage_rows,
        }
        print(json.dumps(payload, indent=2), file=sys.stdout)

    return 1 if fails else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", choices=["text", "json", "none"], default="text",
                        help="informational coverage report format (default: text)")
    raise SystemExit(main(report=parser.parse_args().report))
