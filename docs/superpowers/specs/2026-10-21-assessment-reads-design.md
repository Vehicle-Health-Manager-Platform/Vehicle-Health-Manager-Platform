# 考核政策、月结读取与经营看板验收设计

本阶段补齐 R8B 交付但尚未进入四类证据的两组只读/结算接口：考核政策与月结（`/api/admin/assessment/*`、`/api/merchant/assessment/*`，17 项）与经营看板（`/api/admin/business/*`、`/api/merchant/business/dashboard`，3 项），共 20 项已实现操作。

契约里还有一条 `GET /api/admin/assessment`（无 `x-roles`、无 `x-implementation-status`），代码中没有映射，不在本阶段范围：给它造业务用例只会得到 404，而不是权限拒绝。

## 方案选择

Newman 业务套件（A4d-2 起的默认做法）即可覆盖本阶段：这些接口不碰对象存储、不碰渠道回调，风险集中在"月份窗口、结算修订、权限与不凭空出分"，全都是 MySQL + HTTP 能证明的。相比之下 Java 旅程测试只能重复同一件事，因此不再起 Testcontainers。

## 被测流程

身份、门店、订单与政策种子为 SQL 合成夹具；政策创建、修订、发布、停止、月结、全部读取与看板查询走真实 HTTP。

固定月份夹具（不随执行日期漂移，因此断言可以写死）：

| 月份 | 语义 | 用途 |
| --- | --- | --- |
| `2024-01` | 已结束，本店有已发布政策 | 结算、历史、明细、事实、看板窗口 `[2023-12-31T16:00:00Z, 2024-01-31T16:00:00Z)` |
| `2999-01` | 未开始 | `MONTH_OPEN`、`can_settle=false`、结算 409、政策 `NOT_STARTED`、看板 409 |
| `3000-01` | 未开始且无政策、无快照 | 员工考核 `NO_SNAPSHOT` |

门店 1 的政策是 SQL 种子（`PUBLISHED`，窗口 `2024-01-01 00:00` 至 `2999-01-01 00:00` 北京时间，即覆盖 `2024-01` 且当前仍在有效期内）；门店 2 的政策全部走 HTTP，窗口 `2999-01`。这样做的原因是产品约束本身：`POST /policies` 要求 `valid_from` 晚于当前时间，所以"已结束月份的政策"在运行时不可能由接口创建，只能作为历史夹具存在；而接口写入能力由门店 2 的未来窗口政策证明。

`uk_assessment_published(published_merchant)` 保证一家门店同时只有一份已发布政策，因此写入链固定落在门店 2，读取链固定落在门店 1，两者不互相污染。

## 事实与"不凭空出分"

门店 1 在 `2024-01` 只有两条事实来源：

1. 一笔 `LOCAL_TEST` 支付的完结订单 → 过程事实 `EXCLUDED`（`TEST_SOURCE`），证据为空对象。
2. 一笔 `COMPLETED` 但没有核销回执的订单 → 过程事实 `UNKNOWN`（`COMPLETION_RECEIPT_MISSING`）。

于是结算摘要必须是：拉新月与券月分母来自政策目标（10），无事实也给 `KNOWN/0.00`；过程维度分母只数非 `EXCLUDED`（=1），因为存在 `UNKNOWN` 事实而整体 `UNKNOWN`，`score=null`，`reason=SOURCE_UNKNOWN`；总分为 `null`、等级为 `null`。也就是说，本阶段要证明的是"证据不全时不会给出一个看起来正常的分数"，而不是"能算出分"。

门店 2 在 `2024-01` 没有政策：`policy_snapshot` 落库为 JSON `null`，三个维度全部 `UNKNOWN/POLICY_UNKNOWN`，`fact_count=0`。

## 权限矩阵

| 接口组 | 允许 | 拒绝 |
| --- | --- | --- |
| `/api/admin/assessment/*` | `OPERATOR(can_assessment)` | `OPERATOR(can_analytics)` 403（权限互斥）、门店账号 403、匿名 401 |
| `/api/admin/business/*` | `OPERATOR(can_analytics)` | `OPERATOR(can_assessment)` 403、门店账号 403、匿名 401 |
| `/api/merchant/assessment/month|history|snapshots/{id}|snapshots/{id}/facts` | 本店 `MERCHANT`（店长） | 本店 `STAFF` 403、车主 403、他店 404（按 `merchant_id` 过滤）、匿名 401 |
| `/api/merchant/assessment/policy` | 本店店长 | 店员 403、车主 403、匿名 401 |
| `/api/merchant/assessment/staff` | 本店店长与店员本人 | 车主 403、匿名 401 |
| `/api/merchant/business/dashboard` | 本店店长 | 店员 403、车主 403、匿名 401 |

门店侧越权一律 `404`（`own(a,id)` 按 `merchant_id` 过滤后查不到），角色不符一律 `403`，与既有约定一致。

## 输入严格性

月份必须是 `YYYY-MM`（`[1-9][0-9]{3}-(0[1-9]|1[0-2])`）；`page` 取 `1..1000000`，`page_size` 只接受 `20`；未知或重复 query 参数一律 `40001`。政策正文用 `CouponInput.exact` 做精确字段集校验，因此未知字段、重复维度、`process_requirements` 重复项、等级阈值非严格递减、最低阈值不是 `0.00`、`valid_from` 不是北京时间整月 1 日 0 点、`valid_to<=valid_from` 全部 `40001`；过去窗口、过期修订号、非 `DRAFT` 修订、重复发布、未结束月份结算一律 `409`。

## 幂等与审计

政策创建/修订/状态与月结都走 `WriteIntegrityService`：同键同体重放返回逐字节相同的 `{code,message,data,request_id}`，同键异体 `40001`。审计只记 7 条（政策创建 1、修订 1、状态 2、月结 3），重放与失败不新增。事实明细不进 `audit_log`，`after_state` 里只有摘要与政策快照，落地检查断言 `after_state` 不含 `proof_hash`。

## 生产代码变更

无。本阶段只补执行证据与覆盖核对登记；发现的行为差异以"不改契约"为准记录在验收文档里。

## 边界

- 门店 1 的过程证据链是 `EXCLUDED`+`UNKNOWN`，因此本阶段**不**证明"能算出 100 分"。真正算分需要接车确认、防护、报工、质检签字四段完整证据，那属于履约旅程（A4d-2b）的取值范围，不在本阶段重复。
- `assessment_month.revision` 与最新快照修订不一致时接口返回 503（"月结版本尚不可确认"）。该状态只有并发写入或人工改库才能制造，本阶段不伪造。
- 匿名 401 由匿名集合覆盖，不重复计入业务四类覆盖。
