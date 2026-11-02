# Test strategy and plan

## 1. Layers

| Layer | Tooling | What it proves | Runnable here? |
|---|---|---|---|
| Domain unit tests | JUnit 5 + AssertJ | Scoring maths, normalisation, duplicate confidence, password policy, lifecycle graphs, TOTP windows | ⚠️ 5 classes authored (`PasswordPolicyTest`, `PageQueryTest`, `SecretCipherTest`, `TotpServiceTest`, `LeadStatusTest`), never run — no JDK |
| Web slice tests | `@WebMvcTest` (`spring-boot-starter-webmvc-test`) + `@WithMockUser` | Envelope shape, status mapping, validation errors, capability enforcement | ❌ |
| Persistence tests | `@DataJpaTest` + Testcontainers MariaDB | Migrations apply, entities validate, queries return the expected rows | ⚠️ `SchemaMigrationIT` authored, never run (needs Docker) |
| Integration tests (`*IT`) | `@SpringBootTest` + Testcontainers + MockMvc | Full request → database → response, including outbox publication | ❌ |
| Architecture tests | ArchUnit | Controllers never touch repositories; domain never imports web | ⚠️ `ArchitectureTest` authored, never run |
| Frontend unit tests | Vitest + Testing Library | Formatting, query building, error mapping, component states | ✅ **22 tests passing** (`npm test`) |
| E2E | Playwright | The 20 mandatory scenarios in `docs/testing/uat-scenarios.md` | ❌ (needs a running stack + browsers) |

## 2. Definition of done for a module

1. Every endpoint has a happy-path and a rejection test.
2. Every business rule in `docs/requirements/business-rules.md` has at least one negative test.
3. Workspace isolation has a cross-tenant test (user A cannot read workspace B's record).
4. Capability enforcement has a test per state-changing endpoint.
5. Migrations are exercised by the persistence tests, not by hand.
6. The UI path that calls the endpoint has loading, empty, error and success states.

A module is not "done" because the screen renders (see `DEVELOPMENT_STATUS.md` for current state).

## 3. Priority test inventory

| Area | Test | Type |
|---|---|---|
| Scoring | `ScoringServiceTest` — weights sum to 100, bands, evidence strings, override path | unit |
| Duplicates | `DuplicateServiceTest` — exact email > domain candidate > fuzzy warning; never auto-merge | unit |
| Leads | `LeadLifecycleTest` — allowed/forbidden transitions, CONVERTED only via conversion | unit |
| Opportunities | `OpportunityGateTest` — won needs close date/amount/decision contact; lost needs reason; skip needs evidence | unit |
| Email | `EligibilityServiceTest` — suppression, withdrawal, disqualification | unit |
| Email | `EmailServiceTest` — four-eyes approval, cancel after hand-off blocked | unit |
| Security | `CapabilityEvaluatorTest`, `ScopeGuardTest` | unit |
| Web | `LeadControllerTest` — 201 + envelope, 422 field errors, 409 stale version | slice |
| Persistence | `LeadRepositoryIT` — filters, sorting allowlist, paging | integration |
| Outbox | `OutboxRelayIT` — claim is atomic, dead-letter after max attempts, replay works | integration |
| Arch | `ArchitectureTest` | arch |

## 4. Manual verification checklist (per release)

- [ ] Sign in, MFA prompt for an admin account, session survives refresh, logout revokes.
- [ ] Capture a lead with a duplicate email → duplicate warning shown, nothing merged.
- [ ] Override a score without a reason → rejected with a field error.
- [ ] Move an opportunity to Closed Won without a decision contact → gate error listing the missing field.
- [ ] Create an SMTP profile → cannot activate before a successful test.
- [ ] Compose an email with a suppressed address → blocked with the reason.
- [ ] Approve your own composed email → rejected (four-eyes).
- [ ] Enrol a lead in a sequence twice → rejected.
- [ ] Stop a sequence after a reply → no further steps queued.
- [ ] Kill the worker mid-run → jobs are reclaimed after the lease expires, no duplicate sends.
