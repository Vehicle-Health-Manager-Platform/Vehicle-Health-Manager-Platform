# A5 本机派工与本人接单端到端验收

日期：2026-10-08。范围：[派工与技师接口](../api/TECHNICIAN_DISPATCH.md)、[A5 规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md) §8 的 T12。

## 本次验证的边界

驱动链是**真实后端容器 + 真实隔离 MySQL**：真实 Spring 安全过滤器、真实 JWT 校验（签名、`jti` 会话有效、`app_id`、`binding_id`、`sub` 与员工/绑定/商家一致）、真实控制器与真实 SQL。业务响应没有使用替身。

会话是**测试桥接**：脚本用本机容器的 `JWT_SECRET` 自签 JWT 并写入 `auth_session`，代表不了真实微信/短信登录，也不代表真机页面联调。**接车**（七图上传、ClamAV、MinIO）沿用 A3 已验收的产物状态，由夹具建立 `check_in_completed_at` 与接车单行；**车主确认**与**派工、本人查询、接单**全部走真实接口。

夹具写入 `scripts/local_dispatch_e2e.cjs`，只在隔离库 `vehicle-auth-local-mysql-1`（Compose 项目 `vehicle-auth-local`）运行，必须带 `--allow-local-test-writes`；脚本启动时校验项目标识与迁移列，任何一项不符即拒绝运行。

## 运行前提

- 后端容器 `vehicle-auth-local-backend` 使用含 A5 的镜像（本次 `vehicle-auth/backend:a5-dispatch`），健康 `UP`，`0.0.0.0:18080`。
- 隔离库已应用 **V011**（`pickup_check.dispute_reason`、迁移 `note` 扩为 500 字）与 **V012**（`technician_assignment.assigned_by`、`accepted_at`），表总数 50 不变。本次按“先 `mysqldump` 备份、再执行迁移脚本、重复执行校验幂等”的顺序处理，备份留在 `test-results/`（受 Git 忽略）。
- 容器 env 提供 `JWT_SECRET` 与 `WECHAT_APP_ID`；脚本只读取、不打印。

## 复现步骤

```bash
# 1) 构建含 A5 的后端镜像（Maven 构建在容器内完成）
docker build -t vehicle-auth/backend:a5-dispatch backend

# 2) 备份并补齐隔离库迁移（V011、V012 均可重复执行）
docker exec -i vehicle-auth-local-mysql-1 sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  > test-results/mysql-backup-before-a5-$(date +%Y%m%d%H%M%S).sql
docker exec -i vehicle-auth-local-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < docs/sql/migrations/V011__pickup_owner_decision.sql
docker exec -i vehicle-auth-local-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < docs/sql/migrations/V012__technician_dispatch.sql

# 3) 用新镜像重建后端容器（保留原环境与端口；docker restart 不会重读环境）
docker stop vehicle-auth-local-backend && docker rm vehicle-auth-local-backend
docker run -d --name vehicle-auth-local-backend --network vehicle-auth-local_default \
  -p 0.0.0.0:18080:8080 --restart unless-stopped --env-file <本地私密 env> \
  vehicle-auth/backend:a5-dispatch

# 4) 端到端验收（可重复执行，每次先重置合成行）
node scripts/local_dispatch_e2e.cjs --allow-local-test-writes
```

## 合成身份

| 身份 | 编号 | 说明 |
| --- | --- | --- |
| 合成车主 | `user` 9201290 | 建单/接车单/确认的真实 OWNER 会话 |
| 本店商家 A | `staff_account` 9201201（`merchant` 9201201） | 候选查询、派工、派工详情 |
| 另一店商家 B | `staff_account` 9201202（`merchant` 9201202） | 跨店归属拒绝 |
| 本店技师甲 | `staff_account` 9201231，绑定 9201331 | 本人列表/详情/接单 |
| 本店技师乙 | `staff_account` 9201232，绑定 9201332 | 「另一技师拒绝」 |
| 他店技师 | `staff_account` 9201233，绑定 9201333（店 B） | 跨店不可见 |

绑定 `app_id` 取当前 `WECHAT_APP_ID`；合成订单 `synthetic-a5-positive` 走完整链路，另有 `synthetic-a5-no-checkin` 与 `synthetic-a5-no-confirm` 两条前置负例。

## 已执行结果

`passed: 37 / 37`（连续两次运行结果一致，控制台逐项输出 `ok`，失败会打印 `status` 与正文便于复核）。

- 前置链：车主确认走真实 A4 接口返回 200 且 `no-store`，`owner_confirm=1`、`confirm_at`、`owner_confirmed_at` 均落库；缺接车检查派工 409/43001；车主未确认派工 409/43003。
- 候选范围：本店候选含甲/乙且不含他店技师；他店商家只见本店技师；技师/车主/商家/无令牌调用跨角色接口分别为 403、403、403、401。
- 首次派工：200、`changed=true`、`ASSIGNED`/`RECEIVED`、`no-store`；订单保持 `RECEIVED` 且写入 `assigned_at`；商家详情显示「合成技师甲」；他店商家读本店派工 404。
- 本人链路：技师甲列表含目标单、详情 200 且 `can_accept=true`、`no-store`，投影不含 `user_id`/`vehicle_id`/`phone`/`vin`/`plate_no`/`verify_code`/`private`/`technician_id`；技师乙详情 404、列表为空；他店技师详情 404。
- 幂等与重复：同键同体重放返回原成功响应（解析后逐字段一致）、新键向同一技师重复派工 `changed=false`、新键换技师 409/40905、接单同键重放原响应、新键重复接单 `changed=false`、商家 JWT 不能接单 403。
- 落库证据：一条派工、订单 `IN_SERVICE`、一次订单迁移、派工与接单各一次 `audit_log`、`assigned_at` 与 `accepted_at` 均已写入。
- A5 边界：`repair_protection` 为 0，缺防护不阻断 A5；接单后 `can_accept=false`，`IN_SERVICE` 不等于施工完成（报工属 A6）。
- 会话失效：撤销合成会话后旧令牌 401。

## 过程中的两点发现

1. **本机隔离库此前只到 V010**，缺 V011。A4 车主确认会写 `pickup_check.dispute_reason`，缺列时该写入失败并被统一写入层封装为 `50300`（响应不暴露 SQL），表面现象与「后端不可用」一致。这是**本机环境缺迁移**，不是产品缺陷；按备份→执行迁移→重复执行校验补齐后恢复正常。脚本已把 V011/V012 列存在性纳入启动校验，缺列时给出明确报错而不是 503。
2. **接车单夹具必须带里程**。`PickupInspection` 的投影会计算 `mileage_delta`，接车单 `mileage` 为空时逐字段投影抛空指针，同样被封装成 `50300`。真实 A3 流程必然写入里程，因此这是夹具问题；已按真实接车单补 `mileage`/`mileage_source`/`mileage_baseline`/`fuel_level`/`damage_status`。

另有一处断言口径修正：`idempotency_record.response_body` 是 MySQL `JSON` 列，读回后键序被规范化，字符串比较会误判。验收改为解析后深度比较（含 `request_id`，确认重放返回的就是原响应）。

## 未验收项

真实技师微信登录、真机页面联调（真机调试按钮、开发者工具模拟器上的完整业务 UI）、正式短信、真实相机与测试证书下的直连图片预览继续独立验收，见[下一步规划](../progress/NEXT_STEPS.md)与[外部依赖](../progress/S0_DEPENDENCIES.md)。本次证据来自合成身份与本机隔离库，不代表上述任一场景通过。
