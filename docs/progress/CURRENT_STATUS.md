# 当前进度

> 更新日期：2026-10-07。需求基线为原始 v1.0 交付文档。分别记录代码/CI、本机真实联调和真机/上线验收。

## 仓库与交付状态

**2026-10-07 最新收口**：PR #26–#32 已合并。A2 订单履约状态机见 [PR #33](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/33)：提交 `c5feab6` 六项 CI 通过，后端 251 项、小程序 114 项全部通过；本机 V009 已补迁移，运行镜像为 `vehicle-auth/backend:order-fulfillment`，仍保留 `0.0.0.0:18080`。H5 点击接车已实际返回 43001，PAID 和成功审计均不变。最终提交检查与合并状态以 PR 为准。[执行记录](A2_FULFILLMENT_EXECUTION.md)。

**下一步 A3**：用户已确认手填里程、7 张照片、一次提交的接车检查设计，书面规格复核后进入编码。车主确认、派工、报工与核销尚未实现；真机、正式支付、真实短信仍未验收。以下各阶段表格保留原始验收口径。

PR #12–#17 已合入 `main`。PR #18 的预检脚本与验收文档整合至 PR #19，随真实微信登录修复和本次文档更新一起交付；合并状态及完整 CI 以 [GitHub PR](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/19) 为准。各阶段原始证据保留在[执行记录](S0_EXECUTION_LOG.md)。

