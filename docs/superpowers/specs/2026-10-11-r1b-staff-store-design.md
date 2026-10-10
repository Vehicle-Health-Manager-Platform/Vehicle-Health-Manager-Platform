# R1b 门店员工维护、启停即时失效、员工码受控签发/撤销与门店资料规格

日期 2026-10-11；依赖已合并的 `087e1bc`（R1a 收口）。范围仅微信小程序与必要后端，PC 运营后台不做。本规格只定义规则，不宣称任何能力已完成。

关联：`docs/progress/THREE_ROLE_BUSINESS_PLAN_2026-10-10.md` 第 6 节第 2 行（R1b 员工）与 M-02、T-02。

## 入口与范围

- 店长在微信小程序内新增/查看本店「店员」「技师」，启停账号，签发/撤销技师一次性员工码，维护本店对外资料。
- 沿用现有 JWT 会话、共享限流、幂等记录、审计与私有上传核心，**不新建平行身份体系**。
- 门店账号（店长）仍由 R1a 入驻开店创建（`role=MERCHANT`、账号 `m{merchant_id}`）。本规格新增的是**店长自己创建下属账号**，不是店长自身的注册入口。
- `MERCHANT_STAFF_ENABLED` 默认 false；先迁移 V021、再升级店面页面、最后显式开启，沿用经验审核发布与入驻开关模式。关闭时全部接口（含本人查询）返回 `50300`。
- 员工码签发/撤销从仅离线 CLI（`StaffCodeCli`，profile `staff-admin`）**扩展到受控 HTTP**：离线 CLI 保留为运维兜底，其语义与锁序不变；HTTP 路必须复核调用者身份与本店归属。
- 本规格**不含**：标准项目/品牌车系车型治理（属 R1c）、发券与推广码（R5/R6）、真实短信与真实微信员工绑定验收（R9）。

## 角色模型与权限矩阵

新增角色 `STAFF`（店员）。`staff_account.role` 取值集合变为 `MERCHANT` / `STAFF` / `TECHNICIAN` / `OPERATOR`。

| 能力 | 店长 MERCHANT | 店员 STAFF | 技师 TECHNICIAN |
| --- | --- | --- | --- |
| 本店订单只读、接车、异议处理 | ✓ | ✓ | ✗ |
| 派工/再次派工、核销、防护照片 | ✓ | ✓ | 仅本人接单与报工 |
| 选品定价、上下架（`/api/merchant/projects`） | ✓ | ✗ | ✗ |
| 员工维护、启停、员工码签发/撤销 | ✓ | ✗ | ✗ |
| 门店资料查看 | ✓ | ✓ | ✗ |
| 门店资料修改 | ✓ | ✗ | ✗ |
| 登录方式 | 账号+密码+短信 | 账号+密码+短信 | 微信绑定一次性员工码 |

- **店员业务同级**：店员与店长在履约执行（接车、派工、核销、报工、本店订单）上同级；差异仅在**管理面**（员工、员工码、门店资料、选品定价）限店长。
- **店长只管本店**：所有员工维护/门店资料接口都以调用者 `merchant_id` 为准，跨店资源一律按「不存在」处理（`404`），不泄露其他门店是否存在该员工。
- 店长账号自身**不可**经本接口停用或修改；目标角色只接受 `STAFF` 与 `TECHNICIAN`。

## 员工账号与账号命名

- 店长创建员工时必填：`role`（`STAFF` 或 `TECHNICIAN`）、`display_name`（2–32 字符）、`password`（8–64 字符，须含字母与数字）。
- 手机号：`STAFF` 必填且须匹配大陆手机号格式（店员用账号+密码+短信登录，短信依赖登录手机号）；`TECHNICIAN` 可选（走微信绑定）。
- 账号（`account`）由系统生成，不使用自由文本：店长 `m{merchant_id}`（既有）、店员 `s{merchant_id}-{seq}`、技师 `t{merchant_id}-{seq}`；`seq` 在该门店同角色内自 1 递增。
- 账号生成在**商家行锁**下完成（锁序与既有身份写操作一致），并对 `uk_account` 唯一冲突重试，杜绝并发建号串号。
- 新员工初始 `status=ACTIVE`（店长已显式设定初始密码），`password_hash` 用 BCrypt 存储，明文密码**不出现在任何响应、日志、审计或幂等记录中**。
- 创建后**不返回密码**；响应与**列表行逐字段同形**，即后述列表投影的九字段：`staff_id`、`account`、`role`、`display_name`、`status`、`phone_masked`、`employee_code_issued`、`wechat_bound`、`created_at`。新员工与列表行同形，小程序可直接用创建响应就地渲染新行，无需再拉一次列表。
  （本行曾在初版写成"只含 `staff_id`/`account`/`role`/`display_name`/`status`/`phone_masked` 六字段"，与实现不符；实现返回的九字段是列表行的超集，未违反任何约束，故以实现为准修正规格文字，并在真实容器验收中按九字段断言。）

