"""
Import Artifact MVP cases from artifact_cases.json into MeterSphere global-app project.
Target: global-app / Artifact MVP -- APP Test Cases v1 (Adopted)
"""

import base64
import hashlib
import hmac
import json
import logging
import time
import uuid
from pathlib import Path

import requests

logging.basicConfig(level=logging.INFO, format="%(message)s")
log = logging.getLogger(__name__)

BASE_URL = "http://10.2.5.250:8081"
ACCESS_KEY = "AV8nXs5IzrkwB1Ou"
SECRET_KEY = "TpAFNmKS15QhY7VE"
PROJECT_ID = "1465686383220826112"

# Root module already exists in MS: 正式用例库 > Artifact MVP — APP端测试用例库 v1（已采纳）
# ID retrieved on 2026-06-10
ROOT_MODULE_ID = "1504340865546911744"
TAGS = ["adopted"]

CASES_FILE = Path(__file__).parent / "artifact_cases.json"


# ---------- auth ----------

def _sign(access_key, secret_key):
    timestamp = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    msg = access_key + nonce + timestamp
    signature = base64.b64encode(
        hmac.new(secret_key.encode(), msg.encode(), hashlib.sha256).digest()
    ).decode()
    return {
        "accessKey": access_key,
        "signature": signature,
        "timestamp": timestamp,
        "nonce": nonce,
    }


def _headers():
    sign = _sign(ACCESS_KEY, SECRET_KEY)
    return {
        "accessKey": sign["accessKey"],
        "signature": sign["signature"],
        "timestamp": sign["timestamp"],
        "nonce": sign["nonce"],
    }


def get(path, **kwargs):
    resp = requests.get(BASE_URL + path, headers=_headers(), **kwargs)
    resp.raise_for_status()
    data = resp.json()
    if isinstance(data, dict) and data.get("code") not in (None, 200, "200", 100000, "100000", 100200, "100200"):
        raise RuntimeError(f"API error {data.get('code')}: {data.get('message')}")
    return data


def post_json(path, body):
    resp = requests.post(BASE_URL + path, headers={**_headers(), "Content-Type": "application/json"}, json=body)
    resp.raise_for_status()
    data = resp.json()
    if isinstance(data, dict) and data.get("code") not in (None, 200, "200", 100000, "100000", 100200, "100200"):
        raise RuntimeError(f"API error {data.get('code')}: {data.get('message')}")
    return data


def post_multipart(path, fields):
    resp = requests.post(BASE_URL + path, headers=_headers(), data=fields)
    resp.raise_for_status()
    data = resp.json()
    if isinstance(data, dict) and data.get("code") not in (None, 200, "200", 100000, "100000", 100200, "100200"):
        raise RuntimeError(f"API error {data.get('code')}: {data.get('message')}")
    return data


# ---------- module helpers ----------

def get_module_tree():
    data = get(f"/functional/case/module/tree/{PROJECT_ID}")
    return data.get("data", data) or []


def _find_node(nodes, name):
    for n in nodes:
        if n.get("name") == name:
            return n
    return None


def ensure_module(tree, path_parts, parent_id="root"):
    """Walk / create module path, return leaf node id."""
    if not path_parts:
        return parent_id
    name = path_parts[0]
    node = _find_node(tree, name)
    if node:
        return ensure_module(node.get("children") or [], path_parts[1:], node["id"])
    # create
    body = {"projectId": PROJECT_ID, "name": name, "parentId": parent_id}
    resp = post_json("/functional/case/module/add", body)
    new_id = resp.get("data", {}) if isinstance(resp.get("data"), str) else resp.get("data", {}).get("id") or resp.get("data")
    if not new_id:
        raise RuntimeError(f"Failed to create module '{name}': {resp}")
    log.info(f"  Created module: {name} -> {new_id}")
    # re-fetch tree so children are available for next level
    new_tree = get_module_tree()
    # find the node we just created by id
    def find_by_id(nodes, target_id):
        for n in nodes:
            if n["id"] == target_id:
                return n
            found = find_by_id(n.get("children") or [], target_id)
            if found:
                return found
        return None
    new_node = find_by_id(new_tree, str(new_id))
    if new_node:
        return ensure_module(new_node.get("children") or [], path_parts[1:], str(new_id))
    return ensure_module([], path_parts[1:], str(new_id))


