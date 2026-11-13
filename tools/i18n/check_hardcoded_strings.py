#!/usr/bin/env python3
"""Occurrence-aware Kotlin string inventory for OwnTV's i18n ratchet.

This tool deliberately does not guess whether text is SQL, JSON, a log message, or UI copy. It has
one mechanical job: extract every Kotlin string literal and compare that inventory with two reviewed
files:

* ``hardcoded_baseline.txt`` — existing translation debt; may only shrink against the merge base.
* ``safe_literals.txt`` — explicit technical literals, each carrying a category with a documented
  reason. Policy changes are data diffs rather than new parser heuristics.

Usage:
    python3 tools/i18n/check_hardcoded_strings.py generate
    python3 tools/i18n/check_hardcoded_strings.py verify --base /tmp/base-baseline.txt
    python3 tools/i18n/check_hardcoded_strings.py classify-safe \
        --path app/src/main/java/example.kt --text TAG --category log
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Every Gradle module whose Kotlin ships to users. Code migrates from :app into :core (and later
# :player-core) module by module, and a literal must not escape this gate merely by moving house —
# so the scan follows the modules rather than assuming everything lives under app/.
SRC_ROOTS = [
    ROOT / "app" / "src" / "main" / "java",
    ROOT / "core" / "src" / "main" / "java",
    ROOT / "player-core" / "src" / "main" / "java",
]
# The same modules as git pathspecs, for the scanner-migration guard in verify-ci.
MODULE_MAIN_DIRS = [root.parent.relative_to(ROOT).as_posix() for root in SRC_ROOTS]


def _kotlin_files():
    """Every scannable Kotlin file across all source roots, in a stable order."""
    return sorted(
        (f for root in SRC_ROOTS if root.is_dir() for f in root.rglob("*.kt")),
        key=lambda f: f.relative_to(ROOT).as_posix(),
    )
BASELINE = ROOT / "tools" / "i18n" / "hardcoded_baseline.txt"
SAFE_MANIFEST = ROOT / "tools" / "i18n" / "safe_literals.txt"
MIGRATION = ROOT / "tools" / "i18n" / "baseline_migration.json"
SCANNER_VERSION = 6
SAFE_SCHEMA_VERSION = 1
_GENERATED_MARKER = "// DO NOT EDIT — generated"

# Category names are policy. Their reasons are rendered in safe_literals.txt and validated here.
SAFE_CATEGORIES = {
    "empty": "Empty literal; no user-visible text.",
    "assertion": "Reviewed developer-only assertion text.",
    "log": "Developer diagnostic or log tag.",
    "regex": "Regular-expression syntax.",
    "sql": "Database query or schema syntax.",
    "json": "JSON field or protocol key.",
    "uri": "URI parameter or addressing key.",
    "preference-key": "Stable persisted preference key.",
    "file-path": "Filesystem path or segment.",
    "room-schema": "Room table, index, or column identifier.",
    "compiler": "Compiler or lint directive.",
    "performance": "Developer performance marker.",
    "locale": "Locale or Android resource identifier.",
    "mime": "MIME protocol value.",
    "url": "URL or URI address.",
    "protocol": "Stable protocol or comparison identifier.",
    "technical": "Reviewed non-user-facing technical literal.",
}


# --- mechanical Kotlin literal extraction ------------------------------------

def _iter_literals(src: str):
    """Yield ``(start, end, raw)`` for Kotlin string literals, including interpolation literals."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        pair = src[i:i + 2]
        if pair == "//":
            i = src.find("\n", i)
            if i == -1:
                return
            continue
        if pair == "/*":
            end = src.find("*/", i + 2)
            i = end + 2 if end != -1 else n
            continue
        if c == "'" and i + 2 < n:
            if src[i + 1] == "\\":
                end = src.find("'", i + 2)
                i = end + 1 if end != -1 and end - i <= 8 else i + 1
                continue
            if src[i + 2] == "'":
                i += 3
                continue
            i += 1
            continue
        if src.startswith('"""', i):
            end = src.find('"""', i + 3)
            if end == -1:
                return
            outer = src[i:end + 3]
            yield i, end + 3, outer
            yield from _nested_literals(i, outer)
            i = end + 3
            continue
        if c == '"':
            _, end = _scan_double_quoted(src, i, n)
            if end is None:
                i += 1
                continue
            outer = src[i:end]
            yield i, end, outer
            yield from _nested_literals(i, outer)
            i = end
            continue
        i += 1


