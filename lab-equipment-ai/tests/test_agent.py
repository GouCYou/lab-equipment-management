import base64
import io
import pytest
from docx import Document
from fastapi.testclient import TestClient
from app import agent, rag
from app.providers import ProviderError, embeddings
from app.main import app


class FakeBackend:
    def __init__(self, loans=None):
        self.calls = []
        self.results = []
        self.loans = loans or []
    async def call(self, name, arguments):
        self.calls.append((name, arguments))
        result = {"items": self.loans} if name == "query_loans" else {"error": "缺少授权或资料"}
        if name == "return_loan":
            result = {**self.loans[0], "status": "RETURNED"}
        self.results.append((name,result))
        return result


@pytest.mark.asyncio
async def test_tool_parameters_do_not_contain_runtime_objects():
    backend = FakeBackend()
    tools = agent.make_tools(backend, ["STUDENT"])
    assert "get_statistics" not in tools
    await tools["search_equipment"].ainvoke({"search": "示波器"})
    assert backend.calls == [("search_equipment", {"search":"示波器","labId":"","availableOnly":False})]


@pytest.mark.asyncio
async def test_legacy_mode_setting_still_calls_configured_model(monkeypatch):
    calls=[]
    async def model(config,messages,tools=None):
        calls.append(config)
        return {"role":"assistant","content":"真实接口结果"},{"total_tokens":5}
    monkeypatch.setattr(agent,"chat",model)
    result=await agent.run({"config":{"AI_MODE":"demo","GENERATION_MODEL":"test-model"},"query":"有什么设备","messages":[{"role":"user","content":"有什么设备"}]})
    assert calls and result["mode"]=="real" and result["text"]=="真实接口结果"

@pytest.mark.asyncio
async def test_legacy_mode_missing_config_reports_error():
    with pytest.raises(ProviderError,match="配置"):
        await agent.run({"config":{"AI_MODE":"demo"},"query":"归还设备","messages":[{"role":"user","content":"归还设备"}]})


@pytest.mark.asyncio
async def test_real_missing_config_never_falls_back_to_demo():
    with pytest.raises(ProviderError, match="配置"):
        await agent.real({"config":{},"messages":[{"role":"user","content":"有什么设备"}]}, FakeBackend())
    with pytest.raises(ProviderError, match="Embedding"):
        await embeddings({"AI_MODE":"real"}, ["示波器"])


def test_docx_extraction_and_chunk_ids_are_stable():
    document=Document();document.add_paragraph("数字示波器使用说明。"*180)
    buffer=io.BytesIO();document.save(buffer)
    parts=rag.extract({"name":"manual.docx","base64":base64.b64encode(buffer.getvalue()).decode()})
    first=rag.split(parts,"version-1")
    assert len(first)>1 and first==rag.split(parts,"version-1")
    assert first[0]["id"]!=rag.split(parts,"version-2")[0]["id"]
    assert first[0]["locator"].startswith("段落")


@pytest.mark.asyncio
async def test_permission_filter_is_applied_before_top_k(monkeypatch):
    requests=[]
    async def fake_request(method,path,body=None,allow_missing=False):
        requests.append((method,path,body))
        return {} if method=="GET" else {"points":[]}
    async def fake_embedding(*args):return [[1.0,0.0]]
    monkeypatch.setattr(rag,"embeddings",fake_embedding)
    monkeypatch.setattr(rag,"qrequest",fake_request)
    await rag.search({"query":"开机故障","versionIds":["allowed-version"],"config":{"AI_MODE":"demo"}})
    body=requests[-1][2]
    assert body["filter"]["must"][0]["match"]["any"]==["allowed-version"]
    assert body["limit"]==6


def test_internal_endpoints_require_service_key(monkeypatch):
    monkeypatch.setenv("AI_INTERNAL_KEY","private-test-key")
    client=TestClient(app)
    assert client.get('/health').status_code==200
    assert client.post('/internal/agent',json={}).status_code==401
    assert client.post('/internal/knowledge/search',json={}).status_code==401


@pytest.mark.asyncio
async def test_tool_budget_rejects_before_network():
    backend=agent.Backend({})
    backend.calls=6
    assert "上限" in (await backend.call("return_loan",{}))["error"]


@pytest.mark.asyncio
async def test_vision_model_receives_image_and_photo_receipt(monkeypatch):
    backend=FakeBackend([{"id":"loan-a","assetNo":"LAB-1","equipmentName":"示波器"}])
    calls=[]
    async def fake_chat(config,messages,tools=None):
        calls.append(messages.copy())
        if len(calls)==1:
            assert any(isinstance(m.get("content"),list) and m["content"][1]["type"]=="image_url" for m in messages)
            assert any("本轮已收到 1 张照片" in str(m.get("content")) for m in messages)
            return {"role":"assistant","content":None,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"return_loan","arguments":'{"loanId":"loan-a","identifiedAssetNo":"LAB-1","imageShowsReturn":true}'}}]},{}
        assert messages[-1]["role"]=="tool"
        assert 'RETURNED' in messages[-1]["content"]
        return {"role":"assistant","content":"设备已归还，照片已保存。"},{"prompt_tokens":10,"completion_tokens":8}
    monkeypatch.setattr(agent,"chat",fake_chat)
    result=await agent.real({"config":{"AI_MODE":"real"},"messages":[{"role":"user","content":"归还设备"}],"query":"归还设备","images":[{"mimeType":"image/png","base64":"test-fixture"}],"user":{"roles":["STUDENT"]}},backend)
    assert result["mode"]=="real" and result["usage"]["prompt_tokens"]==10
    assert result["cards"][0]["data"]["status"]=="RETURNED"
    assert backend.calls[0][0]=="return_loan"

@pytest.mark.asyncio
async def test_keyword_index_needs_no_embedding_provider(monkeypatch):
    async def unavailable(*args, **kwargs):
        raise AssertionError('Keyword indexing must not call embedding or Qdrant')
    monkeypatch.setattr(rag,'embeddings',unavailable)
    monkeypatch.setattr(rag,'qrequest',unavailable)
    result=await rag.index({'versionId':'keyword-version','documentId':'manual','text':'示波器探头检查与维护','config':{'AI_MODE':'real'}})
    assert result['mode']=='keyword' and result['chunks'][0]['text']=='示波器探头检查与维护'
