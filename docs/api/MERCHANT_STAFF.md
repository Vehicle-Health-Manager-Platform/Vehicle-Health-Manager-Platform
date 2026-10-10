# R1b 门店员工维护、员工码与门店资料接口

先迁移 V021，再升级店面页面，最后显式开启 `MERCHANT_STAFF_ENABLED`（默认 false）。开关关闭时全部接口（含本人查询）返回 `50300`。规格见[门店员工规格](../superpowers/specs/2026-10-11-r1b-staff-store-design.md)，计划见[实施计划](../superpowers/plans/2026-10-11-r1b-staff-store.md)。

角色模型：`MERCHANT`（店长，R1a 开店创建）／`STAFF`（店员，店长新增）／`TECHNICIAN`（技师，店长新增并微信绑定）。**店员与店长在履约执行面同级，管理面限店长**；技师相关分支不变。

| 方法 | 路径 | 身份与输入 |
| --- | --- | --- |
| GET | /api/merchant/staff | 店长或店员；可选 `role=STAFF\|TECHNICIAN`，`page` 默认 1（≤1000000）、`page_size` 默认 20（≤50） |
| POST | /api/merchant/staff | 店长；UUID 幂等键必填，严格 `role/display_name/phone/password`，无 query |
| POST | /api/merchant/staff/{id}/disable | 店长；UUID 幂等键必填，正文必须为空对象 `{}`，无 query |
| POST | /api/merchant/staff/{id}/enable | 店长；UUID 幂等键必填，正文必须为空对象 `{}`，无 query |
| POST | /api/merchant/staff/{id}/employee-code | 店长；**无幂等键、无正文、无 query** |
| DELETE | /api/merchant/staff/{id}/employee-code | 店长；**无幂等键、无 query** |
| GET | /api/merchant/profile | 店长或店员；无 query |
| PUT | /api/merchant/profile | 店长；UUID 幂等键必填，严格 `name/address/contact_phone/lng/lat`，无 query |

所有参数拒绝重复/未知，JSON 拒绝重复键/尾随/类型转换，全部 `no-store`。

## 新增员工

正文严格三到四个字段：`role`（固定 `STAFF`/`TECHNICIAN`）、`display_name`（2–32 字符，首尾无空白、无控制字符）、`password`（8–64 位可打印 ASCII，须同时含字母与数字）、`phone`（大陆手机号；**店员必填**，技师可选）。店长账号不可经此创建或修改，`role=MERCHANT` 一律 `40001`。

账号由系统生成，不接受自由文本：店长 `m{merchant_id}`、店员 `s{merchant_id}-{seq}`、技师 `t{merchant_id}-{seq}`。生成在商家行锁内完成并对 `uk_account` 冲突顺延，因此并发建号不会串号。新员工 `status='ACTIVE'`、`password_hash` 为 BCrypt；**明文密码只在请求里出现**，响应、审计与幂等记录都不含密码。

响应 `data`：`{staff_id,account,role,display_name,phone_masked,status,employee_code_issued,wechat_bound,created_at}`。

这九个字段与列表行**逐字段同形**：创建成功后会以同一投影回读再作为响应载荷，并有防漂移断言（创建响应的键集合必须等于列表行的键集合）。曾经出现过创建响应少 `created_at`/`employee_code_issued`/`wechat_bound` 三个字段的漂移，客户端会把合法响应判成协议错误——OpenAPI 生成一致性与 MockMvc 都发现不了，只能靠这条断言。

## 启停与"停用立即阻断原会话"

停用在单事务内完成，锁序 **商家 → 员工 → 微信绑定**：

1. 复核会话、员工行与门店同时有效，且角色与会话令牌一致。
2. `UPDATE staff_account SET status='DISABLED', employee_code_hash=NULL`。
3. 撤销该员工**全部** `auth_session`（`revoked_at`）。
4. 若为技师：撤销其 `staff_wechat_identity` 全部 `ACTIVE` 绑定。

第 3 步不可省：JWT 校验器每次请求会实时复核 `status='ACTIVE'`，所以旧访问令牌立刻失效；但**刷新端点只验会话行**，不撤销会话就仍能换出新令牌。两者一起才构成"停用立即生效"。响应回传 `sessions_revoked`。

