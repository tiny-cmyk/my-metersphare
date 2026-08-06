#!/usr/bin/env python3
"""
MeterSphere 回收站定时清理脚本
每天自动删除所有项目回收站中超过 7 天的用例（彻底删除，不可恢复）

用法：
  python3 scripts/clean_trash.py          # 直接执行
  python3 scripts/clean_trash.py --dry-run  # 只打印不执行

crontab（每天凌晨 3 点）：
  0 3 * * * cd /home/ubuntu/data && MS_BASE_URL=http://localhost:8081 python3 scripts/clean_trash.py >> /home/ubuntu/data/clean_trash.log 2>&1
"""

import sys
import os
import time
from datetime import datetime
from pathlib import Path

# 复用 mindmap_server 的鉴权
sys.path.insert(0, str(Path(__file__).parent.parent))
from scripts.mindmap_server import json_headers, BASE_URL, PROJECT_MAP

import requests

RETENTION_DAYS = 180  # 半年
BATCH_SIZE = 50


def clean_project_trash(project_name, project_id, dry_run=False):
    now_ms = int(time.time() * 1000)
    cutoff_ms = now_ms - RETENTION_DAYS * 86400 * 1000

    print(f"\n[{project_name}] 扫描回收站...")

    expired_ids = []
    total = 0
    page = 1
    while True:
        resp = requests.post(
            f"{BASE_URL}/functional/case/trash/page",
            headers=json_headers(),
            json={"projectId": project_id, "current": page, "pageSize": 100},
            timeout=30,
        )
        if resp.status_code != 200:
            print(f"  [错误] HTTP {resp.status_code}")
            return 0

        data = resp.json().get("data", {})
        items = data.get("list", [])
        total = data.get("total", 0)

        for item in items:
            if item.get("deleteTime", 0) < cutoff_ms:
                expired_ids.append(item["id"])

        if len(items) < 100 or page * 100 >= total:
            break
        page += 1

    if not expired_ids:
        print(f"  无超期用例（回收站共 {total} 条）")
        return 0

    print(f"  发现 {len(expired_ids)} 条超过 {RETENTION_DAYS} 天（回收站共 {total} 条）")

    if dry_run:
        print(f"  [DRY RUN] 跳过删除")
        return len(expired_ids)

    deleted = 0
    for i in range(0, len(expired_ids), BATCH_SIZE):
        batch = expired_ids[i : i + BATCH_SIZE]
        resp = requests.post(
            f"{BASE_URL}/functional/case/trash/batch/delete",
            headers=json_headers(),
            json={
                "projectId": project_id,
                "selectAll": False,
                "selectIds": batch,
                "excludeIds": [],
            },
            timeout=60,
        )
        if resp.status_code == 200:
            deleted += len(batch)
            print(f"  已删除 {deleted}/{len(expired_ids)}")
        else:
            print(f"  [错误] HTTP {resp.status_code}")
            break
        time.sleep(1)

    return deleted


def main():
    dry_run = "--dry-run" in sys.argv
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"{'=' * 50}")
    print(f"回收站清理 {'[DRY RUN] ' if dry_run else ''}{ts}")
    print(f"保留期: {RETENTION_DAYS} 天")
    print(f"{'=' * 50}")

    total_deleted = 0
    for name, pid in PROJECT_MAP.items():
        try:
            total_deleted += clean_project_trash(name, pid, dry_run)
        except Exception as e:
            print(f"  [{name}] 异常: {e}")

    print(f"\n清理完成，共删除 {total_deleted} 条")


if __name__ == "__main__":
    main()
