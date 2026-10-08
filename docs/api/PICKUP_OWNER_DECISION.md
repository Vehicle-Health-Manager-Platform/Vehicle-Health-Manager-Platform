# A4 车主确认接车单与异议

车主在本人订单处于 `RECEIVED` 时打开七图接车单，可确认或提出异议。接车单的照片、里程和损伤证据保持不可修改。

`POST /api/check/pickup/confirm` 只接受本人 OWNER、UUID `Idempotency-Key` 和正文 `{ "order_id": 123, "decision": "CONFIRM" }` 或 `{ "order_id": 123, "decision": "DISPUTE", "reason": "车门划痕记录不符" }`。异议原因去除首尾空白后须为 1–500 字；确认时不得填写原因。未知字段、目标状态字段、其他角色和他人订单均被拒绝。

确认将 `pickup_check.owner_confirm` 设为 1，写 `confirm_at` 与 `order.owner_confirmed_at`，订单保持 `RECEIVED`。这只满足后续派工的车主确认前置；A5 的派工前置仍须成立。异议将 `owner_confirm` 设为 2、保存原因，并把订单从 `RECEIVED` 迁至 `DISPUTED`。商家订单详情和接车单显示异议及原因，施工动作由状态矩阵拒绝；争议处理与恢复由后续流程实现。

两种决定均在同一事务中写 `audit_log`；异议迁移另写 `order_status_transition`。失败整体回滚；同键同正文重放原响应，不新增审计。同一接车单只接受一次决定，新键重复或相反决定返回 `40905`。会话失效 `401`，角色不符 `403`，非本人或单据不存在 `404`，输入无效 `40001`，状态冲突 `40905`，数据库不可用 `50300`。响应及图片访问继续使用 `Cache-Control: no-store`。

V011 为接车单增加 `dispute_reason`，并将状态迁移审计的 `note` 扩为 500 字，保留完整异议原因。新 Compose 数据卷自动应用；已有数据卷按迁移脚本补齐。真实相机、真机和本机证书下直连预览仍按独立验收项处理。
