# A5.6 争议处理、车主复核与恢复执行记录

日期：2026-10-08。状态：规格、迁移、后端、小程序、测试与本机端到端联调全部完成并上传阶段分支，[PR #41](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/41) 待合并；A5.2（PR #37）、A5.3（PR #38）、A5.4（PR #39）、A5.5（PR #40）均未合并。

## 基线与范围

基于 A5.5 最终提交 `cde1cf2` 建立 `codex/a5-dispute`。前序 [PR #40](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/40)（A5.5）、[PR #39](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/39)（A5.4）、[PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38)（A5.3）、[PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)（A5.2）、[PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)（A4）均未合并，阶段 PR 以直接前序分支为基线，不自动合并或部署。

A4 只交付了「第一次决定」：车主提出异议后订单置 `DISPUTED`，`OrderStatus.actions(DISPUTED)` 为空，派工与施工被永久阻断，商家没有可执行动作，车主没有复核入口。规格 §2 早已把 `DISPUTED → 前一状态` 划给「争议解决」，但该流程一直缺失。A5.6 补齐这一段，使 A4 的异议不再是死路。

业务基线见[争议处理规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md)，接口见[争议处理契约](../api/DISPUTE_RESOLUTION.md)，实施计划见[中文计划](../superpowers/plans/2026-10-08-dispute-resolution.md)。

## 执行内容

1. **迁移 V013**：新增 `order_dispute`（`uk_order` 保证一单一争议、记录争议前状态 `from_status` 与开启人/时间）与 `order_dispute_record`（只追加的时间线，`idx_dispute`）；把 `pickup_check.owner_confirm` 注释扩为 `0待1确认2异议3争议已解决`。建表用 `CREATE TABLE IF NOT EXISTS`、注释改动用 `information_schema` 条件判断，可重复执行。**不回填历史异议**：A4 之前没有争议单的订单继续没有恢复入口（fail-closed），不猜历史。
2. **后端**：新增 `OrderDisputes`（`handle` 追加处理记录、`review` 复核）与 `OrderDisputesController`（两个 `@PostMapping`），全部走 `WriteIntegrityService.execute`；`OrderStatus` 增加**只读**的 `canResume(DISPUTED, target)`（仅允许回到 `PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`）与审计动作名 `ORDER_DISPUTE_RESOLVE`，**不动** `can()`/`MOVES`；`PickupInspection` 明确 `owner_confirm` 取值常量与 `ownerConfirmed()`，异议分支写入争议单，投影新增 `dispute`（`records`/`can_review`）；`TechnicianAssignments` 的 `DISPUTED` 由通用 `40905` 改为 `43007`。
3. **小程序**：新增 `services/dispute.js`（两个写接口与严格投影校验、`40905`/`43007`/`43008` 文案）；`pages/check/pickup-detail.vue` 重写争议区（商家提交处理记录、车主接受/不接受复核，全部二次确认 + `stillValid(actor,version)` 守卫）；商家与车主订单详情页 `DISPUTED` 文案指向接车单争议入口。
4. **收口文档**：新增本记录与[本机争议验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md)；同步契约、规格、计划、[接口索引](../api/README.md)、[履约状态机](../api/ORDER_FULFILLMENT.md)、[车主决定](../api/PICKUP_OWNER_DECISION.md)、[派工契约](../api/TECHNICIAN_DISPATCH.md)、[数据模型](../sql/SCHEMA.md)与进度入口。

## 实际验证

### 后端（容器内 Maven + 真实 MySQL / Testcontainers）

| 测试类 | 覆盖 | 结果 |
| --- | --- | --- |
| `JdbcOrderDisputesTest` | 完整闭环、追加与同键重放、R1–R5 拒绝矩阵、`43007`/`43008`、双审计与 `order_status_transition`、完整回滚、并发复核最多一次恢复、混合处理/复核/派工无死锁、投影与落库载荷无身份字段 | 11/11 |
| `DisputeHttpTest` | 角色白名单、缺键、正文多余字段、`43008` 透传、`no-store`、参数在触库前拒绝 | 6/6 |
| `DisputeNoDatabaseTest` | 无 `MYSQL_HOST` 时返回 `50300` 而非 500 | 2/2 |
| `OrderStatusTest` | 新增「只有争议能恢复且只能回到前一状态」 | 8/8 |
| `JdbcPickupInspectionTest` | V013 重复执行、异议落库与投影 `dispute` | 12/12 |
| `JdbcTechnicianAssignmentsTest` | `DISPUTED` 断言由 `40905` 改为 `43007` | 13/13 |

本次争议与回归批次合计 **52/52 通过**（`11 + 6 + 2 + 8 + 12 + 13`），无失败、无错误、无跳过。

### 小程序

`npm test --workspace @autocare/miniapp`：**147 项通过**；`npm run build:miniapp`（mp-weixin）与 `npm run build:h5 --workspace @autocare/miniapp` 均构建成功，产物 `dist/build/mp-weixin/services/dispute.js` 含两个争议接口。

### 数据库结构

`bash scripts/verify_mysql_schema.sh`：**52 张表**（V012 后 50 张 + V013 两张），V013 重复执行后 `order_dispute.uk_order` 与 `order_dispute_record.idx_dispute` 保持正确、`pickup_check.owner_confirm` 注释含 `3`。

### 生成物

`python scripts/generate_traceability.py` 141 行、`python scripts/build_init_sql.py` 39 表基线、`python scripts/generate_openapi.py` **91 operations**（新增 `DisputeResult` 与两个争议操作，更新接车单 `dispute` 投影与派工/接单的 409 说明）。重跑三个生成脚本无意外差异，`git diff --check` 通过。

### 本机端到端（真实容器 + 真实隔离 MySQL）

`node scripts/local_dispatch_e2e.cjs --allow-local-test-writes`：**69 项全部通过**，连续两次运行结果一致。在 A5.5 的 37 项派工闭环之上新增 32 项争议检查，明细见[本机争议验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md#已执行结果)。要点：

- 车主异议经真实 A4 接口写入争议单（`OPEN`/`RECEIVED`、接车单 `owner_confirm=2`、订单 `DISPUTED`）；同键重放不新建第二条争议单。
- 争议未解决时派工 `409/43007`、接单被拒；商家未提交处理记录时车主复核 `409/43008`。
- 商家提交处理记录 `record_count=1/HANDLE/OPEN`、`no-store`、响应不含身份 ID、同键重放原快照、新键追加第二条。
- 车主读接车单时间线只投影 `action`/`note`/`created_at`，响应不含车主或商家员工 ID，`can_review=true`。
- 不接受复核保持 `OPEN`/`DISPUTED` 并继续阻断派工；接受复核恢复 `RECEIVED`/`RESOLVED`/`owner_confirm=3`，同键重放原快照，已解决后再复核 `409/40905`。
- 恢复后可正常派工 `ASSIGNED` 并由技师接单进入 `IN_SERVICE`；两次处理、一次不接受、一次接受的审计与记录齐备；`idempotency_record` 响应载荷与争议审计的落库前后快照均不含身份字段。

## 过程中的发现

1. **本机隔离库停在 V012**，缺 V013。脚本启动即校验争议表与 `owner_confirm` 注释，缺迁移直接报错，避免把「缺列」误判成「后端不可用」。本次按「先 `mysqldump` 备份、再执行迁移、重复执行校验幂等」处理，备份留在 `test-results/`（受 Git 忽略）。
2. **镜像必须含本阶段代码**：旧标签 `a5-dispatch` 会让新接口 404，但登录与旧接口正常、很难察觉。本次重建 `vehicle-auth/backend:a5-dispute` 后用 `docker run` 重建容器（`docker restart` 不重读环境）。
3. **异议接口的路径不含 `dispute`**：异议仍走 `POST /api/check/pickup/confirm`，因此「争议路径的幂等记录」口径只覆盖处理与复核（4 条）。验收断言据此校正为 `>= 4`，不把异议单算进该口径。

## 未验收项

真实技师/车主微信登录、真机页面联调、正式短信、真实相机与测试证书下的直连图片预览继续独立验收。本阶段证据来自合成身份与本机隔离库；合成会话是测试桥接，不代表真实登录。

## 不在本阶段范围

退款与取消争议订单（A7）、第二次异议（需重新设计）、超时自动处理、短信/微信推送通知（当前通知形态是页面读取）。

## GitHub 验证

阶段分支 `codex/a5-dispute` 已推送，[PR #41](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/41) 以 `codex/a5-dispatch-e2e` 为基线（A5.5 未合并），只展示本子阶段差异。六项 CI（web、miniapp、backend、schema-and-ocr、schema-mysql、compose-smoke）结果见 PR 页；端到端脚本需要本机容器与隔离库，属 opt-in 本机验收，不进 CI。