| 范围 | 当前状态 | 证据与边界 |
| --- | --- | --- |
| 需求与追踪 | 基线已建立 | [Spec](../SPEC.md) 与[追踪表](S0_TRACEABILITY.md)覆盖 F01–F20；追踪项不等于功能已交付。 |
| 标准服务项目 | 列表/详情已实现，本机验证通过；交付状态见 PR #22 | 分类、分页、参考价、服务内容/质量标准与重试；H5 + 真实会话/后端/MySQL 12 项检查通过。商家报价与价格排序已接入，预约已接入，正式价格来源仍待验收。[契约](../api/SERVICE_CATALOG.md)。 |
| 商家报价与选品 | 已实施、本机 H5 15 项通过；PR #23 已合并，最终 PR/主分支 CI 均六项成功 | 价格排序、本店定价与上下架、历史版本、原键重试与审计；商家会话为隔离合成桥接，车主为真实微信会话。后端 154 项、小程序 65 项、模拟器 12 条路由通过；最终提交结果见 [PR #23](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/23)。真实短信/真机待验收。[契约](../api/MERCHANT_QUOTES.md)。 |
| 预约与本人订单 | 已实现，本机验收通过；最终交付见 PR #24 | 商家发布/关闭时段、容量竞争、报价快照、本人列表/详情、取消和自动过期；后端170项、小程序71项、H5 12项、模拟器16条路由通过。正式扣款/券/退款未接入。[契约](../api/RESERVATION_ORDERS.md)。 |
| 支付基础 | 已实现并通过本机H5 24项，最终交付见PR #25 | 本人支付、默认关闭的隔离LOCAL_TEST、通知验签/去重、PAID与异常核对；测试标识贯穿页面，不代表真实扣款/退款。小程序75项、后端194项通过；完整CI和最终修正提交结果见执行记录。微信16条路由通过。[契约](../api/PAYMENT_FOUNDATION.md)。 |
| 商家本店订单查询 | 已实现，本机H5 17项通过；PR #26最终交付待精确HEAD CI | 本店分页/详情、状态/北京时间预约日期筛选、白名单快照、支付摘要与整单付款异常标识；商家会话与店铺归属复核。修正提交CI六项成功，后端201项、小程序78项，模拟器18条路由；本机后端/真实MySQL已验收，商家登录为隔离合成会话，不代表真实短信。[契约](../api/MERCHANT_ORDERS.md)、[复现](../testing/LOCAL_MERCHANT_ORDERS_ACCEPTANCE.md)。 |
| 小程序与 H5 | 编译、离线测试与部分模拟器验证通过 | uni-app 三角色入口、车主五 Tab；本步 75 项 Node 测试通过。车主真实登录按钮已进入首页，H5 实际车辆/无图档案交互接真实后端已通过；微信拍照相机、完整业务 UI 与真机尚未验收。 |
| 微信云托管真实登录（免配通讯域名） | 代码已交付，离线验证通过；真实环境未部署 | 统一传输层按构建期变量在 `uni.request` 与 `wx.cloud.callContainer` 间切换；`POST /api/auth/cloud-login` 由网关注入的 `X-WX-OPENID` 识别身份，开关 `WECHAT_CLOUD_RUN_ENABLED` 关闭时端点不存在；补齐刷新接口与进页静默登录。后端 14 项、小程序 86 项、双模式构建通过。云托管环境/服务部署、**公网访问关闭**、容器外 MySQL、基础库 ≥2.23.0 为待验收外部依赖。[规格](../superpowers/specs/2026-10-07-cloudrun-login-design.md)、[验收](../operations/CLOUDRUN_LOGIN_RUNBOOK.md)、[部署](../../deploy/cloudrun/README.md)。 |
| 测试号真机真实登录（局域网） | 通道已打通，真机侧未验收 | 官方测试号支持真机预览，真机上「打开调试」可跳过域名校验，因此无需域名/证书/云托管。本机后端改为 `0.0.0.0:18080` 发布、产物指向电脑局域网地址，回环与局域网健康检查均返回 `UP`；`scripts/miniapp_lan_helper.cjs` 负责探测/写入/构建/还原。手机侧 L0–L8 待执行；校园网可能客户端隔离，建议用电脑移动热点。旧回环容器保留可一键回滚。[清单](../operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。 |
| 车主端 AI 管家（DeepSeek） | 真实密钥已配置，本机端到端 19 项通过；真机未验收 | `POST /api/ai/chat` 仅车主可用；传 `vehicle_id` 时校验归属并注入车辆与最近 5 条档案上下文（车牌/VIN 不进入上下文，备注单条截断 200 字），未传则通用回答。上游直连 HTTP 200、返回 `deepseek-flash`；平台链路 19 项通过，其中选车后回答**实际复述了档案中的车型与里程**，追问车牌未泄露。未配置或上游失败返回 `503`+`50301`、限流 `429`+`42900`。后端 18 项、小程序 94 项通过。流式输出、方案/商家联动与真机待验收。[契约](../api/AI_CHAT.md)、[清单](../operations/AI_CHAT_RUNBOOK.md)。 |
| 车主与技师微信身份 | 开发者工具真实微信 code 与本机后端联调通过 | 车主登录、技师员工码绑定/重复登录、刷新轮换和退出撤销已验证；[PR #19 首次 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37317893587) 六项通过。手机号授权、员工禁用/回收后的真实场景和真机网络仍待验收。 |
| 商家身份 | 核心自动化验证通过，真实短信阻塞 | 密码加一次性短信码、限流、会话生命周期已实现；生产 `MerchantSmsSender` 未接入，验证码请求返回 503。 |
| 订单履约状态机（A2） | 后端与本机商家端已实现，离线测试通过；真机与真实业务证据未验收 | 8 个订单状态收敛为服务端唯一权威；`POST /api/merchant/orders/{id}/actions` 只接受动作名（`RECEIVE`/`START_SERVICE`/`FINISH_SERVICE`/`COMPLETE`），前端不能提交目标状态；矩阵判定、前置校验、行锁、24 小时幂等与**同事务双审计**（`order_status_transition` + `audit_log`）统一在 `OrderFulfillment`。前置按 **fail-closed**：接车单（A3）、车主确认与派工（A4/A5）、报工（A6）、核销（A4）未接入时返回 `43001`/`43003`/`43004`/`43005`/`43006`，**不放行也不伪造状态**，因此订单目前仍走不出 `PAID`。`/api/order/list` 与 `/api/merchant/orders` 的 `status` 白名单由 3 个扩到 8 个；商家端订单详情按投影里的 `allowed_actions` 渲染按钮并给出中文失败原因。D11 按建议默认固化：已接车及之后继续占用预约名额。[契约](../api/ORDER_FULFILLMENT.md)、[规格](../superpowers/specs/2026-10-07-fulfillment-state-design.md)。 |
| 数据库 | CI 与本机新建测试卷完成 V001–V009 | V005 增加 `vehicle_archive_file`，V006 增加不可变商家报价版本，V007 增加预约开放与订单快照/关闭字段及索引，V008 补支付字段及事件/异常表（48 表），V009 增加履约时间列与 `order_status_transition` 审计表（49 表）。本机已核对身份表存在；已有外部数据库仍须逐项核对迁移，不能用新卷初始化代替。 |
| 幂等与成功审计 | 已实现并接入车辆、档案、上传、报价、时段与订单写操作 | 24 小时幂等、权限复核及业务/响应/审计同事务提交；完整安全审计、监控与 M0 验收尚未完成。[说明](../api/WRITE_INTEGRITY.md)。 |
| 私有图片 | PR #9/#10/#12/#13 已合并，本机真实私有接口与 H5 图片通过；正式部署待验收 | 文件校验、MinIO、ClamAV、本人访问与短时签名、同键重试已实现；本机官方 daily 28144 验签/新鲜度、真实 CLEAN 上传和签名到期已通过，H5 实际图片交互通过 11 项。正式 HTTPS/微信相机仍待验收。**2026-10-07 修复**：统一传输层改造时 `chooseImage`/`uploadFile`/`previewImage` 未随 `runtime` 一并透传，图片链路整体不可用，且上传失败被误报成「无法连接图片服务」；现已在传输层补齐透传、取图升级为 `chooseMedia`（旧基础库自动回退、开发者工具如实降级为相册），并新增真实装配的契约测试。[复现](../testing/LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)。[上传说明](../api/UPLOAD_HTTP.md)。 |
| 本人车辆与手动录入 | PR #14 已合并 | 四级车型选择、分页、脱敏、本人隔离和幂等创建已实现；生产车型来源待落实。[契约](../api/VEHICLE_MANUAL.md)。 |
| 档案手动录入与查询 | PR #15 已合并 | 七类通用记录、可选里程、最多五张本人 CLEAN 图片、按车分页和审计；真实车主会话的本地无图有数据联调已通过，本机真实 CLEAN 图片归档及手动/拍照混合来源已通过。[契约](../api/ARCHIVE_MANUAL.md)。 |
| 首页车辆与档案摘要 | PR #16 已合并 | 共用车辆选择、服务端总数与最近记录；H5 实际两车切换、跨 Tab 选择、真实总数和最近记录已通过本机后端联调；完整微信业务 UI 和真机待验收。评分、提醒、券与同款经验未交付。 |
| 档案拍照录入 | PR #17 已合并，本机图片通过，真实相机待验收 | `input_type=1`、至少一张本人 CLEAN 图片，手动仍默认 3；列表/摘要同时覆盖两种来源。[最终 PR CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37257482023) 六项通过。取图运行时缺口已随私有图片修复（见上一行），真机相机验收见[真机相机拍照验收](../testing/REAL_CAMERA_ACCEPTANCE.md)。 |
| 登录预检与验收清单 | PR #18 内容整合至 PR #19 | 只读 HTTPS 预检脚本和逐角色验收表已提供；6 项离线测试通过，尚未针对公网测试后端执行。[运维清单](../operations/AUTH_INTEGRATION_RUNBOOK.md)。 |

## 开发环境与发布边界

本机 Docker 后端当前以 `0.0.0.0:18080→8080` 发布，运行镜像为 `vehicle-auth/backend:ai-chat`（含车主端 AI 管家），新建测试数据库与私有微信配置可用。开发者工具通过关闭域名校验连接本机 HTTP，真实微信换码成功；测试号真机预览也可经电脑局域网地址走真实登录（手机侧「打开调试」跳过域名校验）。不需要先购买公网服务器或域名。当前小程序须从本工作树重新构建并导入产物，旧窗口可能仍使用缺少后端地址的版本。真机联调后**不要**执行 `node scripts/miniapp_lan_helper.cjs --restore`，也不必把容器改回回环发布——局域网地址对开发者工具与真机同样有效，来回切换只会让另一边失效（2026-10-07 的真机连不上即源于此）；`--restore` 仅在电脑不再连接该网络时作为应急出口。

真实密钥只放在被忽略的 `.env.auth-backend.local`；`.env.example` 因 `.gitignore` 的 `!.env.example` 例外而**受版本控制**，只能保留占位值（2026-10-07 曾误填一次，已迁移并确认未进入任何提交）。

手机调试需要手机可达的后端地址，不能沿用 `127.0.0.1`；本机已把后端发布到局域网并提供构建助手，测试号阶段即可在真机完成真实登录，步骤与验收见[真机登录清单](../operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。常规真机验收和正式发布的自建后端仍需有效 HTTPS、备案域名及小程序通讯域名配置；微信云托管路径已实施（`callContainer` 免配通讯域名，登录改用网关注入的 `X-WX-OPENID`），尚缺环境部署与真机验收，且需先关闭服务公网访问才能信任身份头。详见[网络说明](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)与[云托管验收清单](../operations/CLOUDRUN_LOGIN_RUNBOOK.md)。

本地车辆与无图档案真实联调已完成，22 项检查与边界见[复现说明](../testing/LOCAL_BUSINESS_ACCEPTANCE.md)。本机私有图片 26 项接口及 11 项 H5 检查已通过，见[复现说明](../testing/LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)。2026-10-06 用户决定先完成本机功能开发，再处理公网、域名、云托管与真机。当前交付标准服务项目列表与详情，已完成本机验证，交付状态以 [PR #22](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/22) 和对应 CI 为准。微信实际相机/预览、第二个真实车主、手机号与真机仍待验收（相机步骤见[真机相机拍照验收](../testing/REAL_CAMERA_ACCEPTANCE.md)）；短信仍须服务商。具体步骤、完成判定与外部依赖见[下一步规划](NEXT_STEPS.md)及[依赖清单](S0_DEPENDENCIES.md)。完整 F01–F04、M0/M1 仍未通过。三端（车主/商家/技师）与运营端还差哪些功能，见[三端功能缺口清点](ROLE_GAP_ANALYSIS.md)；据此重排的实施顺序（阶段 A–E）见[下一步规划](NEXT_STEPS.md)。
