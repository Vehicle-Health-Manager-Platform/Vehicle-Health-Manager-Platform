# R1a 商家入驻申请、审核、区域品类配额与开店规格

日期 2026-10-10；依赖 PR #76 / `f6950cb`（三端业务优先路线）。范围仅微信小程序与必要后端，PC 运营后台不做。本规格只定义规则，不宣称任何能力已完成。

## 入口与范围

- 车主在微信小程序内提交/查看/重提入驻申请；运营沿用现有小程序受限审核入口（独立 operator 会话），新增入驻审核与配额页面。
- 沿用现有 JWT 会话、私有上传核心、共享限流、幂等记录与审计，不新建平行身份体系。申请身份沿用正式认证契约：申请人必须是正式车主（`subject_type=user`、`role=OWNER`，即 `VehicleOwner`）；开发联调用现有隔离合成会话，正式微信认证最后验收。
- `MERCHANT_ONBOARDING_ENABLED` 默认 false；先迁移 V020、再升级两端页面、最后显式开启，沿用经验审核发布开关模式。
- 审核通过开店**不自动发初始密码、不伪造短信**；开店账号置 `PENDING_ACTIVATION`，账号激活与最终正式身份接入（R9）分项，不作为本阶段完成项。
- 撤回申请、商家后续资料变更、员工管理（R1b）不在本规格。

## 申请资料与约束

- 申请人一次只允许存在一份未终结（`PENDING_REVIEW`）申请；`APPROVED` 后不得再次提交（已开店）；`REJECTED` 可修改后重提。
- 字段：`merchant_name`（2–64 字符）、`category`（固定品类枚举，与 `merchant.merchant_type` 一一映射，拒绝自由文本）、`region_code`（6 位行政区划码格式）、`address`（≤256）、`contact_phone`（大陆手机号格式）、`qualification_file_ids`（1–9 个 `file_object` id）。
- 资质文件必须先经现有 `PrivateUploadService` 以申请人主体（`user` + 申请人 id）私有上传获得；申请只保存文件 id 快照，不保存原始文件名。格式/大小/病毒扫描规则沿用私有上传核心，不安全文件在申请提交时被拒绝（校验文件存在、CLEAN、归属申请人）。
- 跨身份隔离：其他车主、无入驻审核权限的运营、商家/技师身份一律不可读取他人资质；运营仅能通过受控详情接口获得短时访问，不产生公开 URL，审计不记录文件内容。

## 状态机与重提

- `PENDING_REVIEW` → `APPROVED`（开店成功）或 `REJECTED`（固定理由码）。基线 `merchant_application` 表已存在（V001），V020 扩展 `revision`、`merchant_id` 回填、驳回理由码与联系/地址字段，不新建平行表。
- `revision` 从 1 递增：重提 = 修改内容后以新 revision 进入 `PENDING_REVIEW`，旧 revision 与旧审核记录不可变。审核记录表 append-only，唯一键 `(application_id, revision)`，沿用 `experience_card_moderation` 模式。
- 驳回理由仅固定码：`QUALIFICATION_INCOMPLETE`（资质不全或不清晰）、`CATEGORY_MISMATCH`（品类与资质不符）、`DUPLICATE_STORE`（重复/已存在同区域同名门店）、`REGION_QUOTA_FULL`（区域品类满额）。无自由文本，不泄露复核细节。
- 旧 revision 的重放请求、旧幂等键不得覆盖新 revision 或已通过的申请；重放须复核当前权限与状态。

## 区域品类配额

- 新表 `merchant_region_category_quota`：`(region_code, category)` 唯一，`max_active` 非负。无配额记录视为 0（fail-closed），运营必须先配置配额才可能开店；配额由具备入驻权限的运营维护，变更写入审计。
- 批准事务锁序固定：申请行 → 配额行 → `merchant` → `staff_account`（与既有身份写操作锁序一致，避免与派工/员工码并发死锁）。锁定配额行后统计该 `(region_code, category)` 下未删除且 `status=1` 的商家数，未满才允许开店。
- 满额时批准返回 409 固定业务码，不自动降级为驳回；运营可先驳回（`REGION_QUOTA_FULL`）或调整配额后重试。并发批准同一区域品类不得超额；配额调小不低于当前有效商家数。

