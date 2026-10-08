# A5.6 本机争议处理与恢复端到端验收

日期：2026-10-08。范围：[争议处理契约](../api/DISPUTE_RESOLUTION.md)、[争议处理规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md) §1 的恢复条件 R1–R5 与 §4 的错误码。

## 本次验证的边界

驱动链是**真实后端容器 + 真实隔离 MySQL**：真实 Spring 安全过滤器、真实 JWT 校验、真实控制器与真实 SQL。业务响应没有使用替身。

会话是**测试桥接**：脚本用本机容器的 `JWT_SECRET` 自签 JWT 并写入 `auth_session`，代表不了真实微信/短信登录，也不代表真机页面联调。**车主异议、商家处理记录、车主复核**全部走真实接口；争议单与时间线落库、审计与幂等载荷由脚本直接读库核对。

脚本 `scripts/local_dispatch_e2e.cjs` 只在隔离库 `vehicle-auth-local-mysql-1`（Compose 项目 `vehicle-auth-local`）运行，必须带 `--allow-local-test-writes`；启动时校验项目标识、V011/V012 列与 V013 争议表及 `owner_confirm` 注释，任何一项不符即拒绝运行。

## 运行前提

- 后端容器 `vehicle-auth-local-backend` 使用含 A5.6 的镜像（本次 `vehicle-auth/backend:a5-dispute`），健康 `UP`，`0.0.0.0:18080`。
- 隔离库已应用 **V013**（`order_dispute`、`order_dispute_record`，`pickup_check.owner_confirm` 注释含 `3`），表总数 **52**。本次按“先 `mysqldump` 备份、再执行迁移脚本、重复执行校验幂等”的顺序处理，备份留在 `test-results/`（受 Git 忽略）。
- 容器 env 提供 `JWT_SECRET` 与 `WECHAT_APP_ID`；脚本只读取、不打印。

## 复现步骤

```bash
# 1) 构建含 A5.6 的后端镜像（Maven 构建在容器内完成）
docker build -t vehicle-auth/backend:a5-dispute backend

# 2) 备份并补齐隔离库迁移（V013 可重复执行）
docker exec -i vehicle-auth-local-mysql-1 sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  > test-results/mysql-backup-before-a5-6-$(date +%Y%m%d%H%M%S).sql
docker exec -i vehicle-auth-local-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE"' \
  < docs/sql/migrations/V013__dispute_resolution.sql

# 3) 用新镜像重建后端容器（保留原环境与端口；docker restart 不会重读环境）
docker stop vehicle-auth-local-backend && docker rm vehicle-auth-local-backend
docker run -d --name vehicle-auth-local-backend --network vehicle-auth-local_default \
  -p 0.0.0.0:18080:8080 --restart unless-stopped --env-file <本地私密 env> \
  vehicle-auth/backend:a5-dispute

# 4) 端到端验收（可重复执行，每次先重置合成行）
node scripts/local_dispatch_e2e.cjs --allow-local-test-writes
```

## 合成身份

沿用 [A5 本机派工验收](LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md#合成身份)的合成车主/商家/技师身份，新增合成订单 `synthetic-a5-dispute`（`order.id=9201404`，状态 `RECEIVED`，带一条 `owner_confirm=0` 的接车单）。

## 已执行结果

`passed: 69 / 69`（连续两次运行结果一致）。共 69 项，其中前 37 项是 A5.5 的派工闭环（车主确认→候选→派工→本人查询→接单），**新增 32 项争议检查**如下：

| # | 检查 | 断言 |
| --- | --- | --- |
| 1 | 车主提出异议 200 | `POST /api/check/pickup/confirm`，`decision=DISPUTE` 真实写库 |
| 2 | 异议响应 no-store | `Cache-Control: no-store` |
| 3 | 异议写库 | 争议单 `OPEN`/`from_status=RECEIVED`、接车单 `owner_confirm=2`、订单 `DISPUTED` |
| 4 | 异议同键重放 | 不新建第二条争议单（`order_dispute` 计数仍为 1） |
| 5 | 争议未解决时派工 | `409 / 43007` |
| 6 | 争议未解决时接单 | 被拒（`404` 或 `409`） |
| 7 | 商家未提交处理记录时复核 | `409 / 43008` |
| 8 | 商家 JWT 不能复核争议 | `403` |
| 9 | 技师 JWT 不能复核争议 | `403` |
| 10 | 车主 JWT 不能提交商家处理记录 | `403` |
| 11 | 他店商家不能提交处理记录 | `404` |
| 12 | 空说明的处理记录 | `400` |
| 13 | 商家提交处理记录 200 | `record_count=1`、`last_action=HANDLE`、`OPEN`/`DISPUTED`、`changed=true` |
| 14 | 处理记录响应 no-store | `no-store` |
| 15 | 处理记录响应不含身份 ID | 正文无 `actor_id`/`staff_id`/`owner_id` |
| 16 | 同键同体重放处理记录 | 返回原成功快照（深度比较） |
| 17 | 新键追加第二条处理记录 | `record_count=2` |
| 18 | 车主读接车单 | 时间线两条 `HANDLE`、`can_review=true` |
| 19 | 接车单投影不含身份 ID | 正文无车主与商家员工 `id` |
| 20 | 时间线只投影动作/说明/时间 | 每条键恰为 `action,created_at,note` |
| 21 | 车主不接受复核 | `OPEN`/`DISPUTED`、`record_count=3`、`changed=true` |
| 22 | 不接受后仍阻断派工 | `409 / 43007` |
| 23 | 车主接受复核 200 | 恢复 `RECEIVED`/`RESOLVED`/`owner_confirm=3`、`last_action=ACCEPT` |
| 24 | 复核响应 no-store | `no-store` |
| 25 | 恢复写库 | `RESOLVED`+`resolved_at`/`resolved_by`、`owner_confirm=3`、订单 `RECEIVED`、一次 `ORDER_DISPUTE_RESOLVE` 迁移 |
| 26 | 同键重放复核 | 返回原成功快照 |
| 27 | 已解决后再复核 | `409 / 40905` |
| 28 | 恢复后可正常派工 | `200 / ASSIGNED / RECEIVED` |
| 29 | 恢复后技师可正常接单 | `200 / IN_SERVICE` |
| 30 | 审计与记录齐备 | 两次处理、一次不接受、一次接受；`order_dispute_record` 共 4 条 |
| 31 | 幂等响应载荷 | `idempotency_record` 中争议路径 4 条且 `response_body` 不含 `actor_id` |
| 32 | 审计落库快照 | `audit_log` 的 `before_state`/`after_state` 不含 `actor_id` |

最后一项（第 33 组）为 A5.5 既有项：撤销合成会话后旧令牌 `401`。

## 失败复核提示

控制台逐项输出 `ok`，失败会打印 `status`、错误码与正文，便于直接对照[争议处理契约](../api/DISPUTE_RESOLUTION.md)的错误码表定位。
