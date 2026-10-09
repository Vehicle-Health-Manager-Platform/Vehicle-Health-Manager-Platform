# 订单履约状态机契约

2026-10-07。书面规则见[接车与履约状态规格](../superpowers/specs/2026-10-07-fulfillment-state-design.md)，
机器契约见 [openapi.json](openapi.json)，本店只读投影见[商家本店订单](MERCHANT_ORDERS.md)。
本步对应三端缺口清点里的[阶段 A2](../progress/ROLE_GAP_ANALYSIS.md)：把
`PENDING_PAYMENT`/`PAID`/`CLOSED` 三个状态收敛为 8 个状态的**服务端唯一权威**，
并提供商家侧的履约操作入口。接车单、车主确认、派工、报工与核销的**业务内容**分别在
A3–A6 实现；本步先把它们的判定挂点与审计通道建好。

阶段进展：A3 已通过接车检查接口写入接车证据并迁至 `RECEIVED`；A4 已通过[车主接车单决策](PICKUP_OWNER_DECISION.md)写入 `owner_confirmed_at` 或迁至 `DISPUTED`。A5.2 已接入[商家派工与技师本人接单](TECHNICIAN_DISPATCH.md)，A5.6 已接入[争议处理与恢复](DISPUTE_RESOLUTION.md)，A6.1 已接入[防护、完整报工与本人质检签字](SERVICE_WORK.md)，签字才进入待核销；核销仍待 A7。

## 状态

沿用现有字符串枚举，不改成数值（`PENDING_PAYMENT` 等已落库并有对外契约）。

| 值 | 含义 | 可逆 |
| --- | --- | --- |
| `PENDING_PAYMENT` | 待支付 | 否（到期/取消即关闭） |
| `PAID` | 已付待接车 | 否 |
| `RECEIVED` | 已接车，待车主确认与派工 | 否 |
| `IN_SERVICE` | 施工中 | 否 |
| `PENDING_VERIFY` | 施工完成待核销 | 否 |
| `COMPLETED` | 已完成 | **不可逆** |
| `CLOSED` | 已取消/已关闭 | **不可逆** |
| `DISPUTED` | 争议中 | 回到争议前的状态 |

`CLOSED` 继续承载"已取消"，不新增 `CANCELLED`，避免两套语义并存。

## 迁移矩阵

目标状态 → 允许的来源状态。未列出的组合一律拒绝（`40905`）。

| 目标 | 允许来源 |
| --- | --- |
| `PAID` | `PENDING_PAYMENT` |
| `RECEIVED` | `PAID` |
| `IN_SERVICE` | `RECEIVED` |
| `PENDING_VERIFY` | `IN_SERVICE` |
| `COMPLETED` | `PENDING_VERIFY` |
| `CLOSED` | `PENDING_PAYMENT`、`PAID`、`RECEIVED`、`IN_SERVICE`、`PENDING_VERIFY` |
| `DISPUTED` | `PAID`、`RECEIVED`、`IN_SERVICE`、`PENDING_VERIFY` |
| `PENDING_PAYMENT` | 无（不允许回到待支付） |

不允许任何跨状态跳跃（如 `PAID → COMPLETED`），也不允许从 `COMPLETED`/`CLOSED` 再出发。
`DISPUTED → 原状态`由 A5.6 的[争议处理与恢复](DISPUTE_RESOLUTION.md)专用接口写入：只有车主本人接受复核后，
订单才回到争议单记录的 `from_status`（仅允许 `PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`，审计动作名
`ORDER_DISPUTE_RESOLVE`）。动作式接口的 `can()`/`MOVES` 矩阵**不含**该迁移，`actions(DISPUTED)` 仍为空——
商家与技师都无法通过通用动作接口恢复，争议也不出现在商家通用动作清单里。

## 动作

前端**只能请求动作，不能提交目标状态**（Spec §2.4）。动作到目标状态的映射在服务端固定。

| 动作 | 目标状态 | 审计动作名 | 当前阶段 |
| --- | --- | --- | --- |
| 接车检查提交 | `RECEIVED` | `ORDER_CHECK_IN` | A3 已接入专用接车接口，通用 RECEIVE 已停止对外开放 |
| 技师本人接单 | `IN_SERVICE` | `ORDER_TECH_ACCEPT` | A5.2 已接入专用接单接口；商家通用 START_SERVICE 始终返回 43004 |
| `FINISH_SERVICE` | `PENDING_VERIFY` | `ORDER_SERVICE_FINISH` | 通用入口停用 43005；A6 本人质检签字迁移 |
| `COMPLETE` | `COMPLETED` | `ORDER_COMPLETE` | A7 补核销校验 |

