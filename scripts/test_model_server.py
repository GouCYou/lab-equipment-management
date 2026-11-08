"""Local OpenAI-compatible fixture for isolated HTTP tests; never used by app startup."""
import json,re,argparse
from http.server import BaseHTTPRequestHandler,HTTPServer
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_GET(self):
        self.send_response(200);self.end_headers();self.wfile.write(b'{}')
    def do_POST(self):
        payload=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages=payload.get('messages',[]);allowed={t['function']['name'] for t in payload.get('tools',[])}
        users=[m['content'] for m in messages if m.get('role')=='user'];raw=users[-1] if users else ''
        query=(raw if isinstance(raw,str) else next((b.get('text','') for b in raw if b.get('type')=='text'),'' )).split('\n本轮已收到')[0]
        images=any(isinstance(m.get('content'),list) and any(b.get('type')=='image_url' for b in m['content']) for m in messages)
        executed={};ids={}
        for m in messages:
            for call in m.get('tool_calls',[]):ids[call['id']]=call['function']['name']
            if m.get('role')=='tool':
                try:executed[ids.get(m['tool_call_id'],'')]=json.loads(m['content'])
                except ValueError:pass
        plan=[]
        if any(w in query for w in ['归还','借还','逾期']):plan.append(('query_loans',{'activeOnly':True,'onlyMine':images}))
        if any(w in query for w in ['工单','故障']):plan.append(('query_tickets',{}))
        if any(w in query for w in ['利用率','统计']):plan.append(('get_statistics',{'days':30}))
        if any(w in query for w in ['知识','说明书','检查步骤']):plan.append(('search_knowledge',{'query':query}))
        if not plan:plan.append(('search_equipment',{'availableOnly':True}))
        loans=executed.get('query_loans',{}).get('items',[])
        matches=[x for x in loans if x.get('assetNo','') and x['assetNo'] in query] or (loans if len(loans)==1 else [])
        if images and '归还' in query and len(matches)==1:plan.insert(0,('return_loan',{'loanId':matches[0]['id']}))
        action=next(((n,a) for n,a in plan if n in allowed and n not in executed),None)
        result=executed.get('return_loan')
        content=('归还失败：'+str(result['error']) if result.get('error') else '已归还设备，照片已保存。') if result is not None else '已完成查询，请查看卡片和资料来源。'
        answer={'role':'assistant','content':content}
        if action:answer={'role':'assistant','content':None,'tool_calls':[{'id':'fixture-'+str(len(messages)),'type':'function','function':{'name':action[0],'arguments':json.dumps(action[1],ensure_ascii=False)}}]}
        body=json.dumps({'choices':[{'message':answer}],'usage':{'prompt_tokens':12,'completion_tokens':8,'total_tokens':20}},ensure_ascii=False).encode()
        self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=18120);args=parser.parse_args();HTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
