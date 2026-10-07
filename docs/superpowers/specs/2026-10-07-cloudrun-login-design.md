# 微信云托管真实登录书面规格

> 2026-10-07。用户选择以**微信云托管**完成真实微信登录，避免自建 HTTPS 备案域名。本规格只定义登录与请求通道的改造，不含数据库/存储/消息队列的完整上云迁移。

## 1. 目标与边界

**目标**：小程序在真机（无需配置通讯域名、无需 ICP 备案域名）完成真实微信身份登录，并让全部后端接口走同一通道。

**采用依据**（微信官方文档，2026-10-07 核对）：

- 小程序/公众号使用 `wx.cloud.callContainer` 调用云托管服务**无需配置服务器域名**，因此无需申请域名、无需备案。
- 云托管网关会在容器收到的请求 header 中注入调用者身份：`X-WX-OPENID`、`X-WX-APPID`、`X-WX-UNIONID`、`X-WX-ENV`、`X-WX-SOURCE`，由微信链路校验，无需再走 `code2session`。
- `X-WX-OPENID` **只在 `callContainer` 通道注入**；公网 `wx.request` 调不动、也拿不到该头。

**边界**：

- 本规格不把 MySQL/Redis/RabbitMQ/MinIO 迁入容器。云托管明确**不支持部署数据库/Redis 等有状态服务**，也**不支持多端口**与**容器内持久化存储**。现有 Compose 全套上云属于独立后续工作，见第 7 节。
- 本规格不改变「商家短信登录」（仍阻塞于生产 `MerchantSmsSender`）与「正式支付」。

## 2. 身份来源与安全前提

新增 `POST /api/auth/cloud-login`，请求体 `{"role":"owner|technician"}`，**不带 code**。

身份取自网关注入头，判定顺序：

| 检查 | 条件 | 失败结果 |
| --- | --- | --- |
| 入口开关 | `WECHAT_CLOUD_RUN_ENABLED=true`；关闭时该控制器不注册（端点不存在） | 404 |
| 来源 | `X-WX-SOURCE` 存在且非空白 | 403 |
| 应用 | `X-WX-APPID` 与 `WECHAT_APP_ID` 相同（忽略大小写） | 403 |
| 用户 | `X-WX-OPENID` 存在且非空白 | 403 |
| 角色 | 仅 `owner`、`technician` | 400 |

通过后复用现有身份与会话逻辑，**与 `/api/auth/wx-login` 完全一致**：`owner` 走 `createOwnerOrRead` 并检查账号可用；`technician` 命中则签发技师会话，未命中返回 `BIND_REQUIRED` 与短时绑定凭证。

**强制部署前提（写入 runbook，不满足则不得开启开关）**：

1. 该云托管服务**关闭公网访问**。公开可直连时，攻击者可伪造 `X-WX-SOURCE`/`X-WX-OPENID` 冒充任意用户，此时本端点等同无鉴权。
2. 服务只被目标小程序/公众号经 `callContainer` 调用（云托管本身只允许被授权的小程序调用）。
3. 轮换过的 `WECHAT_APP_SECRET`、`JWT_SECRET` 只存在于云托管环境变量，不进仓库、不进客户端产物。

`/api/auth/wx-login`（`code2session`）**保留不删除**：本机开发者工具、H5 与实际验收仍在使用。

## 3. 客户端改造

### 3.1 统一请求通道

现状：9 个 service 模块各自 `runtime().request`（即 `uni.request`），各建各的 `baseUrl`。只把登录切到 `callContainer` 会造成「登录能过、业务接口仍要备案域名」的半成品。

方案：新增传输层，把 `callContainer` 收敛到一处，各 service 不感知通道差异。

- `services/api-config.js`：按构建期变量解析
  - 云托管已配置（`VITE_WECHAT_CLOUD_ENV_ID` 与 `VITE_WECHAT_CLOUD_SERVICE` 同时非空）→ 逻辑原点用固定哨兵 `https://cloudrun.invalid`，真实地址由传输层剥离后交给 `callContainer`；该哨兵仅供各 service 拼接路径与通过「未配置」校验，**不发往网络**。
  - 未配置 → 沿用 `VITE_API_BASE_URL`（本机/开发者工具/H5）。
