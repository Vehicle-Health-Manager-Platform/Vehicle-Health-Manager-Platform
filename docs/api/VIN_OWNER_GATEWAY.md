# VIN 车主候选接口与接入

R2a 接通 POST `/api/vehicle/vin-decode` 与小程序添加车辆的 VIN 模式。旧 GET 没有解码实现，迁移本 POST。内部来源适配详见 [R1](VIN_PROVIDER_FOUNDATION.md)；完整可生成契约见 [OpenAPI](openapi.json)。

## 配置与请求

后端 `.env.example`、Compose 及云托管模板提供 `VIN_DECODE_ENABLED=false`、`VIN_DECODER_PROVIDER=unconfigured`。本地测试配置使用下列值后重启后端；Compose 从私有 env 文件读取，云托管手工设置后端环境变量。小程序沿用现有 HTTP 原点或 callContainer 配置，无 VIN 服务密钥或供应商 URL。

```dotenv
VIN_DECODE_ENABLED=true
VIN_DECODER_PROVIDER=NHTSA_VPIC
```

来源启用时必须显式匹配 NHTSA_VPIC，否则启动失败。默认关闭返回 503，仍可手动录车。供应商固定 `https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/`，不支持任意 URL。需要 DNS/TLS 和 HTTPS 出口；不得在 URL/日志/监控中记录请求 VIN 或供应商正文。供应商会接收 VIN 与可选年款，启用前须核实用途说明、数据传输与适用覆盖的接受情况；测试合成 VIN 不发送真实来源。

当前有效 OWNER Bearer 会话，`Content-Type: application/json`，禁止任何 query，无幂等写键：

```json
{"vin":"LTEST223456789012","model_year":2026,"consent":true,"consent_version":"VIN_VPIC_V1"}
```

示例为合成值。年款可省略，不能是 null/字符串/小数，范围 1980..2100。VIN 校验原始 ASCII 后规范为大写17位，排除 I/O/Q。额外键、重复键、尾随 JSON 或缺少本次来源用途同意均 400。

200 的 `data`：

```json
{"vin":"LTEST223456789012","model_year_hint":2026,"status":"CANDIDATE","source":"NHTSA_VPIC","coverage":"US_MARKET_REGISTRATION_ONLY","requires_confirmation":true,"fields":{"make":"Synthetic","model":"Test","model_year":"2026"}}
```

仅示例，不表示真实识别。status 为 CANDIDATE/PARTIAL/NO_MATCH；fields 只允许 make、model、model_year、trim、vehicle_type 非空文本。完整候选必须有品牌/车系/年款；NO_MATCH 为空。无内部 model_id 或车型精确映射，不生成置信度。响应关联原 VIN 与年款提示，Cache-Control 为 no-store。

## 权限、错误与资源

- 401：JWT/数据库会话过期、撤销，或账号停用/删除。调用前后查库，供应商失败也复核，失效后不返回候选。
- 403：商家、技师或其他非 OWNER 身份。绑定凭证不具备业务身份。
- 429：每车主每 UTC 分钟最多5个已准入尝试（失败也计入），或实例同时已有2次查询；不排队。固定分钟窗口边界存在突发，不构成供应商全局配额保证。账号换会话不重置限额。
- 503：未启用、数据库或来源不可用、超时、响应无效；只给安全提示，不透传供应商原文/URL。服务器不作应用级重试；客户端可明确重试，仍计入限额。

供应商总期限10秒、64KiB 响应上限，拒绝重定向、压缩和协议不符；小程序13秒请求期限，微信云通道沿用传输封装。无数据库事务跨越外部 HTTP。限流仅存 scope、账号哈希、分钟与计数；每次准入清理当前账号一天前的 VIN 计数，其他 scope 不受影响。无车辆/档案/业务审计写入。

## 页面流程与验收边界

添加车辆 → VIN录车 → 输入VIN/可选年款 → 阅读NHTSA用途与美国市场覆盖限制 → 勾选本次同意 → 查询 → 核对候选并勾选 → 带入VIN/摘要 → **人工选择内部目录品牌、车系、年款、配置及里程** → 原有录车保存。无结果/未配置可直接手动录车，不要求同意外发。

输入、年款、用途同意、账号或模式改变以及页面隐藏/卸载，取消接收并丢弃迟到结果；运行时支持abort时同时中止传输，否则服务端已准入查询仍可能执行并计入限额。候选经账号绑定、仅内存60秒单次消费的 prefill 传递，摘要不自动选择车型。候选展示与确认都不等于车辆已保存。

真实接入所需资料：获准查询的 VIN/实车年款与对照车型样本、来源条款/用途说明及地区覆盖确认、后端出口与错误预算。验收需获授权真实样本成功/部分/无匹配、年份冲突、超时/限流、关闭降级及跨账号撤销，核对保存结果。NHTSA 以美国市场登记为主，国内车型需另选授权来源和内部目录映射验收，不能借本地替身测试关闭该任务。当前测试结果见 [R2a记录](../progress/RECOGNITION_R2A_VIN_GATEWAY.md)。
