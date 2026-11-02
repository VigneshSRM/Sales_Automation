<#
.SYNOPSIS
    Installs the exact runtime toolchain Infeara Sales OS pins on Windows.

.DESCRIPTION
    Installs:
      * Eclipse Temurin JDK 17 (zip, no installer, so it is inert and easy to remove)
      * Apache Tomcat 11.0.27 (zip) with the manager/host-manager/docs/examples webapps removed
      * MariaDB 11.4 (MSI) configured for utf8mb4 + UTC offsets

    Maven and Node.js are NOT installed: they are build-machine tools, and this script is for
    the server. Use -IncludeBuildTools on a build host or a developer workstation.

    This is the Windows sibling of scripts/install-toolchain.sh and the two are kept
    deliberately aligned - see docs/operations/windows-deployment.md.

.PARAMETER IncludeBuildTools
    Also install Maven 3.9.11 and Node.js 22 LTS (build workstations only).

.PARAMETER SkipMariaDb
    Skip the database server (for hosts that use a managed/external MariaDB).

.EXAMPLE
    .\install-toolchain.ps1
    Installs the server runtime (JDK + Tomcat + MariaDB) under C:\Program Files\Infeara.

.EXAMPLE
    .\install-toolchain.ps1 -IncludeBuildTools -SkipMariaDb
    Build workstation: JDK, Tomcat, Maven, Node.
#>
[CmdletBinding()]
param(
    [switch]$IncludeBuildTools,
    [switch]$SkipMariaDb,
    [switch]$Force
)

. "$PSScriptRoot\common.ps1"

Assert-Administrator
if (-not (Test-IsWindows)) { Exit-WithError 'This script targets Windows.' }

$Ctx = $script:Ctx
Write-Step "Infeara Sales OS toolchain installation (Windows)"
Write-Step "Root: $($Ctx.Root)  |  Data: $($Ctx.Data)  |  Service: $($Ctx.ServiceName)"

foreach ($path in @($Ctx.Root, $Ctx.Data, (Join-Path $Ctx.Data 'logs'), (Join-Path $Ctx.Data 'conf'))) {
    if (-not (Test-Path $path)) { New-Item -ItemType Directory -Force -Path $path | Out-Null }
}

$temp = Join-Path $env:TEMP "infeara-install-$(Get-Random)"
New-Item -ItemType Directory -Force -Path $temp | Out-Null

