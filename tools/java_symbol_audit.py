#!/usr/bin/env python3
"""
Compile-surrogate for an environment without a JDK.

There is no compiler in this sandbox (see docs/operations/sandbox-environment.md), so the
usual safety net for refactors — "it does not compile" — is unavailable. This tool recovers
the most valuable part of it:

  * index every project type: classes, interfaces, records, enums, their declared methods,
    record components, fields and Lombok-generated accessors;
  * for every `<field>.<method>(...)` call inside a project type whose field type is another
    *project* type, prove the method exists (declared, a record accessor, a Lombok accessor,
    or inherited from a project supertype);
  * report calls that cannot exist as MISSING, and calls on types that extend something
    outside the project (Spring Data repositories, JDK collections) as UNVERIFIED.

MISSING entries are almost always real compile errors; UNVERIFIED entries need human eyes.

Usage:  python3 tools/java_symbol_audit.py [--verbose]
Exit:   0 when no MISSING entries, 1 otherwise.
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(os.environ.get("JAVA_AUDIT_SRC", ROOT / "backend/src/main/java"))

TYPE_DECL = re.compile(
    r"^\s*(?:public\s+|protected\s+|private\s+|static\s+|final\s+|sealed\s+|abstract\s+)*"
    r"(class|interface|record|enum)\s+([A-Za-z_]\w*)", re.M)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+);", re.M)
EXTENDS = re.compile(r"\b(?:extends|implements)\s+([^{]+)")
METHOD = re.compile(
    r"^\s{4}(?:public\s+|protected\s+|private\s+|static\s+|final\s+|synchronized\s+|abstract\s+|default\s+|"
    r"@\w+(?:\([^)]*\))?\s+)*[\w<>\[\],.?\s]+\s+([a-z]\w*)\s*\(", re.M)
FIELD = re.compile(r"^\s{4}(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*"
                   r"([A-Za-z_][\w.<>\[\], ?]*?)\s+([a-z]\w*)\s*(?:=|;)", re.M)
RECORD_COMPONENTS = re.compile(r"record\s+(\w+)\s*\((.*?)\)\s*(?:\{|implements)", re.S)
CALL = re.compile(r"\b([a-z]\w*)\.([a-z]\w*)\s*\(")

# Types whose call surface is too large/foreign to prove here.
EXTERNAL_HINTS = ("String", "List", "Map", "Set", "Optional", "Page", "BigDecimal", "Instant",
                  "Object", "Stream", "LocalDateTime", "Integer", "Long", "Boolean", "UUID")


@dataclass
class TypeInfo:
    name: str
    package: str
    kind: str
    path: Path
    methods: set[str] = field(default_factory=set)
    fields: dict[str, str] = field(default_factory=dict)
    lombok_getters: set[str] = field(default_factory=set)
    lombok_setters: set[str] = field(default_factory=set)
    supertypes: list[str] = field(default_factory=list)
    opaque_supertype: bool = False


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def cap(name: str) -> str:
    return name[:1].upper() + name[1:]


def parse(path: Path) -> TypeInfo | None:
    raw = path.read_text(encoding="utf-8")
    src = strip_comments(raw)
    decl = TYPE_DECL.search(src)
    if not decl:
        return None
    kind, name = decl.group(1), decl.group(2)
    pkg = PACKAGE.search(src)
    info = TypeInfo(name=name, package=pkg.group(1) if pkg else "", kind=kind, path=path)

    for match in METHOD.finditer(src):
        info.methods.add(match.group(1))

    for match in FIELD.finditer(src):
        ftype, fname = match.group(1).strip(), match.group(2)
        info.fields[fname] = ftype.split("<")[0].strip()

    if kind == "record":
        rec = RECORD_COMPONENTS.search(src)
        if rec and rec.group(1) == name:
            depth = 0
            current = ""
            for ch in rec.group(2):
                if ch in "<([":
                    depth += 1
                elif ch in ">)]":
                    depth -= 1
                if ch == "," and depth == 0:
                    info.fields[current.strip().split()[-1]] = "component"
                    current = ""
                else:
                    current += ch
            if current.strip():
                info.fields[current.strip().split()[-1]] = "component"
            info.methods.update(info.fields.keys())

    has_getter = "@Getter" in raw or "@Data" in raw or "@Value" in raw
    has_setter = "@Setter" in raw or "@Data" in raw
    if has_getter:
        for fname, ftype in info.fields.items():
            accessor = f"is{cap(fname)}" if ftype == "boolean" else f"get{cap(fname)}"
            info.lombok_getters.add(accessor)
            info.methods.add(accessor)
    if has_setter:
        for fname in info.fields:
            info.lombok_setters.add(f"set{cap(fname)}")
            info.methods.add(f"set{cap(fname)}")

    for ext in EXTENDS.finditer(src):
        for part in ext.group(1).split(","):
            simple = re.sub(r"<.*", "", part).strip().split(".")[-1].strip()
            if simple:
                info.supertypes.append(simple)
    # A supertype that is not in the project makes the call surface unknowable.
    info.opaque_supertype = any(s not in ("Object",) for s in info.supertypes)
    return info


def main(argv: list[str]) -> int:
    verbose = "--verbose" in argv
    index: dict[str, TypeInfo] = {}
    for path in SRC.rglob("*.java"):
        info = parse(path)
        if info:
            index.setdefault(info.name, info)

    # Resolve opaque supertypes: an interface that extends a project type is not opaque.
    for info in index.values():
        if info.kind == "interface" and info.supertypes:
            info.opaque_supertype = any(s not in index for s in info.supertypes)
        elif info.supertypes:
            # A class extending a project class inherits its methods.
            inherited: set[str] = set()
            for parent in info.supertypes:
                target = index.get(parent)
                if target:
                    inherited |= target.methods
            info.methods |= inherited
            info.opaque_supertype = any(s not in index and s != "Object" for s in info.supertypes)

    missing: list[str] = []
    unverified: list[str] = []
    checked = 0

    for info in index.values():
        src = strip_comments(info.path.read_text(encoding="utf-8"))
        # Only calls on fields declared in this type can be typed without inference.
        for field_name, field_type in info.fields.items():
            simple = re.sub(r"<.*", "", field_type).strip()
            target = index.get(simple)
            if target is None or simple in EXTERNAL_HINTS:
                continue
            for call in CALL.finditer(src):
                if call.group(1) != field_name:
                    continue
                method = call.group(2)
                checked += 1
                if method in target.methods:
                    continue
                location = f"{info.path.relative_to(SRC)}:{info.name}.{field_name} -> {simple}.{method}()"
                if target.opaque_supertype or target.kind == "interface":
                    unverified.append(location)
                else:
                    missing.append(location)

    unique_missing = sorted(set(missing))
    unique_unverified = sorted(set(unverified))
    print(f"project types indexed : {len(index)}")
    print(f"call sites verified   : {checked}")
    print(f"missing methods       : {len(unique_missing)}")
    print(f"unverifiable (inherited): {len(unique_unverified)}")
    if unique_missing:
        print("\nMISSING (these cannot compile):")
        for item in unique_missing:
            print("  -", item)
    if verbose and unique_unverified:
        print("\nUNVERIFIED (target inherits outside the project — review by hand):")
        for item in unique_unverified:
            print("  -", item)
    return 0 if not unique_missing else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
