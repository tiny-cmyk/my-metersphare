# MeterSphere 功能用例 API 对接文档

> 适用版本：v3.x  
> 服务地址：`http://10.2.5.250:8081`

---

## 一、鉴权

MeterSphere 使用 Access Key + Signature 双 Header 方式鉴权。

### 1.1 获取 Access Key 和 Secret Key

登录 MeterSphere → 右上角头像 → **个人信息 → API Keys → 创建**，会生成一对：
- `accessKey`
- `secretKey`

### 1.2 每次请求需要携带的 Header

| Header 名 | 说明 |
|---|---|
| `accessKey` | 直接填 Access Key 原文 |
| `signature` | 见下方生成方式 |

### 1.3 Signature 生成规则

```
明文 = accessKey + "|" + 当前时间戳(毫秒)
signature = AES_CBC_加密(明文, secretKey, accessKey)
```

- 加密算法：AES/CBC/PKCS5Padding
- Key（密钥）：secretKey
- IV（向量）：accessKey（取前16字节）
- 输出：Base64 字符串
- **有效期 30 分钟**，建议每次请求重新生成

### 1.4 Python 签名示例

```python
import time, base64
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad

def generate_signature(access_key: str, secret_key: str) -> str:
    plaintext = f"{access_key}|{int(time.time() * 1000)}"
    key = secret_key.encode("utf-8")[:16].ljust(16, b'\0')
    iv  = access_key.encode("utf-8")[:16].ljust(16, b'\0')
    cipher = AES.new(key, AES.MODE_CBC, iv)
    encrypted = cipher.encrypt(pad(plaintext.encode("utf-8"), AES.block_size))
    return base64.b64encode(encrypted).decode("utf-8")

headers = {
    "accessKey": ACCESS_KEY,
    "signature": generate_signature(ACCESS_KEY, SECRET_KEY),
    "Content-Type": "application/json",
}
```

---

## 二、项目与模块

### 2.1 项目 ID 对照表

| 项目名 | projectId |
|---|---|
| global-web | `1465684991651422208` |
| global-app | `1465686383220826112` |
| desktop | `1465686572199387136` |
| CN-web | `1470186838932258816` |
| CN-app | `1470187027910819840` |
| DTC | `1503142483771973632` |

### 2.2 模板 ID 对照表

每个项目有一个默认功能用例模板（`functional_default`）：

| 项目名 | templateId |
|---|---|
| global-web | `1465684991651422258` |
| global-app | `1465686383220826162` |
| desktop | `1465686572199387186` |
| CN-web | `1470186856112128046` |

> 其他项目的 templateId 可通过接口查询（见 2.4）

### 2.3 查询模块树

```
GET /functional/case/module/tree/{projectId}
```

**示例：**
```
GET http://10.2.5.250:8081/functional/case/module/tree/1465684991651422208
```

**返回结构（节点）：**
```json
[
  {
    "id": "模块ID",
    "name": "模块名",
    "parentId": "父模块ID",
    "children": [ ... ]
  }
]
```

> 根模块传 `parentId = "NONE"` 或不传。`id` 就是创建用例时需要的 `moduleId`。

### 2.4 创建模块

```
POST /functional/case/module/add
Content-Type: application/json
```

**请求体：**
```json
{
  "projectId": "1465684991651422208",
  "name": "4.0web端calendar需求",
  "parentId": "NONE"
}
```

**返回：** 新创建模块的 `id`（字符串）

### 2.5 查询默认模板 ID

```
GET /functional/case/default/template/field/{projectId}
```

返回中的 `templateId` 字段即为该项目的默认模板 ID。

---

## 三、创建用例

### 3.1 接口

```
POST /functional/case/add
Content-Type: multipart/form-data
```

**注意：** 这个接口使用 `multipart/form-data`，用例数据放在名为 `request` 的字段中（JSON 字符串），文件（如有）放在 `files` 字段。

### 3.2 request 字段完整结构

```json
{
  "projectId":      "1465684991651422208",   // 必填：项目 ID
  "templateId":     "1465684991651422258",   // 必填：模板 ID
  "moduleId":       "模块ID",                // 必填：目标模块 ID
  "name":           "用例名称",              // 必填：最长 255 字符
  "caseEditType":   "STEP",                  // 必填："STEP"（步骤模式）或 "TEXT"（文本模式）

  // STEP 模式下填以下字段：
  "steps": "[{\"id\":\"1\",\"num\":1,\"desc\":\"操作步骤\",\"result\":\"预期结果\"}]",
  "prerequisite": "前置条件（可选）",

  // TEXT 模式下填以下字段：
  "textDescription": "用例描述",
  "expectedResult":  "预期结果",

  "tags":           ["AI生成", "已采纳"],    // 可选：标签列表
  "aiCreate":       true,                    // 可选：是否 AI 生成，默认 false

  "customFields": [                          // 可选：自定义字段
    { "fieldId": "自定义字段ID", "value": "P0" }
  ]
}
```

