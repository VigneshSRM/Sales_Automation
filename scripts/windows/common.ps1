<#
.SYNOPSIS
    Shared helpers for the Infeara Sales OS Windows deployment scripts.

.DESCRIPTION
    Dot-source this file; it deliberately exports functions rather than state so that every
    script can be read top-to-bottom without hidden side effects.

        . "$PSScriptRoot\common.ps1"

    The PowerShell scripts mirror scripts/*.sh: same steps, same printed evidence, same exit
    codes (0 = ok, 1 = failure, 2 = usage). That is deliberate - the Linux and Windows guides
    describe one procedure, so the output must be comparable between them.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# --- Layout -------------------------------------------------------------------------
# ProgramData, not Program Files: the configuration directory holds credentials and has to
# be ACLed separately from the binaries (see Grant-ConfigAcl).
$script:InfearaRoot    = if ($env:INFEARA_ROOT) { $env:INFEARA_ROOT } else { 'C:\Program Files\Infeara' }
$script:InfearaData    = if ($env:INFEARA_DATA) { $env:INFEARA_DATA } else { 'C:\ProgramData\Infeara' }
$script:InfearaService = if ($env:INFEARA_SERVICE) { $env:INFEARA_SERVICE } else { 'InfearaSalesOS' }

# Pinned versions. Must match scripts/verify-toolchain.sh and docs/architecture/compatibility.md.
$script:JavaVersion    = '17'
$script:MavenVersion   = '3.9.11'
$script:TomcatVersion  = '11.0.27'
$script:MariaDbChannel = '11.4'

$script:Ctx = @{
    Root          = $script:InfearaRoot
    Data          = $script:InfearaData
    ServiceName   = $script:InfearaService
    JavaVersion   = $script:JavaVersion
    MavenVersion  = $script:MavenVersion
    TomcatVersion = $script:TomcatVersion
    MariaDbChannel = $script:MariaDbChannel
    TomcatHome    = Join-Path $script:InfearaRoot 'tomcat'
    JavaHome      = Join-Path $script:InfearaRoot "jdk-$($script:JavaVersion)"
    RepoRoot      = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

# --- Output -------------------------------------------------------------------------
function Write-Step  { param([string]$Message) Write-Host "[windows] $Message" -ForegroundColor Cyan }
function Write-Ok    { param([string]$Message) Write-Host "[windows] OK   $Message" -ForegroundColor Green }
function Write-Warn  { param([string]$Message) Write-Host "[windows] WARN $Message" -ForegroundColor Yellow }
function Write-Fail  { param([string]$Message) Write-Host "[windows] FAIL $Message" -ForegroundColor Red }

function Exit-WithError {
    param([string]$Message, [int]$Code = 1)
    Write-Fail $Message
    exit $Code
}

# --- Preconditions ------------------------------------------------------------------
function Assert-Administrator {
    $identity  = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        Exit-WithError 'This script must run from an elevated PowerShell session (Run as Administrator).'
    }
}

function Test-Command {
    param([Parameter(Mandatory)][string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-IsWindows {
    return ($PSVersionTable.PSVersion.Major -ge 6 -and $IsWindows) -or
           ($PSVersionTable.PSVersion.Major -lt 6 -and $env:OS -eq 'Windows_NT')
}

# --- Downloads ----------------------------------------------------------------------
function Get-RemoteFile {
    <#
        Downloads with TLS 1.2 forced on. Windows Server 2016/2019 ship PowerShell 5.1, which
        still negotiates TLS 1.0 by default and fails against modern hosts with an unhelpful
        "underlying connection was closed" error.
    #>
    param(
        [Parameter(Mandatory)][string]$Uri,
        [Parameter(Mandatory)][string]$Destination
    )
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Write-Step "Downloading $Uri"
    $directory = Split-Path -Parent $Destination
    if ($directory -and -not (Test-Path $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    Invoke-WebRequest -Uri $Uri -OutFile $Destination -UseBasicParsing -MaximumRedirection 5
}

function Expand-ZipArchive {
    param([Parameter(Mandatory)][string]$Zip, [Parameter(Mandatory)][string]$Destination)
    if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Expand-Archive -Path $Zip -DestinationPath $Destination -Force
}

# --- Permissions --------------------------------------------------------------------
function Grant-ConfigAcl {
    <#
        The Windows equivalent of "chmod 0600, owned by the Tomcat user".

        Removes inherited permissions so that only SYSTEM, Administrators and the service
        account can read the directory. Without this, %ProgramData% is readable by every
        authenticated user and the database password / encryption key / AI keys are exposed.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$Account = "NT AUTHORITY\SYSTEM"
    )
    if (-not (Test-Path $Path)) { New-Item -ItemType Directory -Force -Path $Path | Out-Null }
    & icacls $Path /inheritance:r | Out-Null
    & icacls $Path /grant:r "$($Account):(OI)(CI)R" | Out-Null
    & icacls $Path /grant:r "BUILTIN\Administrators:(OI)(CI)F" | Out-Null
    & icacls $Path /grant:r "NT AUTHORITY\SYSTEM:(OI)(CI)F" | Out-Null
    Write-Ok "ACL applied to $Path (read: $Account; full: Administrators, SYSTEM)"
}

# --- Services -----------------------------------------------------------------------
function Test-ServiceExists {
    param([Parameter(Mandatory)][string]$Name)
    return [bool](Get-Service -Name $Name -ErrorAction SilentlyContinue)
}

function Wait-ForHttpHealth {
    <#
        Polls a URL until it returns 200 and (optionally) the body matches a pattern.
        Mirrors the host-app.sh loop: 60 attempts, full failure context printed.
    #>
    param(
        [Parameter(Mandatory)][string]$Url,
        [int]$Attempts = 60,
        [int]$DelaySeconds = 1,
        [string]$BodyPattern = '"status":"UP"',
        [string]$LogHint = ''
    )
    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 10
            if ($response.StatusCode -eq 200 -and ($response.Content -match [regex]::Escape($BodyPattern))) {
                Write-Ok "$Url is healthy after $attempt attempt(s)"
                return $true
            }
        } catch {
            # Connection refused while Tomcat is still starting is expected; keep waiting.
        }
        Start-Sleep -Seconds $DelaySeconds
    }
    Write-Fail "$Url did not report healthy within $Attempts attempts"
    if ($LogHint -and (Test-Path $LogHint)) {
        Write-Host "----- log tail ($LogHint) -----"
        Get-Content $LogHint -Tail 80
    }
    return $false
}

# --- Environment file ---------------------------------------------------------------
function New-RandomSecret {
    param([int]$Bytes = 24)
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    # 32-byte base64 key material for AES-256-GCM; other lengths are passwords.
    return [Convert]::ToBase64String($buffer)
}

function ConvertTo-BatchValue {
    <#
        Escapes a value for use inside `set "NAME=value"` in a .bat file.
        Percent signs are the only real hazard: a batch file expands %VAR%, so a generated
        password containing % would corrupt the script. Base64 output never contains %, but
        operator-supplied passwords might.
    #>
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value) { return '' }
    return ($Value -replace '%', '%%')
}

function ConvertTo-JavaPropertyPath {
    <#
        Java .properties paths on Windows need forward slashes (or doubled backslashes):
        a single backslash starts an escape sequence and silently mangles the path -
        "C:\app\log" becomes "C:applog". Every path written to a .properties file must go
        through this function.
    #>
    param([Parameter(Mandatory)][string]$Path)
    return ($Path -replace '\\', '/')
}

function Write-Utf8NoBom {
    <#
        Writes UTF-8 WITHOUT a byte-order mark.

        Why not Set-Content -Encoding UTF8: under Windows PowerShell 5.1 that emits a BOM, and
        java.util.Properties does not strip it - the first key becomes "\uFEFFspring.datasource.url",
        the datasource URL is never applied, and the application quietly connects to the default
        host. It is a silent failure, so every file a JVM or MariaDB parses goes through here.
    #>
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][AllowEmptyString()][string]$Content
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

function Get-RepoToolPath {
    <#
        Resolves a path inside the repository checkout, failing with a readable message when
        the script is run from somewhere else.
    #>
    param([Parameter(Mandatory)][string]$Relative)
    $full = Join-Path $Ctx.RepoRoot $Relative
    if (-not (Test-Path $full)) { Exit-WithError "Required file not found: $full (run this script from the repository checkout)" }
    return $full
}

Write-Verbose "common.ps1 loaded (root=$($Ctx.Root), data=$($Ctx.Data), service=$($Ctx.ServiceName))"
