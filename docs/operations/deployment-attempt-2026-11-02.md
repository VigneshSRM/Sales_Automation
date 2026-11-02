# Deployment attempt — what actually ran, 2026-11-02

You asked me to run it and show it. This is the record of everything that was executed, including
the failures. Nothing here is a projection.

## 1. Why the *sandbox* cannot host it (unchanged, re-probed today)

```
$ id -u                                   -> 1001        (no root; sudo absent)
$ apt-get update                          -> E: ... Acquire (13: Permission denied)
$ curl https://repo1.maven.org/maven2/    -> 000
$ curl https://repo.maven.apache.org/     -> 000
$ curl https://dlcdn.apache.org/tomcat/   -> 000
$ curl https://archive.apache.org/...     -> 000
$ curl https://downloads.apache.org/...   -> 000
$ curl https://pypi.org/simple/           -> 200
$ curl https://registry.npmjs.org/        -> 200
```

Alternate sources re-checked: `jdk4py` on PyPI ships a **JRE** (no `javac`/`jar`); PyPI has no
OpenJDK/Maven/Tomcat distribution; npm has only type stubs, downloaders for the same blocked hosts, a
Lando (Docker) plugin and an HTTP deployer that needs a Tomcat already running. So this container can
neither compile the backend nor run Tomcat. Confirmed by the gate itself:

```
$ ./scripts/verify-toolchain.sh
java  FAIL not on PATH      javac FAIL not on PATH     maven FAIL not on PATH
tomcat FAIL not found       mariadb FAIL not on PATH   node OK v22.22.3
$ ./scripts/build-war.sh
The pinned toolchain is not installed. Run: sudo ./scripts/install-toolchain.sh
```

## 2. So it is hosted where a JDK exists: GitHub Actions

`.github/workflows/build-and-host-war.yml` runs the identical sequence on `ubuntu-24.04` with a
MariaDB 11.4 service container and a real Apache Tomcat 11.0.27 tarball, and publishes the evidence to
issue #1 on every run.

Most recent run — **`41402405623`, success**
(<https://github.com/VigneshSRM/Sales_Automation/actions/runs/41402405623>):

| Evidence | Result |
|---|---|
| WAR build | `BUILD SUCCESS`, 866 entries, 75.8 MB |
| Packaging contract | 24/24 checks |
| Tomcat 11.0.27 | `ROOT.war` deployed, `Server startup` logged |
| `GET /actuator/health` | `{"groups":["liveness","readiness"],"status":"UP"}` |
| `GET /` · `/leads/` · `/engagement/mailbox/` | 200 · 200 · 200 |
| `GET /api/v1/auth/me` (anonymous) | 401 with the documented envelope |
| `GET /v3/api-docs` | 200, 97 `/api/**` paths |
| Flyway | V1…V10, all `success = 1`; 83 tables |
| Bootstrap | 66 capabilities, 10 roles, 1 Super Admin, audit row written |
| Backend tests | 26/26 `Failures: 0, Errors: 0` |
| Authenticated smoke | **16/16** — login → TOTP enrolment → session → leads/dashboard/email/notifications/audit/catalogue → CSRF-protected write |

## 3. The bug that only this test could find

The first authenticated run (`41402111218`) went red: enrolment returned `AUTHENTICATED` and set
`INF_SESSION`, yet the next nine calls were `401`. The cookie was marked `Secure` because the profile
is `prod`, while the connector is plain HTTP behind a TLS proxy — so every client dropped it.
Fixed in `41402405623` with `app.security.cookie-secure=auto` (request-derived, honours
`X-Forwarded-Proto`) plus `server.forward-headers-strategy: native` in the prod profile. Recorded in
`docs/implementation/KNOWN-ISSUES.md` §Fixed during verification (F-1).

## 4. What still cannot be proven anywhere yet

Live IMAP against a real mailbox, an actual AI provider call, an SMTP send, a Calendly webhook, and
the Testcontainers integration suite. Those need external accounts/credentials, not a better runner.

## 5. Reproduce it yourself

```bash
sudo ./scripts/install-toolchain.sh     # JDK 17, Maven 3.9, Tomcat 11, MariaDB 11.4
./scripts/build-war.sh --with-tests     # UI export -> mvn package -> 24 WAR assertions
sudo ./scripts/host-app.sh              # DB, secrets, ROOT.war, waits for health UP
python3 scripts/smoke-authenticated.py  # optional: the 16-check authenticated probe
# http://localhost:8080/  ->  login   |  /actuator/health -> {"status":"UP"}
```
