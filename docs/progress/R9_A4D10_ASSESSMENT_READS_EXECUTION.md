# R9 A4d10 考核政策、月结与经营看板读接口阶段执行记录

## 结果与范围

2026-10-21 完成实施和本地隔离验收。分支 `codex/r9-a4d10-assessment-reads`，基于 A4d-9 的 `codex/r9-a4d9-pickup-work-files`。本阶段补齐 20 个已实现但尚无四类证据的接口：考核政策（`/api/admin/assessment/policies`、`/api/admin/assessment/merchants`、`/api/merchant/assessment/policy`）、月结（`/api/admin/assessment/snapshots`、`/api/admin/assessment|merchant/assessment/month|history|snapshots/{id}|snapshots/{id}/facts`）、员工考核（`/api/merchant/assessment/staff`）与经营看板（`/api/admin/business/merchants|dashboard`、`/api/merchant/business/dashboard`）。

契约里的 `GET /api/admin/assessment` 既无 `x-roles` 也无实现标记，代码中没有映射，本阶段排除：给它造业务用例只会得到 404，而不是权限拒绝。

形态选择沿用 A4d-2 起的 Newman 业务套件（真后端 + 真 MySQL），因为这些接口不碰对象存储、不碰渠道回调，风险集中在月份窗口、结算修订、权限与"不凭空出分"，都是 MySQL + HTTP 能证明的。

## 实施与行为

- 20 个接口各覆盖正常/边界/错误/未授权，共 153 个业务请求、530 条断言。
- 固定月份夹具，不随执行日期漂移：`2024-01`（已结束、本店有已发布政策）、`2999-01`（未开始）、`3000-01`（未开始且无快照）。因为审计要求"实际 query 与声明 query 逐字相同"，月份必须以字面量出现在集合里，不能做变量替换。
- 门店 1 的政策是 SQL 种子（`PUBLISHED`，北京时间的整月窗口，覆盖 `2024-01` 且当前仍在有效期内）：产品约束是 `POST /policies` 要求 `valid_from` 晚于当前时间，所以"已结束月份的政策"在运行时不可能由接口创建，只能作为历史夹具；接口写入能力由门店 2 的未来窗口政策证明。`uk_assessment_published` 保证一店同时只有一份已发布政策，因此写入链固定门店 2、读取链固定门店 1。
- 运营权限互斥：`can_assessment` 账号访问经营看板一律 403，`can_analytics` 账号访问考核一律 403；门店账号访问运营接口 403，匿名 401。门店侧越权按 `merchant_id` 过滤后 404，角色不符 403。
- 政策写入链：创建（revision 1）→ 同键重放（逐字节相同）→ 同键异体 400 → 修订（2）→ 发布（3）→ 重复发布 409 → `DRAFT` 400 → 停止（4）→ 重复停止 409；过去窗口 409、目标 0、重复过程维度、等级非严格递减、末档非 `0.00`、月中窗口、未知字段、未知 query、未知门店 404 各一条。
- 月结链：`2024-01` 第 0 号修订结算两次得到 revision 1、2；过期修订 409、未结束月份 409（且 `assessment_month` 头不落库）、未知门店 404、同键异体 400；门店 2 的无政策月份结算落库 `policy_snapshot = JSON null`。
- 摘要口径：拉新月与券月的分母来自政策目标（10），无事实也给 `KNOWN/0.00`；过程维度分母只数非 `EXCLUDED`（=1），因存在 `UNKNOWN` 事实（无核销回执的完成订单）整体 `UNKNOWN`、`score=null`、`reason=SOURCE_UNKNOWN`；总分与等级都是 `null`。本阶段证明的是"证据不全时不给看起来正常的分数"。
- 员工考核读的是冻结快照：`2024-01` 为 `FROZEN` 且 revision 2，`3000-01` 为 `NO_SNAPSHOT`；店长与店员本人各自只能看到自己的 `staff_id`。
- 看板：本店 `LOCAL_TEST` 支付 1 笔进入测试桶（`10.00`），2 笔无法验证来源的订单进入 `unclassified_count`，正式桶与测试桶因此同时 `UNKNOWN`，不把未验证订单塞进任何一桶；月结引用块指向 `2024-01` 的 revision 2 且 `total_score` 为 `null`。
- 数据库不变量 13 项：政策状态与修订、每店至多 1 份已发布、种子政策确实覆盖 `2024-01`、2 个月结头只在 `2024-01`、3 份快照修订 `1,2,1`、3 份摘要全 `UNKNOWN` 且分数/等级为 JSON null、无政策月份的 `policy_snapshot` 为 JSON null、事实固定 9 字段、事实从不进入审计、审计动作 7 条（政策创建 1、修订 1、状态 2、月结 3）、无跨店快照、失败的月份没有留下月结头。
- 执行器与门禁：`run_api_acceptance.py` 新增 `--assessment-cases`（并纳入源码哈希与"每套独立数据库"互斥校验）、覆盖汇总新登记 `assessment` 一套、CI 增加验收步骤与 `test_assessment_api_cases.py` 单测步骤。

## 实际验证

结论：PASS，228.873 秒（第二次执行；第一次因两条断言写错失败，见下）。可提交脱敏记录：`docs/testing/reports/R9_A4D10_ASSESSMENT_READS_2026_10_21.json`；验收说明见 `docs/testing/ASSESSMENT_READS_ACCEPTANCE.md`。

- 业务 153 请求 / 530 断言 0 失败，20 个接口四类齐备；7 个幂等作用域与 2 组同键异体实测通过。
- 匿名 338 请求 / 1014 断言 0 失败，匿名流量前后表行数完全不变，默认禁用的集合实测 0 请求。
- 13 项数据库检查全部为真；自有 MySQL / 后端容器 / 网络实际移除；源码哈希执行前后一致。
- 覆盖核对：24 套来源、258/352 项历史四类完整、缺口 94 项，全 API 验收仍为 false。

### 第一次执行的两处失败（已修，记录以备复盘）

1. `policies_seeded` 的第二条业务断言引用了上一条断言脚本里的 `const d`。Newman 的每条 `extra` 都是独立的 `pm.test` 函数作用域，跨条不共享变量。
2. 两条看板边界用例断言 `assessment` 为 `null`，实际指向门店 2 `2024-01` 的第 1 次结算快照 —— 看板引用的就是那份 `UNKNOWN` 快照。改为断言 `snapshot_id=3 / revision=1 / state=UNKNOWN / total_score=null`，比"应为 null"更准确。

## 遗留

- 本阶段未证明"能算出正常分数"：完整四段过程证据（接车确认、防护、报工、质检签字）属于履约旅程范围。
- `assessment_month.revision` 与最新快照修订不一致时的 503 只能由并发写入或人工改库制造，未伪造。
- 性能门禁、真机/模拟器、小程序剩余页面仍未在本阶段覆盖。