### 3.3 步骤格式说明（STEP 模式）

`steps` 是一个 JSON 数组**序列化后的字符串**：

```json
[
  {
    "id":     "1",         // 步骤 ID（字符串，从"1"开始）
    "num":    1,           // 步骤序号（整数）
    "desc":   "点击登录按钮",  // 操作描述
    "result": "跳转到首页"    // 预期结果
  },
  {
    "id":     "2",
    "num":    2,
    "desc":   "检查页面标题",
    "result": "标题显示正确"
  }
]
```

### 3.4 Python 请求示例

```python
import json, requests

BASE_URL = "http://10.2.5.250:8081"

def create_case(case_data: dict, access_key: str, secret_key: str) -> str:
    """返回新建用例的 ID"""
    headers = {
        "accessKey": access_key,
        "signature": generate_signature(access_key, secret_key),
        # 不要设置 Content-Type，让 requests 自动设置 multipart boundary
    }
    files = {"request": (None, json.dumps(case_data, ensure_ascii=False), "application/json")}
    resp = requests.post(f"{BASE_URL}/functional/case/add", headers=headers, files=files)
    resp.raise_for_status()
    return resp.json()["data"]["id"]

# 示例调用
case = {
    "projectId":    "1465684991651422208",
    "templateId":   "1465684991651422258",
    "moduleId":     "模块ID",
    "name":         "用户登录成功",
    "caseEditType": "STEP",
    "steps": json.dumps([
        {"id": "1", "num": 1, "desc": "输入正确的用户名和密码", "result": "密码框正常回显"},
        {"id": "2", "num": 2, "desc": "点击登录", "result": "跳转到首页，显示用户名"}
    ], ensure_ascii=False),
    "prerequisite": "用户已注册且账号正常",
    "tags":         ["AI生成"],
    "aiCreate":     True,
}

case_id = create_case(case, ACCESS_KEY, SECRET_KEY)
print(f"创建成功，用例 ID: {case_id}")
```

---

## 四、更新用例

### 4.1 接口

```
POST /functional/case/update
Content-Type: multipart/form-data
```

### 4.2 request 字段

在创建用例的所有字段基础上，必须额外加上：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | String | **必填**，要更新的用例 ID |

其余字段与创建时相同，不传的字段保持原值。

---

## 五、查询用例列表

```
POST /functional/case/page
Content-Type: application/json
```

**请求体：**
```json
{
  "projectId": "1465684991651422208",
  "current":   1,
  "pageSize":  20,
  "moduleIds": ["模块ID"],        // 可选：按模块筛选
  "keyword":   "关键词"            // 可选：按名称搜索
}
```

**返回：**
```json
{
  "data": {
    "list": [ { "id": "...", "name": "...", "tags": [...], ... } ],
    "total": 184,
    "current": 1,
    "pageSize": 20
  }
}
```

---

## 六、查询用例详情

```
GET /functional/case/detail/{id}
```

返回用例完整信息，包含 `steps`、`prerequisite`、`textDescription`、`expectedResult` 等内容字段。

---

## 七、批量打标签

```
POST /functional/case/batch/edit
Content-Type: application/json
```

**请求体：**
```json
{
  "projectId": "1465684991651422208",
  "selectIds": ["用例ID1", "用例ID2"],
  "tags":      ["已采纳"],
  "append":    true    // true=追加到已有标签；false=覆盖
}
```

---

## 八、常用标签约定

| 标签 | 含义 |
|---|---|
| `AI生成` | 由 AI Agent 生成的用例 |
| `已采纳` | 评审后决定保留执行 |
| `不采纳` | 评审后决定不执行 |

---

## 九、注意事项

1. **Signature 每次请求需重新生成**，有效期 30 分钟
2. **`steps` 字段是 JSON 字符串**（不是 JSON 对象），需要先 `json.dumps()` 再放到 request 中
3. **multipart/form-data 格式**：不要手动设置 `Content-Type: application/json`，否则接口无法解析
4. `moduleId` 必须是已存在的模块 ID，如果目标模块不存在，先调用创建模块接口
5. `templateId` 填对应项目的默认模板 ID（见二、1.2 对照表）
