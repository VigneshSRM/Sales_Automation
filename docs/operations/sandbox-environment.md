# Build/deploy environment reality check (sandbox)

Recorded on 2026-11-01, updated 2026-11-02 in the Arena sandbox, so the team knows exactly which steps were *executed*
and which are only *scripted*.

## 1. What is installed

| Tool | State | How |
|---|---|---|
| Java runtime | ✅ **Java 25.0.2 (Temurin), JRE only** | `pip install jdk4py` → `jdk4py.JAVA_HOME` (no `javac`, no `jar`) |
| Node / npm | ✅ Node 22.22.3, npm 10.9.8 | pre-installed |
| Python | ✅ 3.11 | pre-installed |
| JDK 17 (full, with `javac`) | ❌ not obtainable | Adoptium binaries are GitHub *release assets*, served from `release-assets.githubusercontent.com` → blocked |
| Maven 3.9 | ❌ not obtainable | Maven Central (`repo1.maven.org`, `repo.maven.apache.org`) unreachable; mvnd/maven binaries are GitHub release assets → blocked |
| Apache Tomcat 11 | ❌ not obtainable | `downloads.apache.org`, `archive.apache.org`, `dlcdn.apache.org` unreachable; Tomcat has no GitHub release assets |
| MariaDB | ❌ not obtainable | Debian/Ubuntu archives unreachable; no Docker daemon; no root (`apt` cannot even write its lists) |

## 2. Evidence (actual commands, actual results)

```
$ curl -o /dev/null -w '%{http_code}' https://repo1.maven.org/maven2/            -> 000
$ curl -o /dev/null -w '%{http_code}' https://repo.maven.apache.org/maven2/      -> 000
$ curl -o /dev/null -w '%{http_code}' https://downloads.apache.org/tomcat/       -> 000
$ curl -o /dev/null -w '%{http_code}' https://archive.apache.org/dist/tomcat/    -> 000
$ curl -L  https://github.com/adoptium/temurin17-binaries/releases/download/...  -> 302 → SSL_ERROR_SYSCALL at release-assets.githubusercontent.com
$ curl -o /dev/null -w '%{http_code}' https://registry.npmjs.org/                -> 200
$ curl -o /dev/null -w '%{http_code}' https://pypi.org/simple/jdk4py/            -> 200
$ curl -o /dev/null -w '%{http_code}' https://codeload.github.com/apache/tomcat/tar.gz/refs/tags/11.0.0-M1 -> 200 (source tarball only)
```

`codeload` can deliver **source**, but Tomcat/Maven/Spring artifacts are **binaries** published on
Maven Central or GitHub release assets — both blocked. Building Tomcat from source would itself
require Ant plus Maven Central.

## 3. Consequence

A WAR can be **assembled and inspected** here, and the frontend can be **built and served** here, but
the Spring Boot backend **cannot be compiled or run inside this sandbox**. Everything needed to do it
on any normal machine is scripted and documented:

* `scripts/install-toolchain.sh` — JDK 17 (Temurin), Maven 3.9, Tomcat 11.0.x, MariaDB client check.
* `scripts/build-war.sh` — frontend export → `backend/src/main/resources/static`, `mvn package`,
  then `scripts/verify-war.py` on the produced WAR. Produces **one** WAR with UI + API and the servlet
  container at `provided` scope (Tomcat supplies the container, as the spec requires).
* `scripts/host-app.sh` — provisions MariaDB, renders `bin/setenv.sh`, deploys `ROOT.war`, starts
  Tomcat 11 and blocks until `/actuator/health` reports `UP`.
* `scripts/serve-ui-static.sh` — serves the built UI from the sandbox for design review only
  (clearly labelled: no API is running).
* `scripts/verify-toolchain.sh` — refuses to build unless the pinned versions are present.
* `deploy/tomcat/setenv.sh`, `deploy/tomcat/context.xml` — container configuration.

`scripts/verify-war.py` is executable here, so the *packaging contract* is testable even though the
build is not, and `scripts/verify-war-selftest.py` proves the checker both accepts a conforming
archive and rejects a broken one.

## 4. What was actually executed on 2026-11-02

| Step | Executed here? | Evidence |
|---|---|---|
| Frontend type-check | ✅ yes | `npx tsc --noEmit` → no output (clean) |
| Frontend unit tests | ✅ yes | `npx vitest run` → **28 passed** (4 files) |
| Frontend production build + export into the backend | ✅ yes | `npm run build` → 22 routes prerendered, "UI export copied to …/resources/static" |
| UI served for design review | ✅ yes | `./scripts/serve-ui-static.sh` → HTTP 200 on `/`, `/login/`, `/engagement/mailbox/`, `/engagement/mailbox/settings/`, `/engagement/mailbox/rules/` |
| WAR packaging contract checker | ✅ yes | `python3 scripts/verify-war-selftest.py` → SELFTEST PASSED (24 checks, good archive accepted, bad archive rejected for 14 reasons) |
| Schema/entity drift check | ✅ yes | `python3 tools/schema_report.py` → 49 entities, 82 tables, **no column mismatches** |
| Java symbol audit (compile surrogate) | ✅ yes | `python3 tools/java_symbol_audit.py` → 215 types, 1011 call sites verified, 0 missing |
| Capability audit | ✅ yes | `python3 tools/capability_audit.py` → 66 seeded / 44 enforced / 0 used-but-unseeded after V10 fixed three real 403 bugs |
| `mvn package` (compile the backend) | ❌ **no** | no `javac` in the sandbox; see §1 |
| Flyway migration against MariaDB | ❌ **no** | no MariaDB server available |
| Tomcat deployment of the WAR | ❌ **no** | no Tomcat binary obtainable and nothing to deploy |

The last three rows are the honest limit of this environment. They are fully scripted
(`install-toolchain.sh` → `build-war.sh` → `host-app.sh`) and their preconditions are verified by
`verify-toolchain.sh`; a machine with normal egress performs them without edits.

## 5. Running the same steps where downloads are permitted

```bash
sudo ./scripts/install-toolchain.sh          # JDK 17 + Maven 3.9 + Tomcat 11.0.x + MariaDB 11.4
./scripts/verify-toolchain.sh                # must print "toolchain matches the pinned stack"
./scripts/build-war.sh --with-tests          # UI export + mvn package + WAR contract checks
sudo ./scripts/host-app.sh                   # DB, setenv.sh, ROOT.war, health check
curl -s localhost:8080/actuator/health       # {"status":"UP"}
```
