# A5.4 派工与本人接单：安全与竞争验证执行记录

日期：2026-10-08。状态：本机验证通过并上传阶段分支，PR 待合并；A5.3 界面（PR #38）与 A5.2 后端（PR #37）均未合并。本阶段只新增测试与文档，不改业务代码。

## 基线与范围

基于 A5.3 最终提交 `8d58c2a` 建立 `codex/a5-dispatch-verify`。前序 [PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38)（A5.3）、[PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)（A5.2）与 [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)（A4）均未合并，阶段 PR 以直接前序分支为基线，不自动合并或部署。

A5.2 已提供首次派工/接单、前置拒绝、资格矩阵、幂等与新键重复、派工与接单回滚、两人派工竞争、本人同键并发接单、解绑竞争与迁移重复执行。A5.4 只补 A5.2 未覆盖的部分，对应规格验收矩阵的 T04、T06、T07、T09、T11。

## 计划用例

新增 `backend/src/test/java/com/autocare/platform/order/JdbcTechnicianAssignmentsVerificationTest.java`，沿用 A5.2 的 Testcontainers 真实 MySQL（V001–V012 装配、合成数据、`DataSourceTransactionManager` 与同构 `ReservationStore`）。

| 用例 | 覆盖 | 断言要点 |
| --- | --- | --- |
| 重发员工码与派工并发 | T07、T09 | 无死锁与超时；`issue` 撤销绑定后派工 404 且不回填 `unbound_at`；派工先成功时，绑定撤销后该技师查询/接单为 401 |
| 回收员工码与本人接单并发 | T07、T09 | 结果只能是 200 或 401；撤销后原成功键重放仍 401；已有派工不消失（A5-D10） |
| 停用账号与本人接单并发 | T07 | 禁用提交后接单 401；派工保留，订单状态与迁移数不被改写 |
| 撤销商家会话与派工并发 | T07、T09 | 结果只能是 200 或 401；撤销后原成功键重放 401；派工至多一条 |
| 本人不同键并发接单 | T06、T07 | 两次均返回成功快照，但订单迁移仅一条、接单审计仅一条，`accepted_at` 只写一次 |
| 本人与他人并发接单 | T04、T07 | 本人成功、他人 404；不产生第二条迁移，他人工单列表为空 |
| 持久化载荷不含隐私 | T11 | `idempotency_record.response_body` 与 `audit_log.before_state/after_state` 不含车主 PII、车辆/核销码与私有图引用 |
| 历史异常冻结 | T11 | 未知状态、`ACCEPTED` 无 `accepted_at`、`ASSIGNED` 有 `accepted_at`、缺 `assigned_at`、订单已删除、派工跨店、`assigned_by` 他店：一律冲突/404，行内容逐字段不变，无新增派工与审计 |
| 多轮混合并发无死锁 | T07 | 连续多轮混合竞争全部在事务超时内完成，`SHOW ENGINE INNODB STATUS` 的 `LATEST DETECTED DEADLOCK` 段保持 `(not yet)` |

## 预期命令

- 后端（Maven/JDK17 容器，Docker API 1.44，`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`、`TESTCONTAINERS_RYUK_DISABLED=true`）：先跑本阶段新类与 A5.2 相关类，再跑身份/预约/履约/接车/车主决定回归。
- 前端：`npm test --workspace @autocare/miniapp`、`npm run build:miniapp`、`npm run build:h5 --workspace @autocare/miniapp`。
- 仓库：`python scripts/generate_traceability.py`、`build_init_sql.py`、`generate_openapi.py` 后检查无意外差异，`git diff --check`，文档相对链接检查。
- GitHub：推送阶段分支，创建指向前序分支的 PR，核对六项 CI。

测试数据全部合成，不使用客户身份、真实图片或付款流水。

## 实际验证

新增测试类 `backend/src/test/java/com/autocare/platform/order/JdbcTechnicianAssignmentsVerificationTest.java`，11 项全部通过（失败/错误/跳过均 0）。覆盖：