try {
    # ---------------------------------------------------------------- JDK 17
    $javac = Join-Path $Ctx.JavaHome 'bin\javac.exe'
    if ((Test-Path $javac) -and -not $Force) {
        Write-Ok "JDK already installed at $($Ctx.JavaHome)"
    } else {
        # Temurin publishes a stable "latest 17 GA" redirect; the archive URL pins the build.
        $jdkUrl = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
        $jdkZip = Join-Path $temp 'jdk17.zip'
        Get-RemoteFile -Uri $jdkUrl -Destination $jdkZip
        $expanded = Join-Path $temp 'jdk'
        Expand-ZipArchive -Zip $jdkZip -Destination $expanded
        $inner = Get-ChildItem $expanded -Directory | Select-Object -First 1
        if (-not $inner) { Exit-WithError 'The JDK archive did not contain a directory.' }
        if (Test-Path $Ctx.JavaHome) { Remove-Item -Recurse -Force $Ctx.JavaHome }
        Move-Item $inner.FullName $Ctx.JavaHome
        Write-Ok "JDK installed at $($Ctx.JavaHome)"
    }

    # Machine-wide so the Tomcat service (running as a service account) inherits them.
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $Ctx.JavaHome, 'Machine')
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $javaBin = Join-Path $Ctx.JavaHome 'bin'
    if ($machinePath -notlike "*$javaBin*") {
        [Environment]::SetEnvironmentVariable('Path', "$javaBin;$machinePath", 'Machine')
        Write-Ok "Added $javaBin to the machine PATH"
    }
    $env:JAVA_HOME = $Ctx.JavaHome
    $env:Path = "$javaBin;$env:Path"
    & (Join-Path $javaBin 'java.exe') -version 2>&1 | ForEach-Object { Write-Host "    $_" }

    # ---------------------------------------------------------------- Tomcat 11
    $catalina = Join-Path $Ctx.TomcatHome 'bin\catalina.bat'
    if ((Test-Path $catalina) -and -not $Force) {
        Write-Ok "Tomcat already installed at $($Ctx.TomcatHome)"
    } else {
        $tomcatUrl = "https://archive.apache.org/dist/tomcat/tomcat-11/v$($Ctx.TomcatVersion)/bin/apache-tomcat-$($Ctx.TomcatVersion)-windows-x64.zip"
        $tomcatZip = Join-Path $temp 'tomcat.zip'
        Get-RemoteFile -Uri $tomcatUrl -Destination $tomcatZip
        $expanded = Join-Path $temp 'tomcat'
        Expand-ZipArchive -Zip $tomcatZip -Destination $expanded
        $inner = Get-ChildItem $expanded -Directory | Select-Object -First 1
        if (-not $inner) { Exit-WithError 'The Tomcat archive did not contain a directory.' }
        if (Test-Path $Ctx.TomcatHome) {
            # Tomcat's own service must be removed before its files can be replaced.
            if (Test-ServiceExists -Name $Ctx.ServiceName) {
                & (Join-Path $Ctx.TomcatHome 'bin\service.bat') remove $Ctx.ServiceName | Out-Null
            }
            & (Join-Path $Ctx.TomcatHome 'bin\shutdown.bat') 2>$null | Out-Null
            Start-Sleep -Seconds 3
            Remove-Item -Recurse -Force $Ctx.TomcatHome -ErrorAction SilentlyContinue
        }
        Move-Item $inner.FullName $Ctx.TomcatHome
        # Only what a deployment needs: every extra webapp is attack surface.
        foreach ($webapp in @('manager', 'host-manager', 'docs', 'examples', 'ROOT')) {
            $target = Join-Path $Ctx.TomcatHome "webapps\$webapp"
            if (Test-Path $target) { Remove-Item -Recurse -Force $target }
        }
        Write-Ok "Tomcat $($Ctx.TomcatVersion) installed at $($Ctx.TomcatHome)"
    }

    [Environment]::SetEnvironmentVariable('CATALINA_HOME', $Ctx.TomcatHome, 'Machine')
    [Environment]::SetEnvironmentVariable('CATALINA_BASE', $Ctx.TomcatHome, 'Machine')

    # ---------------------------------------------------------------- MariaDB 11.4
    if ($SkipMariaDb) {
        Write-Warn 'Skipping MariaDB (external database expected)'
    } elseif (Test-Command 'mariadb') {
        Write-Ok "MariaDB client already present ($((& mariadb --version) 2>&1 | Select-Object -First 1))"
    } else {
        $msi = Join-Path $temp 'mariadb.msi'
        $mariadbUrl = "https://archive.mariadb.org/mariadb-$($Ctx.MariaDbChannel).0/winx64-packages/mariadb-$($Ctx.MariaDbChannel).0-winx64.msi"
        try {
            Get-RemoteFile -Uri $mariadbUrl -Destination $msi
        } catch {
            Write-Warn "Direct download failed ($($_.Exception.Message)); falling back to winget/choco"
            if (Test-Command 'winget') {
                & winget install --id MariaDB.Server --silent --accept-package-agreements --accept-source-agreements | Out-Null
            } elseif (Test-Command 'choco') {
                & choco install mariadb -y --no-progress | Out-Null
            } else {
                Exit-WithError 'No download route to MariaDB and neither winget nor choco is available. Install MariaDB 11.4 manually, then re-run verify-toolchain.ps1.'
            }
        }
        if (Test-Path $msi) {
            Write-Step 'Installing MariaDB (silent MSI)'
            # INSTALLDIR is quoted inside the argument because "Program Files" contains a space.
            $arguments = @(
                '/i', "`"$msi`"", '/qn', '/norestart',
                "INSTALLDIR=`"$($Ctx.Root)\mariadb`"",
                'SERVICENAME=MariaDB',
                'PASSWORD=',
                'PORT=3306',
                'STDCONFIG=1'
            )
            $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
            if ($process.ExitCode -ne 0 -and $process.ExitCode -ne 3010) {
                Exit-WithError "MariaDB installer exited with $($process.ExitCode)"
            }
            Write-Ok 'MariaDB installed'
        }
    }

    if (-not $SkipMariaDb) {
        # Windows MariaDB has no populated timezone tables, so a named zone ('UTC') is
        # rejected at startup. The offset form works. This is configured, not assumed.
        $iniPath = Join-Path $env:ProgramData 'Infeara\my.ini'
        $iniTemplate = Get-RepoToolPath 'deploy\windows\my.ini.template'
        $ini = Get-Content $iniTemplate -Raw
        $ini = $ini.Replace('{{DATA_DIR}}', (ConvertTo-JavaPropertyPath (Join-Path $Ctx.Data 'mariadb')))
        Set-Content -Path $iniPath -Value $ini -Encoding ASCII
        Write-Ok "MariaDB configuration written to $iniPath (utf8mb4, default-time-zone=+00:00)"
        Write-Warn 'If the MariaDB service is already running, add the values from my.ini manually and restart it.'
    }

    # ---------------------------------------------------------------- build tools (opt-in)
    if ($IncludeBuildTools) {
        if (Test-Command 'mvn') {
            Write-Ok "Maven already present ($((& mvn -v | Select-Object -First 1)))"
        } else {
            $mavenUrl = "https://archive.apache.org/dist/maven/maven-3/$($Ctx.MavenVersion)/binaries/apache-maven-$($Ctx.MavenVersion)-bin.zip"
            $mavenZip = Join-Path $temp 'maven.zip'
            Get-RemoteFile -Uri $mavenUrl -Destination $mavenZip
            $expanded = Join-Path $temp 'maven'
            Expand-ZipArchive -Zip $mavenZip -Destination $expanded
            $inner = Get-ChildItem $expanded -Directory | Select-Object -First 1
            $mavenHome = Join-Path $Ctx.Root 'maven'
            if (Test-Path $mavenHome) { Remove-Item -Recurse -Force $mavenHome }
            Move-Item $inner.FullName $mavenHome
            [Environment]::SetEnvironmentVariable('MAVEN_HOME', $mavenHome, 'Machine')
            $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
            if ($machinePath -notlike "*$(Join-Path $mavenHome 'bin')*") {
                [Environment]::SetEnvironmentVariable('Path', "$(Join-Path $mavenHome 'bin');$machinePath", 'Machine')
            }
            Write-Ok "Maven $($Ctx.MavenVersion) installed at $mavenHome"
        }
        if (Test-Command 'node') {
            Write-Ok "Node.js already present ($(& node -v))"
        } elseif (Test-Command 'winget') {
            & winget install --id OpenJS.NodeJS.LTS --silent --accept-package-agreements --accept-source-agreements | Out-Null
            Write-Ok 'Node.js LTS installed via winget'
        } else {
            Write-Warn 'Node.js is missing and winget is unavailable: install Node 22 LTS from nodejs.org to build the UI'
        }
    }

    Write-Step 'Toolchain installation finished'
    Write-Step "Next: .\verify-toolchain.ps1  ->  .\build-war.ps1  ->  .\host-app.ps1"
    Write-Warn 'Open a NEW shell (or reboot) so the machine-wide JAVA_HOME/CATALINA_HOME take effect.'
} finally {
    Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue
}
