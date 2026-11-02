<#
.SYNOPSIS
    Verifies that this Windows host has exactly the versions the project pins.

.DESCRIPTION
    The Windows sibling of scripts/verify-toolchain.sh. Same checks, same exit code, same
    table-shaped output, so the Linux and Windows guides describe one procedure.

    Exit codes: 0 = toolchain present, 1 = something is missing or wrong, 2 = usage.
    Safe to run without elevation and safe to run in CI.

.PARAMETER AllowMissingBuildTools
    Treat a missing Maven/Node as a warning rather than a failure. Use this on a server that
    only receives a pre-built WAR - the runtime needs Java and Tomcat, nothing else.

.EXAMPLE
    .\verify-toolchain.ps1
    Server check: JDK, Tomcat, MariaDB client required; Maven/Node reported as optional.

.EXAMPLE
    .\verify-toolchain.ps1 -AllowMissingBuildTools
    Same, but never fails on build tools.
#>
[CmdletBinding()]
param([switch]$AllowMissingBuildTools)

. "$PSScriptRoot\common.ps1"

if (-not (Test-IsWindows)) { Exit-WithError 'This script targets Windows.' }

$Ctx = $script:Ctx
$script:failures = 0
$script:warnings = 0

function Report-Ok   { param([string]$Name, [string]$Detail) '{0,-12} {1} {2}' -f $Name, 'OK  ', $Detail | Write-Host -ForegroundColor Green }
function Report-Fail { param([string]$Name, [string]$Detail) $script:failures++; '{0,-12} {1} {2}' -f $Name, 'FAIL', $Detail | Write-Host -ForegroundColor Red }
function Report-Warn { param([string]$Name, [string]$Detail) $script:warnings++; '{0,-12} {1} {2}' -f $Name, 'WARN', $Detail | Write-Host -ForegroundColor Yellow }

Write-Step "Toolchain verification ($([Environment]::OSVersion.VersionString))"

# --- Java 17 (must be a JDK: a JRE alone cannot build the WAR) -----------------------
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = $Ctx.JavaHome }
$javaExe  = Join-Path $javaHome 'bin\java.exe'
$javacExe = Join-Path $javaHome 'bin\javac.exe'

if (Test-Path $javaExe) {
    $versionText = (& $javaExe -version 2>&1) -join ' '
    if ($versionText -match 'version "(\d+)') {
        $major = $Matches[1]
        if ($major -eq '17') { Report-Ok 'java' "$javaHome ($($versionText -replace '\s+', ' '))" }
        else { Report-Fail 'java' "expected 17, found $major ($javaHome)" }
    } else {
        Report-Fail 'java' "could not parse the version output: $versionText"
    }
} else {
    Report-Fail 'java' "not found at $javaExe (set JAVA_HOME, or run install-toolchain.ps1)"
}

if (Test-Path $javacExe) {
    Report-Ok 'javac' (& $javacExe -version 2>&1 | Select-Object -First 1)
} else {
    Report-Fail 'javac' 'no compiler - a JRE alone cannot build this WAR (install a JDK, not a JRE)'
}

# --- Maven (build only) --------------------------------------------------------------
if (Test-Command 'mvn') {
    $mavenLine = (& mvn -v 2>&1 | Select-Object -First 1)
    if ($mavenLine -match 'Apache Maven (3\.(?:9|[1-9]\d)|[4-9])') { Report-Ok 'maven' $mavenLine }
    else { Report-Fail 'maven' "expected 3.9+, found $mavenLine" }
} elseif ($AllowMissingBuildTools) {
    Report-Warn 'maven' 'not installed (fine on a server that only deploys the WAR)'
} else {
    Report-Fail 'maven' 'not on PATH - required to build; install with install-toolchain.ps1 -IncludeBuildTools'
}

# --- Tomcat 11 -----------------------------------------------------------------------
$tomcatHome = if ($env:CATALINA_HOME) { $env:CATALINA_HOME } else { $Ctx.TomcatHome }
$versionBat = Join-Path $tomcatHome 'bin\version.bat'
if (Test-Path $versionBat) {
    # version.bat prints with a leading "Using CATALINA_BASE:" preamble; match the server line.
    $output = (& cmd /c "`"$versionBat`"" 2>&1) -join "`n"
    if ($output -match 'Apache Tomcat/(11(?:\.\d+)*)') {
        Report-Ok 'tomcat' "Apache Tomcat/$($Matches[1]) at $tomcatHome"
    } else {
        $line = ($output -split "`n" | Where-Object { $_ -match 'Server version' } | Select-Object -First 1)
        Report-Fail 'tomcat' "expected Tomcat 11 (Servlet 6.1), found: $($line ?? 'unknown')"
    }
} else {
    Report-Fail 'tomcat' "version.bat not found at $versionBat (set CATALINA_HOME)"
}

# --- MariaDB client ------------------------------------------------------------------
if (Test-Command 'mariadb') { Report-Ok 'mariadb' ((& mariadb --version) 2>&1 | Select-Object -First 1) }
elseif (Test-Command 'mysql') { Report-Ok 'mariadb' ((& mysql --version) 2>&1 | Select-Object -First 1) }
else { Report-Fail 'mariadb' 'client not on PATH (add the MariaDB bin directory)' }

# --- Node (build only) ---------------------------------------------------------------
if (Test-Command 'node') {
    $nodeVersion = (& node -v)
    $major = [int](($nodeVersion -replace '^v', '') -split '\.')[0]
    if ($major -ge 20) { Report-Ok 'node' $nodeVersion } else { Report-Fail 'node' "expected Node 20+, found $nodeVersion" }
} elseif ($AllowMissingBuildTools) {
    Report-Warn 'node' 'not installed (the UI is pre-built inside the WAR)'
} else {
    Report-Warn 'node' 'not installed - required only to rebuild the UI'
}

# --- Python (packaging verification) -------------------------------------------------
if (Test-Command 'python') { Report-Ok 'python' ((& python --version) 2>&1) }
elseif (Test-Command 'python3') { Report-Ok 'python' ((& python3 --version) 2>&1) }
else { Report-Warn 'python' 'not installed - scripts/verify-war.py cannot run here' }

# --- Summary -------------------------------------------------------------------------
Write-Host ''
if ($script:failures -gt 0) {
    Write-Fail "$($script:failures) check(s) failed, $($script:warnings) warning(s)"
    Write-Host 'Fix the failures before building or deploying. On a server, run:'
    Write-Host '    .\install-toolchain.ps1'
    exit 1
}
Write-Ok "All required checks passed ($($script:warnings) warning(s))"
Write-Host ''
Write-Host 'Reminder: a production server needs Java 17 and Tomcat 11 only. Every application'
Write-Host 'library ships inside the WAR - never install one into Tomcat''s lib directory.'
exit 0