def _scan_double_quoted(src: str, start: int, limit: int) -> tuple[int, int | None]:
    """Return the closing position for one normal Kotlin string, respecting ``${...}``."""
    pos = start + 1
    while pos < limit:
        char = src[pos]
        if char == "\\":
            pos += 2
            continue
        if char == "\n":
            return pos, None
        if char == '"':
            return pos, pos + 1
        if char == "$" and pos + 1 < limit and src[pos + 1] == "{":
            depth = 1
            pos += 2
            while pos < limit and depth:
                char = src[pos]
                if char == "\\":
                    pos += 2
                elif char == "{":
                    depth += 1
                    pos += 1
                elif char == "}":
                    depth -= 1
                    pos += 1
                elif char == '"':
                    _, nested_end = _scan_double_quoted(src, pos, limit)
                    pos = nested_end if nested_end is not None else limit
                else:
                    pos += 1
            continue
        pos += 1
    return pos, None


def _nested_literals(outer_start: int, outer: str):
    """Yield normal string literals nested inside interpolation expressions."""
    if outer.startswith('"""'):
        body, body_offset = outer[3:-3], outer_start + 3
    else:
        body, body_offset = outer[1:-1], outer_start + 1
    pos, limit = 0, len(body)
    while pos < limit:
        if body[pos:pos + 2] != "${":
            pos += 1
            continue
        depth = 1
        pos += 2
        while pos < limit and depth:
            char = body[pos]
            if char == "\\":
                pos += 2
            elif char == "{":
                depth += 1
                pos += 1
            elif char == "}":
                depth -= 1
                pos += 1
            elif char == '"':
                _, end = _scan_double_quoted(body, pos, limit)
                if end is None:
                    return
                yield body_offset + pos, body_offset + end, body[pos:end]
                pos = end
            else:
                pos += 1


def _decode(raw: str) -> str:
    """Decode one Kotlin escaped string without collapsing literal backslash sequences."""
    if raw.startswith('"""'):
        return raw[3:-3]
    body = raw[1:-1]
    escapes = {
        "b": "\b", "t": "\t", "n": "\n", "r": "\r", "f": "\f",
        "\\": "\\", '"': '"', "'": "'", "$": "$",
    }
    out: list[str] = []
    pos = 0
    while pos < len(body):
        if body[pos] != "\\":
            out.append(body[pos])
            pos += 1
            continue
        if pos + 1 >= len(body):
            out.append("\\")
            pos += 1
            continue
        nxt = body[pos + 1]
        if nxt == "u" and pos + 5 < len(body):
            digits = body[pos + 2:pos + 6]
            if re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                out.append(chr(int(digits, 16)))
                pos += 6
                continue
        if nxt in escapes:
            out.append(escapes[nxt])
        else:
            out.extend(("\\", nxt))
        pos += 2
    return "".join(out)


def _normalize(content: str) -> str:
    return re.sub(r"\s+", " ", content).strip()


def _inventory() -> dict[tuple[str, str], int]:
    """Return every Kotlin literal as an occurrence-aware ``(path, text)`` multiset."""
    counts: Counter = Counter()
    for kotlin_file in _kotlin_files():
        text = kotlin_file.read_text(encoding="utf-8")
        if _GENERATED_MARKER in text[:120]:
            continue
        relative = kotlin_file.relative_to(ROOT).as_posix()
        for _, _, raw in _iter_literals(text):
            counts[(relative, _normalize(_decode(raw)))] += 1
    return dict(counts)


# --- manifest formats ---------------------------------------------------------

