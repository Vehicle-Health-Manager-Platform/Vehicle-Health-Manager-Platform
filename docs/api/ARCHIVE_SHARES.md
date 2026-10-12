# 档案摘要分享 R3c3

全部接口须正式 OWNER JWT，检查未撤销且未过期的 auth_session 和正常 user。响应统一 ApiResponse、Cache-Control: no-store。商家、技师、运营 JWT 不可用。能力链接允许其他已登录车主转发读取。

| 接口 | 请求 | 返回 data |
|---|---|---|
| POST /api/archive/shares | UUID Idempotency-Key；`vehicle_id`、可选成对 `from/to`、`expires_in_hours`（1/24/168，默认24） | 分享元信息 |
| GET /api/archive/shares?vehicle_id=... | 本人车辆 | `list` 最近100条元信息 |
| GET /api/archive/shares/{id}/link | 本人有效分享 | `share_id,path,expires_at` |
| POST /api/archive/shares/{id}/revoke | UUID Idempotency-Key；`{}` | 撤销后的元信息 |
| POST /api/archive/share/view | `{"share_token":"64位小写十六进制能力令牌"}`，不需幂等键 | `summary,expires_at` |

元信息：`share_id,vehicle_id,created_at,expires_at,status`。状态 ACTIVE/EXPIRED/REVOKED。列表不含能力令牌。创建与撤销幂等回执和审计不含能力令牌。单车最多20个有效分享，快照最多500条，日期范围规则与私有报告相同。

摘要 v1 字段：`version,generated_at,from,to,registered_mileage,undated_count,entries`。每条仅 `archive_type,input_type,recorded_date,mileage,work_minutes,test_mode`。里程与日期可 null；保留测试标记；快照在创建时固定。无车牌、VIN、车型名称、标题、备注、配件文本、图片、签字、PDF、来源订单或内部 ID。不会授权访问原车主的档案、图片或报告接口。

400 参数错误；401 会话失效；403 非车主；404 非本人车辆/分享；409 上限；410 未找到、已撤销、过期、原账号停用或车辆归属失效；503 数据库/摘要不可用。撤销和读取使用分享行锁，成功读取写最小审计。源账号或车辆异常统一作为链接失效，不泄露归属。链接失效后旧摘要无法通过接口读取，无法追回之前的截图。

数据库仅保存 token_hash；public_id 是随机 UUID。用 JWT_SECRET 和独立 HMAC 域生成 256 位能力令牌，轮换 JWT_SECRET 令所有旧链接失效。取链接接口将令牌放在 `/pages/archive/shared?token=...` 原生路由中；接收请求正文传令牌。不得配置请求正文日志、不得持久化路由令牌到本地存储。真机与真实登录仍待后续验收。
