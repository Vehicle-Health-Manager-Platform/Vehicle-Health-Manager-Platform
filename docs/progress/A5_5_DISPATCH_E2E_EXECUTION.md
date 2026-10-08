# A5.5 本机端到端联调与 A5 收口执行记录

日期：2026-10-08。状态：本机端到端联调通过并上传阶段分支，PR 待合并；A5.2（PR #37）、A5.3（PR #38）、A5.4（PR #39）均未合并。

## 基线与范围

基于 A5.4 最终提交 `526c9b2` 建立 `codex/a5-dispatch-e2e`。前序 [PR #39](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/39)（A5.4）、[PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38)（A5.3）、[PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)（A5.2）与 [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)（A4）均未合并，阶段 PR 以直接前序分支为基线，不自动合并或部署。

A5.2–A5.4 证明的是协议、权限、幂等、竞争与回滚，但都在**进程内服务层或 MockMvc**：真实 JWT 校验链、Spring 安全过滤器、真实容器与真实本机 MySQL 从未在同一条链路跑通。A5.5 补这一段，对应规格验收矩阵 T12，并完成 A5 收口。

## 执行内容

1. **重建本机后端**：从本分支构建 `vehicle-auth/backend:a5-dispatch`（Maven 构建在容器内），用 `docker run` 在原 `vehicle-auth-local_default` 网络、原环境与 `0.0.0.0:18080` 重建 `vehicle-auth-local-backend`，健康 `UP`。`docker restart` 不会重读环境，必须重建。
2. **补齐隔离库迁移**：先 `mysqldump` 备份到 `test-results/`（受忽略），再执行 V011 与 V012 并重复执行校验幂等，表总数保持 50。
3. **新增本机端到端脚本** `scripts/local_dispatch_e2e.cjs`：合成车主/商家/两名本店技师/他店身份，走真实 HTTP 完成「车主确认 → 候选 → 派工 → 本人查询 → 接单」，并验证另一技师、他店与跨角色拒绝、幂等与重复语义、`no-store`、最小投影、双审计与 A5 边界。合成会话是测试桥接，不当作真实登录通过。
4. **收口文档**：新增本记录与[本机派工验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)、[A5 阶段总记录](A5_DISPATCH_EXECUTION.md)；同步计划、规格、API 与进度入口。

## 实际验证

`node scripts/local_dispatch_e2e.cjs --allow-local-test-writes`：**37 项全部通过**，连续两次运行结果一致。明细见[本机派工验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md#已执行结果)。要点：

- 真实 A4 车主确认 200 且落库；缺接车检查派工 43001、未确认派工 43003。
- 本店候选只含本店已绑定技师；技师/车主/商家/无令牌跨角色访问为 403/403/403/401。
- 首次派工 `changed=true`、订单保持 `RECEIVED` 并写 `assigned_at`；本人详情 `can_accept=true`、投影无车主 PII 与车辆/核销/私有字段；另一本店技师与他店技师 404。
- 接单 `ACCEPTED`/`IN_SERVICE`，一条迁移、派工与接单各一次审计，`accepted_at` 落库；同键重放原响应、新键换技师 40905、新键重复接单 `changed=false`。
- 缺防护（`repair_protection=0`）不阻断 A5；接单后 `can_accept=false`；撤销会话后旧令牌 401。

## 过程中的发现

1. **本机隔离库停在 V010**，缺 V011；A4 车主确认写 `pickup_check.dispute_reason` 因缺列失败，被统一写入层封装为 `50300`，现象与「后端不可用」一致。属**本机环境缺迁移**而非产品缺陷；备份后补齐 V011 恢复。脚本已把 V011/V012 列存在性纳入启动校验。
2. **接车单夹具必须带里程**：投影会计算 `mileage_delta`，`mileage` 为空时抛空指针并被封装成 `50300`。真实 A3 流程必然写里程，属夹具问题，已按真实接车单补齐。
3. **幂等响应比较不能比字符串**：`idempotency_record.response_body` 是 MySQL `JSON` 列，读回后键序被规范化。验收改为解析后深度比较（含 `request_id`），确认重放返回原响应。

## 未验收项

真实技师微信登录、真机页面联调、正式短信、真实相机、测试证书下的直连图片预览与公网环境继续独立验收。本阶段证据来自合成身份与本机隔离库。

## GitHub 验证

待提交与推送后补充（阶段 PR、六项 CI 与最终提交）。
