#!/usr/bin/env python3
"""
MeterSphere 用例脑图代理服务
用法：python scripts/mindmap_server.py [--port 8088] [--open]

功能：
  - 解决浏览器跨域问题，代理 MeterSphere API 请求
  - 提供脑图前端页面（GET /）
  - 暴露项目/模块/用例查询和用例更新接口

依赖：pip install fastapi uvicorn pycryptodome requests
"""

import argparse
import base64
import json
import os
import sys
import time
import webbrowser
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Optional

import requests
import uvicorn
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad
from fastapi import FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

# ──────────────────────────────────────────────
# 凭据加载（与 ms_upload.py 完全一致）
# ──────────────────────────────────────────────
CREDS_PATH = Path.home() / ".config" / "plaud" / "metersphere.json"


def load_credentials() -> tuple:
    if not CREDS_PATH.exists():
        print(f"""
❌ 未找到 MeterSphere 凭据文件：{CREDS_PATH}

请按以下步骤创建：
  1. 登录 MeterSphere：http://10.2.5.250:8081
  2. 右上角头像 → 个人信息 → API Keys → 创建
  3. 执行以下命令（替换为你的实际 key）：

     mkdir -p ~/.config/plaud
     cat > ~/.config/plaud/metersphere.json << 'EOF'
     {{
       "access_key": "你的 Access Key",
       "secret_key": "你的 Secret Key"
     }}
     EOF
""")
        sys.exit(1)

    try:
        with open(CREDS_PATH, "r", encoding="utf-8") as f:
            creds = json.load(f)
        access_key = creds["access_key"]
        secret_key = creds["secret_key"]
        if not access_key or not secret_key:
            raise ValueError("access_key 或 secret_key 为空")
        return access_key, secret_key
    except (KeyError, ValueError) as e:
        print(f"❌ 凭据文件格式错误（{CREDS_PATH}）：{e}")
        print('文件应包含：{"access_key": "...", "secret_key": "..."}')
        sys.exit(1)


ACCESS_KEY, SECRET_KEY = load_credentials()

BASE_URL = os.environ.get("MS_BASE_URL", "http://localhost:8081")

# 项目名称 → projectId 映射
PROJECT_MAP = {
    "global-web": "1465684991651422208",
    "global-app": "1465686383220826112",
    "CN-web":     "1470186838932258816",
    "CN-app":     "1470187027910819840",
}

# 脑图前端 HTML 文件路径（与本脚本同级的 ../mindmap/index.html）
MINDMAP_HTML = Path(__file__).parent.parent / "mindmap" / "index.html"


# ──────────────────────────────────────────────
# 鉴权（与 ms_upload.py 完全一致）
# ──────────────────────────────────────────────
def generate_signature() -> str:
    """生成 AES-CBC 加密签名"""
    plaintext = f"{ACCESS_KEY}|{int(time.time() * 1000)}"
    key = SECRET_KEY.encode("utf-8")[:16].ljust(16, b'\0')
    iv  = ACCESS_KEY.encode("utf-8")[:16].ljust(16, b'\0')
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(encrypted).decode("utf-8")


def json_headers() -> dict:
    """返回带签名的 JSON 请求头"""
    return {
        "accessKey": ACCESS_KEY,
        "signature": generate_signature(),
        "Content-Type": "application/json",
    }


def multipart_headers() -> dict:
    """返回带签名的 multipart 请求头（不设 Content-Type，让 requests 自动处理）"""
    return {
        "accessKey": ACCESS_KEY,
        "signature": generate_signature(),
    }


# ──────────────────────────────────────────────
# FastAPI 应用
# ──────────────────────────────────────────────
app = FastAPI(title="MeterSphere 脑图代理", version="1.0.0")

# 允许所有跨域（内网工具，无需严格限制）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
async def serve_mindmap():
    """返回脑图前端 HTML 页面"""
    if not MINDMAP_HTML.exists():
        raise HTTPException(status_code=404, detail=f"未找到前端文件：{MINDMAP_HTML}")
    return FileResponse(str(MINDMAP_HTML), media_type="text/html")


@app.get("/api/projects")
async def get_projects():
    """返回可用项目列表"""
    return JSONResponse(content=list(PROJECT_MAP.keys()))