## 启停语义（停用立即阻断原会话）

停用（disable）单事务完成，锁序 **商家 → 员工 → 微信绑定**：

1. 锁定本店 `merchant` 行并复核 `status=1`、`is_deleted=0`。
2. 锁定目标 `staff_account` 行并复核 `merchant_id` 等于调用者门店、`role ∈ {STAFF,TECHNICIAN}`、`is_deleted=0`；已 `DISABLED` 视为幂等成功。
3. `UPDATE staff_account SET status='DISABLED', employee_code_hash=NULL`。
4. `UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='staff_account' AND subject_id=? AND revoked_at IS NULL`。
5. 若为 `TECHNICIAN`：`UPDATE staff_wechat_identity SET status='REVOKED', unbound_at=UTC_TIMESTAMP() WHERE staff_account_id=? AND status='ACTIVE' AND is_deleted=0`。

- 立即失效是**双保险**：JWT 校验器每次请求实时复核 `staff.status='ACTIVE'` 与门店可用（`SecurityConfig`），会话撤销负责阻断刷新与后续请求；两者缺一不可（只改状态会让 refresh 仍能换新令牌）。
- 启用（enable）只把 `status` 置回 `ACTIVE` 并清除撤销状态之外的字段；**不恢复**已撤销的微信绑定，技师需重新用员工码绑定。
- 停用**不影响**已存在的历史工单、派工记录与接车单（它们是快照/历史，遵循既有"停用不改历史"口径）。

## 员工码受控签发与撤销

- `POST /api/merchant/staff/{id}/employee-code`：仅店长；目标必须为本店 `TECHNICIAN` 且 `status=ACTIVE`。每次调用**轮换**员工码（旧码立即失效），返回一次性明文码，响应只此一次可见。
- `DELETE /api/merchant/staff/{id}/employee-code`：仅店长；清除 `employee_code_hash` 并撤销该技师全部 `ACTIVE` 微信绑定（与 `StaffCodeOperations.revoke` 同语义）。
- 复用既有锁序与哈希逻辑（`TechnicianIdentityLocks` + `AuthTokens.sha256`），**只存 `employee_code_hash`**，明文码不落库、不写审计、不写幂等记录。
- **签发/撤销接口不使用 `Idempotency-Key`**，语义是「以最后一次为准的轮换」而非「重放返回原响应」。理由：一次性密钥若存入 `idempotency_record.response_body` 就是磁盘上的明文泄露（`idempotency_record` 与 `audit_log` 均为真实落库载荷，投影过滤救不了它），而"重放必须返回同一响应"与"旧码立即失效"直接冲突。审计只记录 `STAFF_CODE_ISSUE` / `STAFF_CODE_REVOKE` 与 `staff_id`。
- 一键签发/撤销与启停均为**店长专属**；店员调用返回 `403`。

## 门店资料受控更新

- `GET /api/merchant/profile`：店长与店员均可读，返回本店 `merchant_id`、`name`、`address`、`contact_phone`、`lng`、`lat`、`merchant_type`、`region_code`、`status`。
- `PUT /api/merchant/profile`：**仅店长**，可改字段白名单 `name`（2–64）、`address`（≤256）、`contact_phone`（大陆手机号）、`lng`/`lat`（合法经纬度范围，可空）。其余字段（`merchant_type`、`status`、`region_code`、`qualification`、`grade`、`commission_rate`、`is_deleted`）**一律不可经本接口修改**，出现未知字段按 `400` 拒绝。
- 变更经 `WriteIntegrityService` 单事务完成并写 `audit_log`（`MERCHANT_PROFILE_UPDATE`，`resource_type=merchant`，before/after 仅含白名单字段），`Idempotency-Key` 必填。
- 品类、区域与资质属入驻与运营范畴，不在本接口；店长若需变更走入驻资料变更流程（后续单元）。

## 授权放宽清单（本单元必须同批修改，否则功能割裂）

新增 `STAFF` 意味着「商家执行面」的鉴权口径从 `role='MERCHANT'` 放宽为 `role IN ('MERCHANT','STAFF')`。落点已核实，逐处说明保留或收紧：

