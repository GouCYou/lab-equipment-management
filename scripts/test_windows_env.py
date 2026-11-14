"""Windows installation wiring, tested without installing host software."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts import local_runner as runner


class WindowsEnvironmentTests(unittest.TestCase):
    def test_saved_tools_are_available_on_next_launch(self):
        with tempfile.TemporaryDirectory(prefix='boyi windows ') as tmp:
            root = Path(tmp)
            state = root / '.runtime/windows'
            state.mkdir(parents=True)
            binary = root / 'tools with spaces/bin'
            binary.mkdir(parents=True)
            (state / 'environment.json').write_text(json.dumps({
                'bins': [str(binary), str(root / 'missing')],
                'JAVA_HOME': str(root / 'jdk'), 'LOCAL_AI_PYTHON': str(root / 'python.exe')}))
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'select_windows_jdk', return_value=None), patch.dict(os.environ, {'PATH': 'original'}):
                runner.activate_windows_tools()
                self.assertEqual(os.environ['PATH'], str(binary) + os.pathsep + 'original')
                self.assertEqual(os.environ['JAVA_HOME'], str(root / 'jdk'))
                self.assertEqual(os.environ['LOCAL_AI_PYTHON'], str(root / 'python.exe'))

    def test_shared_environment_is_preferred_to_old_project_copy(self):
        with tempfile.TemporaryDirectory(prefix='boyi shared ') as tmp:
            root = Path(tmp)
            program_data = root / 'ProgramData'
            global_dir = program_data / 'BoyiRuntime'
            local_dir = root / '.runtime/windows'
            global_bin = root / 'ProgramFiles/BoyiRuntime/node'
            global_dir.mkdir(parents=True)
            local_dir.mkdir(parents=True)
            global_bin.mkdir(parents=True)
            (global_dir / 'environment.json').write_text(json.dumps({'bins': [str(global_bin)]}))
            (local_dir / 'environment.json').write_text(json.dumps({'bins': [str(root)]}))
            with patch.object(runner, 'ROOT', root), \
                    patch.object(runner, 'select_windows_jdk', return_value=None), \
                    patch.dict(os.environ, {'ProgramData': str(program_data), 'PATH': 'original'}):
                runner.activate_windows_tools()
                self.assertEqual(os.environ['PATH'], str(global_bin) + os.pathsep + 'original')

    def test_windows_qdrant_is_shared_between_project_directories(self):
        with tempfile.TemporaryDirectory() as tmp, \
                patch.object(runner, 'IS_WINDOWS', True), \
                patch.dict(os.environ, {'LOCALAPPDATA': tmp}):
            with patch.object(runner, 'ROOT', Path(tmp) / 'project1'):
                first = runner.default_qdrant_binary()
            with patch.object(runner, 'ROOT', Path(tmp) / 'project2'):
                second = runner.default_qdrant_binary()
            self.assertEqual(first, second)
            self.assertEqual(first, Path(tmp) / 'Programs/BoyiRuntime/qdrant-1.15.5/qdrant.exe')

    def test_discovery_updates_current_process_without_installation_manifest(self):
        with tempfile.TemporaryDirectory(prefix='boyi discovered ') as tmp:
            root = Path(tmp)
            mysql_bin = root / 'installed mysql/bin'
            maven_bin = root / 'installed maven/bin'
            mysql_bin.mkdir(parents=True)
            maven_bin.mkdir(parents=True)
            found = {'bins': [str(mysql_bin), str(maven_bin)],
                     'LOCAL_REDIS_CLI': str(root / 'Memurai/memurai-cli.exe')}
            with patch.object(runner, 'ROOT', root), \
                    patch.object(runner, 'select_windows_jdk', return_value=None), \
                    patch.object(runner, 'discover_windows_tools', return_value=found), \
                    patch.dict(os.environ, {'PATH': 'old', 'ProgramData': str(root / 'ProgramData')}):
                runner.activate_windows_tools()
                self.assertEqual(os.environ['PATH'].split(os.pathsep)[:2], [str(mysql_bin), str(maven_bin)])
                self.assertEqual(os.environ['LOCAL_REDIS_CLI'], found['LOCAL_REDIS_CLI'])

    def test_java8_default_does_not_hide_java21_or_newer(self):
        import subprocess
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            homes = [base / 'Java' / ('jdk-' + version) for version in ('8', '21', '25')]
            for home in homes:
                (home / 'bin').mkdir(parents=True)
                for executable in ('java.exe', 'javac.exe'):
                    (home / 'bin' / executable).touch()
            def probe(argv, **kwargs):
                version = Path(argv[0]).parent.parent.name.split('-')[1]
                return subprocess.CompletedProcess(argv, 0, '', 'openjdk version "' + version + '.0.1"')
            with patch.dict(os.environ, {'ProgramFiles': str(base), 'JAVA_HOME': str(homes[0])}, clear=True), \
                    patch.object(runner, 'IS_WINDOWS', False), \
                    patch.object(runner.shutil, 'which', return_value=str(homes[0] / 'bin/java.exe')), \
                    patch.object(runner.subprocess, 'run', side_effect=probe):
                self.assertEqual(runner.select_windows_jdk(), homes[2])
                with patch.object(runner, 'ROOT', base):
                    runner.activate_windows_tools()
                self.assertEqual(os.environ['JAVA_HOME'], str(homes[2]))
                self.assertEqual(os.environ['PATH'].split(os.pathsep)[0], str(homes[2] / 'bin'))

    def test_setup_installs_runtime_then_project_dependencies(self):
        env = {'example': 'value'}
        with patch.object(runner, 'run') as run, \
                patch.object(runner, 'activate_windows_tools') as activate, \
                patch.object(runner, 'config', return_value=env), \
                patch.object(runner, 'prepare') as prepare, \
                patch.object(runner, 'qdrant_bin') as qdrant:
            runner.setup_windows_environment()
            run.assert_called_once_with(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                         '-File', runner.ROOT / 'scripts/windows_env.ps1'])
            activate.assert_called_once()
            prepare.assert_called_once_with(env)
            qdrant.assert_called_once_with(env)

    def test_failed_install_does_not_build_project(self):
        with patch.object(runner, 'run', side_effect=RuntimeError('cancelled')), \
                patch.object(runner, 'prepare') as prepare:
            with self.assertRaisesRegex(RuntimeError, 'cancelled'):
                runner.setup_windows_environment()
            prepare.assert_not_called()

    def test_windows_menu_has_install_and_accounts_separately(self):
        env = dict(runner.DEFAULTS)
        snapshot = {'owners': {name: None for name in runner.APP_ORDER},
                    'ports': runner.ports(env),
                    'endpoints': {('127.0.0.1', port): False for port in runner.ports(env).values()}}
        with patch.object(runner, 'IS_WINDOWS', True), \
                patch.object(runner, 'activate_windows_tools') as activate, \
                patch.object(runner, 'config', return_value=env), \
                patch.object(runner, 'collect_status', return_value=snapshot), \
                patch.object(runner, 'inspect_environment', return_value=[]), \
                patch.object(runner, 'inspect_project_dependencies', return_value=[]), \
                patch.object(runner, 'status'), patch.object(runner, 'clear_menu_screen'), \
                patch.object(runner, 'environment_summary'), patch.object(runner, 'wait_for_menu_return'), \
                patch.object(runner, 'read_menu_choice', side_effect=['6', '7', '4']) as choose, \
                patch.object(runner, 'setup_windows_environment') as setup, \
                patch.object(runner, 'show_accounts') as accounts, \
                patch.object(runner, 'installation_display', return_value=__import__('contextlib').nullcontext()):
            runner.menu()
            self.assertIn('安装 / 修复运行环境', choose.call_args_list[0].args[0])
            setup.assert_called_once()
            accounts.assert_called_once_with(env)
            self.assertEqual(activate.call_count, 3)


if __name__ == '__main__':
    unittest.main()