@app.get("/api/modules")
async def get_modules(project: str):
    """
    获取指定项目的模块树
    调用 GET {BASE_URL}/functional/case/module/tree/{projectId}
    返回 .data
    """
    if project not in PROJECT_MAP:
        raise HTTPException(status_code=400, detail=f"未知项目：{project}，可选：{list(PROJECT_MAP.keys())}")

    project_id = PROJECT_MAP[project]
    url = f"{BASE_URL}/functional/case/module/tree/{project_id}"

    try:
        resp = requests.get(url, headers=json_headers(), timeout=15)
        resp.raise_for_status()
        data = resp.json()
        return JSONResponse(content=data.get("data", []))
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"请求 MeterSphere 失败：{e}")


def _fetch_case_detail(case_id: str) -> Optional[dict]:
    """拉取单条用例详情（含 steps/prerequisite/functionalPriority）"""
    try:
        resp = requests.get(
            f"{BASE_URL}/functional/case/detail/{case_id}",
            headers=json_headers(),
            timeout=15,
        )
        resp.raise_for_status()
        return resp.json().get("data")
    except Exception:
        return None


@app.get("/api/cases")
async def get_cases(project: str, moduleId: str):
    """
    获取指定模块的完整用例列表（含 steps/prerequisite）。
    流程：
      1. POST /functional/case/page 拿用例 ID 列表
      2. 并发调 GET /functional/case/detail/{id} 获取完整字段
    """
    if project not in PROJECT_MAP:
        raise HTTPException(status_code=400, detail=f"未知项目：{project}")

    project_id = PROJECT_MAP[project]

    # Step 1：拿列表（只含基础字段，没有 steps）
    try:
        list_resp = requests.post(
            f"{BASE_URL}/functional/case/page",
            headers=json_headers(),
            json={"projectId": project_id, "moduleIds": [moduleId], "current": 1, "pageSize": 500},
            timeout=30,
        )
        list_resp.raise_for_status()
        inner = list_resp.json().get("data", {})
        # API 返回 {list: [...], total: N, ...}
        case_list = inner.get("list", inner.get("records", inner if isinstance(inner, list) else []))
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"获取用例列表失败：{e}")

    if not case_list:
        return JSONResponse(content=[])

    # Step 2：并发拉取详情（最多 20 个并发）
    case_ids = [c["id"] for c in case_list if c.get("id")]
    details = []
    with ThreadPoolExecutor(max_workers=20) as pool:
        futures = {pool.submit(_fetch_case_detail, cid): cid for cid in case_ids}
        for future in as_completed(futures):
            result = future.result()
            if result:
                details.append(result)

    # 按原始列表顺序排列（detail 并发返回顺序不定）
    id_order = {cid: i for i, cid in enumerate(case_ids)}
    details.sort(key=lambda c: id_order.get(c.get("id", ""), 9999))

    return JSONResponse(content=details)


@app.patch("/api/cases/{case_id}")
async def update_case(case_id: str, request: Request):
    """
    更新用例：先拉取完整用例详情，合并变更字段，再提交更新。
    MeterSphere 的 update 接口要求完整字段，不能只传部分。
    """
    try:
        changes: dict = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="请求体必须是合法 JSON")

    # 1. 先拉取完整用例详情
    detail = _fetch_case_detail(case_id)
    if not detail:
        raise HTTPException(status_code=404, detail=f"用例 {case_id} 不存在")

    # 2. 构造完整的更新 body（基于现有数据 + 覆盖变更字段）
    body = {
        "id":             case_id,
        "projectId":      detail.get("projectId", ""),
        "templateId":     detail.get("templateId", ""),
        "moduleId":       detail.get("moduleId", ""),
        "name":           detail.get("name", ""),
        "caseEditType":   detail.get("caseEditType", "STEP"),
        "prerequisite":   detail.get("prerequisite", ""),
        "tags":           detail.get("tags", []),
    }

    # 处理 steps：detail 返回的可能是数组或 JSON 字符串
    existing_steps = detail.get("steps", "[]")
    if isinstance(existing_steps, list):
        body["steps"] = json.dumps(existing_steps, ensure_ascii=False)
    else:
        body["steps"] = existing_steps

    # 3. 合并前端传来的变更
    for key, value in changes.items():
        if key == "steps" and isinstance(value, list):
            body["steps"] = json.dumps(value, ensure_ascii=False)
        else:
            body[key] = value

    url = f"{BASE_URL}/functional/case/update"
    files = {
        "request": (None, json.dumps(body, ensure_ascii=False), "application/json")
    }

    try:
        resp = requests.post(url, headers=multipart_headers(), files=files, timeout=30)
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") not in (100200, 200):
            raise HTTPException(status_code=500, detail=f"更新失败：{result}")
        return JSONResponse(content=result)
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"请求 MeterSphere 失败：{e}")


