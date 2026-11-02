# Architecture Decision Records (ADRs)

Format: Context → Decision → Consequences → Alternatives → Status. Decisions marked **Approved**
reflect the mandated constraints in the SDLC specification; decisions marked **Proposed** need
product-owner sign-off and are recorded as ambiguities where relevant.

---

## ADR-001 — Runtime matrix: Java 17 + Spring Boot 4.0.x + external Tomcat 11 (Servlet 6.1)

**Status:** Approved (constraint-driven) · **Date:** 2026-11-01 · **Gate:** Release 0 → Release 2

**Context.** The specification mandates Java 17 and *local Apache Tomcat 11* with a WAR. Tomcat 11.0
implements Jakarta EE 11 / **Servlet 6.1**. Spring Boot 3.5's officially documented servlet container
support stops at **Tomcat 10.1 (Servlet 6.0)**; Spring Boot 3.5 reached open-source end of life on
2026-06-30. Spring Boot 4.x (Spring Framework 7, Spring Security 7) keeps a **Java 17 baseline**,
targets Jakarta EE 11 and manages **Tomcat 11.0.x** for embedded use, so the same Servlet 6.1 API is
present in both embedded and external deployments.

**Decision.** Pin `spring-boot-starter-parent` **4.0.x** (currently 4.0.9 line), Java
`<release>17</release>`, WAR packaging, `spring-boot-starter-tomcat` scope `provided`,
`spring-boot-starter-webmvc`, `springdoc-openapi-starter-webmvc-ui` **3.0.x** (the only springdoc line
built against Boot 4.0), Jackson 3 (`tools.jackson.*`) as managed by Boot, Hibernate/JPA managed by
Boot, MariaDB Connector/J 3.x, Flyway managed by Boot 4 (with the Flyway starter module).

**Verified facts (documentation-based).**
- Boot 4 minimum Java = 17; Tomcat 11.0.x requires Java 17+ and implements Servlet 6.1. 
- Boot 3.5 officially supports only Tomcat 10.1.25+ (Servlet 6.0) → using Boot 3.5 on Tomcat 11 is
  unsupported and therefore rejected for this project.
- springdoc-openapi 3.x ↔ Boot 4.0.x; springdoc 2.x ↔ Boot 3.x.

**Compatibility proof (executable gate, run in a JDK-17 + Maven environment):**
`deploy/compat-poc/` contains a minimal WAR (`CompatServlet`, one controller, Actuator health) and
`compat-matrix.md` with the pinned versions and the deployment checklist. Exit gate for Release 0:
the PoC WAR starts on Tomcat 11, `/actuator/health` = `UP`, JSP/Servlet-6.1 classloading clean, and
the WAR contains no `jakarta.servlet-api` copy in `WEB-INF/lib`.
> Environment note: the delivery sandbox used to prepare this repository had **no JDK or Maven and no
> access to Maven Central**, so the PoC WAR could not be executed here. The proof procedure is fully
> scripted (`deploy/compat-poc/run-poc.sh`) and must be run as the first action of Release 1 on the
> target machine. This is tracked as `KI-001` in `DEVELOPMENT_STATUS.md`.

**Consequences.** Code must use Boot 4 module names (`spring-boot-starter-webmvc`,
`…-webmvc-test`, `…-data-jpa-test`, `…-security-test`) and Jackson 3 imports; Jackson usage is
centralised in `common.json.JsonCodec` so a downgrade/upgrade is a single-file change.

---

## ADR-002 — Modular monolith, not microservices

**Status:** Approved. **Decision.** One deployable WAR with package-level modules and ArchUnit
boundary tests. Rationale: on-premises single/few-node deployment, transactional consistency across
CRM + outbox, simplest operations, still allows later extraction because module ports are explicit.
**Consequences.** Discipline enforced by tests (`ModuleBoundaryTest`); a transaction may span CRM +
outbox but never a network call.

## ADR-003 — MariaDB transactional outbox + job tables instead of Redis/queue

**Status:** Approved (no-Redis constraint). **Decision.** `outbox_events`, `jobs`, `job_executions`,
`inbox_events` with atomic claim, leases, backoff, dead-letter, idempotency keys.
**Consequences.** Polling load must be bounded (batch size, tick budget, indexed `(status,
available_at)`), workers are horizontally safe, and worker crash recovery relies on lease expiry plus
idempotency. Metrics for backlog/oldest job are mandatory.

## ADR-004 — Frontend bundled into the WAR as static export, same origin

