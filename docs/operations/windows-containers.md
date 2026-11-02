# Windows containers (Tomcat Hosted)

Running Infeara Sales OS in a **Windows container** instead of a Windows Server installation.

**Scope note — this is an approved deviation.** The original architecture decision (ADR-004) states
that production is "a WAR on Tomcat 11, no container image". Windows containers were added on
explicit request; the deviation and its consequences are recorded in
`docs/architecture/architecture-decisions.md` (ADR-010). The supported deployment remains the WAR on
Tomcat; a container is packaging, not a different runtime.

**Why there is no ready-made base image.** Apache does not publish an official Tomcat image for
Windows (`tomcat:11-jdk17` and friends are Linux-only). Microsoft publishes `servercore` and
`nanoserver` bases, so this image is **built from a Server Core base + the same Tomcat 11 zip + the
same JDK 17 zip** the host installation uses. Nothing about the application changes: one WAR, one
container, same Servlet 6.1 container.

---

## 1. Constraints you must accept first

| Constraint | Consequence |
| --- | --- |
| Container OS must match the host Windows build | A `ltsc2022` image runs on Windows Server 2022 / Windows 11 22H2+ only; `ltsc2019` for Server 2019 |
| Base image size | `servercore:ltsc2022` ≈ 5 GB on disk; `nanoserver` works but has no PowerShell (see §6) |
| **MariaDB cannot be containerised alongside the app** | Windows has no official MariaDB Windows container image, and SQL Server would break the "MariaDB 11.4 only" constraint. The database is external — a Windows service on the host, or a Linux MariaDB reachable over TCP. |
| No `docker-compose` parity with the Linux dev stack | Windows containers cannot run the Linux `mariadb`/`mailpit` images. For development use Docker Desktop's Linux engine (see `windows-development.md`) |
| Interactive debugging | `docker run -it … cmd` and read logs from the container's log stream; there is no `docker exec` into a stopped container |

---

## 2. Dockerfile

`deploy/windows/Dockerfile` (build from the repository root so the WAR is in context):

```dockerfile
# escape=`
# ---------------------------------------------------------------------------
# Infeara Sales OS - Windows container (Server Core + Tomcat 11 + JDK 17)
#
# Multi-stage is not possible for Windows in the way it is on Linux: there is no
# "jdk builder" image, so the WAR must be built beforehand (scripts/windows/build-war.ps1)
# and copied in. That is deliberate - the build stays on a machine with Maven and Node,
# the image only receives a verified artefact.
# ---------------------------------------------------------------------------
FROM mcr.microsoft.com/windows/servercore:ltsc2022

SHELL ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

ARG JDK_URL=https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse
ARG TOMCAT_VERSION=11.0.27

ENV JAVA_HOME=C:\infeara\jdk-17 `
    CATALINA_HOME=C:\infeara\tomcat `
    CATALINA_BASE=C:\infeara\tomcat `
    INFEARA_DATA=C:\ProgramData\Infeara

# --- JDK 17 (zip, no installer) -------------------------------------------
RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; `
    Invoke-WebRequest -Uri $env:JDK_URL -OutFile C:\jdk.zip -UseBasicParsing; `
    New-Item -ItemType Directory -Force -Path C:\infeara | Out-Null; `
    Expand-Archive C:\jdk.zip -DestinationPath C:\jdk-extract; `
    Move-Item (Get-ChildItem C:\jdk-extract -Directory | Select-Object -First 1).FullName C:\infeara\jdk-17; `
    Remove-Item -Recurse -Force C:\jdk.zip, C:\jdk-extract

# --- Tomcat 11 -------------------------------------------------------------
RUN [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; `
    Invoke-WebRequest -Uri "https://archive.apache.org/dist/tomcat/tomcat-11/v$env:TOMCAT_VERSION/bin/apache-tomcat-$env:TOMCAT_VERSION-windows-x64.zip" -OutFile C:\tomcat.zip -UseBasicParsing; `
    Expand-Archive C:\tomcat.zip -DestinationPath C:\tomcat-extract; `
    Move-Item (Get-ChildItem C:\tomcat-extract -Directory | Select-Object -First 1).FullName C:\infeara\tomcat; `
    Remove-Item -Recurse -Force C:\tomcat.zip, C:\tomcat-extract; `
    foreach ($w in @('manager','host-manager','docs','examples','ROOT')) { `
        $p = Join-Path $env:CATALINA_HOME "webapps\$w"; if (Test-Path $p) { Remove-Item -Recurse -Force $p } }

# --- The verified WAR ------------------------------------------------------
COPY dist/infeara-sales-os.war C:\infeara\tomcat\webapps\ROOT.war

# --- Runtime directories ---------------------------------------------------
RUN New-Item -ItemType Directory -Force -Path $env:INFEARA_DATA\conf, $env:INFEARA_DATA\logs | Out-Null

# --- JVM + profile (setenv.bat is not used by the image entrypoint) ---------
ENV CATALINA_OPTS="-Xms512m -Xmx1536m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=UTC" `
    SPRING_PROFILES_ACTIVE=prod `
    SPRING_CONFIG_ADDITIONAL_LOCATION="file:C:/ProgramData/Infeara/conf/infeara.properties,file:C:/ProgramData/Infeara/conf/logs.properties"