## 接口

| 方法 | 路径 | 身份 | 正文 |
| --- | --- | --- | --- |
| POST | `/api/merchant/orders/{id}/actions` | 本店 MERCHANT | `action`（必填）+ `note`（可选，去空白后 1–200 字） |

请求必须带 UUID `Idempotency-Key`，正文只接受这两个字段（`additionalProperties: false`），
未知字段、`action` 非字符串、`note` 非字符串一律 `400`。

响应是**本店订单详情投影**加三个字段：`action`、`from_status`、`changed`。
`changed=false` 表示该动作此前已生效、本次是幂等重放，**不会**产生第二条迁移审计。

列表与详情的投影新增 `allowed_actions`：`[{action, to_status}]`，由矩阵与当前状态算出，
**不含前置条件判定**。因此按钮出现不等于一定能执行——点击后由服务端给出具体原因。
`PAID` 订单当前返回 `[]`，页面提供[接车检查](PICKUP_INSPECTION.md)专用入口。直接提交通用 `RECEIVE` 返回 43001。`RECEIVED` 的商家 `allowed_actions=[]`，通用 `START_SERVICE` 始终返回 43004；开始施工必须由被派工技师本人接单。

## 前置条件（fail-closed）

每个动作都有前置，缺一即拒绝。**未接入校验的动作同样拒绝**，不会因为"暂时没实现"而放行。

| 动作 | 前置 | 缺失时的码与提示 |
| --- | --- | --- |
| `RECEIVE` | `order.check_in_completed_at` 非空 | `43001` 接车检查未完成，请先完成接车检查 |
| 技师接单（专用接口） | 接车证据与确认/派工记录一致，本人有效身份，状态 RECEIVED/ASSIGNED | `43001`/`43003`/`40905`；完整规则见派工契约 |
| 商家派工（专用接口） | 接车证据、车主确认或争议已恢复、订单非 `DISPUTED` | `43001`/`43003`/`43007`；完整规则见派工契约 |
| 商家通用 `START_SERVICE` | 已停用 | `43004` 请由被派工技师本人接单并开始施工 |
| 商家处理争议（专用接口） | 本店 `ORDER` 存在 `OPEN` 争议且订单 `DISPUTED` | `40905`；见[争议处理与恢复](DISPUTE_RESOLUTION.md) |
| 车主复核争议（专用接口） | 争议 `OPEN`、接车单 `owner_confirm=2`、接受前已有处理记录、`from_status` 可恢复 | `40905`/`43008`；见[争议处理与恢复](DISPUTE_RESOLUTION.md) |
| `FINISH_SERVICE` | 通用动作停用，完整报工与本人质检签字走 `/api/tech/sign` | `43005` 请由本人报工并质检签字 |
| `COMPLETE` | 核销校验（A7 接入） | `43006` 请通过专用核销验码入口完成订单（A7.1） |

`43001` 与 `43002` 是 Spec 已定义的码（接车未完成、防护照片未上传）；`43003`/`43004`/`43005`
按同一段位新增。`43006` 是分阶段实现期间的显式拒绝，A7 核销接入后不再出现。`43007`
（订单存在未解决的争议，不能派工或接单）与 `43008`（商家尚未提交处理记录，暂不能复核）由 A5.6 新增。

派工、技师接单与争议处理使用各自专用接口（见[派工契约](TECHNICIAN_DISPATCH.md)与[争议处理契约](DISPUTE_RESOLUTION.md)），
不走本节的通用动作接口；通用矩阵 `actions(DISPUTED)` 始终为空，商家与技师都无法用它恢复争议订单。

时间列由各自阶段写入，本步只负责读取：接车检查（A3）写 `check_in_completed_at`，
车主确认（A4）写 `owner_confirmed_at`，派工（A5）写 `assigned_at`，完整报工后质检签字（A6）写
`service_report_ready_at`。

## 审计

一次成功迁移在**同一事务**内写两处：

- `order_status_transition`：`order_id`、`merchant_id`、`from_status`、`to_status`、`action`、
  `actor_type`/`actor_id`、`note`、`occurred_at`。这是履约专用审计，按订单可回放状态时间线。
- `audit_log`：写入完整性服务统一记录的成功变更（`before_state`/`after_state` 为商家投影）。

