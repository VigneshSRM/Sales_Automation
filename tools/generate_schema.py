#!/usr/bin/env python3
"""Generates the Flyway DDL for every JPA entity table.

Why this exists
---------------
Hibernate runs with ``ddl-auto=validate`` in every profile, so the physical schema and
the entity model must agree exactly on table and column names. Hand-maintaining 80+
tables drifts silently; this generator reads the entity sources (the authority) and
emits the CREATE TABLE statements, while the hand-authored CONSTRAINTS table below
adds the primary keys, foreign keys, unique keys, indexes and CHECK constraints that
JPA annotations cannot express.

Run:  python3 tools/generate_schema.py
Writes:
    backend/src/main/resources/db/migration/V1__core_schema.sql       (entity tables)
    (constraints are appended into the same file, grouped per table)

The engagement / meetings / AI / commercial tables that do not yet have entities are
authored by hand in V2..V6 and are not touched by this script.
"""
from __future__ import annotations

import os
import re
from dataclasses import dataclass, field

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend", "src", "main", "java")
OUT = os.path.join(ROOT, "backend", "src", "main", "resources", "db", "migration",
                   "V1__core_schema.sql")

CORE_PACKAGES = (
    "workspace", "identity", "audit", "notification", "common", "automation",
    "crm", "sales",
)

# Physical key / index / check constraints. Keys are generated table names.
UNIQUE: dict[str, list[list[str]]] = {
    "workspaces": [["slug"]],
    "workspace_settings": [["workspace_id"]],
    "users": [["workspace_id", "email_normalized"]],
    "roles": [["code"]],
    "role_capabilities": [["role_id", "capability"]],
    "team_members": [["team_id", "user_id"]],
    "user_roles": [["user_id", "role_id"]],
    "user_sessions": [["token_hash"]],
    "industries": [["workspace_id", "code"]],
    "services": [["workspace_id", "code"]],
    "lead_sources": [["workspace_id", "code"]],
    "loss_reasons": [["workspace_id", "code"]],
    "pipeline_stages": [["workspace_id", "code"], ["workspace_id", "order_no"]],
    "scoring_models": [["workspace_id", "version"]],
    "leads": [["workspace_id", "dedupe_key"]],
    "lead_duplicates": [["lead_id", "candidate_type", "candidate_id", "match_type"]],
    "notifications": [["recipient_user_id", "dedupe_key"]],
    "notification_preferences": [["user_id", "notification_type"]],
    "idempotency_keys": [["workspace_id", "scope", "key_value"]],
    "job_executions": [["job_id", "attempt_no"]],
}

INDEXES: dict[str, list[list[str]]] = {
    "users": [["workspace_id", "status"]],
    "user_sessions": [["user_id", "revoked_at"], ["expires_at"]],
    "audit_logs": [["workspace_id", "occurred_at"], ["object_type", "object_id"],
                   ["workspace_id", "action"]],
    "notifications": [["recipient_user_id", "read_at", "created_at"]],
    "outbox_events": [["status", "available_at"]],
    "jobs": [["status", "available_at"], ["workspace_id", "status"]],
    "accounts": [["workspace_id", "name_normalized"], ["workspace_id", "domain_normalized"],
                 ["workspace_id", "owner_user_id"]],
    "contacts": [["account_id"], ["workspace_id", "email_normalized"],
                 ["workspace_id", "phone_e164"], ["workspace_id", "full_name_normalized"]],
    "leads": [["workspace_id", "status", "created_at"], ["workspace_id", "owner_user_id", "status"],
              ["workspace_id", "email_normalized"], ["workspace_id", "phone_e164"],
              ["workspace_id", "domain_normalized"], ["workspace_id", "company_name_normalized"]],
    "lead_score_history": [["lead_id", "calculated_at"]],
    "lead_duplicates": [["workspace_id", "resolution"]],
    "merge_audits": [["workspace_id", "created_at"]],
    "opportunities": [["workspace_id", "stage_id"], ["account_id"],
                      ["workspace_id", "owner_user_id"], ["workspace_id", "close_date"]],
    "stage_history": [["opportunity_id", "created_at"]],
    "activities": [["workspace_id", "related_type", "related_id"],
                   ["workspace_id", "actor_user_id", "occurred_at"]],
    "tasks": [["workspace_id", "owner_user_id", "status", "due_at"],
              ["workspace_id", "status", "due_at"]],
}