EXPOSE 8080
VOLUME ["C:/ProgramData/Infeara"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 `
    CMD powershell -NoProfile -Command `
        "try { (Invoke-WebRequest http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 8).Content -match '\"status\":\"UP\"' } catch { exit 1 }"

# Keep Tomcat in the foreground so the container's lifecycle == the JVM's lifecycle.
ENTRYPOINT ["C:\\infeara\\tomcat\\bin\\catalina.bat"]
CMD ["run"]
```

Build and run:

```powershell
.\scripts\windows\build-war.ps1
docker build -f deploy\windows\Dockerfile -t infeara/sales-os:1.0.0 .

docker run -d --name infeara-sales-os -p 8080:8080 `
  -v C:\InfearaData:C:\ProgramData\Infeara `
  infeara/sales-os:1.0.0

docker logs -f infeara-sales-os
```

---

## 3. Configuration inside the container

Same model as the host installation: **no credentials in the image.** Mount
`C:\ProgramData\Infeara` and place `infeara.properties` + `logs.properties` there (render them with
`scripts/windows/host-app.ps1` on any machine, or copy them from a running host).

```properties
# infeara.properties - must point at a database the CONTAINER can reach
spring.datasource.url=jdbc:mariadb://host.docker.internal:3306/infeara_sales?useUnicode=true&characterEncoding=utf8
spring.datasource.username=infeara
spring.datasource.password=<generated>
```

`host.docker.internal` resolves from a container to the Windows host. For a MariaDB on another
machine, use its DNS name and make sure the host firewall allows 3306 from the container network.

Paths in these files use forward slashes (`C:/ProgramData/Infeara/logs/sales-os.log`).

Never `COPY` a properties file containing real secrets into the image — it becomes part of every
layer and every registry copy. Use the volume mount, a Docker secret, or environment variables.

---

## 4. Compose (Windows containers)

`deploy/windows/docker-compose.yml`:

```yaml
services:
  app:
    image: infeara/sales-os:1.0.0
    build:
      context: ../..
      dockerfile: deploy/windows/Dockerfile
    ports:
      - "8080:8080"
    environment:
      # Overrides the properties file; the ordering is env > file > packaged yml
      SPRING_PROFILES_ACTIVE: prod
    volumes:
      - C:\InfearaData:C:\ProgramData\Infeara
    extra_hosts:
      - "host.docker.internal:host-gateway"   # supported on Windows hosts; harmless elsewhere
    restart: unless-stopped
```

```powershell
docker compose -f deploy\windows\docker-compose.yml up -d --build
```

Note there is **no `mariadb` service** here, by design (§1). Point the app at a database that
already exists.

---

## 5. Health, logs and lifecycle

| Task | Command |
| --- | --- |
| Health | `docker inspect --format '{{json .State.Health}}' infeara-sales-os` |
| Logs (live) | `docker logs -f infeara-sales-os` |
| Application log file | `Get-Content C:\InfearaData\logs\sales-os.log -Tail 100 -Wait` |
| Restart | `docker restart infeara-sales-os` |
| Stop / remove | `docker stop infeara-sales-os; docker rm infeara-sales-os` |
| Shell inside | `docker exec -it infeara-sales-os powershell` |

Log rotation is configured with `logs.properties` on the mounted volume, exactly as for a host
installation — so log files survive container replacement.

---

## 6. Nanoserver variant

Nanoserver is ~1.5 GB smaller but has **no PowerShell, no `Expand-Archive`**, and no `cmd` in newer
images. Building it requires the JDK and Tomcat to be extracted in a `servercore` stage and copied
across:

```dockerfile
# escape=`
FROM mcr.microsoft.com/windows/servercore:ltsc2022 AS toolchain
SHELL ["powershell", "-NoProfile", "-Command"]
RUN ...extract jdk and tomcat into C:\infeara...

FROM mcr.microsoft.com/windows/nanoserver:ltsc2022
ENV JAVA_HOME=C:\infeara\jdk-17 CATALINA_HOME=C:\infeara\tomcat CATALINA_BASE=C:\infeara\tomcat
COPY --from=toolchain C:\infeara\jdk-17 C:\infeara\jdk-17
COPY --from=toolchain C:\infeara\tomcat C:\infeara\tomcat
COPY dist/infeara-sales-os.war C:\infeara\tomcat\webapps\ROOT.war
EXPOSE 8080
ENTRYPOINT ["C:\\infeara\\tomcat\\bin\\catalina.bat", "run"]
```

Caveats: no shell for `docker exec`, so health checking must rely on the `HEALTHCHECK` (which needs a
client — `catalina` + JDK are present, so use a small Java or `curl.exe`-free check), and debugging is
restricted to logs. For most teams `servercore` is the pragmatic choice; the size difference buys
little against the loss of operability.

---

## 7. What is *not* verified

Be precise about the evidence boundary:

| Item | Status |
| --- | --- |
| WAR on Tomcat 11, Windows Server 2022 | **CI-verified** — `build-and-host-windows` job (`windows-2022` runner) |
| Windows service installation (`service.bat`, Procrun) | Documented; exercised on the CI runner via `catalina.bat run`, not as a registered service |
| Dockerfile in this document | **Not executed in CI** — builds require a Windows container host with Hyper-V isolation; GitHub's `windows-2022` runners cannot run Windows containers without nested virtualisation |
| Nanoserver variant | Not executed anywhere |

Treat the container path as *documented, unverified* until someone runs it and records the result
here. That is the same standard the rest of this repository holds itself to.
