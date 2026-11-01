# Release 1 — Platform foundation

**Scope:** workspace, authentication, MFA, RBAC, sessions, audit, notifications, outbox/jobs,
administration.

## Implemented

| Area | Code |
|---|---|
| Workspace bootstrap (settings, catalogues, stages, scoring model v1, admin user) | `workspace/WorkspaceBootstrapService`, `Workspace`, `WorkspaceSetting` |
| Users, roles, teams, sessions | `identity/*` (13 classes) |
| Password policy, BCrypt hashing, lockout | `identity/PasswordPolicy`, `identity/AuthService` |
| TOTP MFA (RFC 6238, ±1 step, replay guard) | `security/TotpService`, `identity/AuthDtos` |
| Cookie sessions with hashed tokens + CSRF | `config/SecurityConfig`, `security/SessionAuthenticationFilter` |
| Capability RBAC | `security/CapabilityEvaluator`, `V6__seed_rbac.sql` (10 roles, 55 capabilities) |
| Record scope | `security/ScopeGuard` |
| Tamper-evident audit | `audit/AuditRecorder` (per-workspace SHA-256 chain) |
| Notifications with dedupe + preferences | `notification/*` |
| Transactional outbox + durable jobs + lease claim + retry/backoff + dead letter + replay | `automation/outbox/*`, `automation/job/*`, `web/AdminController` |
| Idempotency keys | `common/persistence/IdempotencyService` |
| Rate limiting, security headers, error envelope, correlation ids | `security/RateLimitFilter`, `config/SecurityConfig`, `common/error/*`, `common/web/CorrelationIdFilter` |
| Admin UI + APIs | `web/UserController`, `web/AuditController`, `web/AdminController`, `frontend/app/(app)/admin/*` |

## Migrations

`V1__core_schema.sql` (workspaces, users, roles, sessions, audit, notifications, outbox, jobs,
idempotency, catalogues), `V6__seed_rbac.sql` (capabilities + roles).

## Verification

* Static audit (`tools/static_audit.py`): imports resolve, error codes declared, no duplicate
  mappings.
* **Not compiled, not executed.** No JDK/Maven/MariaDB available in the authoring environment.

## Known issues

1. Build unverified (security filter chain and Boot 4 test starters are the highest-risk areas).
2. No `mvn` run means no ArchUnit enforcement yet; the rules are written but unproven.
3. Password-reset-by-email flow is not implemented (admin-driven reset only).

## Completion statement

Feature code, migrations, API surface, UI and tests are authored. Release is **not** marked verified.
