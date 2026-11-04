"""Run integration tests only against this project's explicitly isolated test database."""
import importlib.util
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("local_runner", ROOT / "scripts/local_runner.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
env = runner.config(initialize=False)
env.update(DB_URL=f'jdbc:mysql://{env["LOCAL_DB_HOST"]}:{env["LOCAL_DB_PORT"]}/lab_equipment_test?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false',
           REDIS_HOST=env['LOCAL_REDIS_HOST'], REDIS_PORT=env['LOCAL_REDIS_PORT'],
           SEED_ADMIN_USERNAME='lab_test_admin', SEED_DEMO_DATA='false', AI_MODE='demo')
command = ['mvn', '-q', 'test']
result = subprocess.run(command, cwd=ROOT / 'lab-equipment-backend', env={**os.environ, **env})
raise SystemExit(result.returncode)
