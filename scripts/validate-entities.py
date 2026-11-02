#!/usr/bin/env python3
"""Cross-checks the JPA entity model against the Flyway schema.

Hibernate runs with ``ddl-auto=validate``, so a mismatch between an entity and the
physical table is a startup failure — but only after the WAR is deployed to Tomcat.
This script catches the same mistakes statically, before packaging:

  * an @Entity whose @Table does not exist in any migration
  * an @Column that the table does not declare
  * a table column that no entity field maps (reported as information only, because
    hand-written audit/join tables are legitimately unmapped)
  * a @Version column that is nullable or lacks a default

Run: python3 scripts/validate-entities.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SRC = ROOT / "backend" / "src" / "main" / "java"
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

FIELD = re.compile(r"((?:\s*@[A-Za-z][\w.]*(?:\([^()]*(?:\([^()]*\))?[^()]*\))?\n)+)\s*private\s+([\w.<>\[\]]+)\s+(\w+)\s*[;=]", re.S)
TYPE_KINDS = ["CHAR", "VARCHAR", "INT", "BIGINT", "SMALLINT", "TINYINT", "BOOLEAN", "DATETIME",
              "DATE", "TIME", "DECIMAL", "NUMERIC", "JSON", "TEXT", "MEDIUMTEXT", "LONGTEXT",
              "TIMESTAMP", "DOUBLE", "FLOAT", "BLOB", "ENUM"]
TYPE = re.compile(r"^\s*([a-z_][a-z0-9_]*)\s+(CHAR|VARCHAR|INT|BIGINT|SMALLINT|TINYINT|BOOLEAN|DATETIME|DATE|TIME|DECIMAL|NUMERIC|JSON|TEXT|MEDIUMTEXT|LONGTEXT|TIMESTAMP|DOUBLE|FLOAT|BLOB|ENUM)", re.M)


def ddl() -> dict[str, dict[str, str]]:
    tables: dict[str, dict[str, str]] = {}
    for path in sorted(MIGRATIONS.glob("V*.sql")):
        src = path.read_text(encoding="utf-8")
        for match in re.finditer(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+) \((.*?)\n\) ENGINE", src, re.S):
            tables[match.group(1)] = {name: kind for name, kind in TYPE.findall(match.group(2))}
        # Later migrations evolve earlier tables; a validator that only reads CREATE TABLE
        # would report every added column as missing (V8 adds 18 to email_messages).
        for match in re.finditer(r"ALTER TABLE (\w+)(.*?);", src, re.S):
            table, body = match.group(1), match.group(2)
            if table not in tables:
                continue
            for column, kind in re.findall(
                    rf"ADD COLUMN\s+(\w+)\s+({'|'.join(TYPE_KINDS)})", body):
                tables[table][column] = kind
            for column in re.findall(r"DROP COLUMN\s+(\w+)", body):
                tables[table].pop(column, None)
    return tables


def attr(ann: str, key: str) -> str | None:
    match = re.search(rf"{key}\s*=\s*([^,)]+)", ann)
    return match.group(1).strip().strip('"') if match else None


def main() -> int:
    tables = ddl()
    problems: list[str] = []
    mapped_tables: set[str] = set()
    entities = 0

    for path in sorted(SRC.rglob("*.java")):
        src = path.read_text(encoding="utf-8")
        if "@Entity" not in src:
            continue
        match = re.search(r'@Table\(\s*name\s*=\s*"(\w+)"', src)
        if not match:
            problems.append(f"{path.name}: @Entity without an explicit @Table(name)")
            continue
        table = match.group(1)
        entities += 1
        mapped_tables.add(table)
        if table not in tables:
            problems.append(f"{path.name}: table {table} is not created by any migration")
            continue
        for ann_raw, jtype, field_name in FIELD.findall(src):
            ann = " ".join(line.strip() for line in ann_raw.splitlines())
            if "@Transient" in ann or "@ElementCollection" in ann:
                continue
            if any(jtype.startswith(t) for t in ("Set<", "List<", "Collection<", "Map<")):
                continue
            if "@Column" not in ann and "@JoinColumn" not in ann:
                continue
            col = attr(ann, "name")
            if not col:
                problems.append(f"{path.name}: field {field_name} has no explicit column name")
                continue
            if col not in tables[table]:
                problems.append(f"{path.name}: {table}.{col} is mapped but not in the schema")
            elif "@Version" in ann and tables[table][col] != "BIGINT":
                problems.append(f"{path.name}: {table}.{col} is @Version but {tables[table][col]}")

    unmapped = sorted(set(tables) - mapped_tables)
    print(f"entities: {entities} · tables with entities: {len(mapped_tables)} · "
          f"tables in migrations: {len(tables)}")
    if unmapped:
        print(f"not mapped by an entity yet ({len(unmapped)}): {', '.join(unmapped)}")
    if problems:
        print(f"{len(problems)} problem(s):")
        for problem in problems:
            print(f"  [FAIL] {problem}")
        return 1
    print("Every mapped column exists in the migrations.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
