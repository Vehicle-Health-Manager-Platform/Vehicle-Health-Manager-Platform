# 商家入驻资质附件授权访问验收设计

本阶段补齐最后一个入驻相关遗留接口：运营侧读取商家入驻申请材料附件（`GET /api/admin/merchant-applications/{id}/files/{file}/access`）。它是"先鉴权、再签短时 URL"的读接口，风险不在状态机，而在**权限位**、**快照归属**与**签名本身**三处。

## 方案选择

Newman 业务套件只起后端与 MySQL，无法在真实对象存储放文件，也无法真正下载签名 URL 验证字节一致、篡改拒绝与自然过期；`A4d-19` 之前，本接口的四类证据只存在于 `MerchantOnboardingHttpTest` 的模拟签名里，没有一次真实取回字节。因此沿用 A4d-2e2b（私有上传）、A4d-2g3（辅助材料）、A4d-9（接车/施工附件）已验证的形态：Java 旅程测试起真实 MySQL/MinIO/ClamAV，Python 执行器只做证据审计与归档。

## 被测流程

身份、门店、运营账号与"门店已开业"的事实为 SQL 合成夹具；上传、入驻申请提交、附件访问与全部签名下载都是真实 HTTP：

1. 车主 1 连续上传 2 张资质图片，车主 2 上传 1 张；三张都经真实 ClamAV 判为 CLEAN。
2. 车主 1 提交入驻申请（`qualification_file_ids=[1,2]`），车主 2 提交另一份（`[3]`）；两份都是 `PENDING_REVIEW`、`revision=1`。
3. 运营 A（`can_onboard=1`）逐个读取附件并实际下载；运营 B（只有 `can_review=1`）被拒。

## 权限矩阵

| 调用方 | 期望 | 依据 |
| --- | --- | --- |
| 运营 `can_onboard=1`，文件在申请快照内 | 200 + 签名 URL | `operators.authorize(ONBOARD)` 通过且 `snapshotFiles(row).contains(file)` |
| 运营 `can_onboard=1`，文件属于别人的申请 | 404 | 快照不含该 id，与"文件不存在"同码，不暴露存在性 |
| 运营 `can_onboard=1`，申请不存在 | 404 | 同上 |
| 运营 `can_onboard=0, can_review=1` | 403 | 审核权限不等于入驻权限，权限位不可互相顶替 |
| 车主 / 商家 | 403 | `OperatorActor.from` 只认 `subject_type=operator_account` + `role=OPERATOR` + `app_id=operator-account` |
| 匿名 | 401 | 无令牌 |
| 任意调用方带 query | 400 `40001` | 该接口只认路径参数 |
| `id` 或 `file` 为 0 | 400 `40001` | `MerchantOnboardingInput.id` 要求 ≥1 且 ≤2^53-1 |

约定沿用既有实现：越权读一律 `404`，角色不符一律 `403`，入参非法一律 `40001`，全部响应带 `Cache-Control: no-store`。

## 数据库不变量

- 3 个 CLEAN 且未删除的文件，且没有第二个归属人；`owner_type='user'` 且 `owner_id ∈ {1,2}`。
- 2 份申请、均为 `PENDING_REVIEW` 且 `revision=1`；每个申请人只有 1 份。
- 快照精确：申请 1 = `[1,2]`，申请 2 = `[3]`；跨申请引用为 0（`JSON_CONTAINS` 双向断言）。
- **未激活门店**：`merchant` 仍只有夹具那 1 家，两份申请 `merchant_id` 与 `reviewed_at` 均为 NULL —— 读附件不得顺带推进审核或建店。

## 签名与租期

签名 URL 由 `PrivateFileAccessService` 在鉴权后签发，TTL 取隔离配置 5 秒；响应体只有 `url` 与 `expires_at`。验收要求：下载字节与上传字节逐字节一致、`Content-Type: image/png`、URL 里 `X-Amz-Expires=5`、`expires_at` 距现在 0–10 秒；去掉 query 后对象存储 403；把 `X-Amz-Signature` 改一个字符 403；等待 6.1 秒后同一 URL 403。

`MINIO_PUBLIC_ENDPOINT` 指向测试内的回环转发端口，从而在不放宽生产"公网 HTTP 端点"限制的前提下完成真实签名下载。

## 生产代码变更

无。本阶段只补执行证据；`MerchantOnboardingAdminController.access` 与 `MerchantOnboarding.fileOwner` 已符合上述语义，未被修改。

## 边界

- 扫描引擎是真实 ClamAV，签名库为合成；生产鲜度策略未改。
- 未覆盖审核通过/拒绝、区域品类配额与建店事务（已在 `MerchantOnboardingMySqlTest` 与 `--merchant-cases` 中验证）。
- 5 秒签名只测一次自然过期；未做并发压测、真机、正式微信身份与外部云存储验收。
- 全 API 验收仍为 false，本阶段只把该接口从"无四类证据"变为"有可复现四类证据"。