def _escape(value: str) -> str:
    if value == "":
        return "\\0"
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def _unescape_serialized(value: str) -> str:
    out: list[str] = []
    pos = 0
    while pos < len(value):
        if value[pos] != "\\" or pos + 1 >= len(value):
            out.append(value[pos])
            pos += 1
            continue
        nxt = value[pos + 1]
        out.append({"\\": "\\", "n": "\n", "t": "\t", "0": ""}.get(nxt, "\\" + nxt))
        pos += 2
    return "".join(out)


def _serialize(counts: dict[tuple[str, str], int]) -> str:
    lines = [
        "# DO NOT EDIT by hand — generated by tools/i18n/check_hardcoded_strings.py.",
        f"# scanner-version: {SCANNER_VERSION}",
        "# One tab-separated line per (file, normalised content) with its occurrence count.",
        "# Entries go as literals are translated or classified; the file may only ever shrink.",
        "# Format: <count>\\t<relative path>\\t<content with \\t/\\n escaped>",
        "",
    ]
    for (relative, content), count in sorted(counts.items()):
        lines.append(f"{count}\t{relative}\t{_escape(content)}")
    return "\n".join(lines).rstrip("\n") + "\n"


def _parse(text: str) -> dict[tuple[str, str], int]:
    counts: Counter = Counter()
    for raw in text.splitlines():
        if not raw or raw.startswith("#"):
            continue
        parts = raw.split("\t", 2)
        if len(parts) != 3:
            continue
        try:
            count = int(parts[0])
        except ValueError:
            continue
        counts[(parts[1], _unescape_serialized(parts[2]))] += count
    return dict(counts)


def _scanner_version(text: str) -> int | None:
    for line in text.splitlines():
        if line.startswith("# scanner-version:"):
            try:
                return int(line.split(":", 1)[1].strip())
            except ValueError:
                return None
    return None


def _serialize_safe(entries: dict[tuple[str, str], tuple[int, str]]) -> str:
    lines = [
        "# Technical Kotlin literals explicitly excluded from translation.",
        f"# schema-version: {SAFE_SCHEMA_VERSION}",
        "# Format: <count>\\t<category>\\t<relative path>\\t<content with \\t/\\n escaped>",
    ]
    lines.extend(f"# category {name}: {reason}" for name, reason in SAFE_CATEGORIES.items())
    lines.append("")
    for (relative, content), (count, category) in sorted(entries.items()):
        lines.append(f"{count}\t{category}\t{relative}\t{_escape(content)}")
    return "\n".join(lines) + "\n"


def _parse_safe(text: str) -> tuple[dict[tuple[str, str], int], dict[tuple[str, str], str], list[str]]:
    counts: Counter = Counter()
    categories: dict[tuple[str, str], str] = {}
    errors: list[str] = []
    schema = None
    for line_no, raw in enumerate(text.splitlines(), 1):
        if raw.startswith("# schema-version:"):
            try:
                schema = int(raw.split(":", 1)[1].strip())
            except ValueError:
                schema = None
        if not raw or raw.startswith("#"):
            continue
        parts = raw.split("\t", 3)
        if len(parts) != 4:
            errors.append(f"safe_literals.txt:{line_no}: expected four tab-separated fields")
            continue
        try:
            count = int(parts[0])
        except ValueError:
            errors.append(f"safe_literals.txt:{line_no}: count must be an integer")
            continue
        category, relative, content = parts[1], parts[2], _unescape_serialized(parts[3])
        if count <= 0:
            errors.append(f"safe_literals.txt:{line_no}: count must be positive")
        if category not in SAFE_CATEGORIES:
            errors.append(f"safe_literals.txt:{line_no}: unknown category {category!r}")
        key = (relative, content)
        if key in categories and categories[key] != category:
            errors.append(f"safe_literals.txt:{line_no}: duplicate literal has conflicting categories")
        counts[key] += count
        categories[key] = category
    if schema != SAFE_SCHEMA_VERSION:
        errors.append(f"safe_literals.txt schema-version {schema!r} does not match {SAFE_SCHEMA_VERSION}")
    return dict(counts), categories, errors


def _safe_entries() -> tuple[dict[tuple[str, str], int], dict[tuple[str, str], str], list[str]]:
    if not SAFE_MANIFEST.is_file():
        return {}, {}, []
    return _parse_safe(SAFE_MANIFEST.read_text(encoding="utf-8"))


