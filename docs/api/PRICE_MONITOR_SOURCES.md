# R8b3c1 报价版本的价格来源依据

2026-10-16。用户已确认按具体门店报价版本确认内容、单位、车型适用说明、来源、有效期及含边界区间；来源变化未知、偏离仅需核对。c1 管理配置，c2 提供[单独只读比较](PRICE_COMPARISONS.md)。当前能力标志 true，原永久创建回执可保留 false；均不代表依据当前有效。V056先迁移，116表，整R8完成前不上传。

## 六API

- GET `/api/admin/price-monitor/quotes?page=1&page_size=20`：当前有效门店/项目的上架报价来源。
- GET `/api/admin/price-monitor/policies?page=1&page_size=20`：依据列表。
- GET `/api/admin/price-monitor/policies/{id}`：原冻结依据详情。
- POST `/api/admin/price-monitor/policies`：新建草稿。
- PUT `/api/admin/price-monitor/policies/{id}`：CAS草稿全量修订。
- PUT `/api/admin/price-monitor/policies/{id}/status`：CAS发布或停止。

仅有效运营与独立can_price_monitor，其他权限不替代。全部no-store；列表仅单值page/page_size且固定20，详情/写拒绝query。写入UUID Idempotency-Key，严格字段/重复/尾随/类型校验。400输入/键冲突，401会话，403权限，404当前来源/依据不存在，409来源/版本/时间变化或已有发布，503无库/事务失败。

## 来源契约

每候选含quote_id/state/source/source_hash。source含quote_id、quote_version_id、quote_version、price、merchant（id/name/region_code）、project（id/revision/project_name/service_content/quality_standard/source_note）。READY时完整正数报价与哈希；UNKNOWN时哈希null，旧版本号可null，遗留非正价格保留为未知来源显示，不能确认。

READY要求最新报价版本与当前价格/上架一致、版本时间不晚于观察时点、原员工标识非空、有唯一匹配且未软删除的MERCHANT_PROJECT_SAVE审计、该凭证也是最后一次报价保存审计，并且回执的报价ID/版本ID/修订/价格/状态一致，项目有来源及服务内容。迁移旧初始版本不凭空提升为已核验，删除最新版本不能退回旧证据。哈希涵盖返回来源、版本时点、原员工及审计编号；不输出私人身份或审计正文。

## 创建与修订

精确字段quote_id/source_hash/lower_price/upper_price/price_unit/applicability_note/source_note/scope_confirmed/valid_from/valid_to；修订加expected_revision。价格为0.01–99999999.99两位正数字符串，下限≤上限、含边界。单位1–32字，适用及来源说明2–256字，去首尾空白且无控制字符；scope_confirmed须true。时间UTC整秒字符串，截止大于生效，保存/发布时生效仍须未来。页面使用北京时间输入并精确转换，不能猜无效日期。

客户端不提交来源快照；服务器按当前来源重新核对哈希，冻结source_snapshot。项目参考价不直接成为限价，运营适用文字不冒充结构化车辆适配模型。

## 状态与恢复

DRAFT可CAS修订；发布再次核验来源与窗口后冻结，仅可STOPPED且不能重开。每报价最多一PUBLISHED，即使到期也须显式停止再替换。新报价版本应新建依据。停止不要求原门店/项目还可用，旧快照保留。

写与成功审计/通用幂等同事务；创建另有永久主体/键/规范化正文哈希/原结果。通用24小时回执过期、来源失效或状态变化后仍可按原正文/键取得原创建结果，不重复创建；重放先复核当前独立权限。同键改正文400；审计失败整体回滚。运营账号→门店→标准项目→报价/版本→政策锁序与既有报价维护保持一致，唯一发布约束防并发双生效。

响应为创建字段加id/source_snapshot/revision/status/rule_version（R8B_PRICE_SOURCE_V1）、created_at/published_at/stopped_at及布尔comparison_available，不返回创建键/哈希/原审计。当前响应为true，c1永久原创建回执可保持false；能力标志不参与冻结业务证据核验。永久创建重放返回原草稿结果，当前状态另GET读取，比较另GET重新核验。

实际配置页有独立账号原请求存储、确认冻结、未知结果重试、重新核验后清理及切号/失权/隐藏迟到隔离。操作见[运维](../operations/PRICE_MONITOR_SOURCES.md)，比较见[只读比较协议](PRICE_COMPARISONS.md)。
