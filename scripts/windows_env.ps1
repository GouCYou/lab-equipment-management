param([switch]$BootstrapOnly, [switch]$DiscoverOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
$root = Split-Path $PSScriptRoot -Parent
$state = Join-Path $root '.runtime\windows'
$installRoot = Join-Path $env:ProgramFiles 'BoyiRuntime'
$sharedState = Join-Path $env:ProgramData 'BoyiRuntime'
$downloadCache = Join-Path $sharedState 'downloads'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User') + ';' + $env:Path
}
function Install-Package([string]$Id, [switch]$ForceInstall) {
    if (!(Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw 'Please install Microsoft App Installer (winget) from Microsoft Store, then run run.bat again.'
    }
    Write-Host "Installing $Id ..."
    $extraArguments = @()
    if ($ForceInstall) { $extraArguments += '--force' }
    if (!$BootstrapOnly) { $extraArguments += @('--scope', 'machine') }
    & winget.exe install @extraArguments --id $Id --exact --source winget --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw "Installation failed: $Id (exit $LASTEXITCODE). Run again to resume." }
    Refresh-Path
}
function Test-Tool([string]$Binary, [string[]]$Arguments, [string]$Pattern) {
    $resolved = Get-Command $Binary -ErrorAction SilentlyContinue
    if (!$resolved) { return $false }
    # Old project-local runtime copies must not prevent installation into the shared location.
    if ($resolved.Source -and $resolved.Source.StartsWith((Join-Path $root '.runtime'), [StringComparison]::OrdinalIgnoreCase)) { return $false }
    try {
        # Version probes may write their normal output to stderr in Windows PowerShell 5.1.
        $ErrorActionPreference = 'Continue'
        $output = (& $Binary @Arguments 2>&1 | Out-String)
        $exitCode = $LASTEXITCODE
        return ($exitCode -eq 0 -and $output -match $Pattern)
    } catch {
        return $false
    }
}

function Find-RedisClient {
    $candidates = @('redis-cli.exe', 'memurai-cli.exe', "$env:ProgramFiles/Memurai/memurai-cli.exe",
                    "$installRoot/redis-cli/redis-cli.exe")
    foreach ($registryPath in @('HKLM:/SOFTWARE/Microsoft/Windows/CurrentVersion/Uninstall/*',
                                'HKLM:/SOFTWARE/WOW6432Node/Microsoft/Windows/CurrentVersion/Uninstall/*')) {
        $entries = Get-ItemProperty $registryPath -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match 'Memurai|Redis' }
        foreach ($entry in $entries) {
            if ($entry.InstallLocation) {
                $candidates += Join-Path $entry.InstallLocation 'memurai-cli.exe'
                $candidates += Join-Path $entry.InstallLocation 'redis-cli.exe'
            }
        }
    }
    $registeredServices = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object { $_.PathName -match '(memurai|redis-server)\.exe' }
    foreach ($registeredService in $registeredServices) {
        if ($registeredService.PathName -match '^\s*"([^"]+\.exe)"|^\s*(.+?\.exe)(?:\s|$)') {
            $executable = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            $directory = Split-Path $executable
            $candidates += Join-Path $directory 'memurai-cli.exe'
            $candidates += Join-Path $directory 'redis-cli.exe'
        }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (Test-Tool $candidate @('--version') '(?i)(redis|memurai)') {
            return (Get-Command $candidate).Source
        }
    }
    return $null
}

