#!/usr/bin/env python3
"""Proves that validate-migrations.py still catches the defects Tomcat found.

The two failures that stopped the first real deployment were:
  * error 1072 — a UNIQUE KEY over a column the table does not declare;
  * error 150  — a FOREIGN KEY naming a table that does not exist.
A linter that silently passes everything is worse than no linter, so this selftest
replays both defects against a fixture and requires a rejection.

Run: python3 scripts/validate-migrations-selftest.py
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LINTER = ROOT / "scripts" / "validate-migrations.py"

GOOD = """\
CREATE TABLE workspaces (
    id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE audit_logs (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_ws FOREIGN KEY (workspace_id) REFERENCES workspaces (id)
) ENGINE=InnoDB;
"""

BAD = """\
CREATE TABLE workspaces (
    id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE outbox_events (
    id VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_workspace_dedupe (workspace_id, dedupe_key)
) ENGINE=InnoDB;

CREATE TABLE price_book_items (
    id VARCHAR(36) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_price_book_service FOREIGN KEY (id) REFERENCES service_offerings (id)
) ENGINE=InnoDB;

INSERT INTO forecast_snapshots (id) VALUES ('x');
"""


def run(fixture: str) -> subprocess.CompletedProcess[str]:
    with tempfile.TemporaryDirectory() as tmp:
        sandbox = pathlib.Path(tmp)
        (sandbox / "mig").mkdir()
        (sandbox / "mig" / "V1__fixture.sql").write_text(fixture, encoding="utf-8")
        source = LINTER.read_text(encoding="utf-8").replace(
            'MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"',
            'MIGRATIONS = pathlib.Path(__file__).parent / "mig"',
        )
        (sandbox / "lint.py").write_text(source, encoding="utf-8")
        return subprocess.run([sys.executable, str(sandbox / "lint.py")],
                              capture_output=True, text=True, cwd=sandbox)


good = run(GOOD)
if good.returncode != 0:
    print(good.stdout)
    print("SELFTEST FAILED — the linter rejected a valid fixture")
    raise SystemExit(1)

bad = run(BAD)
out = bad.stdout
checks = {
    "1072 (key over an undeclared column)": "undeclared column dedupe_key" in out,
    "150 (foreign key to an unknown table)": "unknown table service_offerings" in out,
    "data statement target": "data statement touches unknown table forecast_snapshots" in out,
    "non-zero exit": bad.returncode == 1,
}
failed = [name for name, ok in checks.items() if not ok]
print("valid fixture accepted; broken fixture findings:")
for line in out.splitlines():
    if "[FAIL]" in line:
        print("  " + line.strip())
if failed:
    print("SELFTEST FAILED — the linter missed: " + ", ".join(failed))
    raise SystemExit(1)
print("SELFTEST PASSED — the linter accepts a valid set and rejects every seeded defect.")
