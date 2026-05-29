<p align="center"><a href="https://metersphere.io"><img src="https://metersphere.oss-cn-hangzhou.aliyuncs.com/img/MeterSphere-%E7%B4%AB%E8%89%B2.png" alt="MeterSphere" width="300" /></a></p>
<h3 align="center">新一代的开源持续测试工具 · Plaud 内部定制版</h3>
<p align="center">
  <a href="https://github.com/metersphere/metersphere/releases"><img src="https://img.shields.io/github/v/release/metersphere/metersphere" alt="GitHub release"></a>
  <a href="https://github.com/metersphere/metersphere"><img src="https://img.shields.io/github/stars/metersphere/metersphere?color=%231890FF&style=flat-square" alt="Stars"></a>
</p>
<hr />

> 本仓库是 [MeterSphere](https://github.com/metersphere/metersphere) v3.x 的 **Plaud 内部定制分支**，在官方版本基础上增加了 AI 用例生成、Notion 双向同步、飞书通知等团队定制功能。

---

## 目录

- [内部部署地址](#内部部署地址)
- [定制功能概览](#定制功能概览)
- [项目 ID 速查](#项目-id-速查)
- [环境配置](#环境配置)
- [AI 用例工作流](#ai-用例工作流)
  - [第一步：从 Notion 导入用例到原始库](#第一步从-notion-导入用例到原始库)
  - [第二步：人工审核并打标](#第二步人工审核并打标)
  - [第三步：同步到正式用例库](#第三步同步到正式用例库)
- [脚本参考手册](#脚本参考手册)
- [Notion 定时同步（后端）](#notion-定时同步后端)
- [飞书通知](#飞书通知)
- [数据库工具](#数据库工具)
- [本地开发与部署](#本地开发与部署)
- [技术栈](#技术栈)

---

## 内部部署地址

| 环境 | 地址 |
|------|------|
| 生产环境 | http://10.2.5.250:8081 |
| 组织 ID | `100001` |

---

## 定制功能概览

| 功能 | 说明 |
|------|------|
| **Xpack 登录绕过** | `XpackLoginBypassAspect.java`：绕过企业版 Xpack 登录校验，支持直接用户名密码登录 |
| **Notion AI 用例同步** | `NotionService.java` + `NotionSyncScheduler.java`：从 Notion 数据库拉取 AI 生成的用例，定时同步到 MeterSphere 对应模块 |
| **AI 用例导入脚本** | `ms_ai_import.py`：将 AI 生成的用例 JSON 批量导入原始库，自动创建模块层级 |
| **用例审核同步** | `ms_case_sync.py`：将审核通过（打了「已采纳」标签）的用例从原始库同步到正式库 |
| **飞书通知** | `ms_feishu_notify.py`：测试计划执行失败时自动推送飞书群机器人通知 |

---

## 项目 ID 速查

| 项目 | MeterSphere 项目 ID |
|------|-------------------|
| Web 端（global-web） | `1465684991651422208` |
| App 端（global-app） | `1465686383220826112` |
| Desktop 端（global-desktop） | `1465686572199387136` |

---

## 环境配置

所有脚本通过 `.metersphere-mcp.env` 读取 API 凭证，将此文件放在脚本同目录或 `~/.metersphere-mcp.env`：

```ini
METERSPHERE_BASE_URL=http://10.2.5.250:8081
METERSPHERE_ACCESS_KEY=<你的 Access Key>
METERSPHERE_SECRET_KEY=<你的 Secret Key>
METERSPHERE_ORGANIZATION_ID=100001
```

Access Key 和 Secret Key 在 MeterSphere → 个人中心 → API Keys 中生成。

**安装 Python 依赖：**

```bash
pip3 install requests cryptography pymysql
```

---

## AI 用例工作流

MeterSphere 中每个项目固定两个顶级模块：

```
MeterSphere 项目
├── 🤖 AI原始用例库/
│   └── {需求单名称}/         ← AI 生成的原始用例，永久保留
│       └── {子模块}/
│
└── ✅ 正式用例库/
    └── {需求单名称}/         ← 仅存审核通过的用例
        └── {子模块}/
```

**标签体系：**

| 阶段 | 标签 | 说明 |
|------|------|------|
| 导入后 | `AI生成` | 所有 AI 用例自动打上 |
| 审核通过 | `AI生成` + `已采纳` | 人工在 MeterSphere 中批量打标 |
| 审核拒绝 | `AI生成` + `未采纳` | 人工在 MeterSphere 中批量打标 |
| 同步后 | `AI生成` + `已采纳` + `已同步` | 脚本自动打，防止重复同步 |

---

### 第一步：从 Notion 导入用例到原始库

将 Notion 数据库中的用例整理为 JSON 后，通过 `ms_ai_import.py` 批量导入：

```bash
python3 ms_ai_import.py \
  --cases /path/to/cases.json \
  --project-id 1465686383220826112 \
  --req-name "Extract 需求 App端测试用例（MVP）"
```

cases JSON 格式（每条用例）：

```json
{
  "name": "【正常】用例标题",
  "priority": "P0",
  "modulePath": "生成/自动生成",
  "steps": [
    {"num": 1, "desc": "操作步骤描述", "result": "预期结果"}
  ],
  "prerequisite": "前置条件",
  "description": "备注说明",
  "tags": ["AI生成", "正常"]
}
```

执行后，MeterSphere 中会出现 `🤖 AI原始用例库/{需求单名称}/` 下的全部用例，每条均带 `AI生成` 标签。

---

### 第二步：人工审核并打标

1. 打开 MeterSphere → 用例管理 → `🤖 AI原始用例库/{需求单名称}/`
2. 逐条或批量审核用例
3. **通过的**：批量选中 → 编辑标签 → 添加 `已采纳`
4. **拒绝的**：批量选中 → 编辑标签 → 添加 `未采纳`

> 拒绝的用例不需要删除，原始库永久保留，便于后续统计 AI 准确率。

---

### 第三步：同步到正式用例库

```bash
# 同步指定需求单
python3 ms_case_sync.py \
  --project-id 1465686383220826112 \
  --req-name "Extract 需求 App端测试用例（MVP）"

# 同步该项目下所有需求单
python3 ms_case_sync.py \
  --project-id 1465686383220826112 \
  --all
```

脚本会：
- 找到所有打了 `已采纳` 但还没 `已同步` 的原始用例
- 在 `✅ 正式用例库/{需求单名称}/` 下创建副本
- 给原始库中的用例补打 `已同步` 标签，防止重复

**配置定时自动同步（每 10 分钟）：**

```bash
crontab -e
# 加入：
*/10 * * * * /usr/bin/python3 /opt/ms-review/ms_case_sync.py --project-id 1465686383220826112 --all >> /opt/ms-review/sync.log 2>&1
```

---

## 脚本参考手册

### `ms_ai_import.py` — AI 用例批量导入

从 JSON 文件将用例导入 `🤖 AI原始用例库`，自动创建模块层级。

```bash
python3 ms_ai_import.py \
  --cases cases.json \
  --project-id <项目ID> \
  --req-name "需求单名称" \
  [--dry-run]   # 预览模式，不实际写入
```

---

### `ms_case_sync.py` — 原始库 → 正式库同步

将审核通过的用例（标签含 `已采纳`）复制到正式库。

```bash
python3 ms_case_sync.py --project-id <项目ID> --req-name "需求单名称"
python3 ms_case_sync.py --project-id <项目ID> --all
```

---

### `ms_ai_draft_import.py` — 草稿箱导入（含 Notion 审核记录）

将用例导入 `⏳ AI草稿箱`，同时在 Notion「AI用例审核库」写入审核记录，适用于需要 Notion 审核流程的场景。

```bash
python3 ms_ai_draft_import.py \
  --cases cases.json \
  --project-id <项目ID> \
  --notion-url <需求页面URL>
```

---

### `ms_case_review_sync.py` — Notion 审核结果 → MeterSphere

读取 Notion 审核数据库中的审核结果，自动将通过的用例从草稿箱移入目标模块。

```bash
python3 ms_case_review_sync.py
# 或配置定时任务
*/10 * * * * /usr/bin/python3 /opt/ms-review/ms_case_review_sync.py >> /opt/ms-review/sync.log 2>&1
```

---

### `ms_feishu_notify.py` — 飞书通知

扫描测试计划执行结果，有失败用例时推送飞书群机器人通知。

**配置方式：** 在 MeterSphere「项目管理 → 消息管理 → 机器人列表」中添加飞书类型机器人，填入群机器人 Webhook 地址，脚本自动读取。

```bash
# 手动执行
python3 ms_feishu_notify.py

# 定时任务（每 5 分钟）
*/5 * * * * /usr/bin/python3 /opt/ms-feishu/ms_feishu_notify.py >> /opt/ms-feishu/notify.log 2>&1
```

---

## Notion 定时同步（后端）

后端通过 `NotionSyncScheduler` 定时从 Notion 数据库拉取用例，在 `metersphere.properties` 中配置：

```properties
# Notion API Token
integration.notion.token=ntn_你的token

# 格式：Notion页面ID:MS项目ID，多个用英文逗号分隔
notion.sync.mappings=WEB_PAGE_ID:1465684991651422208,APP_PAGE_ID:1465686383220826112,DESKTOP_PAGE_ID:1465686572199387136

# 同步间隔（毫秒），默认 5 分钟
notion.sync.interval-ms=300000
```

配置文件路径：`/opt/metersphere/conf/metersphere.properties`

---

## 飞书通知

详见 [脚本参考手册 → ms_feishu_notify.py](#ms_feishu_notifypy--飞书通知)。

**部署到服务器：**

```bash
sudo mkdir -p /opt/ms-feishu
scp ms_feishu_notify.py ubuntu@10.2.5.250:/opt/ms-feishu/
```

---

## 数据库工具

### 初始化辅助数据库

```bash
# 本地（通过 SSH 隧道 13306 连接）
python3 init_db.py --env local

# 生产（VPC 直连）
python3 init_db.py --env prod
```

`init_db.sql` 会创建以下表：
- `projects` — 项目与 MeterSphere/Notion 的映射
- `requirements` — 需求单与模块路径的映射
- `sync_records` — 同步历史记录

### 用户修复工具

```bash
# 修复用户 cft_token 和角色权限
python3 fix_users.py

# 修复项目数据
mysql -u root -p metersphere < fix_projects.sql

# 重建用户（谨慎使用）
mysql -u root -p metersphere < recreate_users.sql
```

---

## 本地开发与部署

### 前置条件

- JDK 17+（本地编译后端时需要，或直接在服务器上编译）
- Node.js 18+ / pnpm（本地构建前端）
- SSH 访问服务器：`ubuntu@10.2.5.250`，密钥 `~/.ssh/id_rsa_server`

---

### 前端部署

前端改动（`frontend/src/` 下的 `.vue` / `.ts` 文件）需重新构建后上传到容器。

```bash
cd frontend

# 首次需安装依赖
pnpm install --no-frozen-lockfile

# 构建（产物在 frontend/dist/）
npx vite build --config ./config/vite.config.prod.ts

# 上传到服务器
rsync -az --delete -e "ssh -i ~/.ssh/id_rsa_server" \
  dist/ ubuntu@10.2.5.250:/tmp/frontend_dist/

# SSH 进服务器，将静态文件复制进容器（无需重启）
ssh -i ~/.ssh/id_rsa_server ubuntu@10.2.5.250 \
  "docker cp /tmp/frontend_dist/. ms-app:/app/static/"
```

> 浏览器刷新时按 `Cmd+Shift+R`（Mac）强制清除缓存。

---

### 后端部署

后端改动（`backend/` 下的 `.java` 文件）通过以下流程热更新，**无需完整 Maven 构建**：

#### 第一步：在服务器上提取依赖环境（只需做一次）

```bash
ssh -i ~/.ssh/id_rsa_server ubuntu@10.2.5.250

# 把应用所有依赖 jar 拷到 /tmp/app_libs
docker cp ms-app:/app/lib/. /tmp/app_libs/

# 把当前运行的 case-management jar 解包（用于把新 class 合并进去）
mkdir -p /tmp/case_extract
cd /tmp/case_extract
jar xf /tmp/app_libs/metersphere-case-management-3.x.jar
```

#### 第二步：本地修改代码，上传到服务器

```bash
# 示例：上传修改过的 Java 文件
scp -i ~/.ssh/id_rsa_server \
  backend/services/case-management/src/main/java/io/metersphere/functional/service/NotionSyncService.java \
  ubuntu@10.2.5.250:/tmp/

# 如果同时改了 controller，也一起上传
scp -i ~/.ssh/id_rsa_server \
  backend/services/case-management/src/main/java/io/metersphere/functional/controller/FunctionalCaseAIController.java \
  ubuntu@10.2.5.250:/tmp/
```

#### 第三步：在服务器上编译并打包

```bash
ssh -i ~/.ssh/id_rsa_server ubuntu@10.2.5.250

# 编译（-proc:none 避免 annotation processor 警告干扰报错判断）
CP=$(find /tmp/app_libs -name '*.jar' | tr '\n' ':')
mkdir -p /tmp/compiled_classes
javac -proc:none -cp "/tmp/case_extract:$CP" \
  -d /tmp/compiled_classes \
  /tmp/NotionSyncService.java /tmp/FunctionalCaseAIController.java
echo "编译退出码: $?"   # 必须为 0，否则有编译错误需排查

# 把新 class 合并进解包目录
cp -r /tmp/compiled_classes/* /tmp/case_extract/

# 重新打包成 jar
cd /tmp/case_extract
jar cf /tmp/case-mgmt-new.jar .
```

#### 第四步：备份 → 替换 → 重启

```bash
# 备份原 jar（以防万一）
docker exec ms-app cp \
  /app/lib/metersphere-case-management-3.x.jar \
  /app/lib/metersphere-case-management-3.x.jar.bak

# 替换
docker cp /tmp/case-mgmt-new.jar \
  ms-app:/app/lib/metersphere-case-management-3.x.jar

# 重启容器（约 15 秒启动完成）
docker restart ms-app

# 观察启动日志确认无报错
docker logs ms-app --tail 30 -f
```

> **回滚**：`docker exec ms-app cp /app/lib/metersphere-case-management-3.x.jar.bak /app/lib/metersphere-case-management-3.x.jar && docker restart ms-app`

---

### 本地前端开发（热重载）

```bash
cd frontend
pnpm install --no-frozen-lockfile
pnpm dev
```

访问 `http://localhost:5173`，需在 `config/vite.config.dev.ts` 中配置后端代理地址指向 `http://10.2.5.250:8081`。

---

### 脚本部署（跳板机）

```bash
# 创建目录
sudo mkdir -p /opt/ms-review
sudo chown ubuntu:ubuntu /opt/ms-review

# 上传脚本
scp ms_ai_import.py ms_case_sync.py ms_feishu_notify.py \
    .metersphere-mcp.env ubuntu@10.2.5.250:/opt/ms-review/

# 安装依赖
pip3 install requests cryptography
```

---

## 技术栈

- 后端：[Spring Boot](https://spring.io/projects/spring-boot)
- 前端：[Vue.js](https://vuejs.org/)
- 中间件：[MySQL](https://www.mysql.com/)、[Kafka](https://kafka.apache.org/)、[MinIO](https://min.io/)、[Redis](https://redis.com/)
- 基础设施：[Docker](https://www.docker.com/)
- 测试引擎：[JMeter](https://jmeter.apache.org/)

---

## License

本仓库遵循 [FIT2CLOUD Open Source License](LICENSE) 开源协议（本质为 GPLv3 附加额外限制）。

- 不能替换和修改 MeterSphere 的 Logo 和版权信息
- 二次开发后的衍生作品必须遵守 GPL V3 的开源义务