def get_module_id(full_path):
    """full_path like 'Canvas / Save' returns the module id under ROOT_MODULE_ID, creating if needed."""
    parts = [p.strip() for p in full_path.split("/") if p.strip()]
    if not parts:
        return ROOT_MODULE_ID
    # find children of ROOT_MODULE_ID in the tree
    tree = get_module_tree()

    def find_by_id(nodes, target_id):
        for n in nodes:
            if n["id"] == target_id:
                return n
            found = find_by_id(n.get("children") or [], target_id)
            if found:
                return found
        return None

    root_node = find_by_id(tree, ROOT_MODULE_ID)
    children = (root_node.get("children") or []) if root_node else []
    return ensure_module(children, parts, ROOT_MODULE_ID)


# ---------- template ----------

def get_template_id():
    data = get(f"/functional/case/default/template/field/{PROJECT_ID}")
    inner = data.get("data", data)
    if isinstance(inner, dict):
        return inner.get("id")
    return None


# ---------- steps ----------

def build_steps(steps_text):
    if not steps_text or not steps_text.strip():
        return []
    lines = [l.strip() for l in steps_text.strip().split("\n") if l.strip()]
    result = []
    for line in lines:
        # strip leading "1. " "2. " etc
        import re
        m = re.match(r"^\d+[\.\)]\s*(.+)", line)
        desc = m.group(1) if m else line
        result.append({"step": desc, "expected": ""})
    return result if result else [{"step": steps_text.strip(), "expected": ""}]


# ---------- case creation ----------

def create_case(name, priority, module_id, template_id, steps_text, precondition, expected, remark, tags):
    steps = build_steps(steps_text)
    case_req = {
        "projectId": PROJECT_ID,
        "templateId": template_id,
        "name": name,
        "moduleId": module_id,
        "priority": priority or "P2",
        "tags": tags,
        "caseEditType": "STEP" if steps else "TEXT",
        "steps": json.dumps([{"num": i + 1, "desc": s["step"], "result": s["expected"]} for i, s in enumerate(steps)]),
        "textDescription": "" if steps else steps_text,
        "prerequisite": precondition or "",
        "expectedResult": expected or "",
        "description": remark or "",
    }
    files = {"request": (None, json.dumps(case_req), "application/json")}
    resp = requests.post(BASE_URL + "/functional/case/add", headers=_headers(), files=files)
    resp.raise_for_status()
    data = resp.json()
    if isinstance(data, dict) and data.get("code") not in (None, 200, "200", 100000, "100000", 100200, "100200"):
        raise RuntimeError(f"API error {data.get('code')}: {data.get('message')}")
    return data.get("data", {})


# ---------- main ----------

def main():
    cases = json.loads(CASES_FILE.read_text(encoding="utf-8"))
    log.info(f"Loaded {len(cases)} cases from {CASES_FILE.name}")

    template_id = get_template_id()
    log.info(f"Template ID: {template_id}")

    # cache module_path -> module_id
    module_cache = {}
    ok = 0
    fail = 0

    for i, case in enumerate(cases, 1):
        name = case.get("casename") or case.get("case_name") or case.get("用例名称") or ""
        priority = case.get("priority") or case.get("优先级") or "P2"
        module_path = (case.get("module_path") or case.get("模块路径") or "").strip()
        precondition = case.get("precondition") or case.get("前置条件") or ""
        steps_text = case.get("steps") or case.get("测试步骤") or ""
        expected = case.get("expected") or case.get("预期结果") or ""
        remark = case.get("remark") or case.get("备注") or ""

        if not name:
            log.warning(f"  [{i}/{len(cases)}] Skipped (no name)")
            continue

        # resolve module
        if module_path not in module_cache:
            try:
                module_cache[module_path] = get_module_id(module_path)
            except Exception as e:
                log.error(f"  [{i}/{len(cases)}] Module creation failed for '{module_path}': {e}")
                fail += 1
                continue

        module_id = module_cache[module_path]

        try:
            create_case(name, priority, module_id, template_id, steps_text, precondition, expected, remark, TAGS)
            log.info(f"  [{i}/{len(cases)}] OK  {name[:60]}")
            ok += 1
        except Exception as e:
            log.error(f"  [{i}/{len(cases)}] FAIL {name[:60]} -- {e}")
            fail += 1

    log.info(f"\nDone: {ok} created, {fail} failed")


if __name__ == "__main__":
    main()