| 用例 | 结果 |
| --- | --- |
| 重发员工码与派工并发（5 轮） | 通过：派工只可能是成功或 404；`issue` 撤销绑定后本人查询 401，且不新增幂等与状态迁移记录 |
| 回收员工码与本人接单并发（5 轮） | 通过：接单只可能是成功或 401；撤销后原成功键重放仍 401；派工行保留 |
| 停用账号与本人接单并发（3 轮） | 通过：结果与订单状态自洽（成功则 `IN_SERVICE`、失败则 `RECEIVED`） |
| 撤销商家会话与派工并发（3 轮） | 通过：撤销后原成功键重放 401，派工至多一条 |
| 本人不同键并发接单 | 通过：两次均返回成功快照，订单迁移与接单审计各一次，幂等记录三条（派工 1 + 接单 2） |
| 本人与他人并发接单 | 通过：本人 200、同店他人 404、他店 404，迁移仅一次 |
| 幂等载荷与审计不含隐私 | 通过：两条幂等响应与两条审计载荷均不含车主 PII、车辆/核销码与私有图引用 |
| 历史不一致派工冻结（5 类） | 通过：未知状态、`ACCEPTED` 缺 `accepted_at`、`ASSIGNED` 带 `accepted_at`、缺 `assigned_by`、缺 `assigned_at` 全部 40905，行内容逐字段不变，无新增审计 |
| 跨店归属历史冻结（3 类） | 通过：派工商家不符与 `assigned_by` 他店返回 40905，技师属他店时本人视角 404，均不改写历史 |
| 订单已删除的历史派工 | 通过：商家与技师视角均 404，不自动清除或修复历史行 |
| 多轮混合并发无死锁（6 轮） | 通过：两人派工与「本人接单 + 员工码重发」混合竞争全部在事务超时内完成，`LATEST DETECTED DEADLOCK` 段保持 `(not yet)` |

相关回归（同一容器环境）：

- `JdbcTechnicianAssignmentsTest` 13 项、`TechnicianAssignmentsHttpTest` 7 项、`TechnicianAssignmentsNoDatabaseTest` 2 项、`JdbcIdentityRepositoryTest` 6 项、`JdbcReservationsTest` 11 项、`JdbcOrderFulfillmentTest` 13 项、`JdbcPickupInspectionTest` 12 项、`MerchantOrdersHttpTest` 5 项、`OrderStatusTest` 7 项，合计 **76 项通过**，失败/错误/跳过均 0。
- `npm test --workspace @autocare/miniapp`：**140 项通过**，失败/跳过均 0（本阶段未改前端，属回归）。
- `npm run build:miniapp` 与 `npm run build:h5 --workspace @autocare/miniapp`：通过。
- `python scripts/generate_traceability.py`、`build_init_sql.py`、`generate_openapi.py`：重新生成后无意外差异（89 个操作不变）。
- 文档相对链接 361 条全部有效；`git diff --check` 通过。

本机命令（Windows，Maven/JDK17 容器 + Docker API 1.44）：

```
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v vehicle-auth-local_maven_cache:/root/.m2 \
  -v <repo>:/workspace -w /workspace/backend \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  maven:3.9-eclipse-temurin-17 \
  mvn -Dapi.version=1.44 -Dtest=JdbcTechnicianAssignmentsVerificationTest test
```

## 过程中的两点修正

1. **`SHOW ENGINE INNODB STATUS` 需要 `PROCESS` 权限**：Testcontainers 的业务连接是 `test` 用户，读取死锁报告直接被拒。改为用 `root` 连接（`mysql.getPassword()`）单独建一个只用于读该报告的 `JdbcTemplate`，业务断言仍走原有连接。
2. **跨店历史派工的两种拒绝路径不同**：派工侧（商家读、再次派工）走归属复核返回 `40905`；技师侧在按本人、本店过滤的查询里**根本看不到该资源**，返回 `404`，而不是「看到但归属不符」。测试按两条路径分别断言，避免把 404 写成 409。

## 未验收项

真实技师微信登录、真机页面联调、与真实后端/MySQL 的端到端「派工 → 本人查询 → 接单」属 **A5.5**；本阶段证据来自合成身份与 Testcontainers 数据库，不代表真实登录或页面联调通过。正式短信、真实相机、公网环境继续独立验收。