**Status:** Approving (AMB-01), **implementation refined 2026-11-02**. **Decision.** Next.js
`output: 'export'` builds `frontend/out`; the build's postbuild step copies it to
`backend/src/main/resources/static`, so `mvn package` produces one WAR containing the API and the UI.
The WAR is deployed as **ROOT.war**, and `config/WebConfig` resolves a directory request to its
`index.html` (`/engagement/mailbox/` → `static/engagement/mailbox/index.html`) because the export uses
`trailingSlash: true` and Tomcat's welcome-file mechanism does not apply to DispatcherServlet-served
resources.
**Consequences.** No CORS, no second TLS endpoint, no Node runtime in production; SSR/ISR unavailable
(acceptable for an authenticated internal SPA; public pages are Calendly-hosted). The ROOT context is
required because the export emits absolute `/_next/...` URLs; deploying under a named context
(`/infeara-sales-os`) requires rebuilding the UI with `NEXT_BASE_PATH` set to that prefix. This
supersedes the earlier `/ui` sub-path plan. `scripts/build-war.sh` produces both
`infeara-sales-os.war` and `ROOT.war`; `scripts/verify-war.py` asserts the asset-URL invariant.

## ADR-005 — Session-cookie authentication with CSRF, plus optional stateless API tokens

**Status:** Approved. **Decision.** Browser clients use server-side sessions (`SPRING_SESSION`-less:
`user_sessions` table) with HttpOnly/Secure/SameSite=Lax cookies and a CSRF token for mutations.
Machine clients may use documented bearer tokens (hashed at rest, scoped, revocable).
**Consequences.** No JWT-in-localStorage (XSS risk); session revocation is immediate on deactivation.

## ADR-006 — AI provider neutrality; Copilot excluded from runtime

**Status:** Approved. **Decision.** `AiProvider` SPI with OpenRouter and OpenAI adapters; provider
codes/models/endpoints/secrets are workspace configuration rows; the gateway enforces budget, rate,
timeout, retry and JSON-Schema validation. GitHub Copilot is **not** a runtime inference provider
because no officially documented, licensed general-purpose chat-completions API with supported
authentication can be assumed; if such an entitlement is confirmed, a `CopilotProvider` adapter can be
added without touching business modules.

## ADR-007 — Inbound email via adapter port; IMAP installed as default

**Status:** Proposed (AMB-03). **Decision.** `InboundMailAdapter` SPI; `ImapInboundAdapter` shipped
(incremental UID watermark, Message-ID dedupe, thread matching); mailbox-API and inbound-webhook
adapters can be added per provider. SMTP is never used for inbound (protocol limitation).

## ADR-008 — Money, currency and FX

**Status:** Approved for storage, Proposed for FX policy (AMB-04). **Decision.** `DECIMAL(19,4)` +
ISO-4217 `CHAR(3)`. Aggregations group by currency. Consolidation requires a configured rate source
and rate date; until configured, dashboards show per-currency totals and never silently convert.

## ADR-009 — Calendly as scheduling system of record; webhook-first synchronisation

**Status:** Approved. **Decision.** No native availability engine (spec §9). Webhooks are verified
(HMAC-SHA256 over `{t}.{rawBody}`, ±180 s), persisted, deduplicated by provider event id, then
processed asynchronously. API calls fill gaps and reconcile. Failures produce pending sync jobs and a
stale-sync indicator — never fabricated availability.

## ADR-010 — Optimistic locking + unique constraints as the concurrency backbone

**Status:** Approved. **Decision.** `@Version row_version` on editable aggregates (`If-Match` /
`rowVersion` in DTOs, 409 on conflict); unique constraints for idempotency keys, provider ids,
normalised identifiers and active enrollments; `SELECT … FOR UPDATE` avoided in request paths.

## ADR-011 — Secret management

**Status:** Approved. **Decision.** AES-256-GCM envelope encryption (`SecretCipher`) with key material
from environment/OS keystore (never DB, never repo), `key_id` stored beside ciphertext for rotation,
`secrets_audit` on rotation, secrets never serialised into DTOs, redaction in logs (Logback pattern +
`SensitiveDataMasker`).

## ADR-012 — Spring Modulith-style verification via ArchUnit instead of extra runtime

**Status:** Approved. **Decision.** Use Spring Boot 4 standard stack + ArchUnit tests to enforce module
boundaries; avoid adding Spring Modulith runtime until it is released/validated for Boot 4 (avoid an
unverifiable dependency). Revisit after the PoC.

## ADR-013 — Testing stack

**Status:** Approved. **Decision.** JUnit 5 + Mockito + AssertJ, `@SpringBootTest` slices,
**Testcontainers MariaDB 10.11** for repository/API tests, GreenMail-style SMTP test server for outbound
tests, WireMock for AI/Calendly contract tests, Playwright for E2E, Vitest + Testing Library for the
frontend. Performance: Gatling scenarios kept in `backend/perf` (not run in CI by default).

## ADR-014 — No Hibernate schema generation; additive migration policy

**Status:** Approved. **Decision.** `ddl-auto=validate` (test/prod validator) and `none` in prod
runtime; Flyway only. Production migrations are additive first (add column nullable → backfill →
enforce), destructive changes require an approved migration plan and backup evidence.
