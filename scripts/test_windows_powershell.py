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
