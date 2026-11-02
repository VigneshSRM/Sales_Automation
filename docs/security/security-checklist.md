# Security checklist

Status legend: ✅ implemented · ⚠️ implemented with an open verification item · ❌ not implemented.
Every ✅ below maps to code in this repository; it is *not* a claim of a penetration test.

## Authentication

| Control | Status | Implementation |
|---|---|---|
| Password hashing (adaptive) | ✅ | `PasswordHasher` (BCrypt, cost configured) — never a bespoke hash |
| Password policy | ✅ | `PasswordPolicy`: length ≥ 12, mixed case, digit, symbol, no email/display-name reuse, common-password deny list |
| Account lockout | ✅ | `failed_login_count` + `locked_until`; 5 failures → 15 min (configurable), audit row per attempt |
| Session tokens | ✅ | 256-bit random, stored as SHA-256 hash; cookie `HttpOnly`, `Secure`, `SameSite=Lax` |
| Session expiry and idle timeout | ✅ | `app.security.session-ttl-minutes` / `-idle-minutes`, validated on every request |
| Server-side logout / revocation | ✅ | `user_sessions.revoked_at`, revoked on password change and by admin |
| MFA for privileged roles | ✅ | TOTP (RFC 6238, ±1 step), enrolment required for admin/finance roles, replay protection via `mfa_last_used_step` |
| Credential stuffing resistance | ⚠️ | Rate limiting per IP + per account; **external verification pending** (see open items) |

## Authorization

| Control | Status | Implementation |
|---|---|---|
| Deny-by-default routing | ✅ | `/api/**` requires authentication except auth + webhook paths |
| Capability checks (not role names) | ✅ | `CapabilityEvaluator` in every service method; roles are bundles in the DB |
| Workspace isolation | ✅ | Every query is scoped by `workspaceId` from the authenticated principal, never from the request |
| Record-level scope | ✅ | `ScopeGuard` (OWN/TEAM/WORKSPACE/SHARED) applied to lead/opportunity access |
| Four-eyes on approvals | ✅ | Email approval and template approval reject self-approval |
| Optimistic locking | ✅ | `@Version` + `expectedRowVersion` → `409 STALE_VERSION` |

## Data protection

| Control | Status | Implementation |
|---|---|---|
| Secret encryption at rest | ✅ | SMTP/Calendly/AI credentials: AES-256-GCM, per-record IV, key id recorded for rotation |
| Secrets never returned | ✅ | Response DTOs have no secret fields; audit rows store only masked hints |
| Secrets never logged | ✅ | No request/response body logging; log pattern carries only correlation and workspace ids |
| No hardcoded credentials | ✅ | Everything via environment; bootstrap admin is skipped when unset |
| SQL injection | ✅ | JPA/JPQL with bound parameters; the only `JdbcTemplate` usage is parameterised |
| XSS | ✅ | JSON-only API; template merge values HTML-escaped; UI renders text nodes, no `dangerouslySetInnerHTML` |
| CSRF | ✅ | `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header, SameSite=Lax session cookie |
| PII minimisation | ⚠️ | Fields limited to what the spec requires; retention/erasure workflow not yet implemented |
| File upload validation | ❌ | No upload endpoint yet (schema only) |

## Transport and platform

| Control | Status | Implementation |
|---|---|---|
| TLS termination | ⚠️ | Tomcat connector configuration documented (see deployment guide); certificate management is environment-specific |
| Security headers | ✅ | CSP, `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options: DENY`, HSTS when TLS is on |
| Cookie flags | ✅ | `HttpOnly`, `Secure`, `SameSite=Lax` |
| Rate limiting | ✅ | Bucket per (IP, route-class) with 429 + `Retry-After` |
| Webhook signature verification | ⚠️ | Design + schema in place; Calendly endpoint lands in release 4 |
| Error message hygiene | ✅ | Stack traces never serialised; unknown errors → `INTERNAL_ERROR` + correlation id |
| Dependency hygiene | ⚠️ | Versions pinned; `mvn dependency-check` must be added to CI |
| Audit immutability | ✅ | Append-only table + hash chain; no update/delete endpoints |

## AI-specific (designed, not yet implemented)

| Control | Status | Note |
|---|---|---|
| Provider key isolation | ✅ | Provider credentials encrypted; `ai_providers.api_key_ciphertext` |
| Prompt-injection defence | ⚠️ | Rule: email/attachment/website content is fenced as untrusted data and never concatenated into instructions. Implementation pending release 5 |
| Tool allow-list per agent | ⚠️ | `ai_agent_versions.allowed_tools_json`; enforced in the future gateway |
| Budget and rate limits | ⚠️ | Columns + capability codes exist; enforcement pending |
| Human approval before side effects | ⚠️ | `approvals` table binds payload hash + object version + policy version |

## Open items to close before production sign-off

1. External penetration test and remediation.
2. Dependency vulnerability scan wired into CI (`dependency-check`, `npm audit`).
3. Key rotation rehearsal for `SMTP_SECRET_ENCRYPTION_KEY` (previous-key decryption path).
4. TLS certificate lifecycle ownership and HSTS preload decision.
5. Data-retention and erasure runbook approval.