CHECKS: dict[str, list[str]] = {
    "leads": ["score IS NULL OR (score BETWEEN 0 AND 100)",
              "status IN ('NEW','RESEARCHING','QUALIFIED','CONTACTED','ENGAGED','MEETING_SCHEDULED',"
              "'DISCOVERY_COMPLETED','CONVERTED','NURTURE','DISQUALIFIED','REOPENED')"],
    "opportunities": ["amount >= 0", "probability BETWEEN 0 AND 100"],
    "pipeline_stages": ["terminal_type IN ('NONE','WON','LOST')"],
    "jobs": ["status IN ('PENDING','CLAIMED','RETRY','COMPLETED','DEAD','CANCELLED')"],
    "outbox_events": ["status IN ('PENDING','CLAIMED','PROCESSED','DEAD')"],
    "activities": ["related_type IN ('LEAD','ACCOUNT','CONTACT','OPPORTUNITY')"],
    "tasks": ["status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')",
              "priority IN ('LOW','NORMAL','HIGH','URGENT')"],
}

# column -> (referenced table, referenced column)
FOREIGN_KEYS: dict[str, dict[str, tuple[str, str]]] = {
    "workspace_settings": {"workspace_id": ("workspaces", "id")},
    "users": {"workspace_id": ("workspaces", "id"), "manager_user_id": ("users", "id")},
    "user_roles": {"user_id": ("users", "id"), "role_id": ("roles", "id")},
    "team_members": {"team_id": ("teams", "id"), "user_id": ("users", "id")},
    "teams": {"workspace_id": ("workspaces", "id"), "manager_user_id": ("users", "id")},
    "user_sessions": {"workspace_id": ("workspaces", "id"), "user_id": ("users", "id")},
    "audit_logs": {"workspace_id": ("workspaces", "id")},
    "notifications": {"workspace_id": ("workspaces", "id"), "recipient_user_id": ("users", "id")},
    "notification_preferences": {"user_id": ("users", "id")},
    "accounts": {"workspace_id": ("workspaces", "id"), "owner_user_id": ("users", "id")},
    "contacts": {"workspace_id": ("workspaces", "id"), "account_id": ("accounts", "id"),
                 "owner_user_id": ("users", "id")},
    "leads": {"workspace_id": ("workspaces", "id"), "owner_user_id": ("users", "id"),
              "account_id": ("accounts", "id"), "contact_id": ("contacts", "id"),
              "industry_id": ("industries", "id"), "source_id": ("lead_sources", "id"),
              "primary_service_id": ("services", "id")},
    "lead_score_history": {"workspace_id": ("workspaces", "id"), "lead_id": ("leads", "id")},
    "lead_duplicates": {"workspace_id": ("workspaces", "id"), "lead_id": ("leads", "id")},
    "opportunities": {"workspace_id": ("workspaces", "id"), "account_id": ("accounts", "id"),
                      "lead_id": ("leads", "id"), "primary_contact_id": ("contacts", "id"),
                      "decision_contact_id": ("contacts", "id"),
                      "service_id": ("services", "id"), "stage_id": ("pipeline_stages", "id"),
                      "loss_reason_id": ("loss_reasons", "id"), "owner_user_id": ("users", "id")},
    "stage_history": {"workspace_id": ("workspaces", "id"),
                      "opportunity_id": ("opportunities", "id")},
    "activities": {"workspace_id": ("workspaces", "id")},
    "tasks": {"workspace_id": ("workspaces", "id"), "owner_user_id": ("users", "id")},
}

