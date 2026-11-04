import asyncio
import hmac
import os
from datetime import datetime, timezone
from fastapi import FastAPI, Header, Depends, HTTPException
from . import agent, rag
from .providers import ProviderError, chat

app = FastAPI(title="博仪实验室助手内部服务", docs_url=None, redoc_url=None)


def authorized(x_internal_key: str = Header(default="")):
    expected = os.getenv("AI_INTERNAL_KEY", "")
    if not expected or not hmac.compare_digest(expected, x_internal_key):
        raise HTTPException(401, "内部授权无效")


@app.get("/health")
def health():
    return {"status": "UP", "mode": os.getenv("AI_MODE", "real")}


async def guarded(operation):
    try:
        return await operation
    except (ProviderError, ValueError) as exc:
        raise HTTPException(503, str(exc)) from None
    except TimeoutError:
        raise HTTPException(504, "本轮处理超时，请缩小范围后重试") from None
    except Exception:
        raise HTTPException(503, "智能服务未完成请求，请检查服务状态") from None


@app.post("/internal/agent", dependencies=[Depends(authorized)])
async def run(payload: dict):
    payload["now"] = datetime.now(timezone.utc).isoformat()
    return await guarded(agent.run(payload))


@app.post("/internal/knowledge/index", dependencies=[Depends(authorized)])
async def index(payload: dict):
    return await guarded(asyncio.wait_for(rag.index(payload), 120))


@app.post("/internal/knowledge/search", dependencies=[Depends(authorized)])
async def search(payload: dict):
    return await guarded(rag.search(payload))


@app.post("/internal/test-model", dependencies=[Depends(authorized)])
async def test(payload: dict):
    async def call():
        reply, usage = await chat(payload["config"], [{"role": "user", "content": "请回复：连接成功"}], max_tokens=64)
        return {"ok": True, "message": reply.get("content", ""), "usage": usage}
    return await guarded(call())
