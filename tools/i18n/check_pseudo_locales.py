#!/usr/bin/env python3
"""Verify pseudolocale packaging in the BUILT APK (docs/internationalization.md 0b).

Enabling ``isPseudoLocalesEnabled`` alone is not proof: ``localeFilters`` can then strip the
generated ``en-rXA`` / ``ar-rXB`` resources, leaving the pseudolocale check silently doing
nothing. This script inspects the actual resource table of a built APK via ``aapt2`` and asserts the
full stated invariant, not just pseudolocale presence:

  debug   APK must contain BOTH ``en-rXA`` and ``ar-rXB``, AND no locale configuration outside the
          allowed debug set. ``ar`` is allowed in debug only: generating ``ar-rXB`` causes the
          resource merger to retain the ``ar`` parent qualifier, which is unavoidable and documented.
  release APK must contain NEITHER ``en-rXA`` nor ``ar-rXB``, AND no locale configuration outside the
          allowed release set.

The allowed locale set for both variants is derived from ``tools/i18n/locales.json`` — the same
single source of truth that ``app/build.gradle.kts`` reads to populate ``localeFilters``. This script
automatically includes every community locale listed in the catalogue, so the APK may contain any
recognised locale even when it is not yet packaged for the in-app picker. Removing a locale from
``locales.json`` removes it from the allowed set without a separate CI edit.

Usage:
    python3 tools/i18n/check_pseudo_locales.py --apk path/to/app.apk --mode debug|release
    [--aapt2 /path/to/aapt2]   # auto-detected from $ANDROID_HOME/build-tools if omitted
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOCALES_JSON = ROOT / "tools" / "i18n" / "locales.json"

PSEUDO = ("en-rXA", "ar-rXB")


def _catalogue_locale_qualifiers() -> set[str]:
    """Return every ``resourceQualifier`` listed in ``locales.json``.

    This is the single source of truth shared with ``app/build.gradle.kts``:
    anything the catalogue declares is an expected locale folder in the APK.
    Returns an empty set when the catalogue is absent (test environments)."""
    if not LOCALES_JSON.is_file():
        return set()
    try:
        catalogue = json.loads(LOCALES_JSON.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        sys.exit(f"error: invalid JSON in {LOCALES_JSON}: {exc}")
    qualifiers: set[str] = set()
    for entry in catalogue:
        q = entry.get("resourceQualifier")
        if q and isinstance(q, str) and q.strip():
            qualifiers.add(q.strip())
    return qualifiers


def _allowed_debug() -> set[str]:
    """Allowed locale configurations in the debug APK.

    Includes the source locale (``en``), the regional override (``en-rGB``),
    both pseudolocales, the unavoidable ``ar`` parent of ``ar-rXB``, and every
    community locale declared in ``locales.json``.
    """
    return {"en", "en-rGB", "en-rXA", "ar-rXB", "ar"} | _catalogue_locale_qualifiers()


def _allowed_release() -> set[str]:
    """Allowed locale configurations in the release APK.

    Includes ``en``, ``en-rGB``, and every community locale declared in
    ``locales.json``.  Pseudolocales must never reach release.
    """
    return {"en", "en-rGB"} | _catalogue_locale_qualifiers()


# Module-level snapshots for test compatibility (read once at import time;
# the CI always runs from a clean checkout, so the snapshot is fresh).
_ALLOWED_DEBUG = _allowed_debug()
_ALLOWED_RELEASE = _allowed_release()


def _find_aapt2() -> str:
    explicit = os.environ.get("AAPT2")
    if explicit and Path(explicit).exists():
        return explicit
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        bt = Path(home) / "build-tools"
        if bt.is_dir():
            cands = sorted(p / "aapt2" for p in bt.iterdir() if (p / "aapt2").exists())
            if cands:
                return str(cands[-1])
    found = shutil.which("aapt2")
    if found:
        return found
    sys.exit("error: aapt2 not found; pass --aapt2 or set ANDROID_HOME")


def _apk_configs(apk: Path, aapt2: str) -> set[str]:
    out = subprocess.run([aapt2, "dump", "configurations", str(apk)],
                         capture_output=True, text=True, check=True)
    return {line.strip() for line in out.stdout.splitlines() if line.strip()}


_LANG = re.compile(r"^[a-z]{2,3}$")
_REGION = re.compile(r"^r[A-Z]{2}$")


def _locale_configs(configs: set[str]) -> set[str]:
    """Extract the locale qualifier (``xx`` or ``xx-rYY``) from each aapt2 configuration line.

    aapt2 prints full configs like ``en-rGB-w720dp-h1280dp-long-mdpi``; the locale is the leading
    language tag optionally followed by an ``rYY`` region. Other leading qualifiers (``v26``,
    ``w720dp``, ``long``, ``port``, ...) are not locales and are ignored. The default unqualified
    config prints as a blank line and is already excluded by the caller.
    """
    out: set[str] = set()
    for c in configs:
        if not c:
            continue
        parts = c.split("-")
        first = parts[0]
        if not _LANG.match(first):
            continue
        locale = first
        if len(parts) > 1 and _REGION.match(parts[1]):
            locale += "-" + parts[1]
        out.add(locale)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--mode", required=True, choices=["debug", "release"])
    ap.add_argument("--aapt2", default=None)
    args = ap.parse_args()
    if not args.apk.is_file():
        print(f"error: APK not found: {args.apk}")
        return 1
    aapt2 = args.aapt2 or _find_aapt2()
    configs = _apk_configs(args.apk, aapt2)
    locales = _locale_configs(configs)
    present = {p for p in PSEUDO if p in locales}
    allowed = _allowed_debug() if args.mode == "debug" else _allowed_release()
    leaks = locales - allowed
    if args.mode == "debug":
        missing = set(PSEUDO) - present
        if missing:
            print(f"FAIL (debug): pseudolocale(s) missing from APK: {sorted(missing)}")
            print("  isPseudoLocalesEnabled is set but localeFilters stripped them; "
                  "add the debug-only qualifiers via the per-variant API.")
            return 1
        if leaks:
            print(f"FAIL (debug): unexpected locale configuration(s) in APK: {sorted(leaks)}")
            print("  localeFilters did not strip a library locale folder, or a production locale leaked.")
            return 1
        print(f"OK (debug): pseudolocales present {sorted(present)}; no locale leaks")
        return 0
    # release: neither pseudolocale may be present.
    if present:
        print(f"FAIL (release): pseudolocale(s) leaked into release APK: {sorted(present)}")
        print("  The debug-only qualifier add must not reach release variants.")
        return 1
    if leaks:
        print(f"FAIL (release): unexpected locale configuration(s) in release APK: {sorted(leaks)}")
        return 1
    print("OK (release): no pseudolocales in release APK; no locale leaks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