任一写入失败则整个事务回滚，`order.status` 不会变化。重复动作（`from` 已是目标状态）只返回
当前投影与 `changed=false`，不写第二条迁移审计。同键重放直接复用首次响应。

并发同一订单的迁移用行锁串行化，锁顺序与到期清理一致：商家 → 时段 → 订单。
第二个请求读到新状态后返回 `changed=false`，不报错。

## 错误码

| 码 | HTTP | 含义 |
| --- | --- | --- |
| `40001` | 400 | 动作名、备注、订单号或幂等键不合法 |
| `40100` | 401 | 商家会话失效或被撤销 |
| `40300` | 403 | 非商家身份 |
| `40400` | 404 | 订单不存在或不属于本店（不区分，避免泄露他人订单） |
| `40905` | 409 | 当前状态不允许该动作 |
| `43001`/`43003`/`43004`/`43005`/`43006` | 409 | 前置条件未满足 |
| `40901`–`40904` | 409 | 预约侧既有码，不用于本接口 |
| `50300` | 503 | 数据库或事务暂不可用 |

## 与 Spec §8.3 的关系

Spec §8.3 已固定接车与施工的路径族（`POST /api/check/pickup/submit`、
`POST /api/check/pickup/confirm`、`POST /api/check/protection/upload`、
`POST /api/check/delivery/compare`）与技师报工（`POST /api/tech/report/submit`、`POST /api/tech/sign`）。
本接口是**状态机的统一驱动入口**，不是替代品：

- 它只接受动作名，不接受目标状态，因此不构成"前端直接设置状态"；
- A3 已改由 `POST /api/check/pickup/submit` 提交接车单并写 `check_in_completed_at`，
  `RECEIVE`/`START_SERVICE`/`FINISH_SERVICE` 已从本接口移除，分别走接车检查、本人接单、本人完整报工与质检签字；
- 迁移服务（矩阵、前置、行锁、幂等、审计）保持不变，只换触发它的语义接口。

这一取舍写在此处，避免后续实现者把它当成长期契约。

## 数据迁移

V009 增加 `order.check_in_completed_at`、`owner_confirmed_at`、`assigned_at`、
`service_report_ready_at` 四个可空时间列，以及 `order_status_transition` 审计表
（索引 `order_id,id` 与 `merchant_id,to_status,id`）。迁移可重复执行，表数 48 → 49。

历史订单的四个时间列均为 `NULL`，因此它们的 `RECEIVE` 会返回 `43001` —— 这是正确行为，
不伪造"已完成接车检查"的历史。

## 名额口径（D11）

`appointment_slot` 的名额计算为
`status <> 'CLOSED' AND (status <> 'PENDING_PAYMENT' OR expires_at IS NULL OR expires_at > now)`，
即**已接车及之后的状态继续占用名额**（该时段确实被这台车占用了），只有关闭、软删除与
已过期未支付的订单释放名额。这是规格 §9 建议的默认口径，已由
[真库测试](../../backend/src/test/java/com/autocare/platform/order/JdbcOrderFulfillmentTest.java)
固化。若要改为"接车后释放名额"，只需改这一处 SQL 与对应断言。

## 测试

- 矩阵穷举：[OrderStatusTest](../../backend/src/test/java/com/autocare/platform/order/OrderStatusTest.java)
  用独立期望表校验 8×8 全部组合，不复用被测实现的常量。
- 真库端到端：[JdbcOrderFulfillmentTest](../../backend/src/test/java/com/autocare/platform/order/JdbcOrderFulfillmentTest.java)
  覆盖合法/非法迁移、四个前置、重复动作、同键重放、越权、会话撤销、并发行锁与同事务审计回滚。
- HTTP 契约：[MerchantOrdersHttpTest](../../backend/src/test/java/com/autocare/platform/order/MerchantOrdersHttpTest.java)
  覆盖角色、幂等键、正文形状、订单号与冲突码透传。
- 小程序侧：[order-status.test.js](../../apps/miniapp/test/order-status.test.js) 锁定取值与标签，
  [merchant-orders.test.js](../../apps/miniapp/test/merchant-orders.test.js) 校验动作请求与拒绝码文案。

A7.1 已接入[核销验码](ORDER_REDEMPTION.md)。通用 COMPLETE 在任何状态（含已完成）均拒绝 43006；允许动作投影仍是矩阵声明，不能代替专用验码或就绪检查。
