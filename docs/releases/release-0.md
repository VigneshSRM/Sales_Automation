# Release 0 — Architecture, compatibility proof and schema strategy

**Goal:** prove the mandated stack is internally consistent *before* writing feature code, and pin
exact versions.

## Delivered

* `docs/architecture/system-architecture.md` — modular monolith, layering, runtime topology.
* `docs/architecture/architecture-decisions.md` — ADR-001…ADR-014 (no Redis → MariaDB outbox/jobs;
  WAR on Tomcat 11; capability RBAC; hash-chained audit; static UI export inside the WAR; no demo
  data; secrets encrypted at rest; etc.).
* `docs/architecture/compatibility.md` — the verified matrix: Java 17 · Spring Boot 4.0.9
  (Framework 7.0.x, Security 7.0.x, Hibernate 7.1.x, Jackson 3.x) · Servlet 6.1 / Tomcat 11.0.x ·
  MariaDB 11.4 · springdoc 3.0.3. Includes why Boot 3.5 was rejected (Servlet 6.0 only, OSS EOL).
* `backend/pom.xml` — WAR packaging, `spring-boot-starter-tomcat` **provided**, Boot 4 starter names,
  JaCoCo, Testcontainers, ArchUnit.
* Schema strategy: Flyway is the only schema authority; `ddl-auto=validate` in every profile;
  `tools/generate_schema.py` derives `V1__core_schema.sql` from the JPA entities so the two cannot
  drift.

## Verification performed

| Item | Result |
|---|---|
| Version compatibility researched from primary sources (Boot 4 release/migration notes, springdoc, Calendly webhook docs) | ✅ |
| XML validity of `pom.xml` | ✅ parsed with `xml.dom.minidom` |
| Entity ↔ migration parity tooling | ✅ `tools/schema_report.py` |
| `mvn dependency:tree` / compile | ❌ **not possible** — no JDK/Maven, Maven Central unreachable |

## Known issues at the end of release 0

1. The compatibility proof is documentary, not executable, in this environment. The first build on a
   JDK-equipped machine is the real proof and may require small adjustments (Jackson 3 package move,
   Security 7 CSRF builder, Boot 4 test-starter names).

## Completion statement

Architecture and compatibility deliverables are complete. **No claim is made that the project
compiles.**
