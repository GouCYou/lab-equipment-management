import httpx
import pytest
from app import providers

@pytest.mark.asyncio
async def test_free_campaign_global_429_retries_with_bounded_backoff(monkeypatch):
    calls=[];delays=[]
    async def sleep(delay):delays.append(delay)
    monkeypatch.setattr(providers.asyncio,"sleep",sleep)
    def handler(request):
        calls.append(request)
        return httpx.Response(429,json={'error':{'message':'Too many requests for this free model','code':'free_campaign_global_limit'}})
    client=httpx.AsyncClient
    monkeypatch.setattr(providers.httpx,'AsyncClient',lambda **kw:client(transport=httpx.MockTransport(handler),**kw))
    with pytest.raises(providers.ProviderError,match='全局限流') as error:
        await providers.post_json('https://provider.test/chat/completions',{},'private-test-key')
    assert error.value.status==429 and len(calls)==5
    assert delays==[2,4,8,16]
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


@pytest.mark.asyncio
async def test_retry_eventually_succeeds_and_honors_retry_after(monkeypatch):
    calls=[];delays=[]
    def handler(request):
        calls.append(request)
        return httpx.Response(429,headers={"Retry-After":"5"},json={"error":{"code":"rate_limit"}}) if len(calls)==1 else httpx.Response(200,json={"ok":True})
    async def sleep(delay):delays.append(delay)
    client=httpx.AsyncClient
    monkeypatch.setattr(providers.httpx,"AsyncClient",lambda **kw:client(transport=httpx.MockTransport(handler),**kw))
    monkeypatch.setattr(providers.asyncio,"sleep",sleep)
    assert await providers.post_json("https://provider.test/chat/completions",{}) == {"ok":True}
    assert len(calls)==2 and delays==[5]

@pytest.mark.asyncio
@pytest.mark.parametrize("status,code",[(401,"invalid_api_key"),(403,"forbidden"),(429,"insufficient_quota")])
async def test_permanent_errors_are_not_retried(monkeypatch,status,code):
    calls=[]
    def handler(request):calls.append(request);return httpx.Response(status,json={"error":{"code":code}})
    client=httpx.AsyncClient
    monkeypatch.setattr(providers.httpx,"AsyncClient",lambda **kw:client(transport=httpx.MockTransport(handler),**kw))
    with pytest.raises(providers.ProviderError):await providers.post_json("https://provider.test/chat/completions",{})
    assert len(calls)==1
