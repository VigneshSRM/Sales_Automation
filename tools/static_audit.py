#!/usr/bin/env python3
"""Static consistency checks that do not need a JDK.

Checks:
  1. every `com.infeara.sales.*` import resolves to a declared type;
  2. every file's public top-level type matches its filename;
  3. braces/parentheses balance per file (catches truncated heredocs);
  4. duplicate @RequestMapping base paths across controllers;
  5. `ApiErrorCode`/`ErrorCode` constants referenced exist;
  6. repository calls resolve to a declared or derived method (heuristic).
Run from the repository root: python3 tools/static_audit.py
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend", "src", "main", "java")

files = []
for base, _dirs, names in os.walk(SRC):
    for name in names:
        if name.endswith(".java"):
            files.append(os.path.join(base, name))

declared = defaultdict(list)          # simple type name -> [file]
for path in files:
    src = open(path, encoding="utf-8").read()
    for match in re.finditer(r"\b(?:public\s+|final\s+|abstract\s+|sealed\s+)*(class|interface|enum|record)\s+(\w+)", src):
        declared[match.group(2)].append(path)

problems = []

for path in files:
    src = open(path, encoding="utf-8").read()
    short = os.path.relpath(path, SRC)

    for match in re.finditer(r"import\s+com\.infeara\.sales\.([\w.]+);", src):
        fq = match.group(1)
        simple = fq.split(".")[-1]
        if simple == "*":
            continue
        if simple not in declared:
            problems.append(f"{short}: unresolved import com.infeara.sales.{fq}")

    stem = os.path.splitext(os.path.basename(path))[0]
    if not re.search(rf"\b(class|interface|enum|record)\s+{re.escape(stem)}\b", src):
        problems.append(f"{short}: no top-level type named {stem}")

    if src.count("{") != src.count("}"):
        problems.append(f"{short}: unbalanced braces ({src.count('{')} vs {src.count('}')})")
    if src.count("(") != src.count(")"):
        problems.append(f"{short}: unbalanced parentheses")

# controllers: duplicate base paths
bases = defaultdict(list)
for path in files:
    src = open(path, encoding="utf-8").read()
    for match in re.finditer(r'@RequestMapping\("([^"]+)"\)', src):
        bases[match.group(1)].append(os.path.relpath(path, SRC))
for base, owners in bases.items():
    if len(owners) > 1:
        problems.append(f"duplicate @RequestMapping {base}: {owners}")

# error code constants
codes = set()
for path in files:
    src = open(path, encoding="utf-8").read()
    if "enum ErrorCode" in src or "class ErrorCode" in src:
        codes |= set(re.findall(r"^\s{4}([A-Z][A-Z0-9_]+)\s*\(", src, re.M))
used = set()
for path in files:
    src = open(path, encoding="utf-8").read()
    used |= set(re.findall(r"ErrorCode\.([A-Z][A-Z0-9_]+)", src))
missing = sorted(used - codes)
for code in missing:
    problems.append(f"ErrorCode.{code} referenced but not declared")

# repositories: methods called vs declared
repo_types = {}
for path in files:
    src = open(path, encoding="utf-8").read()
    name = os.path.splitext(os.path.basename(path))[0]
    if re.search(rf"interface\s+{name}\s+extends\s+.*Repository", src):
        methods = set(re.findall(r"(?:Optional<[\w<>,. ]+>|List<[\w<>,. ]+>|Page<[\w<>,. ]+>|long|int|boolean|void|[\w<>,. ]+)\s+(\w+)\s*\(", src))
        repo_types[name] = methods

builtins = {"save", "saveAll", "findById", "findAll", "findAllById", "delete", "deleteById",
            "deleteAll", "count", "existsById", "flush", "deleteAllById", "getReferenceById",
            "saveAndFlush", "countBy", "findAllByIdIn"}
for path in files:
    src = open(path, encoding="utf-8").read()
    short = os.path.relpath(path, SRC)
    for field_type, field_name in re.findall(r"(?:private\s+final\s+|private\s+)(\w+Repository)\s+(\w+)\s*;", src):
        methods = repo_types.get(field_type)
        if methods is None:
            problems.append(f"{short}: unknown repository type {field_type}")
            continue
        for call in re.findall(rf"\b{field_name}\.(\w+)\(", src):
            if call in methods or call in builtins:
                continue
            if re.match(r"(find|read|get|count|exists|delete|query|search)\w*By\w*", call) or \
               re.match(r"(find|count|exists)\w*$", call):
                continue
            problems.append(f"{short}: {field_type}.{call}() not declared")

print(f"files scanned: {len(files)}  types declared: {len(declared)}")
if problems:
    print(f"problems: {len(problems)}")
    for problem in problems:
        print("  -", problem)
    sys.exit(1)
print("no problems found")
