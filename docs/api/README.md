# API 文档

A5.2 已实现六个后端接口，见[商家派工与技师接单契约](TECHNICIAN_DISPATCH.md)，明确六个操作、员工归属、接单迁移、权限、幂等和错误码；界面已在 A5.3 接入小程序（商家派工页、技师工作台/详情/接单）。A5.6 另实现[争议处理与恢复](DISPUTE_RESOLUTION.md)：商家追加处理记录、车主复核后恢复订单，争议未解决时派工/接单返回 `43007`。后端证据见[A5.2 执行记录](../progress/A5_2_DISPATCH_BACKEND_EXECUTION.md)、[A5.6 执行记录](../progress/A5_6_DISPUTE_RESOLUTION_EXECUTION.md)，界面证据见[A5.3 执行记录](../progress/A5_3_DISPATCH_UI_EXECUTION.md)，安全与竞争证据见[A5.4 执行记录](../progress/A5_4_DISPATCH_VERIFICATION_EXECUTION.md)，本机端到端证据见[A5.5 执行记录](../progress/A5_5_DISPATCH_E2E_EXECUTION.md)、[本机派工验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)、[本机争议验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md)。[规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)、[争议处理规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md)与[计划](../superpowers/plans/2026-10-08-a5-dispatch.md)提供编码和验收基线。

交付文档 §8 给出统一响应、错误码和 43 个核心接口。[openapi.json](openapi.json) 是 S0 草案，现含 91 个操作（原始核心接口、规划中的支撑接口、当前认证接口与本地写入示例）；用 `python scripts/generate_openapi.py` 从追踪表、[业务契约](S0_BUSINESS_CONTRACT.md)及已实现接口定义重新生成。草案中的大多数路径尚未实现，不能将文档操作数当作已交付接口数；已实现认证接口标有 `x-implementation-status: core-implemented`。S1 本人档案手动录入/查询契约见[档案接口说明](ARCHIVE_MANUAL.md)。

`POST /api/demo/vehicles/{id}/mileage` 为 local-only 的幂等与成功变更审计验证入口，标有 `x-implementation-status: local-example`，契约与事务边界见[写入说明](WRITE_INTEGRITY.md)。不代表正式车辆业务完成。

[私有上传核心](PRIVATE_UPLOAD_CORE.md)是内部应用服务，核心自身不暴露 HTTP；PR #12 的独立适配层增加上传/访问入口及两个 OpenAPI 操作；真实适配器已提供，业务附件关系及真实小程序联调尚未接入。

真实 MinIO/ClamAV 与内部签名服务的配置和部署见[上传适配器说明](UPLOAD_ADAPTERS.md)。内部服务不等于 HTTP 上传或小程序联调完成；验收结果以[S0 执行记录](../progress/S0_EXECUTION_LOG.md)为准。

原始方案以 `POST /api/auth/wx-login` 为微信授权登录入口。S0-7.1d/e 已实现服务端身份数据库仓储、车主/技师 JWT、`POST /api/auth/technician/bind`、`POST /api/auth/phone/bind`、`POST /api/auth/refresh` 与 `POST /api/auth/logout`；请求与权限边界见 [微信身份设计](WECHAT_IDENTITY_DESIGN.md) 和 [后端说明](../../backend/README.md)。S0-7.1f-2 另实现商家密码加短信码身份核心，接口与尚未接入的短信发送边界见[商家身份说明](MERCHANT_AUTH.md)。这些接口已纳入生成的 OpenAPI 草案，但不表示真实微信和短信联调已完成；前置条件见[依赖清单](../progress/S0_DEPENDENCIES.md)。`local` profile 另提供 `POST /api/dev/token` 与 `GET /api/demo/vehicles/{id}` 作为 JWT 和资源归属校验样例；默认 profile 不暴露演示接口。支付基础与预约已逐项实现，正式扣款、退款及其他草案操作仍待接入。

HTTP 上传和本人短时图片访问见 [HTTP 接入说明](UPLOAD_HTTP.md)：仅正式车主会话，需 V004 及真实适配器；PR #13 已接入小程序图片操作，真实私有环境仍未联调。

PR #14 实现[本人车辆列表、有效车型查询与手动添加](VEHICLE_MANUAL.md)。OpenAPI五个既定操作已标明实现状态，添加仅支持add_type=4；其他车辆录入、档案和驾驶舱路径继续属于规划。该阶段操作总数为69，不将接口数量视为业务完成。

- [标准服务项目查询](SERVICE_CATALOG.md)：车主分类分页、详情与参考价。

- [商家报价](MERCHANT_QUOTES.md)：本店维护与不可变版本。
- [预约与本人订单](RESERVATION_ORDERS.md)：时段、容量、创建/取消/到期。
- [支付基础](PAYMENT_FOUNDATION.md)：默认关闭的隔离测试渠道、通知验签/去重与付款异常；正式微信和退款未接入。
- [车主端 AI 管家](AI_CHAT.md)：`POST /api/ai/chat` 对话、本人车辆与档案上下文、降级与隐私边界。
- [商家本店订单](MERCHANT_ORDERS.md)：本店分页/详情、状态与预约日期筛选、下单快照与支付摘要。
- [订单履约状态机](ORDER_FULFILLMENT.md)：8 个状态的唯一权威矩阵、动作式状态操作接口、前置 fail-closed、双审计与并发行锁。接车单、车主决策、派工与争议恢复已接入；报工与核销仍待后续阶段。
- [车主确认接车单与异议](PICKUP_OWNER_DECISION.md)：A4 本人确认或提出异议、争议阻断、同事务审计与幂等。
- [争议处理与恢复](DISPUTE_RESOLUTION.md)：A5.6 商家追加处理记录、车主复核后才恢复订单，`43007`/`43008` 阻断与恢复条件 R1–R5。
- [接车检查](PICKUP_INSPECTION.md)：商家私有上传、七图检查单、手填里程、预约验码与 PAID→RECEIVED；派工继续后续交付。

`POST /api/ai/chat` 已实现并接入小程序 AI Tab，但**真实 DeepSeek 上游调用与真机尚未验收**，
降级路径与验收项见 [AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md)。该路径尚未写入 `openapi.json` 草案。

## A6 防护与报工

[A6.1 施工接口](SERVICE_WORK.md)：商家防护、本人完整报工与质检签字、施工记录及关联私有图片。页面与端到端验收见后续 A6.2/A6.3。
