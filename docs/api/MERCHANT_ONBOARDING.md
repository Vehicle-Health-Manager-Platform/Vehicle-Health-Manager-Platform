# R1a 商家入驻申请、审核、区域品类配额与开店接口

先迁移 V020、再升级车主端与运营端页面，最后显式开启 `MERCHANT_ONBOARDING_ENABLED`（默认 false）。运营独立身份与 `can_onboard/ can_review` 权限见[运营账号管理](../operations/OPERATOR_ACCOUNTS.md)。规格见[入驻规格](../superpowers/specs/2026-10-10-r1a-merchant-onboarding-design.md)。

| 方法 | 路径 | 身份与输入 |
| --- | --- | --- |
| POST | /api/merchant-applications | 正式车主（`subject_type=user`、`role=OWNER`）；UUID 幂等键必填，严格申请正文，无 query |
| GET | /api/merchant-applications/mine | 正式车主；无参数 |
| GET | /api/admin/merchant-applications | OPERATOR + `can_onboard`；page 默认 1（≤1000000）、page_size 默认 20（≤50） |
| GET | /api/admin/merchant-applications/{id} | OPERATOR + `can_onboard`；无参数 |
| GET | /api/admin/merchant-applications/{id}/files/{file}/access | OPERATOR + `can_onboard`；无参数，返回短时签名地址 |
| POST | /api/admin/merchant-applications/{id}/moderate | OPERATOR + `can_onboard`；UUID 幂等键必填，严格 `revision/decision/reason_code`，无 query |
| GET | /api/admin/merchant-quotas | OPERATOR + `can_onboard`；无参数 |
| PUT | /api/admin/merchant-quotas | OPERATOR + `can_onboard`；UUID 幂等键必填，严格 `region_code/category/max_active`，无 query |

所有参数拒绝重复/未知，JSON 拒绝重复键/尾随/类型转换，全部 `no-store`。申请正文严格六字段：`merchant_name`（2–64 字符，首尾无空白、无控制字符）、`category`（固定枚举 `MAINTENANCE/TIRE/REPAIR/BEAUTY/SERVICE/SUPPLIES`，与 `merchant.merchant_type` 1 对 1）、`region_code`（6 位行政区划码）、`address`（≤256）、`contact_phone`（大陆手机号）、`qualification_file_ids`（1–9 个互不相同的正整数）。请求体按 UTF-8 字符计数，超长或含未知字段一律 `40001`。

## 申请与重提

一个申请人只保留一行申请，`revision` 从 1 递增：首次提交建行，`REJECTED` 后重提复用同一行并把 `revision` 加一、清空驳回码。`PENDING_REVIEW` 或 `APPROVED` 期间再次提交返回 `49001`；已开店不允许重复提交。并发提交同一车主由用户行锁串行化，只可能建出一行。

资质文件必须先经现有[私有上传核心](PRIVATE_UPLOAD_CORE.md)以申请人主体（`owner_type='user'`、`owner_id=申请人`）上传并扫描完成；申请只保存文件 id 快照。提交时逐个复核文件存在、`scan_status='CLEAN'` 且归属申请人，任一不满足返回 `49002`，不产生申请行。

`mine` 返回 `{application, reviews}`：`application` 为本人当前申请（无申请时为 `null`），`reviews` 为按 revision 倒序的不可变审核记录 `{revision,decision,reason_code,merchant_id,decided_at}`。`application` 含 `application_id/merchant_name/category/region_code/address/contact_phone/status/revision/merchant_id/review_reason/qualification_file_ids/created_at/updated_at`，不含申请人 id（本人无需回传）。

## 运营审核与配额

`OPERATOR` 身份每次请求实时复核会话有效、账号 `ACTIVE` 且 `can_onboard=1`；`can_review` 不交叉授权，只有经验审核权限的运营在列表/详情/审核/配额/文件访问上一律 `40300`。列表只返回固定摘要 `{application_id,merchant_name,category,region_code,revision,submitted_at}`，绝不返回申请人 id、手机号或文件；详情才返回联系信息与资质文件 id 快照。资质文件不产生公开 URL，只能通过受控访问接口按申请快照逐个换取短时签名地址，越界文件与不存在的申请返回 `40400`。

