"""Import attributed public-document summaries using the ordinary knowledge APIs."""
import json,time,urllib.request,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
BASE=os.environ.get('LAB_API_URL','http://127.0.0.1:18180/api')
def main():
    token=''
    def call(path,body=None):
        request=urllib.request.Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer '+token} if token else {})})
        with urllib.request.urlopen(request,timeout=30) as response:return json.load(response)
    token=call('/auth/login',{'username':'admin','password':'123456'})['token']
    try:
        for note in json.loads((ROOT/'docs/knowledge/public-notes.json').read_text()):
            existing=next((x for x in call('/knowledge') if x['title']==note['title']),None)
            if existing and existing.get('publishedVersionId'):
                print('已存在：'+note['title']);continue
            doc=call('/knowledge/'+existing['id']) if existing else call('/knowledge',note)
            if not doc.get('versions'):doc=call('/knowledge/'+doc['id'])
            version=doc['versions'][0]
            call('/knowledge/'+doc['id']+'/index',{'versionId':version['id']})
            for _ in range(120):
                detail=call('/knowledge/'+doc['id']);version=next(v for v in detail['versions'] if v['id']==version['id'])
                if version['status']=='READY':break
                if version['status']=='FAILED':raise RuntimeError(version.get('error'))
                time.sleep(.25)
            else:raise RuntimeError('索引超时')
            call('/knowledge/'+doc['id']+'/publish',{'versionId':version['id']})
            print('已发布：'+note['title'])
    finally:call('/auth/logout',{})
if __name__=='__main__':main()
