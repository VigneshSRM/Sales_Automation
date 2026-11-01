#!/usr/bin/env python3
"""
Capability audit — the authorisation equivalent of "does it compile".

Every capability the code enforces must exist in the seed data, and every capability in
the seed data should be enforced somewhere (otherwise it is dead configuration that
misleads administrators). Both directions are checked:

  USED but NOT SEEDED   -> every caller gets 403 forever. A real defect.
  SEEDED but NOT USED   -> not fatal, reported for review.

Capability strings are collected from backend `capabilities.require/requireAny/has(...)`,
from `useCapability("...")` in the frontend, and from the V6/V9 seed inserts.

Usage: python3 tools/capability_audit.py [--verbose]
Exit 1 when a used capability is not seeded.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "backend/src/main/java"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
FRONTEND = ROOT / "frontend"

JAVA_CAPABILITY = re.compile(
    r"(?:capabilities\.(?:require|requireAny|has)\(\s*principal\s*,\s*|"
    r"principal\.has\(\s*|principal\.anyOf\(\s*)\"([a-z][a-z0-9_.]*)\"")
TS_CAPABILITY = re.compile(r"useCapability\(\s*\"([a-z][a-z0-9_.]*)\"\s*\)")
# v('-prefixed?') no. Seed rows look like: ('email.read', '...', 'email'),
SEED_ROW = re.compile(r"^\('([a-z][a-z0-9_.]*)',\s*'", re.M)
SEED_IN = re.compile(r"INSERT INTO capabilities[^;]*?VALUES(.*?);", re.S)


def java_usages() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for path in JAVA.rglob("*.java"):
        for cap in JAVA_CAPABILITY.findall(path.read_text(encoding="utf-8")):
            found.setdefault(cap, []).append(str(path.relative_to(ROOT)))
    return found


def frontend_usages() -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for path in list(FRONTEND.glob("app/**/*.tsx")) + list(FRONTEND.glob("components/**/*.tsx")):
        for cap in TS_CAPABILITY.findall(path.read_text(encoding="utf-8")):
            found.setdefault(cap, []).append(str(path.relative_to(ROOT)))
    return found


def seeded() -> set[str]:
    caps: set[str] = set()
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        text = path.read_text(encoding="utf-8")
        for block in SEED_IN.findall(text):
            caps.update(SEED_ROW.findall(block))
    return caps


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    seeded_caps = seeded()
    java_used = java_usages()
    ts_used = frontend_usages()
    used = set(java_used) | set(ts_used)

    missing = sorted(used - seeded_caps)
    unused = sorted(seeded_caps - used)

    print(f"capabilities seeded : {len(seeded_caps)}")
    print(f"enforced in backend : {len(java_used)} distinct")
    print(f"gated in frontend   : {len(ts_used)} distinct")
    print(f"used but not seeded : {len(missing)}")
    print(f"seeded but unused   : {len(unused)}")

    if missing:
        print("\nUSED BUT NOT SEEDED (these calls always fail):")
        for cap in missing:
            sources = sorted(set(java_used.get(cap, []) + ts_used.get(cap, [])))
            print(f"  - {cap}")
            for source in sources:
                print(f"      {source}")
    if args.verbose and unused:
        print("\nSeeded but never enforced (review whether the feature is missing or the seed stale):")
        for cap in unused:
            print("  -", cap)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
