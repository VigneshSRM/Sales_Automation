<#
.SYNOPSIS
    Builds the single deployable WAR on Windows: infeara-sales-os.war

.DESCRIPTION
    frontend (Next.js static export) -> backend\src\main\resources\static
    backend  (Spring Boot WAR)       -> backend\target\infeara-sales-os.war
    contract verification            -> scripts\verify-war.py
    deliverables                     -> dist\infeara-sales-os.war and dist\ROOT.war

    The pairing of UI and API in one artefact is not a Windows concern, but the build has two
    Windows-specific hazards this script handles:
      * paths with spaces ("C:\Program Files\...") when invoking Python and Maven
      * the WAR being locked by a running Tomcat, which produces an opaque "Access is denied"

.PARAMETER WithTests
    Run the JUnit + Testcontainers suites as part of the build (requires Docker Desktop).

.PARAMETER SkipUi
    Reuse the already-exported UI instead of rebuilding it.

.EXAMPLE
    .\build-war.ps1
.EXAMPLE
    .\build-war.ps1 -WithTests
#>
[CmdletBinding()]
param(
    [switch]$WithTests,
    [switch]$SkipUi
)

. "$PSScriptRoot\common.ps1"

$Ctx       = $script:Ctx
$repo      = $Ctx.RepoRoot
$backend   = Join-Path $repo 'backend'
$frontend  = Join-Path $repo 'frontend'
$dist      = Join-Path $repo 'dist'
$war       = Join-Path $backend 'target\infeara-sales-os.war'

Write-Step 'Checking toolchain'
& (Join-Path $PSScriptRoot 'verify-toolchain.ps1')
if ($LASTEXITCODE -ne 0) {
    Exit-WithError 'The pinned toolchain is not installed. Run .\install-toolchain.ps1 first.'
}

# A running Tomcat holds an exclusive lock on the old WAR; fail with advice, not a stack trace.
$lockedWar = Join-Path $Ctx.TomcatHome 'webapps\ROOT.war'
if ((Test-ServiceExists -Name $Ctx.ServiceName) -and
    (Get-Service -Name $Ctx.ServiceName).Status -eq 'Running') {
    Write-Warn "Service '$($Ctx.ServiceName)' is running and may lock $lockedWar"
    Write-Warn 'If packaging fails with "Access is denied", stop the service first:'
    Write-Warn "    Stop-Service $($Ctx.ServiceName)"
}

# ------------------------------------------------------------------ UI static export
if ($SkipUi) {
    Write-Warn 'Skipping the UI build (-SkipUi); reusing the existing static export'
} else {
    if (-not (Test-Command 'npm')) { Exit-WithError 'npm is not on PATH - install Node 22 LTS to build the UI' }
    Write-Step 'Building the UI (Next.js static export into backend\src\main\resources\static)'
    Push-Location $frontend
    try {
        & cmd /c 'npm ci --no-audit --no-fund'
        if ($LASTEXITCODE -ne 0) { Exit-WithError 'npm ci failed' }
        & cmd /c 'npm run build'
        if ($LASTEXITCODE -ne 0) { Exit-WithError 'npm run build failed' }
        Write-Ok 'UI exported'
    } finally {
        Pop-Location
    }
}

# ------------------------------------------------------------------ backend WAR
Write-Step 'Building the backend WAR'
Push-Location $backend
try {
    $mavenArgs = if ($WithTests) { @('clean', 'package') } else { @('clean', 'package', '-DskipTests') }
    # cmd /c keeps the argument list intact in paths containing spaces.
    & cmd /c ("mvn " + ($mavenArgs -join ' '))
    if ($LASTEXITCODE -ne 0) { Exit-WithError 'Maven build failed' }
} finally {
    Pop-Location
}

if (-not (Test-Path $war)) { Exit-WithError "Expected WAR not found: $war" }
Write-Ok "WAR built: $war ($([math]::Round((Get-Item $war).Length / 1MB, 1)) MB)"

# ------------------------------------------------------------------ packaging contract
$python = if (Test-Command 'python') { 'python' } elseif (Test-Command 'python3') { 'python3' } else { $null }
if ($python) {
    Write-Step 'Verifying the packaging contract (24 assertions)'
    & $python (Join-Path $repo 'scripts\verify-war.py') $war
    if ($LASTEXITCODE -ne 0) { Exit-WithError 'The WAR does not match the delivery contract (see the failed checks above)' }
} else {
    Write-Warn 'Python is not available: skipping scripts/verify-war.py (run it before deploying)'
}

# ------------------------------------------------------------------ deliverables
if (-not (Test-Path $dist)) { New-Item -ItemType Directory -Force -Path $dist | Out-Null }
$distWar = Join-Path $dist 'infeara-sales-os.war'
Copy-Item $war $distWar -Force
Copy-Item $war (Join-Path $dist 'ROOT.war') -Force
Write-Ok "Copied to $distWar and $(Join-Path $dist 'ROOT.war')"

Write-Host ''
Write-Host '  Next: .\host-app.ps1   (database, setenv.bat, ROOT.war, service, health wait)'
Write-Host '  Deploy the WAR as ROOT.war - the exported UI uses absolute /_next/... asset URLs.'
