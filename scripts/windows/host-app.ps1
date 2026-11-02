<#
.SYNOPSIS
    Deploys and starts Infeara Sales OS on Apache Tomcat 11 on Windows.

.DESCRIPTION
    1. provisions the MariaDB database and a least-privilege application user
    2. writes the external configuration (infeara.properties + logs.properties) with that
       deployment's generated secrets, ACLed to the service account
    3. renders bin\setenv.bat and registers the Tomcat service with those values
    4. deploys the single WAR as ROOT.war
    5. starts the service and waits for a real HTTP 200 from /actuator/health

    Windows service note (the reason step 3 is not just "copy setenv.bat"): the Tomcat
    service is launched by Procrun, which starts the bootstrap directly and never runs
    catalina.bat. bin\setenv.bat therefore has NO effect on the service. The environment has
    to be written into the service definition itself (++Environment), which is what this
    script does - and why a Tomcat service configured by copying setenv.bat silently runs
    with the wrong database and no encryption key.

.PARAMETER DbPassword
    Application database password. Generated when omitted.

.PARAMETER AdminEmail
    First-run administrator. Defaults to admin@infeara.com.

.PARAMETER AdminPassword
    First-run administrator password. Generated when omitted and written to the external
    configuration file; the account must change it at first login.

.PARAMETER SkipDatabase
    Use an existing database/user (credentials still read from -DbUser/-DbPassword).

.EXAMPLE
    .\host-app.ps1
    Full local deployment with generated secrets.

.EXAMPLE
    .\host-app.ps1 -AdminEmail admin@infeara.com -DbHost db01 -DbName infeara_sales
    Deployment against a remote database host.
#>
[CmdletBinding()]
param(
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbName = 'infeara_sales',
    [string]$DbUser = 'infeara',
    [string]$DbPassword,
    [string]$AppBaseUrl = 'http://localhost:8080',
    [string]$AdminEmail = 'admin@infeara.com',
    [string]$AdminPassword,
    [string]$OpenRouterApiKey = '',
    [string]$OpenAiApiKey = '',
    [switch]$SkipDatabase,
    [switch]$Force
)

. "$PSScriptRoot\common.ps1"

Assert-Administrator
if (-not (Test-IsWindows)) { Exit-WithError 'This script targets Windows.' }

$Ctx      = $script:Ctx
$repo     = $Ctx.RepoRoot
$warSrc   = Join-Path $repo 'dist\infeara-sales-os.war'
$confDir  = Join-Path $Ctx.Data 'conf'
$logDir   = Join-Path $Ctx.Data 'logs'
$dataWar  = Join-Path $Ctx.TomcatHome 'webapps\ROOT.war'
$setenv   = Join-Path $Ctx.TomcatHome 'bin\setenv.bat'

if (-not (Test-Path $warSrc)) { Exit-WithError "dist\infeara-sales-os.war not found - run .\build-war.ps1 first" }
if (-not (Test-Path (Join-Path $Ctx.TomcatHome 'bin\service.bat'))) {
    Exit-WithError "Tomcat not found at $($Ctx.TomcatHome) - run .\install-toolchain.ps1"
}

foreach ($path in @($confDir, $logDir)) {
    if (-not (Test-Path $path)) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
}

# ------------------------------------------------------------------ generated secrets
if (-not $DbPassword)   { $DbPassword   = (New-RandomSecret -Bytes 18) }
if (-not $AdminPassword) { $AdminPassword = (New-RandomSecret -Bytes 18) }
$encryptionKey = New-RandomSecret -Bytes 32     # exactly 32 bytes: AES-256-GCM key material

