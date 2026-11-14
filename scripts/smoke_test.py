"""HTTP workflows against an explicitly marked test database, never a production site."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import struct
import time
import uuid
import zlib
import httpx


class Client:
    def __init__(self, base, username, password):
        self.base=base
        response=httpx.post(base+'/auth/login',json={'username':username,'password':password},timeout=15)
        response.raise_for_status();data=response.json();self.user=data['user']
        self.headers={'Authorization':'Bearer '+data['token']}
    def call(self,method,path,body=None,expected=200,key=None):
        headers={**self.headers,'Idempotency-Key':key or str(uuid.uuid4())}
        response=httpx.request(method,self.base+path,json=body,headers=headers,timeout=45)
        assert response.status_code==expected,f'{method} {path}: expected {expected}, got {response.status_code}: {response.text[:250]}'
        return response.json()
    def get(self,path):return self.call('GET',path)
    def post(self,path,body=None,**kwargs):return self.call('POST',path,body or {},**kwargs)


def png():
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    pixels=b''.join(b'\x00'+b'\xb0\xa0\xd0'*32 for _ in range(24))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',32,24,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')


def wait_run(client,data):
    for _ in range(800):
        result=client.get('/ai-runs/'+data['runId'])
        if result['status'] not in ('QUEUED','RUNNING'):
            assert result['status']=='SUCCEEDED',result
            return client.get('/conversations/'+data['conversationId'])['messages'][-1]
        time.sleep(.15)
    raise AssertionError('AI workflow timed out')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--base-url',default='http://127.0.0.1:18190/api');parser.add_argument('--report',type=Path,default=Path('docs/validation/http-integration.json'));args=parser.parse_args()
    assert httpx.get(args.base_url+'/health').json().get('testEnvironment') is True,'Refusing to run against a non-test database'
    admin=Client(args.base_url,os.getenv('SMOKE_ADMIN_USERNAME','lab_test_admin'),os.environ['SMOKE_ADMIN_PASSWORD'])
    checks=[]
    def passed(name):checks.append({'name':name,'passed':True});print('PASS '+name,flush=True)
    suffix=uuid.uuid4().hex[:8];password=secrets.token_urlsafe(18)
    lab=admin.post('/admin/laboratories',{'name':'HTTP验收实验室 '+suffix,'location':'独立测试环境','openStart':0,'openEnd':24})
    otherlab=admin.post('/admin/laboratories',{'name':'隔离实验室 '+suffix,'location':'隔离范围','openStart':0,'openEnd':24})
    def account(name,roles,labs=None):
        username=name+'-'+suffix;admin.post('/admin/users',{'username':username,'name':name,'password':password,'roles':roles,'labIds':labs or [],'enabled':True});return Client(args.base_url,username,password)
    a=account('学生A',['STUDENT']);b=account('学生B',['STUDENT']);staff=account('实验员',['LAB_STAFF'],[lab['id']]);outsider=account('范围外实验员',['LAB_STAFF'],[otherlab['id']]);tech=account('维修人员',['TECHNICIAN'])
    cat=admin.post('/admin/categories',{'name':'HTTP测试分类 '+suffix})
    devices=[admin.post('/equipment',{'assetNo':'HTTP-'+suffix+'-'+str(i),'name':'测试仪器 '+str(i),'model':'MODEL-'+suffix,'categoryId':cat['id'],'labId':lab['id'],'description':'独立验收数据'}) for i in range(3)]
    passed('账户、角色、实验室、设备真实接口创建')
    now=datetime.now(timezone.utc);start=now+timedelta(minutes=1);end=start+timedelta(minutes=15)
    request={'equipmentId':devices[0]['id'],'startsAt':start.isoformat(),'endsAt':end.isoformat(),'purpose':'HTTP真实闭环验收','userId':b.user['id']}
    key=str(uuid.uuid4());r1=a.post('/reservations',request,key=key);assert r1['userId']==a.user['id'];assert a.post('/reservations',request,key=key)['id']==r1['id']
    a.post('/reservations',{**request,'purpose':'不同内容'},key=key,expected=409)
    assert not any(r['id']==r1['id'] for r in b.get('/reservations'))
    outsider.post('/reservations/'+r1['id']+'/approve',expected=403)
    passed('请求幂等、客户端不能指定他人身份、本人及实验室隔离')
    r2=b.post('/reservations',request)
    def approve(r):
        response=httpx.post(args.base_url+'/reservations/'+r['id']+'/approve',json={},headers=staff.headers,timeout=20)
        return r,response.status_code
    with ThreadPoolExecutor(2) as pool:results=list(pool.map(approve,[r1,r2]))
    assert sorted(status for _,status in results)==[200,409]
    winner=next(r for r,status in results if status==200);borrower=a if winner['userId']==a.user['id'] else b
    passed('并发审批重叠预约仅一条成功')
    def issue(_):
        response=httpx.post(args.base_url+'/reservations/'+winner['id']+'/issue',json={},headers={**staff.headers,'Idempotency-Key':str(uuid.uuid4())},timeout=20)
        return response
    with ThreadPoolExecutor(2) as pool:issued=list(pool.map(issue,range(2)))
    assert sorted(x.status_code for x in issued)==[200,409]
    loan=next(x.json() for x in issued if x.status_code==200)
    assert len([x for x in borrower.get('/loans?activeOnly=true') if x['equipmentId']==devices[0]['id']])==1
    passed('并发实物发放仅产生一条未归还借用')
    borrower.post('/loans/'+loan['id']+'/return',{'photoIds':[]},expected=409)
    response=httpx.post(args.base_url+'/files',files={'file':('归还凭证.png',png(),'image/png')},headers=borrower.headers);response.raise_for_status();photo=response.json()
    other=b if borrower is a else a
    assert httpx.get(args.base_url+'/files/'+photo['id'],headers=other.headers).status_code==404
    chat_body={'text':'归还设备 '+devices[0]['assetNo'],'photoIds':[photo['id']]};chat_key=str(uuid.uuid4())
    run=borrower.post('/chat',chat_body,key=chat_key)
    assert borrower.post('/chat',chat_body,key=chat_key)['runId']==run['runId']
    message=wait_run(borrower,run)
    assert '已归还' in message['content'];assert borrower.get('/loans/'+loan['id'])['status']=='RETURNED'
    assert not any(x['id']==loan['id'] for x in borrower.get('/loans?activeOnly=true'))
    passed('图片上传、附件隔离、AI调用真实归还服务并释放占用')
    waiting=a.post('/reservations',{**request,'equipmentId':devices[1]['id']});staff.post('/reservations/'+waiting['id']+'/approve')
    tickets=[a.post('/tickets',{'equipmentId':devices[1]['id'],'title':'故障'+str(i),'description':'输出异常，请检查接口与连接线','photoIds':[]}) for i in range(2)]
    for t in tickets:staff.post('/tickets/'+t['id']+'/verify',{'affectsUse':True})
    assert next(r for r in a.get('/reservations') if r['id']==waiting['id'])['status']=='INVALIDATED'
    a.post('/reservations',{**request,'equipmentId':devices[1]['id']},expected=409)
    passed('故障核实禁借并使未领取预约失效')
    def repair(t):
        path='/tickets/'+t['id'];staff.post(path+'/assign',{'assigneeId':tech.user['id']});tech.post(path+'/accept');tech.post(path+'/start-repair');tech.post(path+'/note',{'note':'检查连接线与供电'});tech.post(path+'/submit-acceptance',{'resolution':'更换连接线，输出恢复正常','laborMinutes':15});staff.post(path+'/acceptance',{'passed':False,'note':'需要补充稳定性测试'});assert staff.get('/equipment/'+devices[1]['id'])['operationalStatus'] in ('MAINTENANCE','DISABLED');tech.post(path+'/submit-acceptance',{'resolution':'稳定性复测通过','laborMinutes':5});staff.post(path+'/acceptance',{'passed':True,'note':'现场复测正常'})
    repair(tickets[0]);assert staff.get('/equipment/'+devices[1]['id'])['operationalStatus']=='MAINTENANCE'
    staff.post('/equipment/'+devices[1]['id']+'/toggle',{'enabled':False,'reason':'暂时停用'})
    repair(tickets[1]);assert staff.get('/equipment/'+devices[1]['id'])['operationalStatus']=='DISABLED'
    staff.post('/equipment/'+devices[1]['id']+'/toggle',{'enabled':True,'reason':'恢复开放'});assert staff.get('/equipment/'+devices[1]['id'])['operationalStatus']=='NORMAL'
    passed('分派、维修、验收退回、关闭、多故障和手工停用独立联动')
    summary=wait_run(staff,staff.post('/chat',{'text':'总结当前实验室的设备归还情况、故障工单和利用率','context':{'labId':lab['id']}}))
    assert any(c['type']=='statistics' for c in summary['cards'])
    stats=staff.get('/statistics?labId='+lab['id']);assert 0<=stats['utilization']<=100
    a.call('GET','/statistics',expected=403)
    passed('AI总结借还、工单、利用率；学生不能越权读取管理统计')
    doc=staff.post('/knowledge',{'title':'测试维修规范 '+suffix,'text':('检查设备供电和连接线。故障处理前先断电，核实型号后按说明书操作。'+suffix+'\n')*25,'model':devices[0]['model'],'category':'','labId':lab['id'],'visibility':'PUBLIC'})
    staff.post('/knowledge/'+doc['id']+'/index',{'versionId':doc['versionId']})
    for _ in range(150):
        detail=staff.get('/knowledge/'+doc['id']);version=detail['versions'][0]
        if version['status'] in ('READY','FAILED'):break
        time.sleep(.1)
    assert version['status']=='READY',version.get('error')
    staff.post('/knowledge/'+doc['id']+'/publish',{'versionId':doc['versionId']})
    citations=a.post('/knowledge/search',{'query':'检查设备供电和连接线','model':devices[0]['model']})
    assert citations and any(c['documentId']==doc['id'] for c in citations)
    answer=wait_run(a,a.post('/chat',{'text':'请查知识库中的说明书和检查步骤，检查设备供电和连接线'}))
    assert answer['citations']
    staff.post('/knowledge/'+doc['id']+'/toggle',{'enabled':False})
    assert a.post('/knowledge/search',{'query':'检查设备供电','model':devices[0]['model']})==[]
    passed('资料解析与关键词检索、知识发布、引用展示和停用过滤')
    # No external provider credentials are used for this failure test.
    payload={'config':{'AI_MODE':'real'},'runId':'unavailable-model-test','grant':'invalid','query':'有什么设备','messages':[{'role':'user','content':'有什么设备'}]}
    response=httpx.post(os.environ['SMOKE_AI_URL']+'/internal/agent',json=payload,headers={'X-Internal-Key':os.environ['AI_INTERNAL_KEY']},timeout=15)
    assert response.status_code==503 and '配置' in response.json()['detail']
    assert httpx.post(os.environ['SMOKE_AI_URL']+'/internal/agent',json=payload).status_code==401
    passed('真实模式缺模型明确失败且不回退模拟；内部接口拒绝未授权')
    report={'passed':True,'checks':checks,'count':len(checks),'mode':'real-agent-with-local-test-provider','scope':'isolated lab_equipment_test; real MySQL Redis HTTP and real agent tool loop; local fixture model responses, not model quality evidence','timestamp':datetime.now(timezone.utc).isoformat()}
    args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(f'{len(checks)} HTTP workflows passed; report: {args.report}')


if __name__=='__main__':main()
