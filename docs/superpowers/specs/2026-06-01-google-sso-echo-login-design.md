# 设计：my-metersphere 登录页 → 纯 Google SSO + Echo 风格

- 日期：2026-06-01
- 分支：`feat/google-sso-and-echo-ui`（由 `feat/google-sso-oauth` 重命名）
- 范围：仅登录页前端改造（本轮）

---

## 1. 背景

`feat/google-sso-oauth` 已完成 Google SSO 的后端引导（`/sso/google/bootstrap`）和前端打通（user store `ssoBootstrap()`、`redirectToScriptPlatformLogin()`）。但当前登录页存在两个问题：

1. **登录方式未收敛**：`login-form.vue` 里 LOCAL 账号密码表单、LDAP、QR、OIDC、OAuth2、CAS 的 UI 与逻辑仍全部保留（已是死代码——`onMounted` 在独立访问时无条件 redirect、嵌入时通知 Echo，密码表单分支永不可达）。
2. **视觉未统一**：登录页仍是旧的「左 banner 插画 + 右表单」双栏，未对齐 Q+A 质量先锋队的 Echo Design System。

本轮把登录页收敛为**纯 Google SSO**，并用 **Echo 风格**重做。同时把分支重命名以同时体现「Google SSO」与「适配 Echo UI」两件事。

参考规范：`/Users/plaud/Documents/QA_Project/test_account/ECHO-DESIGN-SYSTEM.md`、`HOW-TO-USE-DESIGN-SYSTEM.md`。

## 2. 目标与非目标

### 本轮目标（In Scope）
- 移除登录页上密码 / LDAP / QR / OIDC / OAuth2 / CAS 等所有非 Google SSO 入口。
- 独立访问 `/login`：展示 Echo 风格**居中引导卡**（Logo + 欢迎语 + 「使用 Google 登录」主按钮 + 「仅支持公司 Google 账号登录」提示）。
- 嵌入 Echo（iframe）：保留 `postMessage` 通知父窗口的逻辑，等待卡改为 Echo 样式。
- 已有同源 sid cookie 时静默登录，不展示引导卡。
- 分支重命名为 `feat/google-sso-and-echo-ui`。

### 非目标（Out of Scope，留作后续独立 spec）
- **②全局主题 token 对齐**：把 Echo 色板/字体/圆角/阴影映射进 Arco 主题变量，实现全站 Arco 组件观感统一。
- **③业务页面逐页 Echo 化**：仪表盘、列表等高价值页面按 §5 组件契约重做。
- **后端收紧**：不在后端禁用非 SSO 登录。`/login`、`/ldap/login`、`LocalRealm`、API key 认证、系统初始化等后端逻辑**一律不动**。

## 3. 设计决策汇总（已与用户确认）

| 决策点 | 结论 |
|---|---|
| 本轮范围 | 仅登录页 |
| 独立访问形态 | Echo 风格引导页（点击按钮才跳转，非自动 redirect） |
| 后端范围 | 仅前端移除入口，后端不动 |
| 布局 | 居中单卡（去掉 banner 双栏） |
| Logo | 保留现有 `GetLoginLogoUrl`（后台可配置） |
| 分支名 | `feat/google-sso-and-echo-ui` |
| Echo token 落地 | 仅登录页局部用 Tailwind arbitrary value + 内联 Echo 色值，不改全局 Arco 主题 |

## 4. 登录页状态机（重做后）

`login-form.vue` 收敛为三个互斥状态：

```
onMounted (非 isPreview):
  ├─ 嵌入 iframe (window.self !== window.top)
  │     → 状态【嵌入等待】+ requestEchoLogin() postMessage
  │
  └─ 独立访问
        → 状态【加载中】
        → await userStore.ssoBootstrap()
              ├─ true（已有有效 sid，静默登录成功）
              │     → 跳进系统首页（复用现有 login 成功后的 router 跳转逻辑）
              └─ false（无 cookie / 失效）
                    → 状态【引导卡】

引导卡「使用 Google 登录」按钮点击
        → redirectToScriptPlatformLogin()  // 跳 ScriptPlatform Google OAuth，回来带 sid
```

| 状态 | 触发 | 呈现 |
|---|---|---|
| 加载中 | 独立访问、ssoBootstrap 进行中 | Echo loading（居中 spinner） |
| 引导卡 | ssoBootstrap 返回 false | Logo + 欢迎语 + Google 登录主按钮 + 提示文案 |
| 嵌入等待 | iframe 内 | 「等待 Echo 完成 Google 登录…」+ 重新唤起按钮 |

> 相对当前行为的关键变化：独立访问不再「无条件自动 redirect」，而是先尝试静默 `ssoBootstrap`，无会话时展示可见的 Echo 引导卡，由用户点击触发跳转。

## 5. 文件改动清单

### 改写
- **`frontend/src/views/login/components/login-form.vue`**（核心）
  - 删除：LOCAL 表单、`handleSubmit`、`switchLoginType`、`redirectAuth`、`initPlatformInfo`、QR tab、`isShowLDAP/OIDC/OAUTH/CAS`、`showDemo`、`userInfo`、`orgOptions`、`activeName`、相关 import（`TabQrCode`、`encrypted`、`getAuthDetailByType`、`getPlatformParamUrl` 等）。
  - 保留并复用：`embeddedInEcho`、`requestEchoLogin`、`redirectToScriptPlatformLogin`、`innerLogo`（`GetLoginLogoUrl`）、`innerSlogan`、`isPreview` prop。
  - 新增：`onMounted` 内 `ssoBootstrap` 静默登录分支 + 成功跳转；三状态模板；Echo 样式。
