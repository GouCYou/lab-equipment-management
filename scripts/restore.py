"""Explicit restore, constrained to lab_equipment databases and project runtime files."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runner',ROOT/'scripts/local_runner.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

def main():
    parser=argparse.ArgumentParser(description='显式恢复到指定 lab_equipment 数据库，会覆盖该目标的表')
    parser.add_argument('archive',type=Path);parser.add_argument('--database',required=True)
    parser.add_argument('--confirm-database',required=True)
    parser.add_argument('--files-dir',type=Path,default=ROOT/'.runtime/local/knowledge')
    args=parser.parse_args()
    if not re.fullmatch(r'lab_equipment(?:_[a-zA-Z0-9_]+)?',args.database) or args.confirm_database!=args.database:
        raise RuntimeError('目标必须为 lab_equipment 项目数据库，且 --confirm-database 必须完全匹配')
    files=args.files_dir.resolve()
    if not files.is_relative_to(ROOT/'.runtime') or files==ROOT/'.runtime':raise RuntimeError('文件恢复目录必须位于本项目 .runtime 子目录内')
    with runner.controller_lock():
        if any(runner.alive(n) for n in ('backend','ai')):raise RuntimeError('请先停止项目后恢复')
        env=runner.config(initialize=False)
        redis_command=['redis-cli','-h',env['LOCAL_REDIS_HOST'],'-p',env['LOCAL_REDIS_PORT']]
        redis_env={**os.environ}
        if env.get('REDIS_PASSWORD'):redis_env['REDISCLI_AUTH']=env['REDIS_PASSWORD']
        else:redis_env.pop('REDISCLI_AUTH',None)
        ping=subprocess.run(redis_command+['ping'],capture_output=True,text=True,env=redis_env)
        if ping.returncode or ping.stdout.strip()!='PONG':raise RuntimeError('恢复前需要 Redis 可用，以撤销目标数据库的旧会话')
        with tempfile.TemporaryDirectory(prefix='lab-restore-') as temp:
            stage=Path(temp).resolve()
            with tarfile.open(args.archive,'r:gz') as archive:
                for member in archive.getmembers():
                    if member.issym() or member.islnk() or not (stage/member.name).resolve().is_relative_to(stage):raise RuntimeError('备份包含不安全路径或链接')
                archive.extractall(stage,filter='data')
            manifest=json.loads((stage/'manifest.json').read_text());dump=stage/'database.sql'
            if manifest.get('format')!='lab-equipment-backup' or hashlib.sha256(dump.read_bytes()).hexdigest()!=manifest['databaseSha256']:raise RuntimeError('备份格式或校验和无效')
            for item in manifest.get('knowledgeFiles',[]):
                path=(stage/'knowledge'/item['path']).resolve()
                if not path.is_relative_to(stage/'knowledge') or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:
                    raise RuntimeError('知识原件或附件的校验和无效')
            cmd=['mysql','--no-defaults','-h',env['LOCAL_DB_HOST'],'-P',env['LOCAL_DB_PORT'],'-u',env['DB_USERNAME'],args.database]
            with dump.open('rb') as stream:
                result=subprocess.run(cmd,stdin=stream,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,env={**os.environ,'MYSQL_PWD':env.get('DB_PASSWORD','')})
            if result.returncode:raise RuntimeError('数据库恢复失败，请保留备份并检查权限；目标可能已部分写入')
            reset="UPDATE sys_user SET auth_version=auth_version+1; UPDATE ai_run SET status='INTERRUPTED',grant_hash=NULL WHERE status IN ('QUEUED','RUNNING'); UPDATE knowledge_version SET status='FAILED',error='恢复后请重新索引' WHERE status='INDEXING';"
            result=subprocess.run(cmd,input=reset.encode(),stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,env={**os.environ,'MYSQL_PWD':env.get('DB_PASSWORD','')})
            if result.returncode:raise RuntimeError('恢复后的状态重置失败，请勿启动项目')
            scan=subprocess.run(redis_command+['--scan','--pattern',f'lab-equipment:{args.database}:session:*'],capture_output=True,text=True,env=redis_env)
            if scan.returncode:raise RuntimeError('数据库已恢复，但旧会话撤销失败，请修复 Redis 后再启动')
            keys=scan.stdout.splitlines()
            for offset in range(0,len(keys),128):
                result=subprocess.run(redis_command+['UNLINK',*keys[offset:offset+128]],capture_output=True,env=redis_env)
                if result.returncode:raise RuntimeError('旧会话清理失败，请勿启动项目')
            if (stage/'knowledge').exists():
                replacement=files.with_name(files.name+'-restoring');shutil.rmtree(replacement,ignore_errors=True);shutil.copytree(stage/'knowledge',replacement)
                if files.exists():files.rename(files.with_name(files.name+'-before-restore-'+str(os.getpid())))
                replacement.rename(files)
        print('恢复完成；目标数据库旧会话已撤销，旧文件目录已保留，重启后重建知识索引。')

if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError,ValueError) as e:raise SystemExit(str(e))