| 位置 | 变更 | 理由 |
| --- | --- | --- |
| `gateway/SecurityConfig.java`（JWT 校验器 `staff_account` 分支） | `MERCHANT` → `MERCHANT` 或 `STAFF`，仍要求 `binding_id==null`、`app_id=merchant-account`、门店可用 | 店员令牌必须能通过校验；技师分支不变 |
| `gateway/identity/MerchantIdentityRepository.Merchant.active()` | 接受 `MERCHANT`/`STAFF` | 登录与刷新共用的可用性判定 |
| `gateway/identity/AuthTokens` | 登录按员工实际角色签发；`refresh`/`currentUser` 支持 `STAFF`；`user.role` 返回 `staff` | 否则店员令牌角色错签成 `MERCHANT`，越权成店长 |
| `service/MerchantActor` | 接受 `MERCHANT`/`STAFF`，新增 `manager()` 判定 | 执行面复用同一 actor |
| `order/ReservationStore.merchant()` | 会话/员工行角色放宽为 `IN ('MERCHANT','STAFF')` | 接车、派工、核销、本店订单、异议共用的鉴权 |
| `service/MerchantQuotes`（选品定价） | 读放宽、**写仍限 `role='MERCHANT'`**（店长专属） | 定价管理面限店长 |
| `file/JdbcUploadRequests`（商家上传） | 放宽为 `IN ('MERCHANT','STAFF')` | 店员拍防护/接车照片 |
| `order/OrderReviews`（核销校验） | 放宽为 `IN ('MERCHANT','STAFF')` | 店员执行核销后评价必须能对上 |
| `order/ServiceWork`（防护照片归属） | 放宽为 `IN ('MERCHANT','STAFF')` | 同上，照片归属者可能是店员 |

技师相关分支（`TechnicianActor`、`ReservationStore.technician()`、`staff_wechat_identity` 校验）**一律不动**。

## 数据迁移 V021

在 `docs/sql/migrations/V021__merchant_staff_store.sql`，沿用 `information_schema` 守卫保证可重复执行：

- `staff_account` 增列：`display_name VARCHAR(64) DEFAULT NULL`、`created_by BIGINT UNSIGNED DEFAULT NULL`（创建者 staff id）。
- 不新建平行表；员工码哈希、状态、账号唯一约束沿用 V001 既有列与索引。
- 门店资料无新列（白名单字段 V001/V020 已有），审计走 `audit_log`。

## API

- `GET /api/merchant/staff?role=&page=&page_size=`：本店员工列表（店长/店员可读；`role` 可选 `STAFF`/`TECHNICIAN`）。
- `POST /api/merchant/staff`：店长创建员工，`Idempotency-Key` 必填。
- `POST /api/merchant/staff/{id}/disable`：店长停用，`Idempotency-Key` 必填。
- `POST /api/merchant/staff/{id}/enable`：店长启用，`Idempotency-Key` 必填。
- `POST /api/merchant/staff/{id}/employee-code`：店长签发/轮换员工码（**无** `Idempotency-Key`）。
- `DELETE /api/merchant/staff/{id}/employee-code`：店长撤销员工码与绑定（**无** `Idempotency-Key`）。
- `GET /api/merchant/profile`：本店资料。
- `PUT /api/merchant/profile`：店长更新资料，`Idempotency-Key` 必填。

写作用域（POST/PUT）响应一律返回存储的**单层** `{code,message,data,request_id}` 信封，**不得二次包装**（R1a 收口已发现的 `data.data` 缺陷）。

## 错误语义

沿用全站约定：`400` 非法/未知/重复字段（`40001`）；`401` 登录或会话失效；`403` 角色/权限（店员调用管理面、跨店写自有资源）；`404` 不存在或不属于本店；`409` 状态冲突（重复账号、员工已停用不可签发员工码）用固定业务码；`429` 限流；`503` 依赖/开关/事务不可用（`50300`，提示用原幂等键重试）。数字为 JS 安全整数，严格 JSON 无重复键/尾随字符，全部响应 `no-store`，错误不暴露凭据、SQL 或哈希。

## 不在本规格内

- R1c 标准项目/品牌车系车型维护与停用审计。
- 店长自助注册、店长账号变更、门店停业/注销。
- 真实短信与真实微信员工绑定验收（R9）；本单元用隔离合成会话联调。
- 员工权限的细粒度自定义（本单元只有「店长 / 店员 / 技师」三角色固定矩阵）。