- **`frontend/src/views/login/index.vue`**
  - 移除 `<banner />` 及其 import；外层容器改为 Echo `bg`(#fafafa) 居中布局。
  - `banner.vue` **文件保留**（被 `setting/system/config/components/pageConfig.vue` 引用），仅此处不再引用。

### 待核实后删除（实现时确认 login 目录外无引用）
- `frontend/src/views/login/components/{dingTalkQrCode,larkQrCode,larkSuiteQrCode,weComQrCode,tabQrCode}.vue`：登录用二维码组件。确认仅被 `login-form.vue` 引用则删除；若被设置页等复用则保留。
  - （已初查：设置页引用的是 `setting/system/config/` 下的二维码**配置**组件，与此处登录组件不同文件。）

### 评估后处理
- **`frontend/src/store/modules/user/index.ts`**：`getAuthentication`、`loginType`、`login()` 等若移除入口后仅登录页使用，则清理或保留不引用；`ssoBootstrap`、`logout` 保留。实现时按实际引用决定，不强行删除可能被守卫/路由复用的方法。
- **登录页 i18n**（`locale/zh-CN.ts`、`en-US.ts`）：新增引导卡文案 key；旧的密码/LDAP/QR 文案保留不删（避免影响设置页等其他引用）。

### 不改动（保护清单）
- 后端全部：`GoogleSsoController/Service`、`LoginController`、`LocalRealm`、`FilterChainUtils`、`/login`、`/ldap/login` 等。
- `App.vue` 已有的 SSO 相关改动、API（`api/modules/user`、`requrls/user`）、`utils/auth.ts`。
- `banner.vue` 文件本体、设置页二维码配置组件。

## 6. Echo 视觉规格（仅登录页局部落地）

不改全局 Arco 主题；在登录页用 Tailwind arbitrary value + 内联色值实现，互不影响其它页面。

### Token（取自 ECHO-DESIGN-SYSTEM §1）
| 用途 | 值 |
|---|---|
| 页面底 `bg` | `#fafafa` |
| 卡片 `paper` | `#ffffff` |
| 主色 indigo | `#6366f1`，hover `#4f46e5` |
| 主文字 ink | `#111827` |
| 弱文字 muted | `#6b7280` |
| 描边 line | `#e5e7eb` |
| 卡片阴影 | `0 1px 2px rgba(15,23,42,.03)` |
| 主按钮阴影 | `0 8px 18px -8px rgba(99,102,241,.55)` |
| 圆角 | 卡片 14px / 按钮 10px |
| 字体 | Inter, PingFang SC, system-ui；字距 `-.005em` |

### 引导卡布局
```
整页 bg(#fafafa) flex 居中
  └─ 卡片 paper 白底, radius 14px, shadow-card, padding 40px, 宽约 400–420px
       ├─ Logo（innerLogo, 居中）
       ├─ 欢迎语 H 标题（ink, 600）
       ├─ 副标题/slogan（muted, 可选）
       ├─ [使用 Google 登录] 主按钮
       │     h-[42px] 满宽 bg-[#6366f1] hover:bg-[#4f46e5]
       │     text-white rounded-[10px] font-semibold shadow-cta
       │     左侧 Google "G" 图标（线性 SVG / 官方彩色 G，二选一，见下）
       └─ 「仅支持公司 Google 账号登录」提示（muted, 12.5px）
```

### Google 图标取舍
Echo §6 规定「线性 SVG icon、不要 emoji」，但 Google 登录按钮的品牌惯例是官方四色「G」。**决策：按钮内用官方彩色 Google G logo**（符合用户认知与 Google 品牌规范），按钮本体仍是 Echo indigo 主按钮样式。这是对 Echo 图标规则的一处合理例外，在 spec 中显式记录。

### 反例自查（ECHO §8 / HOW-TO §5）
- 无渐变背景、无重阴影（仅 shadow-card / shadow-cta）。
- 不靠纯色块；按钮是实心 indigo CTA（符合 §5.1 Primary CTA 契约）。
- 字号用 token，数字无（登录页无数字）。
- 支持 `prefers-reduced-motion`、`:focus-visible` 焦点环。

## 7. 分支重命名

```
git branch -m feat/google-sso-oauth feat/google-sso-and-echo-ui
```
若已推送远端，另需：`git push origin -u feat/google-sso-and-echo-ui` 并删除旧远端分支（实现阶段按用户是否已推送决定，且推送需用户确认）。

## 8. 验证方式

1. 独立访问 `/login`（无 sid）：展示 Echo 引导卡，点击按钮跳转 ScriptPlatform Google OAuth。
2. 独立访问 `/login`（有有效 sid）：静默 `ssoBootstrap` 成功，直接进入系统首页。
3. 在 Echo iframe 内打开：展示等待卡 + 触发 `postMessage`。
4. 页面无密码/LDAP/QR/OIDC/OAuth/CAS 任何入口残留。
5. `prefers-reduced-motion` 下动画关闭；键盘 Tab 焦点环可见。
6. 设置页「登录页配置预览」（引用 `banner.vue`）不受影响、仍正常。
7. `npm run build` 通过，无未使用 import 报错。

## 9. 后续路线图（非本轮）

- **子项目②**：全局 Arco 主题 token 对齐 Echo（独立 spec）。
- **子项目③**：重点业务页面按 Echo 组件契约逐页改造（分批，各自独立 spec）。
