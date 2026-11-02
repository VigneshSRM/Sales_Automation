# Backup and recovery

## 1. What must be backed up

| Asset | Method | Frequency | Retention |
|---|---|---|---|
| MariaDB database | Logical dump + binary log archive | Nightly full, continuous binlog | 30 daily / 12 monthly / 7 years for audit extracts |
| `SMTP_SECRET_ENCRYPTION_KEY` (+ previous keys) | Secret manager export, sealed | On rotation | Lifetime of the data it protects |
| Tomcat configuration (`setenv.sh`, `server.xml`) | Configuration repository | On change | History |
| WAR artifacts | Artifact repository (versioned) | Per release | Current + 2 previous |
| Uploaded files (when file storage ships) | Object storage versioning | Continuous | Per retention policy |

The database alone is **not** a complete backup: without the encryption key, every stored SMTP,
Calendly and AI credential becomes unrecoverable and must be re-entered.

## 2. Nightly logical backup

```bash
#!/usr/bin/env bash
set -euo pipefail
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
DEST=/var/backups/infeara
mkdir -p "$DEST"
mariadb-dump --single-transaction --quick --routines --triggers \
  --default-character-set=utf8mb4 \
  -h "$DB_HOST" -u "$DB_USER" -p"$DB_PASSWORD" infeara_sales \
  | gzip -9 > "$DEST/infeara_sales_$STAMP.sql.gz"
sha256sum "$DEST/infeara_sales_$STAMP.sql.gz" > "$DEST/infeara_sales_$STAMP.sha256"
# keep 30 days
find "$DEST" -name 'infeara_sales_*.sql.gz' -mtime +30 -delete
```

`--single-transaction` gives a consistent InnoDB snapshot without locking the application.

Point-in-time recovery needs binary logs:

```ini
# /etc/mysql/conf.d/infeara.cnf
[mysqld]
log_bin = /var/log/mysql/mysql-bin
binlog_expire_logs_seconds = 604800
server_id = 1
```

## 3. Restore procedure (target: RPO 15 min, RTO 2 h)

1. Stop Tomcat (`systemctl stop tomcat11`) to prevent writes.
2. Create an empty database with the same charset/collation.
3. Restore the most recent dump, then replay binary logs up to the failure point:
   ```bash
   gunzip -c infeara_sales_20261101T010000Z.sql.gz | mariadb -u root -p infeara_sales
   mariadb-binlog --stop-datetime="2026-11-01 09:29:00" /var/log/mysql/mysql-bin.000042 \
     | mariadb -u root -p infeara_sales
   ```
4. Restore the encryption key material **before** starting the application.
5. Start Tomcat; Flyway validates that the schema matches the deployed WAR.
6. Verify:
   ```sql
   SELECT count(*) FROM audit_logs;
   SELECT status, count(*) FROM jobs GROUP BY status;
   SELECT count(*) FROM outbox_events WHERE status = 'PENDING';
   ```
   Then sign in, open the dashboard, and confirm `GET /api/v1/admin/automation-health`.
7. Re-enable the worker (`WORKER_ENABLED=true`) last, so a backlog is processed deliberately.

## 4. Rehearsal schedule

| Test | Cadence | Success criterion |
|---|---|---|
| Restore into a scratch instance | Monthly | Application boots, `ddl-auto=validate` passes, smoke tests green |
| Point-in-time recovery drill | Quarterly | Data loss ≤ 15 min, restore ≤ 2 h |
| Key-rotation drill (previous-key decryption) | Quarterly | Existing SMTP profiles still send after rotation |
| Audit-chain verification | Monthly | Hash chain has no gap (see below) |

## 5. Audit-chain verification

`audit_logs` is append-only and each row hashes the previous row. Verify a workspace chain:

```sql
SELECT id, prev_hash, record_hash, occurred_at
  FROM audit_logs
 WHERE workspace_id = ? 
 ORDER BY occurred_at ASC, id ASC;
```

Recompute `SHA-256(prev_hash|id|workspace_id|actor_type|actor_user_id|action|object_type|object_id|outcome|before_json|after_json|correlation_id)`
for each row and compare. Any mismatch means the row was altered outside the application and must be
treated as a security incident.
