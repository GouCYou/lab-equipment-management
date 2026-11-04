import httpx
import pytest
from app import providers

@pytest.mark.asyncio
async def test_free_campaign_global_429_is_explained_without_retry(monkeypatch):
    calls=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(429,json={'error':{'message':'Too many requests for this free model','code':'free_campaign_global_limit'}})
    client=httpx.AsyncClient
    monkeypatch.setattr(providers.httpx,'AsyncClient',lambda **kw:client(transport=httpx.MockTransport(handler),**kw))
    with pytest.raises(providers.ProviderError,match='全局限流') as error:
        await providers.post_json('https://provider.test/chat/completions',{},'private-test-key')
    assert error.value.status==429 and len(calls)==1
    assert 'private-test-key' not in str(error.value)

@pytest.mark.asyncio
async def test_text_only_model_retries_without_images_and_keeps_tools(monkeypatch):
    providers._vision_unavailable.clear()
    requests=[]
    async def post(url,payload,key):
        requests.append(dict(payload))
        if len(requests)==1:raise providers.ProviderError('image input is not supported',400)
        assert all(not isinstance(m.get('content'),list) for m in payload['messages'])
        assert payload['tools']==[{'type':'function'}]
        return {'choices':[{'message':{'role':'assistant','content':'归还已提交'}}]}
    monkeypatch.setattr(providers,'post_json',post)
    result,_=await providers.chat({'GENERATION_BASE_URL':'https://text.test/v1','GENERATION_MODEL':'text-model'},[{'role':'user','content':[{'type':'text','text':'归还设备'},{'type':'image_url','image_url':{'url':'data:image/png;base64,test'}}]}],[{'type':'function'}])
    assert result['content']=='归还已提交' and len(requests)==2

@pytest.mark.asyncio
async def test_429_never_triggers_image_or_demo_fallback(monkeypatch):
    providers._vision_unavailable.clear();calls=[]
    async def post(*args):
        calls.append(args);raise providers.ProviderError('全局限流',429,'free_campaign_global_limit')
    monkeypatch.setattr(providers,'post_json',post)
    with pytest.raises(providers.ProviderError):
        await providers.chat({'GENERATION_BASE_URL':'https://vision.test/v1','GENERATION_MODEL':'vision'},[{'role':'user','content':[{'type':'image_url','image_url':{'url':'data:image/png;base64,test'}}]}])
    assert len(calls)==1
