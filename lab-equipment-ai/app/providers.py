"""OpenAI-compatible providers. Credentials never enter tool arguments or logs."""
import asyncio
import hashlib
import math
import os
import re
import time
import httpx


class ProviderError(RuntimeError):
    def __init__(self, message, status=None, code=""):
        super().__init__(message)
        self.status = status
        self.code = code


_vision_unavailable = {}


def error_detail(response, key):
    try:
        error = response.json().get("error", {})
        if not isinstance(error, dict): error = {"message": str(error)}
    except (ValueError, AttributeError):
        error = {}
    code = str(error.get("code") or error.get("type") or "")[:100]
    detail = str(error.get("message") or "")
    if key: detail = detail.replace(key, "[已隐藏]")
    detail = re.sub(r"(?:sk-|Bearer )[A-Za-z0-9_.-]{12,}", "[已隐藏]", detail)[:350]
    if response.status_code == 429:
        if code == "free_campaign_global_limit":
            message = "免费活动模型触发平台全局限流（429），当前免费模型繁忙，请稍后再试或手动选择其他模型。"
        elif any(word in (code+detail).lower() for word in ("quota", "tpd", "daily", "rpd", "insufficient")):
            message = "模型额度或每日调用上限已用尽（429），请查看供应商额度与重置时间。"
        else:
            message = "模型请求频率或 Token 速率达到供应商上限（429），请稍后重试。"
        if code: message += " 错误代码：" + code
        retry = response.headers.get("retry-after", "")
        if retry.isdigit(): message += "；建议等待 " + retry + " 秒。"
    else:
        hint = "，请检查密钥和模型访问权限" if response.status_code in (401,403) else ""
        message = f"模型服务返回 HTTP {response.status_code}{hint}"
        if detail: message += "：" + detail
        if code: message += "（" + code + "）"
    return ProviderError(message, response.status_code, code)


def without_images(messages):
    output=[]
    for message in messages:
        item=dict(message)
        if isinstance(item.get("content"), list):
            item["content"]="\n".join(block.get("text", "") for block in item["content"] if block.get("type")=="text")
        output.append(item)
    output.append({"role":"system","content":"当前模型仅接收文本，照片已保存为凭证，但没有向当前模型提供图片内容。不要编造图像描述，仍可依据归还话语和本人借用记录办理归还。"})
    return output



async def post_json(url, payload, key="", timeout=35):
    headers = {"Authorization": f"Bearer {key}"} if key else {}
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        for attempt in range(2):
            try:
                response = await client.post(url, json=payload, headers=headers)
            except (httpx.TimeoutException, httpx.NetworkError):
                if attempt == 0:
                    await asyncio.sleep(.5)
                    continue
                raise ProviderError("模型连接超时或网络不可用") from None
            if response.status_code in (502, 503, 504) and attempt == 0:
                await asyncio.sleep(1)
                continue
            if response.status_code >= 400:
                raise error_detail(response, key)
            try:
                return response.json()
            except ValueError:
                raise ProviderError("模型服务没有返回有效 JSON") from None


async def chat(config, messages, tools=None, max_tokens=1800):
    base, model, key = (config.get("GENERATION_" + x, "") for x in ("BASE_URL", "MODEL", "API_KEY"))
    if not base or not model:
        raise ProviderError("请在管理后台配置生成模型地址、模型名称和密钥；基础业务不受影响")
    payload = {"model": model, "messages": messages, "temperature": .2, "max_tokens": max_tokens}
    if tools:
        payload.update(tools=tools, tool_choice="auto", parallel_tool_calls=False)
    identity = (base, model)
    has_images = any(isinstance(m.get("content"),list) and any(b.get("type")=="image_url" for b in m["content"]) for m in messages)
    if has_images and _vision_unavailable.get(identity, 0) > time.monotonic():
        payload["messages"] = without_images(messages)
    try:
        result = await post_json(base.rstrip("/") + "/chat/completions", payload, key)
    except ProviderError as exc:
        text = str(exc).lower()
        unsupported = any(w in text for w in ("not support", "unsupported", "不支持", "must be a string"))
        image_error = any(w in text for w in ("image", "vision", "multimodal", "图片", "图像", "content", "多模态"))
        if not (has_images and exc.status in (400,415,422) and unsupported and image_error): raise
        if len(_vision_unavailable)>128: _vision_unavailable.clear()
        _vision_unavailable[identity]=time.monotonic()+300
        payload["messages"]=without_images(messages)
        result=await post_json(base.rstrip("/") + "/chat/completions",payload,key)
    try:
        return result["choices"][0]["message"], result.get("usage", {})
    except (KeyError, IndexError, TypeError):
        raise ProviderError("模型回复结构无效") from None


def demo_vector(text, dimension=64):
    # This is explicitly synthetic, never used to claim real semantic quality.
    vector = [0.] * dimension
    tokens = re.findall(r"[\u4e00-\u9fff]|[a-z0-9]+", text.lower())
    for token in tokens:
        digest = hashlib.sha256(token.encode()).digest()
        vector[int.from_bytes(digest[:2], "big") % dimension] += 1
    norm = math.sqrt(sum(x*x for x in vector)) or 1
    return [x/norm for x in vector]


async def embeddings(config, texts):
    if config.get("AI_MODE", os.getenv("AI_MODE", "real")) == "demo":
        return [demo_vector(t) for t in texts]
    base, model, key = (config.get("EMBEDDING_" + x, "") for x in ("BASE_URL", "MODEL", "API_KEY"))
    if not base or not model:
        raise ProviderError("缺少 Embedding 配置，请在管理后台单独配置向量模型")
    result = await post_json(base.rstrip("/") + "/embeddings", {"model": model, "input": texts}, key, 45)
    try:
        rows = sorted(result["data"], key=lambda x: x["index"])
        vectors = [x["embedding"] for x in rows]
        if len(vectors) != len(texts) or not vectors or not vectors[0] or any(len(v) != len(vectors[0]) for v in vectors):
            raise ValueError()
        if any(not math.isfinite(x) for v in vectors for x in v):
            raise ValueError()
        return vectors
    except (KeyError, TypeError, ValueError):
        raise ProviderError("Embedding 返回数量、维度或数值无效") from None
