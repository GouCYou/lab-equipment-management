"""Real-provider ablation: the same question/model with direct answers versus Agent+RAG."""
import argparse
import importlib.util
import json
from pathlib import Path
import statistics
import time
from smoke_test import Client, wait_run

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description='使用已配置的真实模型进行对照实验；会产生供应商调用用量')
    parser.add_argument('--base-url',default='http://127.0.0.1:18180/api')
    parser.add_argument('--cases',type=Path,default=ROOT/'docs/examples/evaluation-cases.json')
    parser.add_argument('--output',type=Path,default=ROOT/'docs/validation/ai-evaluation.json')
    parser.add_argument('--limit',type=int,default=30)
    args=parser.parse_args()
    spec=importlib.util.spec_from_file_location('runner',ROOT/'scripts/local_runner.py');runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
    env=runner.config(initialize=False)
    client=Client(args.base_url,env.get('SEED_ADMIN_USERNAME','admin'),env['SEED_ADMIN_PASSWORD'])
    settings=client.get('/admin/ai-settings')
    if settings.get('AI_MODE')!='real':raise SystemExit('当前是演示模式，不能进行模型质量实验。请以 real 模式启动并配置模型。')
    manifest=client.get('/knowledge');cases=json.loads(args.cases.read_text())[:max(1,min(args.limit,100))]
    report={'models':settings,'knowledgeManifest':manifest,'cases':[],'humanScoring':'人工填写相关性、依据支持率和幻觉情况；程序不预设提升结论'}
    for case in cases:
        entry={'id':case['id'],'question':case['question'],'category':case['category'],'outputs':{}}
        for mode in ('DIRECT','AGENT_RAG'):
            started=time.monotonic()
            try:
                run=client.post('/chat',{'text':case['question'],'context':{'evaluationMode':mode}})
                message=wait_run(client,run);result=client.get('/ai-runs/'+run['runId'])
                entry['outputs'][mode]={'text':message['content'],'citations':message.get('citations',[]),'tools':result['events'],'usage':result.get('usage'),'durationSeconds':round(time.monotonic()-started,3),'success':True,'humanScores':{'relevance':None,'evidenceSupport':None,'hallucination':None}}
            except Exception as exc:
                entry['outputs'][mode]={'success':False,'error':str(exc)[:600],'durationSeconds':round(time.monotonic()-started,3)}
        report['cases'].append(entry)
        args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print('已保存问题 '+case['id'],flush=True)
    summary={}
    for mode in ('DIRECT','AGENT_RAG'):
        outputs=[r['outputs'][mode] for r in report['cases']];times=sorted(r['durationSeconds'] for r in outputs if r['success'])
        summary[mode]={'count':len(outputs),'successes':len(times),'p50Seconds':statistics.median(times) if times else None,'p95Seconds':times[max(0,int(len(times)*.95)-1)] if times else None}
    report['summary']=summary;args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('原始输出与时延已保存；回答质量需结合原文进行人工评价。')

if __name__=='__main__':main()
