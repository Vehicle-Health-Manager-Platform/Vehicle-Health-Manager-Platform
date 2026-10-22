# R9 A4d19 商家入驻资质附件授权访问阶段执行记录

## 结果与范围

2026-10-22 完成实施和本地隔离验收。分支 `codex/r9-a4d19-onboarding-files`，基于 A4d18 的 `codex/r9-a4d18-auth-sessions`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。本阶段补齐最后一个入驻相关遗留读接口的四类执行证据：运营侧读取商家入驻申请材料附件 `GET /api/admin/merchant-applications/{id}/files/{file}/access`。生产代码零变更。

采用 Java 真实旅程 + Python 证据审计（沿用 A4d-2e2b 私有上传、A4d-2g3 辅助材料、A4d-9 接车/施工附件的形态），因为 Newman 套件无法在真实对象存储放文件、也无法真正下载签名 URL 验证字节一致与自然过期。

## 实施与行为

- 身份、门店、运营账号与"门店已开业"为 SQL 合成夹具；上传、入驻申请提交、附件访问与签名下载全部走真实 HTTP。
- 3 次真实 multipart 上传经真实 ClamAV 判为 CLEAN：申请人 1 两张、申请人 2 一张，全部 `owner_type='user'` 且归属本人，无第三方归属行。
- 两份入驻申请真实提交（`PENDING_REVIEW`、`revision=1`），快照精确为 `[1,2]` 与 `[3]`；跨申请引用双向为 0。
- 单接口覆盖正常、边界、错误、未授权：两份申请的三个附件均 200 且可下载；跨申请文件 404、申请不存在 404；未知 query 400、申请 id 0 → 400、文件 id 0 → 400；匿名 401、只有 `can_review` 的运营 403、车主 403、商家 403。
- 签名验证：下载逐字节等于上传字节、`Content-Type=image/png`、租期 5 秒且 `expires_at` 有界；去签名、篡改 `X-Amz-Signature`、等待 6.1 秒重放均 403。
- 数据库不变量：3 个 CLEAN 文件、2 份申请、每申请人 1 份、快照精确、跨申请引用为 0，且**未激活门店**（`merchant` 仍 1 家、两份申请 `merchant_id`/`reviewed_at` 为 NULL）。
- 执行器新增 `--check-only` 证据校验与 `--docker` 隔离执行，接入 CI 构建证据步骤、上传产物与覆盖汇总适配器（`JOURNEYS` 新增 `merchant_onboarding_files`）。

## 实际验证

结论：PASS，307.395 秒。可提交脱敏记录：`docs/testing/reports/R9_A4D19_MERCHANT_ONBOARDING_FILES_2026_10_22.json`。

- 业务请求 12 个、1 个接口四类覆盖，0 失败；验收说明见 `docs/testing/MERCHANT_ONBOARDING_PRIVATE_FILES_ACCEPTANCE.md`。
- 11 项检查（3 个 CLEAN 上传、文件归属申请人、两份申请、快照精确、每申请人一份、未激活门店、跨申请零引用、去签名/篡改/过期拒绝、租期有界）全部为真。
- 自有 MySQL / ClamAV / MinIO / 初始化容器与网络实际移除，源码哈希执行前后一致。
- 回归：同一 maven 调用的 `MerchantOnboardingHttpTest`(6)、`MerchantOnboardingMySqlTest`(9) 全部通过；Python 侧 113 项相关回归通过（78 项执行器单测 + 32 项覆盖核对 + 3 项清理证据）。
- 覆盖核对：33 套所选记录、352 项契约中 314 项具备完整四类历史证据、缺口 38 项；全 API 验收仍为 false。

## 下一阶段与边界

下一步继续按缺口清单处理剩余业务域（AI 与首页读接口、运营剩余读接口、社区审核等），并保持每阶段独立运行、独立上传。

本阶段身份与附件元数据为隔离夹具；审核决策、区域品类配额与建店事务不在本阶段范围（已由 `MerchantOnboardingMySqlTest` 与 `--merchant-cases` 覆盖）。正式微信身份、真实资金、生产扫描鲜度、真机与外部云存储仍未验收。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。


阶段已上传[草稿PR210](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/210)，实现提交 `a1ea4ad`，base=`codex/r9-a4d18-auth-sessions`，依赖PR209；远程CI已触发，结果以PR实际检查为准，未合并。
