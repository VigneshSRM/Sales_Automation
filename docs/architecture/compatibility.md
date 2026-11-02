# Compatibility and version matrix

**Decision:** Java 17 · Spring Boot 4.0.9 · Apache Tomcat 11 · MariaDB 11.4 (LTS).
This document records *why*, and what had to be verified before pinning.

## 1. Constraint set

The specification mandates Java 17, Spring Boot, and deployment as a WAR on **Tomcat 11**.
Tomcat 11 implements **Jakarta Servlet 6.1 / Jakarta EE 11**, which is a higher baseline than the
Servlet 6.0 used by Spring Boot 3.5. Those two facts decide the framework version.

## 2. Verified facts

| Fact | Source | Consequence |
|---|---|---|
| Spring Boot 4.0 raises the baseline to Spring Framework 7, Spring Security 7, Jackson 3, Hibernate 7, and **Tomcat 11.0.x / Servlet 6.1** | Spring Boot 4.0 release notes & migration guide | Boot 4 is the only Boot line that *officially* supports Tomcat 11 |
| Spring Framework 7 baseline is **Java 17** (Java 21/25 recommended) | Boot 4 release notes, Framework 7 announcement | Java 17 remains supported → spec requirement satisfied without a Java upgrade |
| Spring Boot 3.5 reached OSS end-of-life before this project's go-live; it targets Servlet 6.0 | Spring release calendar | Boot 3.5 would be both unsupported and formally incompatible with Tomcat 11 |
| Boot 4 is a **modular, renamed** starter set (`spring-boot-starter-webmvc`, `-flyway`, `-restclient`, `-jackson`, and `-<tech>-test` companions) | Boot 4.0 migration guide, "Starters" table | `pom.xml` uses the new names only; `spring-boot-starter-web`/`-test` are **not** used |
| `springdoc-openapi` 3.x is the Boot 4.x line; 3.0.3 targets Boot 4.0.x | springdoc documentation | Pinned to 3.0.3 (3.1+/3.2+ target Boot 4.1+, which would be an unverified pairing) |
| Boot 4.0.9 is the newest 4.0.x patch (4.1.2 is the newest minor) | `spring-projects/spring-boot` release list | Pinned to the mature 4.0.x patch line; 4.1.x is the documented upgrade path |

## 3. Resulting matrix

| Component | Version | Notes |
|---|---|---|
| JDK | 17 (LTS) | `maven.compiler.release=17` |
| Spring Boot | 4.0.9 | manages Spring Framework 7.0.x, Security 7.0.x, Hibernate 7.1.x, Jackson 3.x, Tomcat 11.0.x |
| Servlet API | 6.1 (provided) | Tomcat supplies it — `spring-boot-starter-tomcat` is `provided` |
| Tomcat | 11.0.x | External installation; WAR deployment, not the embedded launcher |
| MariaDB | 11.4 LTS | `mariadb-java-client`, `flyway-mysql` |
| springdoc-openapi | 3.0.3 | OpenAPI 3.1 |
| Jackson | 3.x (`tools.jackson.*`) | Boot 4 default; annotations remain `com.fasterxml.jackson.annotation` |
| BouncyCastle | bcprov-jdk18on 1.78.1 | Base32/secure random for TOTP |
| Node | 22 LTS | Frontend build only |
| Next.js / React / TypeScript | 16.4.3 / 19.3.1 / 5.9 | Static export |

## 4. Compatibility proof (release 0 evidence)

The "prove it compiles and boots" step must be executed on any machine with a JDK, because the
authoring environment had neither JDK nor Maven nor network access to Maven Central.

```bash
# 1. dependency resolution against the pinned matrix
mvn -q dependency:tree -Dscope=compile | grep -E "spring-boot|tomcat|jackson|hibernate"
#    expect: spring-boot 4.0.9, spring-core 7.0.x, spring-security-core 7.0.x,
#            tomcat-embed-core 11.0.x (provided), tools.jackson.core:jackson-databind 3.x

# 2. compile + unit tests
mvn -q verify

# 3. WAR structure: Servlet 6.1 descriptors, no embedded launcher requirement
python3 scripts/verify-war.py target/infeara-sales-os.war

# 4. deploy to Tomcat 11 and confirm the context starts and Flyway migrates
$CATALINA_HOME/bin/catalina.sh run
curl -s localhost:8080/infeara-sales-os/actuator/health
```

**Known risks to confirm in step 2** (they are the parts most sensitive to Boot 4 changes):

1. Jackson 3 package move to `tools.jackson.*` — isolated in `common/json/JsonCodec` and `config`.
2. Spring Security 7 CSRF/session APIs — isolated in `config/SecurityConfig`.
3. `spring-boot-starter-*-test` companions for `@WebMvcTest`, `@DataJpaTest`, `@WithMockUser`.
4. Flyway 11 + MariaDB module pairing (`flyway-mysql`).

If any of these fails, the fix is local to the named file — the rest of the codebase uses Jakarta
and Spring APIs that are stable across the 3.x → 4.x transition.

## 5. Deployment target compatibility

| Requirement | Tomcat 11 | Evidence |
|---|---|---|
| Servlet 6.1 | ✅ | `spring-boot-starter-tomcat` scope `provided`, descriptor-free deployment |
| `SpringBootServletInitializer` | ✅ | `SalesApplication` extends it |
| Java 17 runtime | ✅ | Tomcat 11 requires Java 17+ |
| UTF-8 + `Asia/Kolkata` | ✅ | `JAVA_OPTS` and `application.yml` (see deployment guide) |
