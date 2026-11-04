import base64
import hashlib
import io
import os
import uuid
import zipfile
import httpx
from docx import Document
from pypdf import PdfReader
from .providers import embeddings, ProviderError


def extract(file):
    try:
        data = base64.b64decode(file["base64"], validate=True)
        if len(data) > 20*1024*1024:
            raise ValueError("文件过大")
        name = file["name"].lower()
        if name.endswith(".docx"):
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                if sum(x.file_size for x in archive.infolist()) > 60*1024*1024:
                    raise ValueError("文档解压体积过大")
            doc = Document(io.BytesIO(data))
            parts = [(f"段落 {i+1}", p.text) for i, p in enumerate(doc.paragraphs) if p.text.strip()]
            parts += [(f"表格 {i+1}", "\n".join(" | ".join(c.text for c in r.cells) for r in table.rows)) for i, table in enumerate(doc.tables)]
            return parts
        if name.endswith(".pdf"):
            doc = PdfReader(io.BytesIO(data))
            if len(doc.pages) > 500:
                raise ValueError("PDF 不得超过 500 页")
            return [(f"第 {i+1} 页", page.extract_text() or "") for i, page in enumerate(doc.pages)]
        if name.endswith((".txt", ".md")):
            return [("正文", data.decode("utf-8-sig"))]
        raise ValueError("不支持此文档格式")
    except Exception as exc:
        if isinstance(exc, ValueError):
            raise
        raise ValueError("无法解析文件，请检查格式；扫描 PDF 需先进行 OCR") from None


def split(parts, version_id, size=700, overlap=100):
    chunks = []
    for locator, text in parts:
        text = text.replace("\x00", "").strip()
        for offset in range(0, len(text), size-overlap):
            value = text[offset:offset+size].strip()
            if not value:
                continue
            index = len(chunks)
            identity = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{version_id}:{index}:{hashlib.sha256(value.encode()).hexdigest()}"))
            chunks.append({"id": identity, "index": index, "text": value, "locator": locator + (f"，片段 {offset//(size-overlap)+1}" if len(text)>size else "")})
            if len(chunks) > 1000:
                raise ValueError("资料过长，请拆分为多个文档")
    return chunks


def collection(config):
    base = os.getenv("QDRANT_COLLECTION", "lab_equipment_knowledge")
    if config.get("AI_MODE", os.getenv("AI_MODE", "real")) == "demo":
        return base + "_demo"
    fingerprint = hashlib.sha256(config.get("EMBEDDING_MODEL", "").encode()).hexdigest()[:10]
    return base + "_" + fingerprint


async def qrequest(method, path, body=None, allow_missing=False):
    headers = {"api-key": os.getenv("QDRANT_API_KEY")} if os.getenv("QDRANT_API_KEY") else {}
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            r = await client.request(method, os.getenv("QDRANT_URL", "http://127.0.0.1:17333") + path, json=body, headers=headers)
            if allow_missing and r.status_code == 404:
                return None
            if r.status_code >= 400:
                raise ProviderError(f"向量数据库请求失败 HTTP {r.status_code}，请检查索引及向量维度")
            return r.json().get("result")
    except httpx.HTTPError:
        raise ProviderError("无法连接 Qdrant，基础借还与工单功能不受影响") from None


async def index(payload):
    parts = extract(payload["file"]) if payload.get("file") else [("正文", payload.get("text", ""))]
    if not any(t.strip() for _, t in parts):
        raise ValueError("未提取到有效文本；扫描文档需先进行 OCR")
    if sum(len(t) for _, t in parts) > 500000:
        raise ValueError("资料文字过长")
    chunks = split(parts, payload["versionId"])
    return {"text": "\n\n".join(t for _, t in parts), "chunks": chunks, "collection": None, "mode": "keyword"}


async def search(payload):
    versions = payload.get("versionIds", [])
    if not versions:
        return {"items": []}
    if len(versions) > 500:
        raise ValueError("资料范围过大，请按实验室和型号缩小范围")
    vector = (await embeddings(payload["config"], [payload["query"]]))[0]
    name = collection(payload["config"])
    if await qrequest("GET", f"/collections/{name}", allow_missing=True) is None:
        raise ProviderError("当前向量模型尚无索引，请重新索引资料后检索")
    result = await qrequest("POST", f"/collections/{name}/points/query", {"query": vector, "filter": {"must": [{"key": "versionId", "match": {"any": versions}}]}, "limit": 6, "with_payload": True, "score_threshold": .12})
    return {"items": [{"chunkId": p["payload"]["chunkId"], "score": p["score"]} for p in result.get("points", [])]}
