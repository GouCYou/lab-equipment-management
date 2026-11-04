"""Consistent offline backup of this project's native database and immutable files."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
from datetime import datetime

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runner',ROOT/'scripts/local_runner.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

def main():
    parser=argparse.ArgumentParser(description='停机一致性备份；先使用 run.sh/run.bat stop')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    with runner.controller_lock():
        if any(runner.alive(n) for n in ('backend','ai')):
            raise RuntimeError('请先停止项目后备份，确保数据库与归还照片一致。数据库服务保持运行即可。')
        env=runner.config(initialize=False)
        output=(args.output or ROOT/'.runtime/backups'/datetime.now().strftime('lab-equipment-%Y%m%d-%H%M%S.tar.gz')).resolve()
        output.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='lab-backup-') as temp:
            stage=Path(temp);dump=stage/'database.sql'
            command=['mysqldump','--no-defaults','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF','-h',env['LOCAL_DB_HOST'],'-P',env['LOCAL_DB_PORT'],'-u',env['DB_USERNAME'],env['DB_NAME']]
            with dump.open('wb') as target:
                result=subprocess.run(command,stdout=target,stderr=subprocess.PIPE,env={**os.environ,'MYSQL_PWD':env.get('DB_PASSWORD','')})
            if result.returncode:raise RuntimeError('数据库导出失败，请检查账户权限和 mysqldump 版本')
            manifest={'format':'lab-equipment-backup','version':1,'database':env['DB_NAME'],'createdAt':datetime.now().isoformat(),'databaseSha256':hashlib.sha256(dump.read_bytes()).hexdigest(),'qdrant':'rebuild-from-published-documents','secrets':'preserve-original-AI_INTERNAL_KEY-separately'}
            directory=Path(env.get('KNOWLEDGE_DIR',str(runner.STATE/'knowledge')))
            manifest['knowledgeFiles']=[{'path':str(path.relative_to(directory)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()} for path in sorted(directory.rglob('*')) if path.is_file()] if directory.exists() else []
            (stage/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
            with tarfile.open(output,'w:gz') as archive:
                archive.add(dump,arcname='database.sql');archive.add(stage/'manifest.json',arcname='manifest.json')
                directory=Path(env.get('KNOWLEDGE_DIR',str(runner.STATE/'knowledge')))
                if directory.exists():archive.add(directory,arcname='knowledge')
            output.chmod(0o600)
        print('备份完成：'+str(output))
        print('请另行保管原 AI_INTERNAL_KEY；向量索引可在恢复后重建。')

if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError) as e:raise SystemExit(str(e))
