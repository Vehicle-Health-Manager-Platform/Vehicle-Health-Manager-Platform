# 争议处理与恢复：中文实施计划

日期：2026-10-08。状态：A5.6 规格与实施计划已整理，代码/迁移/界面按本计划实施。业务基线见
[争议处理规格](../specs/2026-10-08-dispute-resolution-design.md)，请求与返回见[中文接口契约](../../api/DISPUTE_RESOLUTION.md)。
用户要求按阶段整理文档并上传 GitHub，遵循[交付规则](../../progress/STAGE_DELIVERY.md)。

## 为什么现在做

[A5 阶段](../../progress/A5_DISPATCH_EXECUTION.md)已闭环派工与本人接单，但异议之后订单停在
`DISPUTED`：商家没有可执行动作，车主没有入口，派工与施工永久阻断。规格 §2 早就把
`DISPUTED → 前一状态` 划给"争议解决"，该流程一直缺失。补上它，A4 的异议才不是死路。

## 子阶段

| 子阶段 | 交付 | 完成门禁 |
| --- | --- | --- |
| A5.6 | 规格、契约、V013、`OrderDisputes` 服务与控制器、小程序争议界面、测试、执行记录 | HTTP 与真实 MySQL 测试、无死锁、投影无隐私、小程序测试与两端构建、六项 CI |

分支 `codex/a5-dispute` 基于 A5.5 最终提交 `cde1cf2`。A5.5 的 PR #40 未合并，本阶段 PR 指向
`codex/a5-dispatch-e2e`，只展示本阶段差异；依赖合并后调整基线到 `main` 并复核 CI。

## 数据库

- 新增 `docs/sql/migrations/V013__dispute_resolution.sql`：`order_dispute`（一单一争议，`uk_order`）
  与 `order_dispute_record`（只追加时间线）；`pickup_check.owner_confirm` 注释补 `3争议已解决`。
- 可重复执行：建表用 `CREATE TABLE IF NOT EXISTS`，注释改动用 `information_schema` 条件判断。
- 更新 `deploy/compose/compose.yml` 挂载、`scripts/verify_mysql_schema.sh`（表数 50→52、
  重复执行后唯一键与注释正确）、`docs/sql/SCHEMA.md`；`docs/sql/init.sql` 由生成脚本重建。
- **不回填历史异议**：A4 之前没有争议表的订单继续没有恢复入口（fail-closed），不猜历史。

## 后端

### 规则

- `OrderStatus` 增加**只读**的恢复判定：`canResume(DISPUTED, target)` 允许
  `PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`，并新增审计动作名 `ORDER_DISPUTE_RESOLVE`。
  **不动** `can()` 与 `MOVES`：商家动作矩阵继续不含争议解决，`actions(DISPUTED)` 仍为空。
- `PickupInspection` 明确 `owner_confirm` 取值常量（0 未决定/1 已确认/2 已异议/3 争议经复核接受），
  提供 `ownerConfirmed()`；`owner_confirm=3` 与 `1` 同样满足派工前置。
- 新增 `OrderDisputes`：`handle(MerchantActor,key,orderId,note)` 与
  `review(VehicleOwner,key,orderId,decision,note)`，全部走 `WriteIntegrityService.execute`。
- 恢复事务按规格 §1 的六项效果一次完成；`ACCEPT` 校验 R1–R5，`REJECT` 只追加记录。
- `TechnicianAssignments.prerequisites`：`DISPUTED` 返回 `43007`（原为通用 `40905`），
  其余状态与前置判定不变；`confirmed()` 改用 `PickupInspection.ownerConfirmed()`。
- 接车单投影新增 `dispute`（`dispute_id`/`status`/`reason`/`from_status`/`opened_at`/`resolved_at`/
  `records[]`/`can_review`），**不投影任何 `actor_id`**。

### 锁与幂等

- 商家处理：`商家 → 商家行 → 时段 → 订单 → 接车单 → 争议单 → 记录`，与 A5 顺序同向前缀。
- 车主复核：`车主 → 商家 → 时段 → 订单 → 接车单 → 争议单`，与 A4 车主决定同序。
- 两个接口都要求 UUID `Idempotency-Key`；同键同体重放原响应且仍重新校验权限，换键换语义。

### 最低验证

- `DisputeHttpTest`：角色白名单（车主/技师/绑定凭证拒绝）、缺键、正文多余字段、
  `REJECT` 缺原因、非本人或非本店 `404`、无数据库 `503`、`no-store`。
- `DisputeNoDatabaseTest`：无 `MYSQL_HOST` 时返回 `50300`，不抛 500。
- `JdbcOrderDisputesTest`：真实 MySQL 的完整闭环、R1–R5 拒绝矩阵、`43007`/`43008`、
  双审计与 `order_status_transition`、完整回滚、同键重放、并发复核最多一次恢复、
  时间线不落隐私字段、历史异常数据 fail-closed。
- 回归：`OrderStatusTest`、派工三个类、接车与车主决定两类，确认 `DISPUTED` 之外的判定未变。

## 小程序

- 新增 `apps/miniapp/src/services/dispute.js`：`handle`/`review` 两个写接口与严格形状校验，
  `pickup.js` 复用其中的争议投影校验。
- `pages/check/pickup-detail.vue`：争议区展示原因、时间线与状态；商家可提交处理记录，
  车主可在有处理记录后接受/不接受并二次确认；`can_review=false` 时不显示按钮。
- `pages/merchant/order-detail.vue` 与 `pages/order/detail.vue`：`DISPUTED` 文案改为指向接车单的
  争议处理入口，说明"是否恢复由车主复核决定"。
- 沿用 `createReservationWriteFlow` 的幂等重试、代次失效与 `onConflict` 刷新；
  页面隐藏/卸载/切换身份使旧请求与确认回调作废。
- `apps/miniapp/test/dispute.test.js`：协议、错误码文案、投影越界、`REJECT` 必填原因、
  同键重试与换键、确认回调守卫。

## 生成物与文档

仓库根目录依次运行 `python scripts/generate_traceability.py`、`python scripts/build_init_sql.py`、
`python scripts/generate_openapi.py`；`git diff --check` 并确认无意外差异。
`generate_openapi.py` 新增两个写接口的严格 schema、角色、幂等要求与错误码，并更新
`/api/check/pickup/{order}` 的 `dispute` 投影说明。

收口：`docs/progress/A5_6_DISPUTE_RESOLUTION_EXECUTION.md` 记录实际证据；同步
`CURRENT_STATUS.md`、`NEXT_STEPS.md`、`ROLE_GAP_ANALYSIS.md`、`docs/api/README.md`、
`ORDER_FULFILLMENT.md`、`PICKUP_OWNER_DECISION.md`、`TECHNICIAN_DISPATCH.md` 与 `docs/sql/SCHEMA.md`。
按生成检查 → 差异复核 → 提交 → 推送 → PR → 全部 CI → 记录最终结果收口；只有实际通过项写入验收证据。

## 验证命令

前端 `npm test --workspace @autocare/miniapp`、`npm run build:miniapp`、
`npm run build:h5 --workspace @autocare/miniapp`（两条构建不连跑）。后端在容器内
`mvn -Dapi.version=1.44 -Dtest=<类名> test`，完整真实数据库测试需要 Docker。
数据库结构用 `bash scripts/verify_mysql_schema.sh`。GitHub 六项 CI 为完整回归门禁。

## 不在本阶段范围

退款与取消争议订单（A7）、第二次异议（需重新设计）、超时自动处理、短信/微信推送通知
（当前通知形态是页面读取）、真实技师微信登录与真机页面联调（独立验收项）。