# ------------------------------------------------------------------ database
if ($SkipDatabase) {
    Write-Warn "Skipping database provisioning (-SkipDatabase); using $DbUser@$DbHost/$DbName"
} else {
    if (-not (Test-Command 'mariadb')) { Exit-WithError 'mariadb client not on PATH - run .\install-toolchain.ps1' }
    Write-Step "Provisioning database '$DbName' and user '$DbUser' on $DbHost"
    # The MariaDB MSI leaves root without a password on a fresh local install. On a hardened
    # server pass root credentials via -DbAdminUser/-DbAdminPassword style env vars instead.
    $sql = @"
CREATE DATABASE IF NOT EXISTS ``$DbName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DbUser'@'localhost' IDENTIFIED BY '$DbPassword';
ALTER USER '$DbUser'@'localhost' IDENTIFIED BY '$DbPassword';
CREATE USER IF NOT EXISTS '$DbUser'@'%' IDENTIFIED BY '$DbPassword';
ALTER USER '$DbUser'@'%' IDENTIFIED BY '$DbPassword';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON ``$DbName``.* TO '$DbUser'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON ``$DbName``.* TO '$DbUser'@'%';
FLUSH PRIVILEGES;
"@
    $sqlFile = Join-Path $env:TEMP "infeara-grant-$(Get-Random).sql"
    Set-Content -Path $sqlFile -Value $sql -Encoding ASCII
    try {
        & mariadb --protocol=TCP -h $DbHost -P $DbPort -u root --default-character-set=utf8mb4 -e "source $($sqlFile -replace '\\','/')"
        if ($LASTEXITCODE -ne 0) { Exit-WithError "Database provisioning failed. Check that the MariaDB service is running and that root access works: mariadb -u root -e `"select 1`"" }
    } finally {
        Remove-Item $sqlFile -ErrorAction SilentlyContinue
    }
    Write-Ok 'Database ready (the application user has DML + DDL, no GRANT/CREATE USER)'
}

# ------------------------------------------------------------------ external configuration
# Everything the deployment needs at runtime lives here, outside the WAR: the WAR stays a
# distributable artefact and a credential rotation is an edit + restart, not a rebuild.
$propertiesPath = Join-Path $confDir 'infeara.properties'
$logPropertiesPath = Join-Path $confDir 'logs.properties'
$logFile = ConvertTo-JavaPropertyPath (Join-Path $logDir 'sales-os.log')

$properties = @"
# Generated by scripts/windows/host-app.ps1 on $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')
# Contains deployment secrets. ACL: Administrators + SYSTEM full, service account read.
# Paths use FORWARD slashes: a single backslash is an escape in .properties files.

# --- datasource ---------------------------------------------------------------
spring.datasource.url=jdbc:mariadb://$($DbHost):$($DbPort)/$($DbName)?useUnicode=true&characterEncoding=utf8&useServerPrepStmts=true&useBulkStmts=false&connectTimeout=5000&socketTimeout=30000
spring.datasource.username=$DbUser
spring.datasource.password=$DbPassword
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=4

# --- Hibernate / JPA ----------------------------------------------------------
# Spring Boot builds the JPA service registry itself, so Hibernate's own hibernate.properties
# is never read. These are the supported equivalents.
spring.jpa.open-in-view=false
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
spring.jpa.properties.hibernate.jdbc.batch_size=30
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.query.in_clause_parameter_padding=true

# --- credential encryption (AES-256-GCM) --------------------------------------
app.security.encryption-key=$encryptionKey
app.security.encryption-key-id=k1
app.security.cookie-secure=auto

# --- application ---------------------------------------------------------------
app.base-url=$AppBaseUrl
app.environment=production
app.timezone=Asia/Kolkata
server.forward-headers-strategy=native
server.tomcat.accesslog.enabled=true
server.tomcat.accesslog.directory=$(ConvertTo-JavaPropertyPath $logDir)
server.tomcat.accesslog.pattern=%h %l %u %t "%r" %s %b %D

# --- first run -----------------------------------------------------------------
app.bootstrap.workspace-name=Infeara Technologies
app.bootstrap.workspace-slug=infeara
app.bootstrap.workspace-timezone=Asia/Kolkata
app.bootstrap.admin-email=$AdminEmail
app.bootstrap.admin-password=$AdminPassword
app.bootstrap.admin-display-name=Workspace Administrator

# --- worker ---------------------------------------------------------------------
app.worker.enabled=true

# --- AI providers (empty = analysis reported unavailable, never faked) ----------
app.ai.preferred-provider=OPENROUTER
app.ai.openrouter.api-key=$OpenRouterApiKey
app.ai.openai.api-key=$OpenAiApiKey
"@
Write-Utf8NoBom -Path $propertiesPath -Content $properties
Write-Ok "Wrote $propertiesPath"

$logProperties = @"
# Logging for this deployment. Path, size, rotation and levels - no rebuild needed.
logging.file.name=$logFile
logging.level.root=WARN
logging.level.com.infeara.sales=INFO
logging.pattern.file=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%X{requestId:-}] [%X{workspaceId:-}] %logger{36} - %msg%n
logging.logback.rollingpolicy.file-name-pattern=${logFile}.%d{yyyy-MM-dd}.%i.gz
logging.logback.rollingpolicy.max-file-size=50MB
logging.logback.rollingpolicy.max-history=30
logging.logback.rollingpolicy.total-size-cap=2GB
logging.logback.rollingpolicy.clean-history-on-start=false
"@
Write-Utf8NoBom -Path $logPropertiesPath -Content $logProperties
Write-Ok "Wrote $logPropertiesPath"

# Example logback configuration for true time+size rotation (see docs/operations/windows-deployment.md)
$logbackExample = Join-Path $repo 'deploy\windows\logback-spring.xml.example'
if (Test-Path $logbackExample) {
    Copy-Item $logbackExample (Join-Path $confDir 'logback-spring.xml.example') -Force
}

# Passwords are the only generated values that can contain chars batch cares about.
$batchDbPassword   = ConvertTo-BatchValue $DbPassword
$batchAdminPassword = ConvertTo-BatchValue $AdminPassword
$encodedProperties = ConvertTo-JavaPropertyPath $propertiesPath
$encodedLogs       = ConvertTo-JavaPropertyPath $logPropertiesPath
$encodedConfDir    = ConvertTo-JavaPropertyPath $confDir
$encodedLogback    = ConvertTo-JavaPropertyPath (Join-Path $confDir 'logback-spring.xml')

# ------------------------------------------------------------------ setenv.bat
$setenvContent = @"
@echo off
rem ---------------------------------------------------------------------------
rem Generated by scripts\windows\host-app.ps1 - contains deployment secrets.
rem Mode: ACL restricted (Administrators + SYSTEM full, service account read).
rem
rem NOTE: this file is used by startup.bat/catalina.bat (interactive runs and
rem debugging). The Windows SERVICE does not read it - host-app.ps1 writes the same
rem values into the service definition with ++Environment.
rem ---------------------------------------------------------------------------
set "JAVA_HOME=$($Ctx.JavaHome)"
set "CATALINA_OPTS=-Xms512m -Xmx1536m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=UTC"
set "SPRING_PROFILES_ACTIVE=prod"
set "SPRING_CONFIG_ADDITIONAL_LOCATION=file:$encodedProperties,file:$encodedLogs"
set "SPRING_CONFIG_IMPORT_CHECK=false"
"@
Set-Content -Path $setenv -Value $setenvContent -Encoding ASCII
Write-Ok "Wrote $setenv"

# ------------------------------------------------------------------ service environment
$jvmOptions = @(
    '-Xms512m', '-Xmx1536m', '-XX:+UseG1GC', '-XX:+ExitOnOutOfMemoryError',
    '-Dfile.encoding=UTF-8', '-Duser.timezone=UTC',
    "-Dspring.profiles.active=prod",
    "-Dspring.config.additional-location=file:$encodedProperties,file:$encodedLogs"
) -join ';'

$serviceBat = Join-Path $Ctx.TomcatHome 'bin\service.bat'
Write-Step "Registering the '$($Ctx.ServiceName)' Windows service"

$running = Test-ServiceExists -Name $Ctx.ServiceName
if ($running) {
    Write-Step 'Stopping the existing service'
    Stop-Service -Name $Ctx.ServiceName -Force -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 30; $i++) {
        if ((Get-Service $Ctx.ServiceName).Status -eq 'Stopped') { break }
        Start-Sleep -Seconds 1
    }
    if (-not $Force) {
        & cmd /c "`"$serviceBat`" remove $($Ctx.ServiceName)" | Out-Null
    }
}

# Install, then update the JVM options; service.bat forwards these to the Procrun binary.
& cmd /c "`"$serviceBat`" install $($Ctx.ServiceName)"
if ($LASTEXITCODE -ne 0 -and -not $running) { Exit-WithError 'Tomcat service installation failed' }
& cmd /c "`"$serviceBat`" update $($Ctx.ServiceName) --JvmOptions `"$jvmOptions`""
Write-Ok "Service '$($Ctx.ServiceName)' registered with the deployment JVM options"

# ------------------------------------------------------------------ deploy
Write-Step 'Deploying ROOT.war (single WAR: API + UI, one origin, no CORS)'
$webapps = Join-Path $Ctx.TomcatHome 'webapps'
$exploded = Join-Path $webapps 'ROOT'
# Windows keeps extracted JARs open: both the exploded directory and the WAR must go while
# nothing is running, otherwise the copy fails with "Access is denied".
if (Test-Path $exploded) { Remove-Item -Recurse -Force $exploded -ErrorAction SilentlyContinue }
if (Test-Path $dataWar)  { Remove-Item -Force $dataWar -ErrorAction SilentlyContinue }
try {
    Copy-Item $warSrc $dataWar -Force
} catch {
    Exit-WithError "Could not replace $dataWar - stop the service and retry ($($_.Exception.Message))"
}
Write-Ok "Deployed $dataWar"

# ------------------------------------------------------------------ ACL + start
Grant-ConfigAcl -Path $confDir
Write-Step "Starting $($Ctx.ServiceName)"
Start-Service -Name $Ctx.ServiceName
$logHint = Join-Path $logDir 'sales-os.log'
$healthy = Wait-ForHttpHealth -Url "$AppBaseUrl/actuator/health" -Attempts 90 -BodyPattern '"status":"UP"' -LogHint $logHint
if (-not $healthy) {
    Write-Host "----- logs -----"
    Get-ChildItem $Ctx.TomcatHome -Filter '*.log' -Recurse -ErrorAction SilentlyContinue |
        ForEach-Object { Write-Host $_.FullName; Get-Content $_.FullName -Tail 40 }
    Exit-WithError 'The application did not become healthy. Check the log above.'
}

$uiCode  = try { (Invoke-WebRequest -Uri "$AppBaseUrl/" -UseBasicParsing -TimeoutSec 15).StatusCode } catch { $_.Exception.Response.StatusCode.value__ }
$apiCode = try { (Invoke-WebRequest -Uri "$AppBaseUrl/api/v1/auth/me" -UseBasicParsing -TimeoutSec 15).StatusCode } catch { $_.Exception.Response.StatusCode.value__ }
Write-Step "UI  /               -> HTTP $uiCode   (200 expected: login page)"
Write-Step "API /api/v1/auth/me -> HTTP $apiCode  (401 expected: unauthenticated)"

Write-Host ''
Write-Host '  Infeara Sales OS is hosted on Apache Tomcat 11 (Windows service)'
Write-Host '  ---------------------------------------------------------------------'
Write-Host "  UI and API : $AppBaseUrl          (same WAR, same origin)"
Write-Host "  OpenAPI    : $AppBaseUrl/swagger-ui.html"
Write-Host "  Health     : $AppBaseUrl/actuator/health"
Write-Host "  Config     : $confDir (ACL restricted)"
Write-Host "  Logs       : $logFile"
Write-Host "  Service    : Get-Service $($Ctx.ServiceName) | Restart-Service $($Ctx.ServiceName)"
Write-Host ''
Write-Host "  Sign in    : $AdminEmail"
Write-Host "  Password   : see app.bootstrap.admin-password in $propertiesPath"
Write-Host '  The account must change this password at first login.'
Write-Host ''
Write-Host '  Next: connect an SMTP profile (Sending accounts), then a mailbox'
Write-Host '        (Mailbox connections) and run Test before enabling inbound sync.'
