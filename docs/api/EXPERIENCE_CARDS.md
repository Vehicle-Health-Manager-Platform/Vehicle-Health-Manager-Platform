# A7.2c1 私有经验卡片 API

仅正式 OWNER。自动生成默认 DRAFT；授权仅进入 PENDING_REVIEW，当前没有批准或公开接口，不写 community_content，不进入推荐、评分或 AI。

| 方法 | 路径 | 请求 |
| --- | --- | --- |
| GET | /api/experience-cards | vehicle_id 必选，page 默认1，page_size 默认20，1–50；未知/重复参数拒绝 |
| POST | /api/experience-cards/{id}/consent | 严格 `{"agree":true,"consent_version":"experience-v1"}` |
| POST | /api/experience-cards/{id}/withdraw | 严格 `{}` |

POST 必须 UUID Idempotency-Key，无 query。重复键、尾随 JSON、多余字段、隐式类型转换均拒绝。全部响应 no-store，统一 ApiResponse。

列表 data 为 `{items,total,page,page_size}`；写响应 data 为 `{card}`。卡片：card_id、vehicle_id、order_id、archive_id（全部仅本人私有来源）、固定 title、status、revision、test_mode、consent_version、consented_at、withdrawn_at、summary。summary 严格五字段：version=1、work_minutes、part_kinds（种类数不是件数）、no_parts、recorded_month（YYYY-MM）。没有原文、配件详情、图片或任何签名链接。授权/撤回时间 UTC ISO 或 null。

真实 DRAFT/WITHDRAWN 可明确授权进入 PENDING_REVIEW；撤回任何本阶段状态进入 WITHDRAWN 并清除有效授权，原档案与评价保留；允许新键重新授权。相同状态操作不再递增 revision 或重复业务审计；同键缓存仍复核身份与来源，卡片变更后旧操作键拒绝 45002，必须刷新后新确认。测试卡片禁止授权。授权时冻结来源/当前档案/施工证据/核销付款完整一致；撤回不要求来源仍完整。

错误：40001 参数，40100 会话失效，40300 角色，40400 非本人/失效车辆，45001 测试或来源不可信，45002 旧操作键与当前版本不符，50300 数据库或事务失败（原键原载荷重试）。

## 发布与恢复

先备份并迁移 V018、后端和页面，再显式开启 EXPERIENCE_CARD_ENABLED（默认 false）。它仅控制新归档事务生成，不控制既有卡片查看/撤回。归档消费者 SERVICE_ARCHIVE_ENABLED 与可靠任务保持原配置；新归档与卡片、审计原子提交，失败共用 PENDING/attempts/next_attempt_at 重试。同订单/档案唯一，无历史回填。V018 可重复执行，累计59表。

审核发布在 A7.2c2 建设运营身份与权限后实施。尚未公开的私有元数据不得直接作为公共 DTO；测试卡片永久排除，真实公开内容需重核车主授权、来源和脱敏规则。
