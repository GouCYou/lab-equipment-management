"""Launch a disposable service stack against lab_equipment_test, run HTTP checks, stop only owned processes."""
import argparse
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runner',ROOT/'scripts/local_runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--skip-build',action='store_true');args=parser.parse_args()
    env=runner.config(initialize=False)
    if not env.get('SEED_ADMIN_PASSWORD'):raise RuntimeError('请先启动项目，生成本项目私有凭据')
    if not args.skip_build:subprocess.run(['mvn','-q','-DskipTests','package'],cwd=ROOT/'lab-equipment-backend',check=True)
    for port in (17433,17434,18110,18190):
        if runner.tcp('127.0.0.1',port):raise RuntimeError(f'测试端口 {port} 已被占用，不会终止其他进程')
    mysql=['mysql','--no-defaults','-h',env['LOCAL_DB_HOST'],'-P',env['LOCAL_DB_PORT'],'-u',env['DB_USERNAME']]
    subprocess.run(mysql+['-e','CREATE DATABASE IF NOT EXISTS lab_equipment_test CHARACTER SET utf8mb4'],env={**os.environ,'MYSQL_PWD':env.get('DB_PASSWORD','')},check=True,stdout=subprocess.DEVNULL)
    directory=ROOT/'.runtime/test';directory.mkdir(parents=True,exist_ok=True)
    testenv={**env,'AI_MODE':'demo','SEED_DEMO_DATA':'false','SEED_ADMIN_USERNAME':'lab_test_admin','SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'18190','AI_BASE_URL':'http://127.0.0.1:18110','BACKEND_INTERNAL_URL':'http://127.0.0.1:18190','QDRANT_URL':'http://127.0.0.1:17433','QDRANT_COLLECTION':'lab_equipment_test','KNOWLEDGE_DIR':str(directory/'knowledge'),'DB_URL':f'jdbc:mysql://{env["LOCAL_DB_HOST"]}:{env["LOCAL_DB_PORT"]}/lab_equipment_test?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false','REDIS_HOST':env['LOCAL_REDIS_HOST'],'REDIS_PORT':env['LOCAL_REDIS_PORT'],'SMOKE_ADMIN_USERNAME':'lab_test_admin','SMOKE_ADMIN_PASSWORD':env['SEED_ADMIN_PASSWORD'],'SMOKE_AI_URL':'http://127.0.0.1:18110'}
    python=ROOT/'lab-equipment-ai'/('.venv/Scripts/python.exe' if os.name=='nt' else '.venv/bin/python')
    qenv={**testenv,'QDRANT__SERVICE__HOST':'127.0.0.1','QDRANT__SERVICE__HTTP_PORT':'17433','QDRANT__SERVICE__GRPC_PORT':'17434','QDRANT__STORAGE__STORAGE_PATH':str(directory/'qdrant'),'QDRANT__TELEMETRY_DISABLED':'true'}
    specs=[('qdrant',[str(runner.qdrant_bin(env))],qenv,directory,'http://127.0.0.1:17433/healthz'),('ai',[str(python),'-m','uvicorn','app.main:app','--host','127.0.0.1','--port','18110'],testenv,ROOT/'lab-equipment-ai','http://127.0.0.1:18110/health'),('backend',['java','-jar',str(ROOT/'lab-equipment-backend/target/lab-equipment-backend-1.0.0.jar')],testenv,ROOT,'http://127.0.0.1:18190/api/health')]
    processes=[];logs=[]
    try:
        for name,command,variables,cwd,url in specs:
            log=(directory/(name+'.log')).open('ab');logs.append(log)
            options={'creationflags':subprocess.CREATE_NEW_PROCESS_GROUP} if os.name=='nt' else {'start_new_session':True}
            process=subprocess.Popen(command,cwd=cwd,env={**os.environ,**variables},stdout=log,stderr=subprocess.STDOUT,**options);processes.append(process)
            for _ in range(120):
                if process.poll() is not None:raise RuntimeError(f'{name} 测试进程退出，请查看 .runtime/test/{name}.log')
                try:
                    with urllib.request.urlopen(url,timeout=1) as response:
                        if response.status==200:break
                except OSError:time.sleep(.25)
            else:raise RuntimeError(name+' 测试启动超时')
        result=subprocess.run([str(python),str(ROOT/'scripts/smoke_test.py')],cwd=ROOT,env={**os.environ,**testenv})
        if result.returncode:raise RuntimeError('HTTP验收失败，测试进程已回收，请查看报告与日志')
    finally:
        for process in reversed(processes):
            if process.poll() is not None:continue
            if os.name=='nt':process.terminate()
            else:os.killpg(process.pid,signal.SIGTERM)
            try:process.wait(timeout=8)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for log in logs:log.close()

if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError,subprocess.CalledProcessError) as error:raise SystemExit(str(error))