TYPE_DEFAULTS = {
    "Instant": "DATETIME(6)",
    "LocalDate": "DATE",
    "LocalDateTime": "DATETIME(6)",
    "LocalTime": "TIME",
    "boolean": "BOOLEAN",
    "Boolean": "BOOLEAN",
    "int": "INT",
    "Integer": "INT",
    "long": "BIGINT",
    "Long": "BIGINT",
    "short": "SMALLINT",
    "double": "DOUBLE",
    "Double": "DOUBLE",
    "float": "FLOAT",
    "UUID": "CHAR(36)",
}


@dataclass
class Column:
    name: str
    sql: str
    nullable: bool = True
    default: str | None = None
    note: str = ""


@dataclass
class Table:
    name: str
    entity: str
    columns: list[Column] = field(default_factory=list)


FIELD_RE = re.compile(
    r"((?:\s*@[A-Za-z][\w.]*(?:\([^)]*\))?\n)+)\s*private\s+([\w.<>\[\]]+)\s+(\w+)\s*[;=]",
    re.S,
)


def camel_to_snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def attr(ann: str, key: str) -> str | None:
    m = re.search(rf"{key}\s*=\s*([^,)]+)", ann)
    return m.group(1).strip().strip('"') if m else None


def sql_type(jtype: str, ann: str, field_name: str) -> str:
    if 'columnDefinition = "json"' in ann:
        return "JSON"
    length = attr(ann, "length")
    if jtype == "String":
        if "columnDefinition" in ann:
            m = re.search(r'columnDefinition\s*=\s*"([^"]+)"', ann)
            return m.group(1).upper()
        if length:
            n = int(length)
            if n > 8000:
                return "MEDIUMTEXT"
            if n > 1000:
                return "TEXT"
            return f"VARCHAR({n})"
        return "TEXT"
    if jtype in ("BigDecimal",):
        precision = attr(ann, "precision") or "19"
        scale = attr(ann, "scale") or "2"
        return f"DECIMAL({precision},{scale})"
    if jtype.endswith("[]"):
        return "BLOB"
    if jtype not in TYPE_DEFAULTS:
        # Enum or value object persisted through @Enumerated(EnumType.STRING)
        return f"VARCHAR({length or 32})"
    return TYPE_DEFAULTS[jtype]


def parse_entities() -> list[Table]:
    tables: list[Table] = []
    for dirpath, _dirs, files in os.walk(SRC):
        rel = os.path.relpath(dirpath, os.path.join(SRC, "com", "infeara", "sales"))
        if not any(rel.split(os.sep)[0] == pkg for pkg in CORE_PACKAGES):
            continue
        for fname in files:
            if not fname.endswith(".java"):
                continue
            path = os.path.join(dirpath, fname)
            src = open(path, encoding="utf-8").read()
            table_m = re.search(r'@Table\(\s*name\s*=\s*"(\w+)"', src)
            if "@Entity" not in src or not table_m:
                continue
            table = Table(name=table_m.group(1), entity=fname[:-5])
            for ann_raw, jtype, fname_field in FIELD_RE.findall(src):
                ann = " ".join(line.strip() for line in ann_raw.splitlines())
                if "@ElementCollection" in ann or "@Transient" in ann:
                    continue
                if any(jtype.startswith(t) for t in ("Set<", "List<", "Collection<", "Map<")):
                    # Collection mappings live in a join/collection table, never in a column.
                    # Reading the @Column of a collection field would invent a column that the
                    # entity does not have (that is how a phantom roles.capability appeared).
                    continue
                if "@Id" not in ann and "@Column" not in ann:
                    continue
                col_name = attr(ann, "name") or camel_to_snake(fname_field)
                sql = sql_type(jtype, ann, fname_field)
                nullable = "nullable = false" not in ann
                default = None
                if "insertable = false" in ann and sql.startswith("DATETIME"):
                    default = "CURRENT_TIMESTAMP(6)"
                if col_name == "updated_at" and default:
                    default += " ON UPDATE CURRENT_TIMESTAMP(6)"
                if "@Version" in ann:
                    sql, nullable, default = "BIGINT", False, "0"
                if "@Id" in ann:
                    nullable, default = False, None
                table.columns.append(Column(col_name, sql, nullable, default))
            tables.append(table)
    return tables