## 开店事务（批准）

单事务原子完成，任一步失败全部回滚：

1. 锁申请行，复核状态 `PENDING_REVIEW`、revision 匹配、申请人未变。
2. 锁配额行并复核未满额。
3. `INSERT merchant`：`merchant_type` 取品类映射、名称/地址/联系电话取申请快照、`qualification` 存文件 id 快照、`status=1`（通过）。
4. `INSERT staff_account`：`role=MERCHANT`、`merchant_id` 指向新门店、账号名按 `m{merchant_id}` 规则生成（依赖 `uk_account` 唯一约束防重复）、`password_hash=NULL`、`status=PENDING_ACTIVATION`。无密码哈希的账号无法通过现有商家登录校验，不存在绕过路径。
5. 申请置 `APPROVED`、回填 `merchant_id` 与 `reviewed_by/reviewed_at`。
6. 追加审核记录（APPROVED）、幂等响应、`audit_log` 最小 before/after（不含资质文件内容）。

## 运营权限

- `operator_account` 新增 `can_onboard`（V020，默认 0），由离线 CLI 维护（复用 `OperatorAdminCli` 模式），不与 `can_review` 交叉复用；经验审核权限不能审入驻，反之亦然。
- 入驻列表/详情/批准/驳回/配额接口每次请求实时复核会话有效、账号 ACTIVE 且 `can_onboard=1`；沿用 operator 共享限流。
- 待审列表分页仅返回固定摘要（申请 id、名称、品类、区域、提交时间、revision），不返回申请人 id、手机号或文件；详情接口按需返回资质文件受控访问。

## API

- `POST /api/merchant-applications`：车主提交/重提，`Idempotency-Key`（UUID）必填；重复未终结申请返回 409。
- `GET /api/merchant-applications/mine`：本人申请进度、历史 revision 审核结果与驳回码。
- `GET /api/admin/merchant-applications`：`page/page_size`，待审列表。
- `GET /api/admin/merchant-applications/{id}`：详情 + 资质文件短时受控访问。
- `POST /api/admin/merchant-applications/{id}/moderate`：`revision/decision/reason_code`（批准时 null，驳回固定码），`Idempotency-Key` 必填。
- `GET/PUT /api/admin/merchant-quotas`：查询/设置 `(region_code, category, max_active)`，`can_onboard`。

错误语义沿用全站约定：400 非法/未知/重复字段（40001），401 登录/会话失效，403 角色/权限/跨身份，404 不存在，409 状态/revision 不匹配/重复申请/满额（固定业务码），429 限流，503 依赖/开关/事务不可用（50300）。数字为 JS 安全整数，严格 JSON 无重复键/尾随字符，全部响应 no-store，错误不暴露凭据、SQL 或文件内容。

## 阶段与验收

规格 → 后端（V020 + 服务/接口 + 测试 + API 契约）→ 小程序页面 → 真实联调收口，逐阶段堆叠 PR，不自动合并部署。

- MySQL/Testcontainers：配额并发批准不超额、多运营竞争/重复幂等、驳回重提 revision 唯一、跨身份与越权拒绝、开店事务失败回滚、`uk_account` 唯一、锁序无死锁。
- MockMvc：输入/身份/权限矩阵、开关关闭时 503。
- 小程序：申请表单校验、私有上传失败明确报错、进度/重提、运营列表/详情/审核/配额操作，离线测试 + 微信/H5 构建。
- 真实后端容器 + 隔离库 + 模拟器：成功开店、驳回重提、满额 409、并发、重复提交、过期会话/越权、不安全文件拒绝与审核失败回滚；结束清理合成数据。
- 正式短信、真实微信登录、账号激活、真机与上线不在本阶段销项，保留待 R9。

## 交付拆分

1. PR：本规格 + 实施计划（docs only）。
2. PR：V020 迁移 + 后端实现 + 测试 + `docs/api/MERCHANT_ONBOARDING.md` 契约。
3. PR：小程序车主申请与运营审核/配额页面 + 服务层 + 测试。
4. PR：真实联调收口 + 执行/验收中文记录 + 状态文档更新。