启用只把 `status` 置回 `ACTIVE`，**不恢复**已撤销的微信绑定（技师需重新用员工码绑定）。停用不影响历史工单、派工与接车单快照。

重复停用/启用是幂等成功；跨店目标（不属于调用者门店）返回 `40400`，不泄露其他门店是否存在该员工。

## 员工码：轮换语义（刻意不使用幂等键）

`POST …/employee-code` 每次都**轮换**员工码并使其全部有效微信绑定失效，返回一次性明文码；`DELETE …/employee-code` 清空哈希并撤销绑定。库中只存 `employee_code_hash`（`AuthTokens.sha256`），**明文码不落库、不写审计、不写幂等记录**。

这两个接口**没有** `Idempotency-Key`，因为"重放必须返回原响应"与"旧码立即失效"直接冲突，而且一次性密钥若存入 `idempotency_record.response_body` 或 `audit_log`，就是磁盘上的明文泄露（投影过滤救不了落库载荷）。审计只记 `STAFF_CODE_ISSUE`/`STAFF_CODE_REVOKE` 与 `staff_id`。

目标必须是本店 `TECHNICIAN`：非技师或跨店返回 `40400`，员工已停用返回 `40900`。

## 门店资料

`GET` 返回 `{merchant_id,name,address,contact_phone,lng,lat,merchant_type,region_code,status,can_edit}`；`can_edit` 表示当前身份是否为店长。

`PUT` 白名单只有 `name`（2–64）、`address`（≤256）、`contact_phone`（大陆手机号）、`lng`（±180）、`lat`（±90，均可为 `null`）。**`merchant_type`、`status`、`region_code`、`qualification`、`grade`、`commission_rate`、`is_deleted` 一律不可经本接口修改**，出现未知字段或字段数不为 5 即 `40001`。改动经 `WriteIntegrityService` 单事务写入并追加 `audit_log`（`MERCHANT_PROFILE_UPDATE`，`resource_type=merchant`，before/after 只含白名单字段）。

## 授权放宽（同批修改）

新增 `STAFF` 后，履约执行面的鉴权口径由 `role='MERCHANT'` 放宽为 `role IN ('MERCHANT','STAFF')`，涉及：JWT 校验器（`SecurityConfig`）、`MerchantIdentityRepository.active()`、`AuthTokens` 登录/刷新、`MerchantActor`、`ReservationStore.merchant()`、商家上传（`JdbcUploadRequests`）、核销校验（`OrderReviews`）、防护照片归属（`ServiceWork`）。**选品定价 `MerchantQuotes` 的写路径保持店长专属**（店员读本店报价仍可）。技师分支（`TechnicianActor`、`ReservationStore.technician()`、`staff_wechat_identity` 校验）未改动。

`SecurityConfig` 与刷新路径同时要求**令牌角色等于员工行真实角色**，避免把店长令牌降级或把店员令牌升级使用。

## 数据与审计

V021 只给 `staff_account` 加 `display_name`、`created_by` 两列，可重复执行，不新建平行表；累计仍 63 表。写作用域（新增员工/启停/资料更新）的审计由 `WriteIntegrityService` 写入 `audit_log`，只含 `staff_id`、状态与白名单资料字段，不含密码、手机号或员工码。

错误：`40001` 参数/未知字段/重复键，`40100` 会话失效，`40300` 店员调用管理面，`40400` 员工或门店不可用（含跨店），`40900` 员工已停用不可签发员工码、本店账号序号用尽，`50300` 依赖/事务不可用（写请用原幂等键原载荷重试）。

**响应信封为单层**：成功为 `{code:0,message,data,request_id}`，写接口返回的就是幂等记录里保存的同一份信封，不得再次包裹（R1a 曾把写响应多包一层成 `data.data`，真实环境会把合法响应判成协议错误）。

## 状态

后端、迁移与接口已实现；MockMvc 与真实 MySQL 测试、契约与 OpenAPI（当前 127 操作）已更新。**小程序页面、真实容器端到端验收、正式短信与真实微信员工绑定（R9）尚未完成**，不得当作已交付。
