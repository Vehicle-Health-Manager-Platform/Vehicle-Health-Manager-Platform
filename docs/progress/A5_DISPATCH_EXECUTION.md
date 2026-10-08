# A5 商家派工与技师本人接单阶段总记录

日期：2026-10-08。范围：商家把本店已接车、车主已确认的订单派给本店有效技师，技师在本人工作台查看工单并接单开始施工。业务规则见[A5 规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)，接口见[派工与技师契约](../api/TECHNICIAN_DISPATCH.md)，步骤见[实施计划](../superpowers/plans/2026-10-08-a5-dispatch.md)。

## 子阶段与证据

| 子阶段 | 交付 | 证据 |
| --- | --- | --- |
| A5.1 | 规格、中文接口契约、实施步骤与验收矩阵 | [执行记录](A5_1_DISPATCH_SPEC_EXECUTION.md)，[PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36) |
| A5.2 | 六个后端接口、V012、本人接单进入施工、停用商家通用 `START_SERVICE` | [执行记录](A5_2_DISPATCH_BACKEND_EXECUTION.md)，[PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37) |
| A5.3 | 商家派工页、技师独立会话、本人工单/详情/接单界面 | [执行记录](A5_3_DISPATCH_UI_EXECUTION.md)，[PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38) |
| A5.4 | 身份变更、竞争、持久化载荷与历史异常验证 | [执行记录](A5_4_DISPATCH_VERIFICATION_EXECUTION.md)，[PR #39](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/39) |
| A5.5 | 本机端到端联调与阶段收口 | [执行记录](A5_5_DISPATCH_E2E_EXECUTION.md)、[本机验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)，[PR #40](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/40) |
| A5.6 | 争议处理记录、车主复核恢复、两个写接口与 V013 | [执行记录](A5_6_DISPUTE_RESOLUTION_EXECUTION.md)、[本机验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md)、[争议处理契约](../api/DISPUTE_RESOLUTION.md)、[争议处理规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md)，[PR #41](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/41) |

## 已实现的能力

- **一单一位技师**：首次派工写 `technician_assignment` 与 `order.assigned_at`，订单保持 `RECEIVED`；本人接单把派工置 `ACCEPTED`、订单置 `IN_SERVICE`，订单迁移与成功审计、幂等响应同事务。
- **归属与资格**：商家只列本店候选、只读本店派工；技师 JWT 必须是 `staff_account/TECHNICIAN`，服务层再次校验会话、员工、绑定、商家并复核当前 AppID。
- **前置不靠时间列伪造**：派工与接单都重新读取真实接车单与车主确认，缺接车检查 43001、未确认 43003、状态冲突 40905。
- **幂等与重复**：同键同体重放原响应、异体 `40001`、新键同技师重复派工 `changed=false`、新键换技师 40905、重复接单不再迁移。
- **最小投影**：技师读取不含车主 ID/手机号/车牌/VIN/核销码/付款流水/接车私有图；所有业务读写 `Cache-Control: no-store`。
- **A5 边界**：防护照片不阻断派工/接单，报工在 A6；`IN_SERVICE` 只表示已开始施工，不是完工。
- **争议处理（A5.6）**：异议后订单置 `DISPUTED`，派工/接单返回 `43007`；商家只能追加处理记录，**只有车主本人复核接受后订单才恢复到争议前的状态**（`from_status`，仅限 `PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`），接受前须已有处理记录（`43008`）。恢复后接车单 `owner_confirm=3`，与 `1` 同等满足派工前置。历史异议没有争议单，恢复一律拒绝（fail-closed）。

## 全阶段验证口径

- 后端：A5.2 接口与真实 MySQL 13 项、A5.4 并发与故障 11 项、A5.6 争议与回归 52 项，以及身份/预约/履约/接车/车主决定回归通过；后端全量测试在 GitHub CI 的 backend job 执行。
- 前端：小程序离线测试 147 项、微信与 H5 构建通过。
- 端到端：A5.5 与 A5.6 用合成身份接真实后端容器与真实隔离 MySQL，端到端 **69 项通过**（派工闭环 37 项 + 争议闭环 32 项）。
- 生成文件与契约一致（91 个操作），`git diff --check` 通过，文档相对链接有效。
- 六项 CI（web、miniapp、backend、schema-and-ocr、schema-mysql、compose-smoke）为完整回归门禁；各子阶段 PR 的最终检查结论以 PR 页面为准。

## 明确的未验收项

真实技师/车主微信登录、真机页面联调、正式短信、真实相机、测试证书下的直连图片预览与公网环境仍各自独立验收，见[外部依赖](S0_DEPENDENCIES.md)与[下一步规划](NEXT_STEPS.md)。A5 的合成会话与本地库证据不能替代上述场景。

## 后续

按[下一步规划](NEXT_STEPS.md)推进 **A6 防护与报工**（防护缺失不能报工、必要证据齐全才送核销、只能操作本人派工）。争议处理的历史遗留（A4 之前没有争议单的异议）仍由人工处理；退款/取消争议订单（A7）、第二次异议与超时自动处理不在本阶段范围。
