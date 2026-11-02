# Windows deployment guide — Infeara Sales OS on Apache Tomcat 11

Production deployment of the single WAR on **Windows Server 2019 / 2022** (and Windows 10/11 for
local production-like testing). The Linux guide is `docs/operations/installation-setup.md`; the two
describe the same artefact, the same procedure and the same evidence, so read whichever matches your
host and consult the other for environment-variable detail.

**The rule is unchanged on Windows: the server installs a Java 17 runtime and Apache Tomcat 11 —
nothing else.** Every application library is inside the WAR (`WEB-INF/lib`, 101 jars); Tomcat
supplies only the servlet container (`WEB-INF/lib-provided`, 4 jars). Maven and Node.js are
build-machine tools. MariaDB is a separate service, not a library.

---

## 1. Supported Windows topology

| Topology | Status | Notes |
| --- | --- | --- |
| Windows Server 2022: IIS (ARR) → Tomcat 11 service → MariaDB, single host | **Supported, CI-verified** | The topology this guide deploys |
| Windows Server 2019 | **Supported, CI-verified** | Same steps; PowerShell 5.1 is fine |
| Windows 10/11 (developer or small production host) | Supported | Use `scripts/windows` unchanged; see `windows-development.md` |
| Plain HTTP, no proxy | Local/network testing only | `app.security.cookie-secure=auto` (the default in the generated config) |
| Windows containers (`servercore`/`nanoserver`) | Supported | Separate guide: `windows-containers.md` |
| Tomcat 10.1 / 9 / embedded `bootJar` | **Not supported** | The WAR targets Servlet 6.1 |

Verify with the CI job `build-and-host-windows` in `.github/workflows/build-and-host-war.yml` — it
installs JDK 17 + Tomcat 11.0.27 + MariaDB on a `windows-2022` runner, builds the WAR, deploys it
and runs the smoke and authenticated suites. The run's log is the evidence; this document is the
procedure.

---

## 2. Prerequisites

| Component | Version | Where | Notes |
| --- | --- | --- | --- |
| Java | **17 (JDK)** | Host | Temurin zip, installed by `install-toolchain.ps1` |
| Apache Tomcat | **11.0.27** | Host | `windows-x64.zip`, installed as the `InfearaSalesOS` service |
| MariaDB | **11.4** | Host, separate service | MSI; `utf8mb4` + `default-time-zone=+00:00` |
| PowerShell | 5.1 or 7.x | Host | 5.1 is enough; the scripts avoid 7-only syntax |
| IIS + ARR + URL Rewrite | current | Host, optional | Only for TLS termination (`deploy/windows/iis-arr-web.config`) |
| Python 3 | 3.9+ | Build host | Runs `scripts/verify-war.py` |
| Maven 3.9 / Node 22 | pinned | Build host only | `install-toolchain.ps1 -IncludeBuildTools` |
| .NET / VS Build Tools | — | **not required** | No native components |

Disk: ~2 GB (WAR 75.8 MB, Tomcat, JDK, MSI cache). RAM: 4 GB; the worker (outbox relay, job engine,
email dispatcher, inbound discovery) runs inside the same JVM as the API.

Directory layout this guide creates:

```
C:\Program Files\Infeara\
    jdk-17\                        JDK 17 (Temurin)
    tomcat\                        Tomcat 11.0.27   (CATALINA_HOME/CATALINA_BASE)
    maven\                         Maven 3.9.11     (build host only)
C:\ProgramData\Infeara\
    conf\                          infeara.properties, logs.properties  (ACL-restricted)
    logs\                          sales-os.log, error log, Tomcat access log
    my.ini                         MariaDB configuration
```

`ProgramData` (not `Program Files`) for configuration and logs: the config directory holds
credentials and must be ACLed separately from the binaries, and the service account needs write
access to logs but **not** to its own installation directory.

---

## 3. Toolchain installation

Run from an **elevated** PowerShell in the repository checkout:

```powershell
cd C:\src\Sales_Automation
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass   # per-session, not machine-wide

.\scripts\windows\install-toolchain.ps1
.\scripts\windows\verify-toolchain.ps1
```

What `install-toolchain.ps1` does, and why each step exists:

| Step | Detail |
| --- | --- |
| JDK 17 | Temurin zip → `C:\Program Files\Infeara\jdk-17`. No MSI installer, so removal is a directory delete. `JAVA_HOME` and the machine `PATH` are set machine-wide (a service account does not inherit a user's `PATH`). |
| Tomcat 11.0.27 | `windows-x64.zip` from the Apache archive → `C:\Program Files\Infeara\tomcat`. `manager`, `host-manager`, `docs`, `examples` **and the default `ROOT`** are deleted: the WAR provides `ROOT`, and every extra webapp is attack surface. |
| MariaDB 11.4 | Silent MSI, then `my.ini` rendered from `deploy/windows/my.ini.template` into `C:\ProgramData\Infeara\my.ini`. |
| Maven / Node | Only with `-IncludeBuildTools`. |

TLS 1.2 is forced in every download because Windows Server 2016/2019 ship PowerShell 5.1, which
still offers TLS 1.0 first and fails against modern hosts with an unhelpful *"underlying connection
was closed"*.

Open a **new** shell afterwards so the machine-wide variables are loaded.

### MariaDB specifics on Windows

```ini
[mysqld]
character-set-server    = utf8mb4
collation-server        = utf8mb4_unicode_ci
default-time-zone       = +00:00
innodb-buffer-pool-size = 512M
max_connections         = 200
```

* **`default-time-zone` must be an offset on Windows.** A named zone (`UTC`) fails at startup
  because the Windows MariaDB packages do not include populated timezone tables and the usual
  `mysql_tzinfo_to_sql` helper is a Linux tool. The generated `my.ini` uses `+00:00`, and the
  application also pins `sessionVariables.time_zone=+00:00` in the JDBC URL.
* If MariaDB is already installed: merge the `[mysqld]` block into the existing `my.ini`
  (`SHOW VARIABLES LIKE 'datadir';` tells you which file is in use), then `Restart-Service MariaDB`.
* Verify: `mariadb -u root -e "SELECT @@character_set_server, @@collation_server, @@time_zone;"` →
  `utf8mb4 | utf8mb4_unicode_ci | +00:00`.

---

## 4. Build the WAR (on a build host)

```powershell
.\scripts\windows\verify-toolchain.ps1
.\scripts\windows\build-war.ps1                 # add -WithTests to run JUnit + Testcontainers
# or, reusing an already-exported UI:
.\scripts\windows\build-war.ps1 -SkipUi
```

`build-war.ps1` mirrors `build-war.sh`: `npm ci` → `npm run build` (static export into
`backend\src\main\resources\static`) → `mvn package` → `python scripts\verify-war.py` (24 packaging
assertions) → `dist\infeara-sales-os.war` and `dist\ROOT.war`.

A running Tomcat holds a **mandatory lock** on the deployed WAR's extracted jars. The script warns
when the service is running; if packaging fails with *"Access is denied"*, stop it first:

```powershell
Stop-Service InfearaSalesOS
```

---

## 5. Deploy

```powershell
.\scripts\windows\host-app.ps1                       # generated secrets
# or with explicit values:
.\scripts\windows\host-app.ps1 -AdminEmail admin@infeara.com -AppBaseUrl https://sales.example.com
```

`host-app.ps1` performs, in order:

1. **Database** — creates `infeara_sales` (utf8mb4) and the application user with
   `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES`. `CREATE/ALTER/INDEX` are
   required because Flyway owns the schema; `GRANT`/`CREATE USER` are deliberately not granted.
2. **External configuration** — writes `C:\ProgramData\Infeara\conf\infeara.properties` and
   `logs.properties` with this deployment's generated secrets (§6).
3. **`bin\setenv.bat`** — rendered for interactive runs, with a header warning that the service does
   not read it (§7).
4. **Service** — registers `InfearaSalesOS` via `service.bat install` and applies the JVM options
   with `service.bat update --JvmOptions`.
5. **Deploy** — stops nothing it did not start, removes `webapps\ROOT` **and** `ROOT.war`, copies
   the WAR, applies ACLs, starts the service.
6. **Health gate** — polls `http://localhost:8080/actuator/health` for up to 90 × 1 s and prints the
   log tail on failure.

Doing it by hand (equivalent commands):

```powershell
# database
mariadb -u root -e "CREATE DATABASE infeara_sales CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mariadb -u root -e "CREATE USER 'infeara'@'localhost' IDENTIFIED BY '<strong>'; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON infeara_sales.* TO 'infeara'@'localhost'; FLUSH PRIVILEGES;"

# configuration + service
Copy-Item dist\infeara-sales-os.war "C:\Program Files\Infeara\tomcat\webapps\ROOT.war" -Force
& "C:\Program Files\Infeara\tomcat\bin\service.bat" install InfearaSalesOS
& "C:\Program Files\Infeara\tomcat\bin\service.bat" update  InfearaSalesOS --JvmOptions "-Xms512m;-Xmx1536m;-Dfile.encoding=UTF-8;-Duser.timezone=UTC;-Dspring.profiles.active=prod;-Dspring.config.additional-location=file:C:/ProgramData/Infeara/conf/infeara.properties,file:C:/ProgramData/Infeara/conf/logs.properties"
Start-Service InfearaSalesOS
```

**Deploy as `ROOT.war`.** The exported UI references absolute `/_next/...` assets, so a non-root
context path serves an unstyled page.

---

## 6. Configuration outside the WAR

The WAR contains no credentials. Everything deployment-specific lives in two files that
`host-app.ps1` generates and ACLs, and that are loaded at startup via
`SPRING_CONFIG_ADDITIONAL_LOCATION`:

```
C:\ProgramData\Infeara\conf\infeara.properties    datasource, Hibernate, encryption key, AI keys,
                                                  bootstrap administrator, base URL
C:\ProgramData\Infeara\conf\logs.properties       log path, rotation, levels, patterns
```

| Property | Meaning |
| --- | --- |
| `spring.datasource.url` / `.username` / `.password` | MariaDB connection |
| `spring.jpa.properties.hibernate.*` | Hibernate tuning. **Hibernate's own `hibernate.properties` is never read by Spring Boot** — Boot builds the JPA service registry itself, so `spring.jpa.properties.hibernate.jdbc.batch_size`, `order_inserts`, `jdbc.time_zone` are the supported equivalent |
| `app.security.encryption-key` | Base64 32-byte AES-256-GCM key for stored SMTP/IMAP/Calendly secrets. Losing it means re-entering every integration secret |
| `app.security.cookie-secure` | `auto` (default) marks the session cookie `Secure` only when the request arrived over TLS |
| `app.bootstrap.admin-email` / `-password` | First-run administrator, e.g. `admin@infeara.com` with a generated password |
| `app.ai.openrouter.api-key` / `app.ai.openai.api-key` | Empty = AI analysis reports `AI_PROVIDER_UNAVAILABLE`, never invented data |

**Paths in these files use forward slashes** (`C:/ProgramData/Infeara/logs/sales-os.log`). A single
backslash is an escape character in `.properties` files: `C:\ProgramData\log` silently becomes
`C:ProgramDatalog`. This is the most common Windows configuration mistake with Spring Boot and it
does not raise an error — the file simply lands somewhere unexpected.

Environment variables (set in the service definition) override these files, and the files override
the packaged `application*.yml`. That ordering is what makes "change a password without rebuilding"
and "override one value for a test" both work.

### Permissions (the `chmod 0600` equivalent)

```powershell
icacls C:\ProgramData\Infeara\conf /inheritance:r
icacls C:\ProgramData\Infeara\conf /grant:r "NT AUTHORITY\SYSTEM:(OI)(CI)F"
icacls C:\ProgramData\Infeara\conf /grant:r "BUILTIN\Administrators:(OI)(CI)F"
# when the service runs as a dedicated account, give it read-only:
icacls C:\ProgramData\Infeara\conf /grant:r "DOMAIN\infeara-svc:(OI)(CI)R"
```

`host-app.ps1` applies the first three. Without `/inheritance:r`, `%ProgramData%` inherits
`Users: read`, so every logged-in user can read the database password and the AI keys.

### Running the service as a dedicated account

```powershell
sc.exe config InfearaSalesOS obj= "DOMAIN\infeara-svc" password= "<secret>"
sc.exe config InfearaSalesOS start= auto
# the account needs: read on C:\Program Files\Infeara\tomcat, modify on C:\ProgramData\Infeara
icacls "C:\Program Files\Infeara\tomcat" /grant "DOMAIN\infeara-svc:(OI)(CI)RX"
icacls "C:\ProgramData\Infeara" /grant "DOMAIN\infeara-svc:(OI)(CI)M"
```

LocalSystem works for a quick start; a dedicated account is required for real hardening (it keeps
the JVM out of the machine's full local privilege).

---

## 7. The `setenv.bat` trap (read this before changing JVM options)

The Tomcat Windows **service is launched by Procrun**, which starts `bootstrap.jar` directly. It
does **not** run `catalina.bat`, and therefore **does not read `bin\setenv.bat`**.

| Launch route | Reads `setenv.bat`? | Where the environment must live |
| --- | --- | --- |
| `service.bat` installed service (production) | **No** | Service definition: `service.bat update <n> --JvmOptions "…"` and `++Environment` |
| `bin\startup.bat` / `catalina.bat run` (interactive) | Yes | `bin\setenv.bat` |
| Debugging in an IDE | No | Run configuration |

The symptom of getting this wrong is a service that quietly uses a stale database or a different
profile than the file you just edited. `host-app.ps1` writes both places; keep them in step.

Inspect what the service actually runs with:

```powershell
& "C:\Program Files\Infeara\tomcat\bin\tomcat11.exe" //RS//InfearaSalesOS    # (start)
Get-ItemProperty "HKLM:\SOFTWARE\WOW6432Node\Apache Software Foundation\Procrun 2.0\InfearaSalesOS\Parameters\Java" |
    Select-Object JvmOptions, JvmMs, JvmMx
```

The JVM flags that matter on Windows:

| Flag | Why |
| --- | --- |
| `-Dfile.encoding=UTF-8` | The default Windows code page is not UTF-8; without this, non-ASCII names and subject lines are corrupted |
| `-Duser.timezone=UTC` | Logs and the audit chain stay aligned with `DATETIME(6)` columns stored in UTC |
| `-Xms512m -Xmx1536m` | Worker runs in the same JVM; attachments are capped by `EMAIL_INBOUND_MAX_ATTACHMENT_BYTES` |
| `-XX:+ExitOnOutOfMemoryError` | A wedged JVM is restarted by the service manager instead of serving 500s |
| `-Dspring.config.additional-location=…` | The external configuration from §6 |

---

## 8. TLS with IIS (recommended)

Tomcat stays on plain HTTP on `127.0.0.1:8080`; IIS terminates TLS and proxies.

1. Install the URL Rewrite 2.1 and Application Request Routing 3.0 modules, then enable the proxy in
   IIS Manager → *Application Request Routing Cache* → *Server Proxy Settings* (time-out 120 s).
2. Copy `deploy/windows/iis-arr-web.config` to the site root (it forces HTTPS and sets
   `X-Forwarded-Proto`, `X-Forwarded-For`, `X-Real-IP`).
3. Bind the certificate to the site (`New-WebBinding` / IIS Manager → *Site Bindings* → *https*).
4. Confirm the app sees the forwarded scheme:

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" https://sales.example.com/actuator/health
curl.exe -s -o NUL -w "%{http_code}`n" https://sales.example.com/          # 200 login page
```

Firewall: expose 443 only, and keep 8080 on loopback.

```powershell
New-NetFirewallRule -DisplayName "Infeara HTTPS" -Direction Inbound -LocalPort 443 -Protocol TCP -Action Allow
New-NetFirewallRule -DisplayName "Block Tomcat 8080 from network" -Direction Inbound -LocalPort 8080 -Protocol TCP -RemoteAddress 127.0.0.1 -Action Allow
```

Cookie matrix (identical to Linux — the F-1 defect reproduces on Windows):

| Deployment | `app.security.cookie-secure` | Result |
| --- | --- | --- |
| IIS/TLS in front, `X-Forwarded-Proto: https` forwarded | `auto` | Cookie `Secure` — correct |
| Tomcat TLS connector | `auto` | Cookie `Secure` — correct |
| Plain HTTP, local testing | `auto` | Cookie not `Secure` — works |
| Plain HTTP with `always` | ✗ | Clients discard the cookie → **every authenticated request 401** |

---

## 9. First-run provisioning and sign-in

On first start, while the workspace has no users, the application creates the workspace, seeds RBAC
(10 roles, 66 capabilities), pipeline stages, services, sources, loss reasons and the scoring model,
then creates the bootstrap administrator from `app.bootstrap.admin-*`.

1. `POST /api/v1/auth/login` → `MFA_ENROLMENT_REQUIRED`. **No session exists yet.**
2. The UI shows the TOTP provisioning URI; scan it and confirm → `AUTHENTICATED`, `INF_SESSION` set.
3. Subsequent logins → `MFA_CHALLENGE_REQUIRED` → `/auth/mfa/verify`.
4. Five failed logins lock the account for 15 minutes.

The bootstrap administrator is created with `must_change_password = true`. Enforcement of the forced
change at first login is tracked in `DEVELOPMENT_STATUS.md`; until it is enforced server-side, change
the password immediately after the first sign-in via *Profile → Change password*
(`POST /api/v1/auth/password`).

Integrations are configured in the UI and hit real endpoints: SMTP profile → **Test** before
activation; mailbox → **Test** then enable sync (IMAP only; `GMAIL_API`/`MICROSOFT_GRAPH` return
`501 UNSUPPORTED_OPERATION` by design); response-policy rules decide whether anything may be sent
automatically.

---

## 10. Verify the deployment

```powershell
$base = 'http://localhost:8080'
(Invoke-WebRequest "$base/actuator/health" -UseBasicParsing).Content          # {"status":"UP",...}
(Invoke-WebRequest "$base/" -UseBasicParsing).StatusCode                     # 200
(Invoke-WebRequest "$base/leads/" -UseBasicParsing).StatusCode               # 200
(Invoke-WebRequest "$base/engagement/mailbox/" -UseBasicParsing).StatusCode  # 200
(Invoke-WebRequest "$base/v3/api-docs" -UseBasicParsing).StatusCode          # 200 (97 paths)
# unauthenticated API must answer 401 with the error envelope:
try { Invoke-WebRequest "$base/api/v1/auth/me" -UseBasicParsing } catch { $_.Exception.Response.StatusCode.value__ }

# authenticated end-to-end (login -> MFA enrolment -> module reads -> CSRF write)
python scripts\smoke-authenticated.py --base-url $base --email admin@infeara.com --password '<from infeara.properties>'
```

Expected: 16/16 checks. The same script runs in CI, so a pass here means this host behaves like the
verified one.

Database sanity:

```sql
SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;   -- 10
SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;   -- 0
SHOW TABLES;                                                    -- 83
```

---

## 11. Upgrade

```powershell
Stop-Service InfearaSalesOS
git -C C:\src\Sales_Automation fetch --tags
git -C C:\src\Sales_Automation checkout <tag>
.\scripts\windows\build-war.ps1
.\scripts\windows\host-app.ps1        # regenerates configuration, redeploys, restarts, waits for health
python scripts\smoke-authenticated.py --base-url http://localhost:8080 --email admin@infeara.com --password '<pw>'
```

`host-app.ps1` **regenerates** `infeara.properties` with new secrets. On an upgrade you usually want
to keep the existing ones:

```powershell
Copy-Item C:\ProgramData\Infeara\conf\infeara.properties C:\ProgramData\Infeara\conf\infeara.properties.bak
.\scripts\windows\host-app.ps1 -DbPassword '<existing>' -AdminPassword '<existing>'
```

Better: keep `infeara.properties` in your secret manager and render it at deploy time, then pass
`-SkipDatabase` so nothing is regenerated.

Migrations run on startup (`validate-on-migrate: true`). Never edit an applied migration — add
`V{n}__description.sql`. Take a database dump first (`docs/operations/backup-recovery.md`).

### Rollback

Windows keeps extracted jars open, so rollback is stop → replace → start:

```powershell
Stop-Service InfearaSalesOS
Remove-Item -Recurse -Force "C:\Program Files\Infeara\tomcat\webapps\ROOT"
Copy-Item C:\releases\infeara-sales-os-<previous>.war "C:\Program Files\Infeara\tomcat\webapps\ROOT.war" -Force
Start-Service InfearaSalesOS
```

Restore the matching database dump: Flyway does not roll back, so a WAR that predates the last
migration set must be paired with the dump taken before that upgrade.

---

## 12. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Service starts, then stops immediately | JVM options or config unreadable | `Get-Content C:\ProgramData\Infeara\logs\sales-os.log -Tail 50` and `...\tomcat\logs\stderr*.log` |
| `%CATALINA_HOME%\logs` empty | Service logs to the configured file instead | The generated `logs.properties` sets `logging.file.name` to `C:/ProgramData/Infeara/logs/sales-os.log` |
| "Access is denied" replacing `ROOT.war` | Tomcat holds an exclusive file lock | `Stop-Service InfearaSalesOS`, delete `webapps\ROOT` **and** `ROOT.war`, copy, start |
| Login succeeds, then every call 401 | Cookie `Secure` on a plain-HTTP connector, or IIS is not sending `X-Forwarded-Proto` | `app.security.cookie-secure=auto`; check the ARR server variables |
| Health `DOWN` (db) | Wrong credentials, or MariaDB on a non-default port | `mariadb -h 127.0.0.1 -u infeara -p infeara_sales -e "select 1"` |
| `Schema-validation: missing table` | Migrations did not run (no DDL rights) | Grant `CREATE, ALTER, INDEX, REFERENCES`; restart |
| Startup fails: "Unknown or incorrect time zone" | `default-time-zone` is a named zone | Use `+00:00` (§3) |
| Log file appears at `C:ProgramDatalog…` | Backslashes in `.properties` | Use forward slashes (§6) |
| `NoClassDefFoundError` at startup | Jar missing from the WAR | Add it to `backend/pom.xml`, rebuild — never copy into Tomcat `lib\` |
| Non-ASCII text is corrupted | Missing `-Dfile.encoding=UTF-8` | Add it to the service JVM options (§7) |
| `verify-toolchain.ps1` fails on `javac` | JRE installed, not a JDK | Install Temurin 17 JDK |
| UI loads unstyled | WAR deployed under a context path | Deploy as `ROOT.war` |
| Script won't run at all | Execution policy | `Set-ExecutionPolicy -Scope Process Bypass` |
| `$'\r': command not found` from a `.sh` script | CRLF checkout | `.gitattributes` sets LF for `*.sh`; re-checkout the file |
| PowerShell parse error inside a here-string | CRLF was converted to LF by git | `.gitattributes` sets CRLF for `*.ps1`; re-checkout |

---

## 13. File and permission map

| Path | Contents | ACL |
| --- | --- | --- |
| `C:\Program Files\Infeara\jdk-17` | JDK 17 | Administrators full, service read+execute |
| `C:\Program Files\Infeara\tomcat` | Tomcat 11.0.27 | Administrators full, service read+execute |
| `…\tomcat\webapps\ROOT.war` | The single WAR (UI + API) | service read |
| `…\tomcat\bin\setenv.bat` | Interactive-run environment (**not** the service) | Administrators + SYSTEM |
| `C:\ProgramData\Infeara\conf\` | `infeara.properties`, `logs.properties` | **inheritance removed**; SYSTEM/Administrators full, service read |
| `C:\ProgramData\Infeara\logs\` | `sales-os.log`, error log, access log | service modify |
| `C:\ProgramData\Infeara\my.ini` | MariaDB configuration | Administrators + SYSTEM |

---

## Appendix — Windows Server 2019 notes

* PowerShell 5.1 is sufficient; the scripts avoid `??`, `-Parallel` and ternary operators.
* `Expand-Archive` is slow on large zips; `install-toolchain.ps1` uses it anyway to avoid depending
  on optional modules.
* IIS 10 on Server 2019 supports ARR 3.0 identically. Reboot after installing ARR.
* Windows Defender may scan the extracted WAR on first start, delaying it by 10–30 s. Add an
  exclusion for `C:\Program Files\Infeara\tomcat\webapps` if your health gate times out:
  `Add-MpPreference -ExclusionPath "C:\Program Files\Infeara\tomcat\webapps"`.