function Install-Memurai {
    # Explicit MSI options avoid automatic firewall/service custom actions. Service registration follows separately.
    $installer = Join-Path $downloadCache 'Memurai-Developer-v4.1.2.msi'
    $expectedHash = '11d009c8e93912e1899b5263d0e7d78907903913e1672b30020612ada6d27446'
    if (!(Test-Path $installer) -or (Get-FileHash $installer -Algorithm SHA256).Hash -ne $expectedHash) {
        Write-Host 'Downloading the official Memurai installer ...'
        Invoke-WebRequest -UseBasicParsing 'https://dist.memurai.com/releases/Memurai-Developer/4.1.2/Memurai-Developer-v4.1.2.msi' -OutFile "$installer.part"
        if ((Get-FileHash "$installer.part" -Algorithm SHA256).Hash -ne $expectedHash) {
            Remove-Item -Force "$installer.part"
            throw 'Memurai installer checksum mismatch.'
        }
        Move-Item -Force "$installer.part" $installer
    }
    $msiLog = Join-Path $sharedState 'memurai-install.log'
    $arguments = "/i `"$installer`" /qn /norestart /L*v `"$msiLog`" INSTALL_SERVICE=0 ADD_FIREWALL_RULE=0 ADD_INSTALLFOLDER_TO_PATH=1"
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin @(0, 1641, 3010)) {
        if (Test-Path $msiLog) {
            Select-String -Path $msiLog -Pattern 'Return value 3|Error [0-9]+|error|failed' -Context 2,2 | Select-Object -Last 12 | Out-String | Write-Host
        }
        throw "Memurai MSI failed (Windows Installer code $($process.ExitCode)). Detailed log: $msiLog"
    }
    Refresh-Path
    if ($process.ExitCode -in @(1641, 3010)) { Write-Host 'Memurai installation requests a Windows restart if the service cannot start.' }
}

function Find-MySqlClient {
    $candidates = @('mysql.exe')
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:MYSQL_HOME, $env:MYSQL_BASEDIR)) {
        if (!$base) { continue }
        foreach ($pattern in @('bin/mysql.exe', 'MySQL/MySQL Server */bin/mysql.exe', 'BoyiRuntime/mysql*/mysql-*/bin/mysql.exe')) {
            $candidates += @(Get-ChildItem (Join-Path $base $pattern) -File -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
        }
    }
    foreach ($registryPath in @('HKLM:/SOFTWARE/MySQL AB/*', 'HKLM:/SOFTWARE/WOW6432Node/MySQL AB/*')) {
        foreach ($entry in (Get-ItemProperty $registryPath -ErrorAction SilentlyContinue)) {
            if ($entry.Location) { $candidates += Join-Path $entry.Location 'bin/mysql.exe' }
        }
    }
    $databaseServices = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue | Where-Object { $_.PathName -match 'mysqld\.exe' }
    foreach ($databaseService in $databaseServices) {
        if ($databaseService.PathName -match '^\s*"([^"]+\.exe)"|^\s*(.+?\.exe)(?:\s|$)') {
            $serverExecutable = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            $candidates += Join-Path (Split-Path $serverExecutable) 'mysql.exe'
        }
    }
    $fallback = $null
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if (!$command) { continue }
        if (!$fallback) { $fallback = $command.Source }
        if (Test-Tool $candidate @('--version') '(?:Ver|Distrib) 8\.') { return $command.Source }
    }
    return $fallback
}

function Get-InstalledToolEnvironment {
    $directories = @()
    foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (!$base) { continue }
        foreach ($pattern in @('BoyiRuntime/node*/node-*', 'BoyiRuntime/maven/apache-maven-*/bin',
                               'BoyiRuntime/mysql*/mysql-*/bin', 'BoyiRuntime/redis-cli',
                               'Apache/Maven/*/bin', 'Apache/maven/bin', 'Apache Software Foundation/apache-maven-*/bin',
                               'apache-maven-*/bin', 'Maven/bin', 'nodejs', 'Memurai')) {
            $directories += @(Get-ChildItem (Join-Path $base $pattern) -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
        }
    }
    foreach ($mavenDirectory in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($mavenDirectory -and (Test-Path "$mavenDirectory/bin/mvn.cmd")) { $directories += Join-Path $mavenDirectory 'bin' }
    }
    $selectedJava = Find-Jdk
    if ($selectedJava) { $directories = @("$selectedJava/bin") + $directories; $env:JAVA_HOME=$selectedJava }
    $env:Path = ($directories -join ';') + ';' + $env:Path
    $mysqlClient = Find-MySqlClient
    $redisClient = Find-RedisClient
    if ($mysqlClient) { $directories += Split-Path $mysqlClient }
    if ($redisClient) { $directories += Split-Path $redisClient }
    return @{ bins=@($directories | Select-Object -Unique); JAVA_HOME=$selectedJava; LOCAL_REDIS_CLI=$redisClient }
}