def _add_counts(*inventories: dict[tuple[str, str], int]) -> dict[tuple[str, str], int]:
    total: Counter = Counter()
    for inventory in inventories:
        total.update(inventory)
    return dict(total)


def _subtract_counts(source: dict[tuple[str, str], int], excluded: dict[tuple[str, str], int]):
    result: Counter = Counter(source)
    result.subtract(excluded)
    return {key: count for key, count in result.items() if count > 0}


def _subset(actual: dict, allowed: dict) -> list[str]:
    reasons: list[str] = []
    for key, count in actual.items():
        limit = allowed.get(key, 0)
        if count > limit:
            relative, content = key
            reasons.append(
                f"new/extra literal in {relative}: count {count} > allowed {limit} :: {content[:80]!r}"
            )
    return reasons


# --- developer-facing failure report ------------------------------------------
#
# The inventory itself is occurrence-counted and line-agnostic, which is right for the ratchet but
# useless to somebody who just hit a red build. These helpers re-walk the sources only when a run is
# already failing, so the happy path pays nothing for them.

_REPORT_LIMIT = 40


def _excess(actual: dict, allowed: dict) -> list[tuple[str, str]]:
    """Keys in ``actual`` that exceed their allowance in ``allowed``, in source order."""
    return sorted(key for key, count in actual.items() if count > allowed.get(key, 0))


def _locations() -> dict[tuple[str, str], list[int]]:
    """Map every inventory key to the 1-based lines it occurs on."""
    found: dict[tuple[str, str], list[int]] = {}
    for kotlin_file in _kotlin_files():
        text = kotlin_file.read_text(encoding="utf-8")
        if _GENERATED_MARKER in text[:120]:
            continue
        relative = kotlin_file.relative_to(ROOT).as_posix()
        for start, _, raw in _iter_literals(text):
            key = (relative, _normalize(_decode(raw)))
            found.setdefault(key, []).append(text.count("\n", 0, start) + 1)
    return found


def _render_unclassified(keys: list[tuple[str, str]]) -> list[str]:
    """A red build should name the file, the line and the exact text, then say what to do."""
    locations = _locations()
    lines = [
        "",
        "Hardcoded text found. Every literal must be reachable by translators or declared technical.",
        "",
    ]
    for relative, content in keys[:_REPORT_LIMIT]:
        where = locations.get((relative, content)) or []
        anchor = f"{relative}:{where[0]}" if where else relative
        extra = f"  (and {len(where) - 1} more occurrence(s) in this file)" if len(where) > 1 else ""
        lines.append(f"  {anchor}{extra}")
        lines.append(f"      {content[:120]!r}")
    if len(keys) > _REPORT_LIMIT:
        lines.append(f"  ... and {len(keys) - _REPORT_LIMIT} more")
    lines += [
        "",
        "  How to fix each one:",
        "",
        "  * A user can read it -> add it to core/src/main/res/values/strings_*.xml, then reference it:",
        "        stringResource(R.string.your_key)          // Compose",
        "        context.getString(R.string.your_key)       // everywhere else",
        "    Add the English string only. Translations arrive from Weblate after the merge, and a",
        "    missing translation never fails a build.",
        "",
        "  * No user can read it (log line, SQL, JSON key, regex, preference key) -> declare it:",
        "        python tools/i18n/check_hardcoded_strings.py classify-safe \\",
        "            --path <file> --text '<exact text>' --category <sql|log|json|regex|...>",
        "    Categories and their meanings are listed at the top of tools/i18n/safe_literals.txt.",
        "",
    ]
    return lines


def _render_stale(keys: list[tuple[str, str]]) -> list[str]:
    lines = [
        "",
        "Stale classification. These literals are on record but no longer exist in the code, usually",
        "because the line was edited or deleted:",
        "",
    ]
    for relative, content in keys[:_REPORT_LIMIT]:
        lines.append(f"  {relative}")
        lines.append(f"      {content[:120]!r}")
    if len(keys) > _REPORT_LIMIT:
        lines.append(f"  ... and {len(keys) - _REPORT_LIMIT} more")
    lines += [
        "",
        "  Refresh the records:",
        "        python tools/i18n/check_hardcoded_strings.py prune-safe",
        "",
    ]
    return lines