@app.delete("/api/cases/{case_id}")
async def delete_case(case_id: str):
    """删除用例（移到回收站）"""
    detail = _fetch_case_detail(case_id)
    if not detail:
        raise HTTPException(status_code=404, detail=f"用例 {case_id} 不存在")

    url = f"{BASE_URL}/functional/case/delete"
    body = {
        "id": case_id,
        "projectId": detail.get("projectId", ""),
        "deleteAll": True,
    }
    try:
        resp = requests.post(url, headers=json_headers(), json=body, timeout=30)
        resp.raise_for_status()
        return JSONResponse(content=resp.json())
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"删除失败：{e}")


@app.post("/api/cases")
async def create_case(request: Request):
    """
    创建新用例
    接收 JSON body: { projectId, moduleId, name, steps?, prerequisite? }
    """
    try:
        body: dict = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="请求体必须是合法 JSON")

    project_key = body.pop("projectKey", None)
    if not body.get("projectId") and project_key and project_key in PROJECT_MAP:
        body["projectId"] = PROJECT_MAP[project_key]

    project_id = body.get("projectId")
    if not project_id:
        raise HTTPException(status_code=400, detail="缺少 projectId 或 projectKey")

    # 查询模板 ID
    try:
        tpl_url = f"{BASE_URL}/functional/case/default/template/field/{project_id}"
        tpl_resp = requests.get(tpl_url, headers=json_headers(), timeout=15)
        tpl_resp.raise_for_status()
        tpl_data = tpl_resp.json().get("data", {})
        template_id = tpl_data.get("id")
    except Exception:
        template_id = None

    # 构造步骤
    steps = body.get("steps", [])
    if isinstance(steps, list):
        formatted = []
        for i, s in enumerate(steps, 1):
            formatted.append({
                "id": str(i), "num": i,
                "desc": s.get("desc", ""),
                "result": s.get("result", ""),
            })
        body["steps"] = json.dumps(formatted, ensure_ascii=False)

    body.setdefault("caseEditType", "STEP")
    body.setdefault("tags", ["AI生成"])
    if template_id:
        body["templateId"] = template_id

    url = f"{BASE_URL}/functional/case/add"
    files = {
        "request": (None, json.dumps(body, ensure_ascii=False), "application/json")
    }

    try:
        resp = requests.post(url, headers=multipart_headers(), files=files, timeout=30)
        resp.raise_for_status()
        result = resp.json()
        if result.get("code") not in (100200, 200):
            raise HTTPException(status_code=500, detail=f"创建失败：{result}")
        return JSONResponse(content=result)
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"请求 MeterSphere 失败：{e}")


@app.post("/api/cases/drag-sort")
async def drag_sort_case(request: Request):
    """
    拖拽排序用例
    接收 JSON body: { projectId, moveId, targetId, moveMode }
    moveMode: 'BEFORE' | 'AFTER'
    """
    try:
        body: dict = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="请求体必须是合法 JSON")

    url = f"{BASE_URL}/functional/case/edit/pos"
    try:
        resp = requests.post(url, headers=json_headers(), json=body, timeout=30)
        resp.raise_for_status()
        return JSONResponse(content=resp.json())
    except requests.RequestException as e:
        raise HTTPException(status_code=502, detail=f"排序失败：{e}")


# ──────────────────────────────────────────────
# CLI 入口
# ──────────────────────────────────────────────
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="启动 MeterSphere 脑图代理服务")
    parser.add_argument("--port", type=int, default=8088, help="监听端口（默认 8088）")
    parser.add_argument("--open", action="store_true", help="启动后自动打开浏览器")
    args = parser.parse_args()

    if args.open:
        # 延迟 1 秒打开，等服务就绪
        import threading
        threading.Timer(1.0, lambda: webbrowser.open(f"http://localhost:{args.port}")).start()

    print(f"🗺  脑图服务：http://localhost:{args.port}")
    print(f"   按 Ctrl+C 停止")

    import uvicorn.config
    config = uvicorn.Config(app, host="0.0.0.0", port=args.port)
    server = uvicorn.Server(config)
    # 允许端口重用，避免 TIME_WAIT 导致重启失败
    import socket
    orig_bind = socket.socket.bind
    def reuse_bind(self, address):
        self.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        return orig_bind(self, address)
    socket.socket.bind = reuse_bind
    server.run()