- `services/api-runtime.js`：产出与 `uni` 同形的运行时对象
  - `request(options)`：云托管模式下改为 `wx.cloud.callContainer({ config:{env}, path, method, header:{... , 'X-WX-SERVICE': service}, data, timeout })`；POST 带 body 且未显式指定时补 `content-type: application/json`。未配置时原样透传 `uni.request`。
  - `login(options)`：原样透传 `uni.login`（云托管登录不需要它，保留给回退通道）。
  - 云能力探测使用运行时判断（`typeof wx !== 'undefined' && wx.cloud`），**不用条件编译**，保证 Node 离线测试与 H5 构建均安全。
  - `initCloud()`：首次调用时执行一次 `wx.cloud.init({ env })`，并在 `App.vue onLaunch` 主动调用。
- 各 service 仅把 `baseUrl: import.meta.env?.VITE_API_BASE_URL, runtime: () => uni` 换成 `baseUrl: apiOrigin, runtime: () => apiRuntime`，请求逻辑与校验不变。

### 3.2 登录分支

`services/wechat-auth.js` 的 `createAuthApi` 增加 `cloud` 入参：

- 云托管模式：`requestWechatLogin(role)` 直接 `POST /api/auth/cloud-login`，**不调用 `uni.login`**（无授权弹窗、无 code 重放问题）。
- 非云托管：保持现有 `uni.login` → `/api/auth/wx-login` 流程不变。
- 新增 `refreshSession(refreshToken)` 调用 `/api/auth/refresh`，补齐此前客户端从未调用的刷新能力。

### 3.3 打开即登录

车主/技师入口页在云托管模式下、且本地无会话时，进入页面自动执行一次静默登录（`callContainer` 不弹授权框）。商家角色不自动登录（走密码+短信）。失败仍按既有错误态展示并提供重试，不静默吞错。

## 4. 兼容与回退

| 场景 | 通道 | 登录端点 |
| --- | --- | --- |
| 本机开发者工具（关域名校验） | `uni.request` | `/api/auth/wx-login` |
| H5 本机联调 | `uni.request` | `/api/auth/wx-login` |
| 云托管（真机/体验版） | `callContainer` | `/api/auth/cloud-login` |

两条通道可同时存在于同一后端；切换只由小程序构建期变量决定，不改后端代码。

## 5. 验收条件

离线（可在本机完成，本规格交付时执行）：

1. 未配置云托管时，传输层与既有 `uni.request` 行为逐项一致；既有小程序测试全绿。
2. 配置云托管时，请求走 `callContainer`，携带 `X-WX-SERVICE`，路径正确，`content-type` 正确，响应与 `uni.request` 同形处理。
3. 云托管模式登录不发 `uni.login`。
4. 后端：开关关闭时端点不存在；缺少 `X-WX-SOURCE`、`X-WX-APPID` 不匹配、缺少 `X-WX-OPENID` 分别被拒绝；合法头按角色签发会话；技师未绑定返回 `BIND_REQUIRED`。

真实环境（需用户提供云托管环境，属外部依赖）：

5. 云托管服务部署成功、**公网访问已关闭**、环境变量已配。
6. 小程序基础库最低版本设为 ≥ 2.23.0；真机在**未关闭域名校验**的情况下完成登录、绑定手机号、刷新与退出。
7. 另一真实微信身份复核车主数据隔离。

## 6. 记录要求

- 证据脱敏：不记录 `X-WX-OPENID`、`X-WX-UNIONID`、令牌、手机号明文与完整请求/响应体。
- 未真实执行的项目记「未执行/阻塞」，不因本机结果标记通过。

## 7. 明确不在本次范围

- MySQL/Redis/RabbitMQ 迁出 Compose（云托管不能承载有状态服务，需外部实例或云数据库）。
- MinIO → 对象存储：容器无持久化存储，且 `callContainer` 请求体上限 100K、超时上限 15s，图片必须走对象存储直传，现有 HTTP 上传/签名流程需独立适配。
- 商家短信、正式支付、PC 运营后台。
