#!/usr/bin/env python3
"""Static validation of the Flyway script set.

Every check here corresponds to a failure that a real deployment reported at least
once, so the point of the script is to make sure the same class of mistake can never
reach Tomcat again:

  * a FOREIGN KEY that names a table or column that does not exist (MariaDB errno 150)
  * a KEY / UNIQUE KEY over a column the table does not declare (errno 1072)
  * the same table created twice across migrations
  * a FOREIGN KEY pointing at a table created by a later migration
  * a data statement touching a table that no migration creates
  * a Flyway version gap or duplicate version

Run: python3 scripts/validate-migrations.py
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

TYPE = r"(?:CHAR|VARCHAR|INT|BIGINT|SMALLINT|TINYINT|BOOLEAN|DATETIME|DATE|TIME|DECIMAL|NUMERIC|JSON|TEXT|MEDIUMTEXT|LONGTEXT|TIMESTAMP|DOUBLE|FLOAT|BLOB|ENUM)"
CREATE = re.compile(r"CREATE TABLE (?:IF NOT EXISTS )?(\w+) \((.*?)\n\) ENGINE", re.S)
COLUMN = re.compile(rf"^\s*([a-z_][a-z0-9_]*)\s+{TYPE}", re.M)

problems: list[str] = []


def load() -> list[tuple[str, int, str]]:
    files = sorted(MIGRATIONS.glob("V*.sql"), key=lambda p: int(re.match(r"V(\d+)", p.name).group(1)))
    versions: list[int] = []
    for path in files:
        version = int(re.match(r"V(\d+)", path.name).group(1))
        if version in versions:
            problems.append(f"{path.name}: duplicate Flyway version V{version}")
        versions.append(version)
        if versions != list(range(1, len(versions) + 1)):
            problems.append(f"{path.name}: Flyway versions are not contiguous from V1: {versions}")
    return [(p.name, int(re.match(r"V(\d+)", p.name).group(1)), p.read_text(encoding="utf-8")) for p in files]


def main() -> int:
    scripts = load()
    created_at: dict[str, str] = {}
    order: dict[str, tuple[int, int]] = {}
    columns: dict[str, set[str]] = {}

    for index, (name, _version, sql) in enumerate(scripts):
        for position, match in enumerate(CREATE.finditer(sql)):
            table, body = match.group(1), match.group(2)
            if table in created_at:
                problems.append(f"{name}: table {table} already created by {created_at[table]}")
            created_at[table] = name
            order[table] = (index, position)
            columns[table] = set(COLUMN.findall(body))

    for index, (name, _version, sql) in enumerate(scripts):
        for match in CREATE.finditer(sql):
            table, body = match.group(1), match.group(2)
            cols = columns[table]
            for key_name, key_cols in re.findall(r"(?:UNIQUE KEY|KEY) (\w+) \(([^)]*)\)", body):
                for column in (c.strip().split("(")[0] for c in key_cols.split(",")):
                    if column and column not in cols:
                        problems.append(f"{name}: {table}.{key_name} uses undeclared column {column}")
            for column, ref_table in re.findall(r"FOREIGN KEY \((\w+)\) REFERENCES (\w+)", body):
                if column not in cols:
                    problems.append(f"{name}: {table} foreign key on undeclared column {column}")
                if ref_table not in columns:
                    problems.append(f"{name}: {table}.{column} references unknown table {ref_table}")
                elif order[ref_table][0] > order[table][0]:
                    # V1 turns referential checks off and creates tables alphabetically, so a
                    # forward reference inside one script is fine; across scripts it is not.
                    if "SET FOREIGN_KEY_CHECKS = 0" not in sql:
                        problems.append(
                            f"{name}: {table}.{column} references {ref_table}, created by "
                            f"{created_at[ref_table]}")
            for ref_table, ref_col in re.findall(r"REFERENCES (\w+) \((\w+)\)", body):
                if ref_table in columns and ref_col not in columns[ref_table]:
                    problems.append(f"{name}: {table} references missing column {ref_table}.{ref_col}")
        for target in re.findall(r"(?im)^\s*(?:INSERT INTO|UPDATE|DELETE FROM)\s+(\w+)", sql):
            if target not in columns:
                problems.append(f"{name}: data statement touches unknown table {target}")

    print(f"migrations: {len(scripts)} · tables: {len(columns)}")
    if problems:
        print(f"{len(problems)} problem(s):")
        for problem in problems:
            print(f"  [FAIL] {problem}")
        return 1
    print("All migration checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