def _scan() -> dict[tuple[str, str], int]:
    """Compatibility helper: current inventory minus explicitly safe entries."""
    safe, _, _ = _safe_entries()
    return _subtract_counts(_inventory(), safe)


# --- commands -----------------------------------------------------------------

def cmd_generate(_args) -> int:
    current = _inventory()
    safe, _, errors = _safe_entries()
    stale_safe = _subset(safe, current)
    if errors or stale_safe:
        print("Cannot generate while safe_literals.txt is invalid or stale:")
        for reason in (errors + stale_safe)[:50]:
            print("  " + reason)
        print("Run 'prune-safe' after reviewing removed technical literals.")
        return 1
    baseline = _subtract_counts(current, safe)
    BASELINE.write_text(_serialize(baseline), encoding="utf-8")
    print(f"baseline written: {BASELINE.name} ({len(baseline)} unique, {sum(baseline.values())} occurrences)")
    return 0


def cmd_prune_safe(_args) -> int:
    current = _inventory()
    safe, categories, errors = _safe_entries()
    if errors:
        for error in errors:
            print(error)
        return 1
    pruned = {
        key: (min(count, current.get(key, 0)), categories[key])
        for key, count in safe.items() if current.get(key, 0) > 0
    }
    SAFE_MANIFEST.write_text(_serialize_safe(pruned), encoding="utf-8")
    return cmd_generate(None)


def cmd_classify_safe(args) -> int:
    relative = Path(args.path).as_posix()
    key = (relative, _normalize(args.text))
    current = _inventory()
    available = current.get(key, 0)
    if available == 0:
        print(f"Literal not found in current inventory: {relative} :: {args.text!r}")
        return 1
    safe, categories, errors = _safe_entries()
    if errors:
        for error in errors:
            print(error)
        return 1
    requested = args.count if args.count is not None else available
    if requested <= 0 or requested > available:
        print(f"--count must be between 1 and {available}")
        return 1
    safe[key] = requested
    categories[key] = args.category
    entries = {item: (count, categories[item]) for item, count in safe.items()}
    SAFE_MANIFEST.write_text(_serialize_safe(entries), encoding="utf-8")
    return cmd_generate(None)


def cmd_verify(args) -> int:
    if not BASELINE.is_file():
        print(f"missing committed baseline: {BASELINE}")
        return 1
    baseline_text = BASELINE.read_text(encoding="utf-8")
    baseline = _parse(baseline_text)
    safe, _, safe_errors = _safe_entries()
    current = _inventory()
    classified = _add_counts(baseline, safe)

    failures: list[str] = []
    if _scanner_version(baseline_text) != SCANNER_VERSION:
        failures.append(
            f"baseline scanner-version {_scanner_version(baseline_text)!r} does not match {SCANNER_VERSION}"
        )
    failures.extend(safe_errors)
    unclassified = _excess(current, classified)
    stale = _excess(classified, current)
    failures.extend("UNCLASSIFIED — " + reason for reason in _subset(current, classified))
    failures.extend("STALE CLASSIFICATION — " + reason for reason in _subset(classified, current))

    if not getattr(args, "bootstrap", False):
        if not getattr(args, "base", None):
            failures.append("verify requires --base unless --bootstrap is used")
        else:
            base_text = Path(args.base).read_text(encoding="utf-8")
            base = _parse(base_text)
            failures.extend("REGRESSION — " + reason for reason in _subset(baseline, base))

    if failures:
        # Unclassified and stale entries are what a developer actually hits, so they get the
        # located, actionable report; anything else stays in the terse diagnostic list.
        other = [
            reason for reason in failures
            if not reason.startswith(("UNCLASSIFIED — ", "STALE CLASSIFICATION — "))
        ]
        print("i18n literal inventory failed:")
        if unclassified:
            print("\n".join(_render_unclassified(unclassified)))
        if stale:
            print("\n".join(_render_stale(stale)))
        for reason in other[:_REPORT_LIMIT]:
            print("  " + reason)
        if len(other) > _REPORT_LIMIT:
            print(f"  ... and {len(other) - _REPORT_LIMIT} more")
        return 1
    mode = "bootstrap" if getattr(args, "bootstrap", False) else "merge-base ratchet"
    print(f"i18n literal inventory OK ({mode}): {len(baseline)} baseline + {len(safe)} safe entries")
    return 0


