#!/usr/bin/env python3
"""
Set configuration values inside a built WAR - no rebuild, no unzip.

Why this exists
---------------
The application reads `WEB-INF/classes/infeara.properties` from inside the WAR
(`spring.config.import=optional:classpath:/infeara.properties`). An operator can therefore
configure the database password, the AES-256-GCM encryption key and the AI provider keys
after the artefact is built:

    * on an exploded deployment: edit `webapps/ROOT/WEB-INF/classes/infeara.properties`
    * on the artefact itself:    this script (rewrites the single zip entry in place)

Both are legitimate. What this script adds is repeatability: the same edit can be applied at
deploy time on every host, from a secret manager, without unpacking and re-zipping a 75 MB WAR
by hand - a manual re-zip silently drops file attributes and, on Windows, can corrupt entries.

Usage
-----
    # see the current values (secrets masked)
    python3 scripts/configure-war.py dist/infeara-sales-os.war --print

    # set values
    python3 scripts/configure-war.py dist/infeara-sales-os.war \
        --set spring.datasource.password='...' \
        --set app.security.encryption-key='...' \
        --set app.ai.openrouter.api-key='...'

    # set from environment variables (keeps secrets out of the shell history)
    DB_PASSWORD=... AI_OPENROUTER_API_KEY=... \
    python3 scripts/configure-war.py dist/infeara-sales-os.war \
        --from-env spring.datasource.password=DB_PASSWORD \
        --from-env app.ai.openrouter.api-key=AI_OPENROUTER_API_KEY

    # write the result somewhere else and leave the original untouched
    python3 scripts/configure-war.py in.war -o out.war --set logging.level.com.infeara.sales=DEBUG

Exit codes: 0 ok, 1 error, 2 usage.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ENTRY = "WEB-INF/classes/infeara.properties"
SECRET_HINTS = ("password", "secret", "api-key", "encryption-key")


def read_properties(text: str) -> list[tuple[str, str]]:
    """Parses `key=value` lines, preserving comments and order (returns only real entries)."""
    entries: list[tuple[str, str]] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped[0] in "#!":
            continue
        if "=" in stripped:
            key, value = stripped.split("=", 1)
            entries.append((key.strip(), value.strip()))
    return entries


def mask(key: str, value: str) -> str:
    if not any(hint in key.lower() for hint in SECRET_HINTS):
        return value
    if not value:
        return "(empty)"
    if value.startswith("${") and value.endswith("}"):
        # Still a placeholder: show it, it is not a secret yet.
        return value
    return f"**** (set, {len(value)} chars)"


def apply_values(text: str, updates: dict[str, str]) -> tuple[str, list[str], list[str]]:
    """
    Replaces existing keys in place and appends new ones at the end.

    Appending rather than inserting keeps the file's structure and every comment intact -
    an operator reading the deployed file must still recognise it.
    """
    lines = text.splitlines()
    replaced: list[str] = []
    seen: set[str] = set()

    pattern_cache: dict[str, re.Pattern[str]] = {}
    for key, value in updates.items():
        pattern_cache[key] = re.compile(rf"^\s*{re.escape(key)}\s*=")
        seen.add(key)

    for index, line in enumerate(lines):
        for key, pattern in pattern_cache.items():
            if pattern.match(line):
                lines[index] = f"{key}={updates[key]}"
                replaced.append(key)
                seen.discard(key)
                break

    added = sorted(seen)
    if added:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append("# --- appended by scripts/configure-war.py ---")
        for key in added:
            lines.append(f"{key}={updates[key]}")

    result = "\n".join(lines)
    if not result.endswith("\n"):
        result += "\n"
    return result, replaced, added


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="configure-war.py",
        description="Set values in WEB-INF/classes/infeara.properties inside a built WAR.",
    )
    parser.add_argument("war", type=Path, help="the WAR to configure")
    parser.add_argument("-o", "--output", type=Path, help="write the result here instead of in place")
    parser.add_argument("--set", action="append", default=[], metavar="KEY=VALUE",
                        help="set a property (repeatable)")
    parser.add_argument("--from-env", action="append", default=[], metavar="KEY=ENV_VAR",
                        help="set a property from an environment variable (repeatable)")
    parser.add_argument("--print", dest="show", action="store_true",
                        help="print the current values (secrets masked) and exit")
    arguments = parser.parse_args(argv)

    if not arguments.war.is_file():
        print(f"error: {arguments.war} not found", file=sys.stderr)
        return 1

    if not zipfile.is_zipfile(arguments.war):
        print(f"error: {arguments.war} is not a readable WAR (zip) archive", file=sys.stderr)
        return 1

    with zipfile.ZipFile(arguments.war) as archive:
        names = archive.namelist()
        if ENTRY not in names:
            print(f"error: {ENTRY} is not packaged in {arguments.war.name}", file=sys.stderr)
            print("       Build the WAR with ./scripts/build-war.sh (it packages "
                  "backend/src/main/resources/infeara.properties).", file=sys.stderr)
            return 1
        original = archive.read(ENTRY).decode("utf-8")

    if arguments.show:
        print(f"{ENTRY} in {arguments.war.name}:")
        for key, value in read_properties(original):
            print(f"  {key:<50} {mask(key, value)}")
        print("\nValues written as ${ENV_VAR:default} are resolved from the environment at "
              "startup;\nreplace the whole value with a literal to fix it inside the WAR.")
        return 0

    updates: dict[str, str] = {}
    for item in arguments.set:
        if "=" not in item:
            print(f"error: --set expects KEY=VALUE, got {item!r}", file=sys.stderr)
            return 2
        key, value = item.split("=", 1)
        updates[key.strip()] = value

    for item in arguments.from_env:
        if "=" not in item:
            print(f"error: --from-env expects KEY=ENV_VAR, got {item!r}", file=sys.stderr)
            return 2
        key, variable = (part.strip() for part in item.split("=", 1))
        value = os.environ.get(variable)
        if value is None:
            print(f"error: environment variable {variable} is not set", file=sys.stderr)
            return 1
        updates[key] = value

    if not updates:
        print("error: nothing to do - pass --set, --from-env or --print", file=sys.stderr)
        return 2

    updated_text, replaced, added = apply_values(original, updates)

    # Never print secrets; print which keys changed.
    for key in updates:
        state = "replaced" if key in replaced else "added"
        print(f"  {state:<8} {key}")

    target = arguments.output or arguments.war
    if arguments.output and arguments.output.resolve() == arguments.war.resolve():
        print("error: --output must differ from the input WAR", file=sys.stderr)
        return 2

    # Rewrite the archive: copy every entry byte-for-byte, substituting only our entry.
    # Doing it through zipfile (rather than editing in place) keeps the central directory
    # and per-entry metadata consistent; a hand-edited zip is a corrupted zip.
    temporary = target.with_suffix(target.suffix + ".tmp")
    with zipfile.ZipFile(arguments.war) as source, \
            zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as destination:
        for item in source.infolist():
            if item.filename == ENTRY:
                destination.writestr(item, updated_text.encode("utf-8"))
            else:
                destination.writestr(item, source.read(item.filename))
    shutil.move(temporary, target)

    print(f"\nWrote {target} ({target.stat().st_size / (1024 * 1024):.1f} MB)")
    print("Redeploy it, then restart Tomcat. Note: a WAR containing a real secret must not be")
    print("uploaded as a build artifact or committed - keep it in your own controlled storage.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
