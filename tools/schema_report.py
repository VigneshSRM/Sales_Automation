"""Introspects JPA entity sources and reports the physical columns each entity expects.

Used to keep Flyway migrations in lock-step with the entity model (Hibernate runs with
ddl-auto=validate in every environment, so a missing/renamed column fails fast at boot).
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                    "backend", "src", "main", "java")

ENTITY_RE = re.compile(r"@Entity\b")
TABLE_RE = re.compile(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"')
FIELD_RE = re.compile(
    r'((?:@[A-Za-z]+(?:\([^)]*\))?\s*)*)'
    r'private\s+([A-Za-z0-9_.<>\[\], ]+?)\s+([a-zA-Z0-9_]+)\s*(?:=|;)',
    re.S)


def camel(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def scan():
    tables = {}
    joins = []
    for dirpath, _dirs, files in os.walk(ROOT):
        for fname in files:
            if not fname.endswith(".java"):
                continue
            path = os.path.join(dirpath, fname)
            src = open(path, encoding="utf-8").read()
            if not ENTITY_RE.search(src):
                continue
            table = TABLE_RE.search(src)
            if not table:
                print(f"!! entity without @Table: {path}", file=sys.stderr)
                continue
            cols = []
            for ann, jtype, fname_field in FIELD_RE.findall(src):
                if "@Id" in ann or "@Column" in ann:
                    col = re.search(r'name\s*=\s*"([^"]+)"', ann)
                    if col:
                        cols.append(col.group(1))
                    elif "@Column" in ann:
                        cols.append(camel(fname_field))
                if "@JoinColumn" in ann:
                    jc = re.search(r'name\s*=\s*"([^"]+)"', ann)
                    joins.append((table.group(1), jc.group(1) if jc else camel(fname_field) + "_id", jtype))
            tables[table.group(1)] = {"file": path, "columns": cols}
    return tables, joins


def parse_migrations():
    mig_dir = os.path.join(os.path.dirname(ROOT), "resources", "db", "migration")
    found = {}
    for fname in sorted(os.listdir(mig_dir)):
        src = open(os.path.join(mig_dir, fname), encoding="utf-8").read()
        for m in re.finditer(r"CREATE TABLE (\w+) \((.*?)\n\) ENGINE", src, re.S):
            table, body = m.group(1), m.group(2)
            cols = []
            depth = 0
            current = ""
            for line in body.splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("--"):
                    continue
                if depth == 0 and re.match(r"^(PRIMARY KEY|UNIQUE KEY|KEY|CONSTRAINT|INDEX)", stripped):
                    continue
                name = stripped.split()[0]
                if re.match(r"^[a-z][a-z0-9_]*$", name):
                    cols.append(name)
            found.setdefault(table, {"file": fname, "columns": []})
            found[table]["columns"] = cols
        # Later migrations evolve tables in place; a validate-mode boot fails if these
        # columns are not accounted for, so follow ALTER TABLE ... ADD COLUMN too.
        for m in re.finditer(r"ALTER TABLE (\w+)(.*?);", src, re.S):
            table, body = m.group(1), m.group(2)
            if table not in found:
                continue
            for add in re.finditer(r"ADD COLUMN\s+(\w+)", body):
                if add.group(1) not in found[table]["columns"]:
                    found[table]["columns"].append(add.group(1))
            for drop in re.finditer(r"DROP COLUMN\s+(\w+)", body):
                if drop.group(1) in found[table]["columns"]:
                    found[table]["columns"].remove(drop.group(1))
    return found


def main():
    tables, joins = scan()
    migrations = parse_migrations()
    print(f"entities: {len(tables)}  tables in migrations: {len(migrations)}")
    missing_tables = sorted(set(tables) - set(migrations))
    if missing_tables:
        print("\nTABLES MISSING FROM MIGRATIONS:")
        for t in missing_tables:
            print(f"  {t}  (entity {tables[t]['file']})")

    for table in sorted(set(tables) & set(migrations)):
        ent = set(tables[table]["columns"])
        mig = set(migrations[table]["columns"])
        for _t, jc, _j in joins:
            if _t == table:
                ent.add(jc)
        only_entity = sorted(ent - mig)
        only_migration = sorted(mig - ent)
        if only_entity or only_migration:
            print(f"\n{table}:")
            if only_entity:
                print(f"  entity expects but migration lacks: {only_entity}")
            if only_migration:
                print(f"  migration has but no entity field: {only_migration}")

    extra = sorted(set(migrations) - set(tables))
    if extra:
        print(f"\ntables with no entity yet: {extra}")


if __name__ == "__main__":
    main()
