# 小程序开发与上线的网络环境

更新日期：2026-10-07。适用于当前 uni-app 小程序、Spring Boot 后端及私有图片流程。规则依据[微信官方网络说明](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)，2026-10-05 核对。

## 是否必须有公网或域名

开发阶段不必先购买公网服务器或域名。开发者工具可连接本机后端，在开发设置中临时跳过请求域名、TLS 和 HTTPS 证书校验。当前已用这种方式完成真实微信 `wx.login` code 兑换；后端仍需联网访问微信接口。

微信官方[测试号](https://developers.weixin.qq.com/miniprogram/dev/devtools/sandbox)分配的是带 AppID/AppSecret 的真实账号，并支持**真机预览**；真机上跳过域名校验的官方做法是在预览后点击右上角「…」→「打开调试」。因此测试号阶段即可在真机完成真实微信登录，无需域名、证书与备案。这条路径的操作与验收见[真机真实微信登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)。

| 环境 | 后端地址与要求 | 当前证据 |
| --- | --- | --- |
| 本机开发者工具 | 本机 HTTP，例如 `http://127.0.0.1:18080`；仅在开发工具中临时跳过域名校验 | 真实车主登录、技师绑定、刷新与退出已通过；只监听本机回环地址 |
| 手机局域网/调试模式 | 手机与电脑网络互通，使用电脑的可达地址；手机调试模式可临时跳过域名校验 | 已打通：后端改为 `0.0.0.0:18080` 发布，产物指向局域网地址，两个地址健康检查均通过；手机侧按[真机登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)验收 |
| 常规真机与正式发布，自建后端 | 可访问的 HTTPS 服务、有效证书、经过 ICP 备案的域名，并在小程序后台配置通讯域名 | 尚未配置与验收 |
| 微信云托管 | 使用 `callContainer` / `connectContainer` 指定调用方式，可无需另配通讯域名 | 通道与登录已适配并通过离线验证；云托管环境部署与真机验收待执行，见[云托管登录清单](CLOUDRUN_LOGIN_RUNBOOK.md) |

手机中的 `127.0.0.1` 指向手机自身。原先后端只发布在回环地址、产物也指向回环，手机构建因此连不上电脑上的 Docker 后端；现已同时调整后端发布范围、产物地址，并把步骤固化为 `scripts/miniapp_lan_helper.cjs`。注意校园网/公共 WiFi 常见客户端隔离，可能仍需改用电脑移动热点。局域网调试只用于受控测试，常规真机验收应开启域名与证书校验。

## 当前本机配置

- 后端私有 `.env.auth.local` 保存微信、JWT 与数据库变量，并受 Git 忽略；AppSecret 只进入后端环境。
- 本机后端容器 `vehicle-auth-local-backend` 当前以 `0.0.0.0:18080→8080` 发布，供真机经局域网访问；旧的回环容器保留为 `vehicle-auth-local-backend-before-lan` 以便回滚。恢复回环发布的命令见[真机登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)。
- 小程序私有 `apps/miniapp/.env.local` 当前配置为局域网地址（如 `http://10.66.1.251:18080`）。改地址、构建与还原统一走 `node scripts/miniapp_lan_helper.cjs`，改后须重新构建并导入本工作树的 `apps/miniapp/dist/build/mp-weixin`。
- 切到云托管时改为配置 `VITE_WECHAT_CLOUD_ENV_ID` 与 `VITE_WECHAT_CLOUD_SERVICE`，此时 `VITE_API_BASE_URL` 被忽略；两者同时非空才启用云托管通道。
- 临时跳过域名校验仅用于开发者工具的项目设置/忽略的构建产物；源码默认校验保留。
- 本机数据库为隔离的新建测试卷，不能据此认定其他已有数据库已迁移。

微信开发者工具可能同时打开旧分支窗口。若页面提示“登录服务尚未配置”，先检查该窗口导入路径、构建时间与后端地址。[构建说明](../../apps/miniapp/README.md)及[真实登录记录](../progress/S0_EXECUTION_LOG.md)提供复核依据。

## 自建后端上线准备

1. 确定测试/生产服务地址、域名管理权限及备案状态，配置 HTTPS 和系统信任的完整证书链。
2. 在小程序后台“开发 → 开发设置 → 服务器域名”配置请求、上传、下载等实际用到的域名。普通请求不能把公网 IP 或 `localhost` 当作合法通讯域名；局域网 IP 有官方例外。
3. 私有图片的 HTTPS 签名地址也须能从手机访问；分别核对 API、上传、下载/预览的域名与过期行为。
4. 运行 `python scripts/check_auth_readiness.py --base-url https://实际测试域名`，再关闭调试例外完成 iOS/Android 真机登录、手机号与图片验收。

脚本通过只证明执行脚本的机器能访问入口，不能代替手机网络和微信能力验收。完整步骤见[登录联调清单](AUTH_INTEGRATION_RUNBOOK.md)。

## 云托管方案

2026-10-07 起已实施：全部后端请求收敛到统一传输层（`apps/miniapp/src/services/api-runtime.js`），云托管模式下改走 `wx.cloud.callContainer`，无需配置通讯域名；登录改由网关注入的 `X-WX-OPENID` 识别（`POST /api/auth/cloud-login`，开关 `WECHAT_CLOUD_RUN_ENABLED`），不再需要 `wx.login` 换码。未配置云托管时行为与改造前完全一致。

采用前必须核对的云托管限制：不支持部署数据库/Redis 等有状态服务、不支持 Docker Compose、不支持多端口、容器无持久化存储、`callContainer` 超时 ≤15s 且请求体 ≤100K。因此 MySQL/Redis/RabbitMQ 须为容器外实例，MinIO 须改为对象存储，图片上传不能经容器中转。

车主端 AI 管家同样走统一传输层，本机与开发者工具下请求超时为 60s；云托管模式下受 `callContainer` 15s 上限约束，慢回答可能超时，需要缩短输出、改流式或让 AI 单独走通讯域名，见 [AI 管家接入清单](AI_CHAT_RUNBOOK.md)。

**安全前提**：`X-WX-OPENID` 只在 `callContainer` 通道注入，其可信性依赖服务关闭公网访问。公开可直连时该头可被伪造，登录端点等同无鉴权。

部署产物见 [`deploy/cloudrun`](../../deploy/cloudrun/README.md)，验收清单见[云托管登录联调与验收](CLOUDRUN_LOGIN_RUNBOOK.md)，边界见[书面规格](../superpowers/specs/2026-10-07-cloudrun-login-design.md)。
