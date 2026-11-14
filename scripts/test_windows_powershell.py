"""Run the actual installer parser/JDK function with PowerShell, without installing anything."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PWSH = os.environ.get('PWSH') or shutil.which('pwsh')


@unittest.skipUnless(PWSH, 'PowerShell runtime not installed; set PWSH to enable')
class PowerShellInstallerTests(unittest.TestCase):
    def run_script(self, script, extra=None):
        env = dict(os.environ, BOYI_INSTALLER_PATH=str(ROOT / 'scripts/windows_env.ps1'))
        env.update(extra or {})
        result = subprocess.run([PWSH, '-NoProfile', '-NonInteractive', '-Command', script],
                                env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout

    def test_actual_parser_and_readonly_foreach_variables(self):
        self.run_script('''
$ErrorActionPreference = 'Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
if ($issues.Count) { throw ($issues | Out-String) }
$loops=$ast.FindAll({param($node) $node -is [System.Management.Automation.Language.ForEachStatementAst]},$true)
foreach ($loop in $loops) {
    $name=$loop.Variable.VariablePath.UserPath
    $existing=Get-Variable $name -ErrorAction SilentlyContinue
    if ($existing -and ($existing.Options -band ([System.Management.Automation.ScopedItemOptions]::ReadOnly -bor [System.Management.Automation.ScopedItemOptions]::Constant))) {
        throw "Cannot assign protected variable: $name"
    }
}
exit 0
''')

    def test_tool_compatibility_probes_accept_memurai_and_reject_old_maven(self):
        self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
$fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Test-Tool'},$true)
Invoke-Expression $fn.Extent.Text
function redis-cli.exe { 'Memurai CLI version 4.1.2'; $global:LASTEXITCODE=0 }
function mvn.cmd { 'Apache Maven 3.5.4'; $global:LASTEXITCODE=0 }
function node.exe { 'v18.20.0'; $global:LASTEXITCODE=0 }
if (!(Test-Tool 'redis-cli.exe' @('--version') '(?i)(redis|memurai)')) { throw 'Memurai rejected' }
if (Test-Tool 'mvn.cmd' @('--version') 'Apache Maven (?:3\\.(?:[6-9]|[1-9][0-9])\\.|[4-9]\\.)') { throw 'Old Maven accepted' }
if (Test-Tool 'node.exe' @('--version') '^v(?:22\\.(?:1[2-9]|[2-9][0-9])|2[3-9]\\.|[3-9][0-9]\\.)') { throw 'Old Node accepted' }
function mvn.cmd { 'Apache Maven 3.9.9'; $global:LASTEXITCODE=0 }
if (!(Test-Tool 'mvn.cmd' @('--version') 'Apache Maven (?:3\\.(?:[6-9]|[1-9][0-9])\\.|[4-9]\\.)')) { throw 'Compatible Maven rejected' }
exit 0
''')

    def test_existing_package_is_reused_outside_project(self):
        with tempfile.TemporaryDirectory(prefix='boyi global ') as tmp:
            base = Path(tmp)
            (base / 'ProgramFiles/BoyiRuntime/maven').mkdir(parents=True)
            output = self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
$fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Expand-Package'},$true)
Invoke-Expression $fn.Extent.Text
$installRoot=Join-Path $env:BOYI_TEST_ROOT 'ProgramFiles/BoyiRuntime'
$downloadCache=Join-Path $env:BOYI_TEST_ROOT 'ProgramData/BoyiRuntime/downloads'
$state=Join-Path $env:BOYI_TEST_ROOT 'project/.runtime/windows'
function Invoke-WebRequest { throw 'Should reuse the existing global package' }
Expand-Package 'https://unused.example/package.zip' 'maven'
''', {'BOYI_TEST_ROOT': tmp})
            self.assertEqual(output.strip(), str(base / 'ProgramFiles/BoyiRuntime/maven'))
            self.assertFalse((base / 'project').exists())

    def test_memurai_installer_uses_separate_service_setup_and_reports_native_error(self):
        with tempfile.TemporaryDirectory(prefix='boyi msi ') as tmp:
            (Path(tmp) / 'Memurai-Developer-v4.1.2.msi').touch()
            self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
$fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Install-Memurai'},$true)
Invoke-Expression $fn.Extent.Text
$downloadCache=$env:BOYI_TEST_ROOT; $sharedState=$env:BOYI_TEST_ROOT
function Get-FileHash { [pscustomobject]@{Hash='11d009c8e93912e1899b5263d0e7d78907903913e1672b30020612ada6d27446'} }
function Invoke-WebRequest { throw 'Valid cached MSI should be reused' }
function Refresh-Path { }
function Start-Process {
    param($FilePath,$ArgumentList,[switch]$Wait,[switch]$PassThru)
    if ($FilePath -ne 'msiexec.exe') { throw 'Unexpected installer' }
    if ($ArgumentList -notmatch 'INSTALL_SERVICE=0' -or $ArgumentList -notmatch 'ADD_FIREWALL_RULE=0' -or $ArgumentList -notmatch '/L\\*v') { throw 'Missing explicit MSI options/log' }
    [pscustomobject]@{ExitCode=$global:testInstallerExitCode}
}
$global:testInstallerExitCode=0
Install-Memurai
$global:testInstallerExitCode=1603
$observedFailure=$false
try { Install-Memurai } catch {
    if ($_.Exception.Message -notmatch '1603' -or $_.Exception.Message -notmatch 'memurai-install.log') { throw }
    $observedFailure=$true
}
if (!$observedFailure) { throw 'MSI failure swallowed' }
exit 0
''', {'BOYI_TEST_ROOT': tmp})

    @unittest.skipIf(os.name == 'nt', 'Mock MySQL executable uses a POSIX script')
    def test_discovers_mysql_service_directory_and_partially_installed_maven(self):
        with tempfile.TemporaryDirectory(prefix='boyi detection ') as tmp:
            base = Path(tmp)
            maven_bin = base / 'ProgramFiles/BoyiRuntime/maven/apache-maven-3.9.9/bin'
            maven_bin.mkdir(parents=True)
            (maven_bin / 'mvn.cmd').touch()
            mysql_bin = base / 'Custom MySQL/bin'
            mysql_bin.mkdir(parents=True)
            mysql = mysql_bin / 'mysql.exe'
            mysql.write_text('#!/bin/sh\necho "mysql Ver 8.0.40 for Win64"\n')
            mysql.chmod(0o755)
            output = self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
foreach ($functionName in @('Test-Tool','Find-MySqlClient','Get-InstalledToolEnvironment')) {
    $fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName},$true)
    Invoke-Expression $fn.Extent.Text
}
$root=Join-Path $env:BOYI_TEST_ROOT 'project'
$installRoot=Join-Path $env:ProgramFiles 'BoyiRuntime'
function Find-Jdk { $null }
function Find-RedisClient { $null }
function Get-ItemProperty { @() }
function Get-CimInstance { [pscustomobject]@{PathName=('"' + (Join-Path $env:BOYI_TEST_ROOT 'Custom MySQL/bin/mysqld.exe') + '" --defaults-file="some file.ini"')} }
Get-InstalledToolEnvironment | ConvertTo-Json -Depth 4 -Compress
''', {'BOYI_TEST_ROOT': tmp, 'ProgramFiles': str(base / 'ProgramFiles'),
      'ProgramFiles(x86)': str(base / 'ProgramFiles-x86')})
            import json
            found = json.loads(output)
            self.assertIn(str(maven_bin), found['bins'])
            self.assertIn(str(mysql_bin), found['bins'])
            self.assertFalse((base / 'project').exists())

    @unittest.skipIf(os.name == 'nt', 'Mock executable uses a POSIX script')
    def test_existing_custom_memurai_installation_is_discovered(self):
        with tempfile.TemporaryDirectory(prefix='boyi redis ') as tmp:
            base = Path(tmp)
            custom = base / 'custom global install'
            custom.mkdir()
            executable = custom / 'memurai-cli.exe'
            executable.write_text('#!/bin/sh\necho "Memurai CLI version 4.1.2"\n')
            executable.chmod(0o755)
            output = self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
foreach ($functionName in @('Test-Tool','Find-RedisClient')) {
    $fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName},$true)
    Invoke-Expression $fn.Extent.Text
}
$root=Join-Path $env:BOYI_TEST_ROOT 'project'
$installRoot=Join-Path $env:BOYI_TEST_ROOT 'ProgramFiles/BoyiRuntime'
function Get-ItemProperty { [pscustomobject]@{DisplayName='Memurai'; InstallLocation=(Join-Path $env:BOYI_TEST_ROOT 'custom global install')} }
function Get-CimInstance { @() }
Find-RedisClient
''', {'BOYI_TEST_ROOT': tmp, 'ProgramFiles': str(base / 'ProgramFiles')})
            self.assertEqual(output.strip(), str(executable))

    @unittest.skipIf(os.name == 'nt', 'Mock executables use POSIX scripts')
    def test_real_find_jdk_selects_21_with_home_set_to_8(self):
        with tempfile.TemporaryDirectory(prefix='boyi jdk ') as tmp:
            base = Path(tmp)
            for major in (8, 21):
                binary = base / 'Java' / f'jdk-{major}' / 'bin'
                binary.mkdir(parents=True)
                for name in ('java.exe', 'javac.exe'):
                    executable = binary / name
                    executable.write_text(f'#!/bin/sh\necho \'openjdk version "{major}.0.1"\' >&2\n')
                    executable.chmod(0o755)
            output = self.run_script('''
$ErrorActionPreference='Stop'
$tokens=$null; $issues=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($env:BOYI_INSTALLER_PATH,[ref]$tokens,[ref]$issues)
$fn=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Find-Jdk'},$true)
Invoke-Expression $fn.Extent.Text
Find-Jdk
''', {'JAVA_HOME': str(base / 'Java/jdk-8'), 'ProgramFiles': str(base),
      'ProgramW6432': str(base), 'LOCALAPPDATA': str(base)})
            self.assertEqual(output.strip(), str(base / 'Java/jdk-21'))


if __name__ == '__main__':
    unittest.main()
