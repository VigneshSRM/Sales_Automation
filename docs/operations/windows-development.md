# Windows development guide

Local development of Infeara Sales OS on **Windows 10 / 11** (and Windows Server if you develop
there). Production deployment is covered by `windows-deployment.md`; the Linux equivalent of this
page is Appendix A of `installation-setup.md`.

The application is platform-neutral Java + TypeScript. What changes on Windows is the toolchain, the
process model (services instead of `systemd`) and path handling — nothing in the code.

---

## 1. One-time setup

```powershell
# winget is present on Windows 10 1809+ and Server 2022; use the vendor installers otherwise
winget install --id EclipseAdoptium.Temurin.17.JDK --silent --accept-package-agreements
winget install --id Apache.Maven --silent
winget install --id OpenJS.NodeJS.LTS --silent
winget install --id Python.Python.3.12 --silent
winget install --id Docker.DockerDesktop     # MariaDB + Mailpit containers, and Testcontainers
```

Close and reopen the shell, then confirm:

```powershell
java -version      # 17.x
javac -version     # must exist - a JRE cannot build the WAR
mvn -v             # 3.9+
node -v            # v22.x
python --version   # 3.9+
docker version
```

If you prefer the repository's own installer (it pins Temurin 17, Maven 3.9.11, Tomcat 11.0.27 and
can also stand up MariaDB locally):

```powershell
.\scripts\windows\install-toolchain.ps1 -IncludeBuildTools
.\scripts\windows\verify-toolchain.ps1
```

`-SkipMariaDb` avoids installing a local server when you plan to use the container instead.

---

## 2. Infrastructure: MariaDB + Mailpit

```powershell
docker compose -f deploy\docker-compose.dev.yml up -d
docker compose -f deploy\docker-compose.dev.yml ps
```

* MariaDB 11.4 on `localhost:3306` — database `infeara_sales`, user `infeara`, password `dev-password`,
  root password `dev-root-password`.
* Mailpit on `localhost:1025` (SMTP) and <http://localhost:8025> (UI) — every outbound message is
  captured here, so you can test the whole send path without delivering anything.

Docker Desktop must be running; if the containers exit immediately, check that WSL 2 or Hyper-V is
enabled (Settings → Resources).

---

## 3. Run the backend

```powershell
$env:DB_USER='infeara'; $env:DB_PASSWORD='dev-password'
cd backend
mvn spring-boot:run
```

The default profile is `dev`: Flyway applies `V1`–`V10` on first start, `ddl-auto=validate` fails
fast on entity/schema drift, cookies are not `Secure`, and SQL logging is at DEBUG.

First run also provisions the workspace, RBAC seed data, pipeline stages, services, sources, loss
reasons and the scoring model. An administrator is created only when
`BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` are supplied:

```powershell
$env:BOOTSTRAP_ADMIN_EMAIL='admin@infeara.com'
$env:BOOTSTRAP_ADMIN_PASSWORD='<something strong>'
```

No credentials exist in source control; nothing is seeded with a default password.

---

## 4. Run the UI

```powershell
cd frontend
npm ci
npm run dev          # http://localhost:3000, /api proxied to http://127.0.0.1:8080
```

The proxy keeps the browser on a single origin, so cookies and CSRF behave exactly as they do in the
packaged WAR. To point at a different backend: `$env:API_PROXY_TARGET='http://127.0.0.1:8090'`.

Then sign in, complete MFA enrolment, and configure an SMTP profile pointing at
`localhost:1025` (TLS `NONE`, any username/password) — mail appears at <http://localhost:8025>.

---

## 5. Tests

```powershell
cd frontend; npx tsc --noEmit; npx vitest run        # type-check + 28 unit tests
cd backend;  mvn test                                # 26 unit + architecture tests
cd ..;       .\scripts\windows\build-war.ps1 -WithTests   # includes the Testcontainers ITs
```

`SchemaMigrationIT` uses Testcontainers and therefore needs Docker Desktop. If Docker is unavailable,
the unit and architecture suites still run; the IT is skipped only if you exclude it explicitly.

Useful static checks (Python, no JDK needed):

```powershell
python scripts\validate-migrations.py       # Flyway script static checks
python scripts\validate-entities.py         # entity <-> schema parity
python scripts\verify-war.py dist\infeara-sales-os.war
python tools\capability_audit.py            # capability strings vs seeded roles
```

---

## 6. Run Tomcat locally (production-shaped testing)

Sometimes you need the real container, not `spring-boot:run`:

```powershell
.\scripts\windows\build-war.ps1
$env:CATALINA_HOME='C:\Program Files\Infeara\tomcat'
Remove-Item -Recurse -Force "$env:CATALINA_HOME\webapps\ROOT" -ErrorAction SilentlyContinue
Copy-Item dist\ROOT.war "$env:CATALINA_HOME\webapps\ROOT.war" -Force
& "$env:CATALINA_HOME\bin\catalina.bat" run
```

`catalina.bat run` keeps the console attached so `Ctrl+C` stops it and you see the stack trace. In
this mode `bin\setenv.bat` **is** read (the Windows service path is different — see
`windows-deployment.md` §7).

For a service-shaped run, `.\scripts\windows\host-app.ps1` deploys and registers the service.

---

## 7. Windows-specific gotchas

| Symptom | Cause | Fix |
| --- | --- | --- |
| `$'\r': command not found` running a `.sh` | CRLF checkout | `.gitattributes` forces LF for `*.sh`; re-checkout (`git rm --cached -r . ; git reset --hard`) |
| PowerShell parse error in a repo `.ps1` | LF checkout of a CRLF file | `.gitattributes` forces CRLF for `*.ps1`; re-checkout |
| "Access is denied" writing `target\*.war` | A Tomcat service holds the old WAR open | `Stop-Service InfearaSalesOS` |
| Port 8080 already in use | Another Tomcat/IIS site | `Get-NetTCPConnection -LocalPort 8080` then stop it, or `$env:SERVER_PORT='8090'` |
| Paths in `application-*.yml` misbehave | Backslashes in a `.properties` file are escapes | Use forward slashes: `C:/ProgramData/Infeara/logs` |
| MariaDB rejects `default-time-zone=UTC` | Windows MariaDB has no timezone tables | Use `+00:00` |
| Non-ASCII data looks corrupted | JVM code page | `-Dfile.encoding=UTF-8` (already in `setenv.bat` and the service options) |
| `docker compose` fails on `:` volume paths | Path translation in older Docker | Use Docker Desktop's WSL 2 backend; run from a path without spaces |
| Execution policy blocks a script | Windows default policy | `Set-ExecutionPolicy -Scope Process Bypass` |
| Long path errors during `npm ci` | 260-character limit | `git config --system core.longpaths true`, enable Long Paths in the registry |

---

## 8. IDE notes

| IDE | Setup |
| --- | --- |
| IntelliJ IDEA | Open `backend/pom.xml` as a project; set the SDK to the JDK 17 you installed; run configuration `SalesApplication` with profile `dev` and the `DB_*` environment variables |
| VS Code | Open the repository root; install *Extension Pack for Java*, *Spring Boot Extension Pack*, *ESLint*, *Prettier*, *PowerShell*; use the `frontend` folder for the UI |
| Visual Studio | Not needed — there is no .NET component |

Line endings in the editor: the repository's `.gitattributes` decides, so save with your editor's
default and let git normalise. Do not click "convert line endings" on shell/PowerShell scripts.