function Find-Python {
    foreach ($candidate in @("$env:LOCALAPPDATA\Programs\Python\Python312\python.exe", "$env:ProgramFiles\Python312\python.exe", (Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source))) {
        if ($candidate -and (Test-Path $candidate)) {
            & $candidate -c 'import sys; sys.exit(sys.version_info < (3,11))' 2>$null
            if ($LASTEXITCODE -eq 0) { return $candidate }
        }
    }
    if (Get-Command py.exe -ErrorAction SilentlyContinue) {
        $candidate = & py.exe -3 -c 'import sys; print(sys.executable)' 2>$null
        if ($LASTEXITCODE -eq 0 -and $candidate) {
            & $candidate -c 'import sys; sys.exit(sys.version_info < (3,11))'
            if ($LASTEXITCODE -eq 0) { return $candidate }
        }
    }
}
function Find-Jdk {
    $jdkDirectories = @($env:JAVA_HOME)
    foreach ($base in @($env:ProgramFiles, $env:ProgramW6432, $env:LOCALAPPDATA)) {
        if (!$base) { continue }
        foreach ($vendor in @('Java','Eclipse Adoptium','Microsoft','Amazon Corretto','BellSoft','Programs/Eclipse Adoptium')) {
            $jdkDirectories += @(Get-ChildItem (Join-Path $base "$vendor/*") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
        }
    }
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) { $jdkDirectories += Split-Path (Split-Path $javac.Source) }
    $valid = @()
    foreach ($jdkDirectory in ($jdkDirectories | Select-Object -Unique)) {
        if (!$jdkDirectory -or !(Test-Path "$jdkDirectory/bin/javac.exe")) { continue }
        $ErrorActionPreference = 'Continue'
        $text = (& "$jdkDirectory/bin/java.exe" -version 2>&1 | Out-String)
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -eq 0 -and $text -match 'version "([0-9]+)(?:\.([0-9]+))?(?:\.([0-9]+))?') {
            if ([int]$Matches[1] -ge 21) {
                $version = [version]("{0}.{1}.{2}" -f $Matches[1], [int]$Matches[2], [int]$Matches[3])
                $valid += [pscustomobject]@{Home=$jdkDirectory; Version=$version}
            }
        }
    }
    return ($valid | Sort-Object Version -Descending | Select-Object -First 1 -ExpandProperty Home)
}

function Expand-Package([string]$Url, [string]$Directory) {
    $zip = Join-Path $downloadCache "$Directory.zip"
    $target = Join-Path $installRoot $Directory
    if (!(Test-Path $target)) {
        Write-Host "Downloading $Directory ..."
        Invoke-WebRequest -UseBasicParsing $Url -OutFile "$zip.part"
        Move-Item -Force "$zip.part" $zip
        $staging = "$target.extract"
        if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
        Expand-Archive -Path $zip -DestinationPath $staging -Force
        Move-Item $staging $target
    }
    return $target
}

if ($DiscoverOnly) {
    Refresh-Path
    Get-InstalledToolEnvironment | ConvertTo-Json -Depth 4 -Compress
    exit 0
}
New-Item -ItemType Directory -Force $state | Out-Null