def cmd_verify_ci(args) -> int:
    """Apply bootstrap/version/merge-base policy for the GitHub workflow."""
    base_sha = args.base_sha
    probe = subprocess.run(
        ["git", "cat-file", "-e", f"{base_sha}:tools/i18n/hardcoded_baseline.txt"],
        cwd=ROOT, capture_output=True,
    )
    if probe.returncode != 0:
        return cmd_verify(argparse.Namespace(base=None, bootstrap=True))

    base_text = subprocess.run(
        ["git", "show", f"{base_sha}:tools/i18n/hardcoded_baseline.txt"],
        cwd=ROOT, text=True, capture_output=True, check=True,
    ).stdout
    current_text = BASELINE.read_text(encoding="utf-8")
    base_version = _scanner_version(base_text) or 1
    current_version = _scanner_version(current_text)
    if current_version == base_version:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as handle:
            handle.write(base_text)
            base_file = handle.name
        try:
            return cmd_verify(argparse.Namespace(base=base_file, bootstrap=False))
        finally:
            Path(base_file).unlink(missing_ok=True)

    try:
        migration = json.loads(MIGRATION.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"invalid baseline migration: {error}")
        return 1
    if (migration.get("fromScannerVersion"), migration.get("toScannerVersion")) != (
        base_version, current_version,
    ):
        print(f"baseline_migration.json does not match {base_version} -> {current_version}")
        return 1
    # Every module's main source set, not just app/'s — the same reason SRC_ROOTS is a list. A
    # pathspec that matches nothing in this repo simply yields no output, so one list works for both
    # the app repos and the core repo.
    changed_app = subprocess.run(
        ["git", "diff", "--name-only", base_sha, "HEAD", "--", *MODULE_MAIN_DIRS],
        cwd=ROOT, text=True, capture_output=True, check=True,
    ).stdout.strip()
    if changed_app:
        print("Scanner migrations may not change module source; separate scanner and application changes.")
        print(changed_app)
        return 1
    return cmd_verify(argparse.Namespace(base=None, bootstrap=True))


def main() -> int:
    # Literal content is echoed back on failure and the inventory contains non-ASCII (bullets,
    # dashes, CJK). Windows consoles and piped stdout default to cp1252, where printing those raises
    # UnicodeEncodeError and the real message is lost behind a traceback. Only the CLI entry point
    # reconfigures; importers (the test suite) keep their own stream setup.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command")
    sub.add_parser("generate", help="regenerate the shrinking hardcoded baseline")
    verify = sub.add_parser("verify", help="verify exact classification and merge-base ratchet")
    verify.add_argument("--base", help="baseline file from the pull-request merge base")
    verify.add_argument("--bootstrap", action="store_true", help="skip merge-base comparison once")
    sub.add_parser("prune-safe", help="remove safe entries no longer present, then regenerate")
    ci = sub.add_parser("verify-ci", help="apply merge-base and scanner-migration CI policy")
    ci.add_argument("--base-sha", required=True)
    classify = sub.add_parser("classify-safe", help="explicitly classify one technical literal")
    classify.add_argument("--path", required=True, help="repository-relative Kotlin file path")
    classify.add_argument("--text", required=True, help="decoded literal text")
    classify.add_argument("--category", required=True, choices=sorted(SAFE_CATEGORIES))
    classify.add_argument("--count", type=int, help="occurrences to classify; defaults to all")
    args = parser.parse_args()
    if args.command == "generate":
        return cmd_generate(args)
    if args.command == "verify":
        return cmd_verify(args)
    if args.command == "prune-safe":
        return cmd_prune_safe(args)
    if args.command == "verify-ci":
        return cmd_verify_ci(args)
    if args.command == "classify-safe":
        return cmd_classify_safe(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
