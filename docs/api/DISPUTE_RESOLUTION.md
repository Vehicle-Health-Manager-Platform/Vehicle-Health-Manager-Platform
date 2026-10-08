# 争议处理与恢复接口契约

更新：2026-10-08，A5.6。两个写接口已实现，争议时间线嵌在既有接车单投影里。业务规则与恢复条件见
[争议处理规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md)；前置的异议入口见
[A4 车主确认与异议](PICKUP_OWNER_DECISION.md)。

## 通用规则

返回 `{code,message,data,request_id}`；时间为 ISO8601 UTC；所有响应 `Cache-Control: no-store`。
写请求必带 UUID `Idempotency-Key`，24 小时内按调用者、方法、路径、键与规范化正文去重，同键异体 `40001`。
正文严格验证，不接受额外字段、浮点/字符串 ID、目标状态或商家 ID。身份与归属在成功缓存重放时仍复核。

争议时间线**不投影任何 `actor_id`**：车主 ID 与商家员工 ID 都不出现在响应里，只在库里留痕。

## 1. 商家提交争议处理记录

`POST /api/merchant/orders/{id}/dispute/handle`，仅 MERCHANT，路径 ID 为订单 ID。

严格正文：`{"note":"已核对接车照片，同意补拍左后门并重新生成接车单"}`，`note` 去除首尾空白后 1–500 字。

`data:{dispute_id,order_id,dispute_status:"OPEN",order_status:"DISPUTED",owner_confirm:2,record_count:2,last_action:"HANDLE",updated_at,changed:true}`。

订单必须处于 `DISPUTED` 且争议为 `OPEN`，否则 `40905`。**可以多次提交**：每次追加一条时间线记录，
不覆盖历史。商家**不能**通过本接口宣布争议解决或恢复订单——那是车主复核的权限。

## 2. 车主复核争议处理

`POST /api/check/pickup/dispute/review`，仅 OWNER，按正文里的订单 ID 定位。

严格正文 `{"order_id":1,"decision":"ACCEPT"}`，或 `{"order_id":1,"decision":"REJECT","note":"左后门仍未补拍"}`。
`decision` 为 `ACCEPT`/`REJECT`；`REJECT` 必须附 1–500 字原因，`ACCEPT` 可附 0–500 字说明。

- `ACCEPT`：争议置 `RESOLVED`，订单回到争议前状态，接车单 `owner_confirm` 由 2 变 3 并写
  `owner_confirmed_at`，派工前置随之成立。返回 `dispute_status:"RESOLVED"`、`order_status:"RECEIVED"`、
  `owner_confirm:3`、`last_action:"ACCEPT"`。
- `REJECT`：争议保持 `OPEN`、订单仍 `DISPUTED`，只追加一条 `REJECT` 记录；
  商家可继续提交处理记录。返回 `owner_confirm:2`、`last_action:"REJECT"`。

**恢复条件（缺一不可）**：订单为 `DISPUTED`、争议为 `OPEN`、争议前状态属于
`PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`、接车单存在且 `owner_confirm=2`、商家已提交至少
一条处理记录。前四项不满足返回 `40905`；最后一项返回 `43008`。

**接受处理即视为确认接车单**：这是规格的 E3 决定，接车单不再回到"未决定"。

## 3. 争议时间线（既有接车单投影）

`GET /api/check/pickup/{order}`（OWNER/MERCHANT）在既有字段之外新增 `dispute`：

```json
"dispute": {
  "dispute_id": 1,
  "status": "OPEN",
  "reason": "车门划痕记录不符",
  "from_status": "RECEIVED",
  "opened_at": "2026-10-08T02:00:00Z",
  "resolved_at": null,
  "can_review": true,
  "records": [
    {"action": "HANDLE", "note": "已核对照片，同意补拍左后门", "created_at": "2026-10-08T03:00:00Z"},
    {"action": "REJECT", "note": "左后门仍未补拍", "created_at": "2026-10-08T03:30:00Z"}
  ]
}
```

没有争议时为 `null`。`records` 按 `id` 升序，`action` 为 `HANDLE`/`ACCEPT`/`REJECT`。
`can_review` 由服务端计算（订单 `DISPUTED` + 争议 `OPEN` + 至少一条 `HANDLE`），
**是否真的能复核仍以服务端写接口判定为准**，前端只据此显示按钮。
既有 `dispute_reason` 字段保留不变（A4 契约的一部分），与 `dispute.reason` 同源。

本阶段不新增读接口：商家与车主读同一份接车单投影，避免两套视图漂移。商家订单详情
（`/api/merchant/orders/{id}`）投影不变，`DISPUTED` 的 `allowed_actions` 仍为 `[]`。

## 4. 错误码

| HTTP / code | 含义 |
| --- | --- |
| 400 / 40001 | 正文/ID/UUID 键无效、`REJECT` 缺原因、同键异体 |
| 401 / 40100 | 会话到期/撤销，账号或商家失效 |
| 403 / 40300 | 角色错误（技师、商家凭证调车主复核、未完成绑定的凭证） |
| 404 / 40400 | 订单/接车单不存在、非本人或非本店 |
| 409 / 40905 | 订单不在争议中、争议已解决、恢复目标状态异常 |
| 409 / 43007 | 订单存在未解决的争议，不能派工或接单 |
| 409 / 43008 | 商家尚未提交处理记录，暂不能复核 |
| 503 / 50300 | 数据库未配置或读写事务失败，使用原键重试写操作 |

`43007` 出现在[派工与接单接口](TECHNICIAN_DISPATCH.md)的前置拒绝里：`DISPUTED` 订单此前返回通用
`40905`，现在返回明确的业务码，便于界面提示"请先在接车单处理争议"。这是收敛，不是放宽——
`DISPUTED` 订单依旧派不了工。

## 5. 事务、锁与并发

一次写请求在同一事务内完成：行锁串行化、争议记录追加、订单状态迁移、`order_status_transition`、
`audit_log` 与幂等成功响应。任一步失败整体回滚。

- 商家处理：`商家 → 商家行 → 时段 → 订单 → 接车单 → 争议单 → 记录`；
- 车主复核：`车主 → 商家 → 时段 → 订单 → 接车单 → 争议单`（与 A4 车主决定同序）；
- 两名商家员工并发提交处理记录：两次都成功，时间线两条（追加，不是竞争）；
- 复核与提交处理记录并发：复核要么看到记录（成功），要么 `43008`；
- 同一车主并发复核：最多一次恢复订单，第二次读到 `RESOLVED` 返回 `40905`；
- 复核与派工并发：派工只能看到 `DISPUTED`（`43007`）或恢复后的 `RECEIVED`（成功）。

## 6. OpenAPI 与版本范围

两个操作纳入 OpenAPI 生成器，标记 `dispute-resolution-implemented`，同步严格 schema、角色、
幂等键与错误码。运行本版本需 V001–V013、有效数据库、JWT 配置；新 Compose 卷自动迁移，
已有库备份后补 V013。小程序客户端在 `apps/miniapp/src/services/dispute.js`，
界面在 `pages/check/pickup-detail.vue` 的争议区。