$transcriptStarted = $false
$setupLog = Join-Path $state 'setup.log'
$errorFile = Join-Path $state 'install-error.txt'
try {
    if (!$BootstrapOnly) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        if (!(New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            if (Test-Path $setupLog) { Remove-Item -Force $setupLog }
            if (Test-Path $errorFile) { Remove-Item -Force $errorFile }
            Write-Host 'Windows will request administrator permission. Installation progress appears in that window.'
            $child = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
            if (Test-Path $setupLog) { Get-Content -Raw -Encoding UTF8 $setupLog | Write-Output }
            if ($child.ExitCode -ne 0) {
                if (Test-Path $errorFile) { Get-Content -Raw -Encoding UTF8 $errorFile | Write-Output }
                else { Write-Host "Elevated installer exited with code $($child.ExitCode). Log: $setupLog" }
            }
            exit $child.ExitCode
        }
        if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'This installer requires Windows x64.' }
    }
    Start-Transcript -Path $setupLog -Force | Out-Null
    $transcriptStarted = $true
    if (Test-Path $errorFile) { Remove-Item -Force $errorFile }
    Refresh-Path
    $detectedEnvironment = Get-InstalledToolEnvironment
    $env:Path = ($detectedEnvironment.bins -join ';') + ';' + $env:Path
    $python = Find-Python
    if (!$python) { Install-Package 'Python.Python.3.12'; $python = Find-Python }
    if (!$python) { throw 'Python was installed but could not be located. Reopen run.bat.' }
    # UTF-8 without BOM; CMD reads this path directly, including spaces.
    [IO.File]::WriteAllText((Join-Path $state 'python-path.txt'), $python, (New-Object Text.UTF8Encoding($false)))
    if ($BootstrapOnly) { exit 0 }

    New-Item -ItemType Directory -Force $installRoot, $sharedState, $downloadCache | Out-Null
    # Load globally installed tools before checking whether another download is necessary.
    $globalManifest = Join-Path $sharedState 'environment.json'
    if (Test-Path $globalManifest) {
        $sharedEnvironment = Get-Content -Raw -Encoding UTF8 $globalManifest | ConvertFrom-Json
        $env:Path = ($sharedEnvironment.bins -join ';') + ';' + $env:Path
    }

    $javaHome = Find-Jdk
    if (!$javaHome) {
        Install-Package 'EclipseAdoptium.Temurin.21.JDK'
        $javaHome = Find-Jdk
    }
    if (!$javaHome) { throw 'Java 21 JDK not found.' }
    $bins = @("$javaHome\bin", (Split-Path $python))
    if ($sharedEnvironment) { $bins += @($sharedEnvironment.bins | Where-Object { Test-Path $_ }) }

    if (!(Test-Tool 'node.exe' @('--version') '^v(?:22\.(?:1[2-9]|[2-9][0-9])|2[3-9]\.|[3-9][0-9]\.)') -or
        !(Test-Tool 'npm.cmd' @('--version') '\d+\.')) {
        $nodeDir = Expand-Package 'https://nodejs.org/dist/v24.11.1/node-v24.11.1-win-x64.zip' 'node24'
        $bins += Join-Path $nodeDir 'node-v24.11.1-win-x64'
    }
    # Prefer the selected JDK during Maven probing as well as during the build.
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;" + $env:Path
    if (!(Test-Tool 'mvn.cmd' @('--version') 'Apache Maven (?:3\.(?:[6-9]|[1-9][0-9])\.|[4-9]\.)')) {
        $mavenDir = Expand-Package 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' 'maven'
        $bins += Join-Path $mavenDir 'apache-maven-3.9.9\bin'
    }

    $mysqlClient = Find-MySqlClient
    $mysqlBin = if ($mysqlClient) { Split-Path $mysqlClient } else { $null }
    if (!$mysqlBin -or !(Test-Tool (Join-Path $mysqlBin 'mysql.exe') @('--version') '(?:Ver|Distrib) 8\.')) {
        if (!(Test-Path "$env:WINDIR\System32\vcruntime140_1.dll")) { Install-Package 'Microsoft.VCRedist.2015+.x64' }
        $mysqlDir = Expand-Package 'https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-8.4.8-winx64.zip' 'mysql84'
        $mysqlBin = Join-Path $mysqlDir 'mysql-8.4.8-winx64\bin'
    }
    $bins += $mysqlBin
    $services = @(Get-Service | Where-Object { $_.Name -match 'mysql' })
    # Only initialize data owned by this installer; never alter an existing service or password.
    if (!$services.Count) {
        $data = Join-Path $sharedState 'mysql-data'
        $ini = Join-Path $sharedState 'mysql.ini'
        if (!(Test-Path $ini)) {
            $content = "[mysqld]`r`nbasedir=$((Split-Path $mysqlBin).Replace('\','/'))`r`ndatadir=$($data.Replace('\','/'))`r`nport=3306`r`nbind-address=127.0.0.1`r`n"
            [IO.File]::WriteAllText($ini, $content, (New-Object Text.UTF8Encoding($false)))
        }
        if (!(Test-Path "$data\mysql")) {
            # MySQL writes progress to stderr even when initialization succeeds (Windows PowerShell 5.1).
            $ErrorActionPreference = 'Continue'
            & "$mysqlBin\mysqld.exe" "--defaults-file=$ini" --initialize-insecure --console
            $mysqlExitCode = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($mysqlExitCode -ne 0) { throw 'MySQL initialization failed; existing data has been preserved.' }
        }
        & "$mysqlBin\mysqld.exe" --install BoyiMySQL "--defaults-file=$ini"
        if ($LASTEXITCODE -ne 0) { throw 'MySQL service registration failed.' }
        $services = @(Get-Service BoyiMySQL)
    }
    if ($services.Count -eq 1 -and $services[0].Status -ne 'Running') { Start-Service $services[0].Name }

    $redisClient = Find-RedisClient
    if (!$redisClient) {
        Install-Memurai
        $redisClient = Find-RedisClient
    }
    if (!$redisClient) { throw 'Redis-compatible client is still unavailable after installation. See memurai-install.log.' }
    Write-Host "Using Redis-compatible client: $redisClient"
    if ((Split-Path $redisClient -Leaf) -ne 'redis-cli.exe') {
        $cliDir = Join-Path $installRoot 'redis-cli'
        New-Item -ItemType Directory -Force $cliDir | Out-Null
        Copy-Item $redisClient "$cliDir/redis-cli.exe" -Force
        Get-ChildItem (Join-Path (Split-Path $redisClient) '*.dll') -ErrorAction SilentlyContinue | Copy-Item -Destination $cliDir -Force
        $bins += $cliDir
    } else {
        $bins += Split-Path $redisClient
    }
    $redisServices = @(Get-Service | Where-Object { $_.Name -match 'redis|memurai' })
    if (!$redisServices.Count) {
        $memuraiDirectory = Split-Path $redisClient
        if (!(Test-Path "$memuraiDirectory/memurai.exe")) { $memuraiDirectory = "$env:ProgramFiles/Memurai" }
        $server = Join-Path $memuraiDirectory 'memurai.exe'
        $configuration = Join-Path $memuraiDirectory 'memurai.conf'
        if (!(Test-Path $server) -or !(Test-Path $configuration)) { throw 'Redis server/configuration not found. Client installation alone cannot start a server.' }
        $ErrorActionPreference = 'Continue'
        & $server --service-install $configuration
        $serviceExitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($serviceExitCode -ne 0) { throw "Memurai service registration failed (exit $serviceExitCode)." }
        $redisServices = @(Get-Service Memurai)
    }
    if ($redisServices.Count -eq 1 -and $redisServices[0].Status -ne 'Running') { Start-Service $redisServices[0].Name }

    $manifest = @{ bins=$bins; JAVA_HOME=$javaHome; LOCAL_AI_PYTHON=$python }
    $manifestJson = $manifest | ConvertTo-Json
    [IO.File]::WriteAllText($globalManifest, $manifestJson, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $state 'environment.json'), $manifestJson, (New-Object Text.UTF8Encoding($false)))
    # Make shared command-line tools available to other projects and new terminals.
    $machinePath = [Environment]::GetEnvironmentVariable('Path','Machine')
    $pathEntries = @($machinePath -split ';' | Where-Object { $_ })
    foreach ($binDirectory in $bins) {
        if ($pathEntries -notcontains $binDirectory) { $pathEntries += $binDirectory }
    }
    [Environment]::SetEnvironmentVariable('Path', ($pathEntries -join ';'), 'Machine')
    $mavenBin = $bins | Where-Object { $_ -match 'apache-maven-[^\\]+\\bin$' } | Select-Object -First 1
    if ($mavenBin) { [Environment]::SetEnvironmentVariable('MAVEN_HOME', (Split-Path $mavenBin), 'Machine') }

    Write-Host 'Runtime installation complete. Project dependencies will be installed next.'
} catch {
    $failure = "Installation failed: $($_.Exception.Message)`r`n$($_.InvocationInfo.PositionMessage)`r`n$($_.ScriptStackTrace)"
    [IO.File]::WriteAllText($errorFile, $failure, (New-Object Text.UTF8Encoding($false)))
    Write-Host $failure -ForegroundColor Red
    exit 1
} finally {
    if ($transcriptStarted) { Stop-Transcript | Out-Null }
}
