param([switch]$BootstrapOnly)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding
$root = Split-Path $PSScriptRoot -Parent
$state = Join-Path $root '.runtime\windows'
New-Item -ItemType Directory -Force $state | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Refresh-Path {
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User') + ';' + $env:Path
}
function Install-Package([string]$Id) {
    if (!(Get-Command winget.exe -ErrorAction SilentlyContinue)) {
        throw 'Please install Microsoft App Installer (winget) from Microsoft Store, then run run.bat again.'
    }
    Write-Host "Installing $Id ..."
    & winget.exe install --id $Id --exact --source winget --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw "Installation failed: $Id (exit $LASTEXITCODE). Run again to resume." }
    Refresh-Path
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
    $homes = @($env:JAVA_HOME)
    foreach ($base in @($env:ProgramFiles, $env:ProgramW6432, $env:LOCALAPPDATA)) {
        if (!$base) { continue }
        foreach ($vendor in @('Java','Eclipse Adoptium','Microsoft','Amazon Corretto','BellSoft','Programs/Eclipse Adoptium')) {
            $homes += @(Get-ChildItem (Join-Path $base "$vendor/*") -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
        }
    }
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javac) { $homes += Split-Path (Split-Path $javac.Source) }
    $valid = @()
    foreach ($home in ($homes | Select-Object -Unique)) {
        if (!$home -or !(Test-Path "$home/bin/javac.exe")) { continue }
        $ErrorActionPreference = 'Continue'
        $text = (& "$home/bin/java.exe" -version 2>&1 | Out-String)
        $code = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($code -eq 0 -and $text -match 'version "([0-9]+)(?:\.([0-9]+))?(?:\.([0-9]+))?') {
            if ([int]$Matches[1] -ge 21) {
                $version = [version]("{0}.{1}.{2}" -f $Matches[1], [int]$Matches[2], [int]$Matches[3])
                $valid += [pscustomobject]@{Home=$home; Version=$version}
            }
        }
    }
    return ($valid | Sort-Object Version -Descending | Select-Object -First 1 -ExpandProperty Home)
}

function Expand-Package([string]$Url, [string]$Directory) {
    $zip = Join-Path $state "$Directory.zip"
    $target = Join-Path $state $Directory
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

try {
    if (!$BootstrapOnly) {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        if (!(New-Object Security.Principal.WindowsPrincipal($identity)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
            $child = Start-Process powershell.exe -Verb RunAs -Wait -PassThru -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
            exit $child.ExitCode
        }
        if ($env:PROCESSOR_ARCHITECTURE -ne 'AMD64') { throw 'This installer requires Windows x64.' }
    }
    Refresh-Path
    $python = Find-Python
    if (!$python) { Install-Package 'Python.Python.3.12'; $python = Find-Python }
    if (!$python) { throw 'Python was installed but could not be located. Reopen run.bat.' }
    # UTF-8 without BOM; CMD reads this path directly, including spaces.
    [IO.File]::WriteAllText((Join-Path $state 'python-path.txt'), $python, (New-Object Text.UTF8Encoding($false)))
    if ($BootstrapOnly) { exit 0 }

    $javaHome = Find-Jdk
    if (!$javaHome) {
        Install-Package 'EclipseAdoptium.Temurin.21.JDK'
        $javaHome = Find-Jdk
    }
    if (!$javaHome) { throw 'Java 21 JDK not found.' }
    $bins = @("$javaHome\bin", (Split-Path $python))

    $node = Get-Command node.exe -ErrorAction SilentlyContinue
    $nodeVersion = if ($node) { (& node.exe --version) } else { '' }
    if ($nodeVersion -notmatch '^v(2[4-9]|[3-9][0-9])\.') {
        $nodeDir = Expand-Package 'https://nodejs.org/dist/v24.11.1/node-v24.11.1-win-x64.zip' 'node24'
        $bins += Join-Path $nodeDir 'node-v24.11.1-win-x64'
    }
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (!$maven) {
        $mavenDir = Expand-Package 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' 'maven'
        $bins += Join-Path $mavenDir 'apache-maven-3.9.9\bin'
    }

    $mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue
    $mysqlBin = if ($mysql) { Split-Path $mysql.Source } else {
        Get-ChildItem "$env:ProgramFiles\MySQL\MySQL Server 8*\bin\mysql.exe" -ErrorAction SilentlyContinue | Select-Object -First 1 | ForEach-Object { Split-Path $_.FullName }
    }
    if (!$mysqlBin) {
        Install-Package 'Microsoft.VCRedist.2015+.x64'
        $mysqlDir = Expand-Package 'https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-8.4.8-winx64.zip' 'mysql84'
        $mysqlBin = Join-Path $mysqlDir 'mysql-8.4.8-winx64\bin'
    }
    $bins += $mysqlBin
    $services = @(Get-Service | Where-Object { $_.Name -match 'mysql' })
    # Only initialize data owned by this installer; never alter an existing service or password.
    if (!$services.Count) {
        $data = Join-Path $state 'mysql-data'
        $ini = Join-Path $state 'mysql.ini'
        if (!(Test-Path $ini)) {
            $content = "[mysqld]`r`nbasedir=$((Split-Path $mysqlBin).Replace('\','/'))`r`ndatadir=$($data.Replace('\','/'))`r`nport=3306`r`nbind-address=127.0.0.1`r`n"
            [IO.File]::WriteAllText($ini, $content, (New-Object Text.UTF8Encoding($false)))
        }
        if (!(Test-Path "$data\mysql")) {
            & "$mysqlBin\mysqld.exe" "--defaults-file=$ini" --initialize-insecure --console
            if ($LASTEXITCODE -ne 0) { throw 'MySQL initialization failed; existing data has been preserved.' }
        }
        & "$mysqlBin\mysqld.exe" --install BoyiMySQL "--defaults-file=$ini"
        if ($LASTEXITCODE -ne 0) { throw 'MySQL service registration failed.' }
        $services = @(Get-Service BoyiMySQL)
    }
    if ($services.Count -eq 1 -and $services[0].Status -ne 'Running') { Start-Service $services[0].Name }

    $redis = Get-Command redis-cli.exe -ErrorAction SilentlyContinue
    $memuraiCli = "$env:ProgramFiles\Memurai\memurai-cli.exe"
    if (!$redis -and !(Test-Path $memuraiCli)) { Install-Package 'Memurai.MemuraiDeveloper' }
    if (!$redis) {
        if (!(Test-Path $memuraiCli)) { throw 'Memurai client not found.' }
        $cliDir = Join-Path $state 'redis-cli'
        New-Item -ItemType Directory -Force $cliDir | Out-Null
        Copy-Item $memuraiCli "$cliDir\redis-cli.exe" -Force
        # Preserve any DLL dependencies distributed alongside the client.
        Get-ChildItem "$env:ProgramFiles\Memurai\*.dll" -ErrorAction SilentlyContinue | Copy-Item -Destination $cliDir -Force
        $bins += $cliDir
    }
    $redisServices = @(Get-Service | Where-Object { $_.Name -match 'redis|memurai' })
    if ($redisServices.Count -eq 1 -and $redisServices[0].Status -ne 'Running') { Start-Service $redisServices[0].Name }
    if (!$redisServices.Count) { throw 'Redis-compatible service not found.' }

    $manifest = @{ bins=$bins; JAVA_HOME=$javaHome; LOCAL_AI_PYTHON=$python }
    [IO.File]::WriteAllText((Join-Path $state 'environment.json'), ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Runtime installation complete. Project dependencies will be installed next.'
} catch {
    Write-Host "Installation failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