def render(tables: list[Table]) -> str:
    parts = [
        "-- =====================================================================",
        "-- V1 — Core schema, generated from the JPA entity model.",
        "-- Regenerate with: python3 tools/generate_schema.py",
        "-- Do not hand-edit: adjust the entities or the generator's constraint",
        "-- tables instead (docs/database/ERD.md explains the model).",
        "-- MariaDB 11.4 / InnoDB / utf8mb4 / all timestamps UTC.",
        "-- =====================================================================",
        "-- Foreign keys are declared inline but parent tables are created in",
        "-- alphabetical order, so referential checks are relaxed for this script only.",
        "SET FOREIGN_KEY_CHECKS = 0;",
        "",
    ]
    names = {t.name for t in tables}
    for table in sorted(tables, key=lambda t: t.name):
        lines = [f"CREATE TABLE {table.name} ("]
        body: list[str] = []
        seen: set[str] = set()
        for col in table.columns:
            if col.name in seen:
                continue
            seen.add(col.name)
            piece = f"    {col.name} {col.sql}"
            if not col.nullable:
                piece += " NOT NULL"
            if col.default:
                piece += f" DEFAULT {col.default}"
            body.append(piece)
        body.append("    PRIMARY KEY (id)")
        for cols in UNIQUE.get(table.name, []):
            fk = FOREIGN_KEYS.get(table.name, {})
            foreign = [c for c in cols if c in fk]
            if foreign:
                # Columns that point at another table are indexed, not unique
                # (A lead may legitimately share an industry, a source, etc.).
                continue
            missing = [c for c in cols if c not in seen]
            if missing:
                # A key over a column the entity does not declare would make MariaDB
                # reject the whole CREATE TABLE (error 1072). Fail the generation
                # instead of emitting a migration that cannot run.
                raise SystemExit(
                    f"!! {table.name}: UNIQUE {cols} references columns absent from the "
                    f"entity: {missing}")
            body.append(f"    UNIQUE KEY uk_{table.name}_{'_'.join(cols)} ({', '.join(cols)})")
        for cols in INDEXES.get(table.name, []):
            missing = [c for c in cols if c not in seen]
            if missing:
                raise SystemExit(
                    f"!! {table.name}: INDEX {cols} references columns absent from the "
                    f"entity: {missing}")
            body.append(f"    KEY ix_{table.name}_{'_'.join(cols)} ({', '.join(cols)})")
        for col, (ref_table, ref_col) in FOREIGN_KEYS.get(table.name, {}).items():
            if col not in seen or ref_table not in names:
                continue
            body.append(
                f"    CONSTRAINT fk_{table.name}_{col} FOREIGN KEY ({col}) "
                f"REFERENCES {ref_table} ({ref_col})"
            )
        for position, check in enumerate(CHECKS.get(table.name, []), start=1):
            body.append(f"    CONSTRAINT ck_{table.name}_{position} CHECK ({check})")
        lines.append(",\n".join(body))
        lines.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;")
        lines.append("")
        parts.append("\n".join(lines))

    # role_capabilities is an @ElementCollection table, not an entity.
    parts.append(
        "CREATE TABLE role_capabilities (\n"
        "    role_id CHAR(36) NOT NULL,\n"
        "    capability VARCHAR(64) NOT NULL,\n"
        "    PRIMARY KEY (role_id, capability),\n"
        "    CONSTRAINT fk_role_capabilities_role FOREIGN KEY (role_id) "
        "REFERENCES roles (id) ON DELETE CASCADE\n"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n"
    )
    parts.append("SET FOREIGN_KEY_CHECKS = 1;")
    return "\n".join(parts)


def main() -> None:
    tables = parse_entities()
    generated = render(tables)
    open(OUT, "w", encoding="utf-8").write(generated)
    print(f"wrote {OUT} with {len(tables)} entity tables")


if __name__ == "__main__":
    main()