审核写正文 `{"revision":1,"decision":"APPROVE","reason_code":null}`：批准时 `reason_code` 必须为 null，驳回只接受固定码 `QUALIFICATION_INCOMPLETE`/`CATEGORY_MISMATCH`/`DUPLICATE_STORE`/`REGION_QUOTA_FULL`，不接受自由文字。`revision` 与当前申请不一致、状态不是 `PENDING_REVIEW`、或同一 revision 已有审核记录时返回 `49003`；同一幂等键重放返回原响应，不去覆盖更新的 revision 或已通过的申请。

配额表 `merchant_region_category_quota` 以 `(region_code, category)` 唯一，`max_active` 0–100000。**没有配额记录即视为 0**（fail-closed），运营必须先配置配额才可能开店；未配置时批准返回 `49010`（不是自动降级为驳回）。批准事务锁序固定为 申请行 → 配额行 → `merchant` → `staff_account`，与身份写操作锁序一致；在配额行锁内以当前已提交数据统计该区域品类下 `status=1` 且未删除的门店数，未满才允许开店，并发批准不会超额。配额调小不得低于当前有效门店数，否则 `49011`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /api/admin/merchant-quotas | `{items:[{region_code,category,max_active,active_stores}]}`，active_stores 为当前有效门店数 |
| PUT | /api/admin/merchant-quotas | 正文 `{region_code,category,max_active}`，同值重复设置不产生新审计 |

## 开店事务

批准在单个事务内完成，任一步失败整体回滚（含幂等记录与审计）：

1. 锁申请行并复核 `PENDING_REVIEW` 与 revision。
2. 锁配额行并复核未满额。
3. 插入 `merchant`：`merchant_type` 取品类映射、名称/地址/联系电话取申请快照、`qualification` 存文件 id 快照、`region_code` 存申请区域、`status=1`。
4. 插入 `staff_account`：`role='MERCHANT'`、`account='m{merchant_id}'`、`password_hash=NULL`、`status='PENDING_ACTIVATION'`。
5. 申请置 `APPROVED`、回填 `merchant_id/reviewed_by/reviewed_at`。
6. 追加 `merchant_application_review`（`(application_id,revision)` 唯一、不可变）、幂等响应与最小审计。

**不自动下发初始密码、不伪造短信**：开店账号为 `PENDING_ACTIVATION` 且无密码哈希，现有商家登录校验（要求 `status='ACTIVE'`）无法通过，不存在绕过路径。账号激活与正式短信、真实微信登录统一放在 R9，不作为本阶段已交付项。

## 数据与审计

V020 扩展 `merchant_application`（`address/contact_phone/revision/merchant_id/last_reason_code`）并新增 `merchant_application_review`、`merchant_region_category_quota`、`operator_account.can_onboard`、`merchant.region_code`。迁移可重复执行；迁移前的存量门店 `region_code=''`，不占用任何区域配额。

审计只记录申请状态、revision、品类、区域与门店 id，不记录手机号、资质文件内容或私有对象键。开关关闭时全部接口（含本人查询）返回 `50300`。

错误：`40001` 参数/未知字段/重复键，`40100` 会话失效，`40300` 角色或运营权限不足，`40400` 申请或资质文件不可用，`40900` 业务冲突（`49001` 重复未终结申请、`49002` 资质文件不可用、`49003` 版本或状态变化、`49010` 区域品类满额、`49011` 配额低于当前有效门店数），`42900` 运营共享限流，`50300` 开关/依赖/事务不可用（写请用原幂等键原载荷重试）。

## 状态

后端与迁移：接口、锁序、幂等、审计、配额并发与失败回滚已有 MockMvc 与真实 MySQL 测试覆盖。

小程序侧已接入，服务层 `apps/miniapp/src/services/merchant-onboarding.js` 与服务端逐字段对齐（固定品类、固定驳回码、6 位行政区划码、1..9 个互不相同的资质文件 id 与 0–100000 配额上限），页面为车主端 `pages/owner/onboarding`（申请＋进度＋驳回重提，入口在「我的」）、运营端 `pages/operator/onboarding`（待审＋详情＋批准/固定码驳回＋资质受控查看）与 `pages/operator/quotas`（配额列表与设置）。运营身份登录响应固定携带 `can_review` 与 `can_onboard` 两个布尔权限位，客户端按各自权限决定入口，缺字段或类型不符即判协议错误。

**真实后端容器 + 隔离库 + 微信开发者工具的端到端验收、正式短信与门店账号激活（R9）均未完成**，不得当作已验收；开发者工具也无法替代真机验证。
