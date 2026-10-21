# R9 A4d9 接车与施工私有附件访问阶段执行记录

## 结果与范围

2026-10-21 完成实施和本地隔离验收。分支 `codex/r9-a4d9-pickup-work-files`，基于 A4d-8 的 `codex/r9-a4d8-dispute-recovery`；成果上传[草稿PR200](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/200)，远程CI结论以该PR实际结果为准。本阶段补齐四个既有读接口的四类执行证据：接车检查附件、商家/技师/车主三侧施工证据附件访问，并修掉一个真实缺口——接车附件访问此前忽略未知与重复 query。

采用 Java 真实旅程 + Python 证据审计（沿用 A4d-2e2b 私有上传、A4d-2g3 辅助材料的形态），因为 Newman 套件无法在真实对象存储中放文件、也无法真正下载签名 URL 验证字节一致与自然过期。

## 实施与行为

- 身份、订单、支付、预约时段为 SQL 合成夹具；上传、接车提交与确认、派工接单、防护、报工、质检签字、附件访问与签名下载全部走真实 HTTP。
- 19 次真实 multipart 上传经真实 ClamAV 判为 CLEAN：本店店长 8 张（7 接车 + 1 防护）、同店店员 7 张（第二单接车）、技师 4 张（过程/故障件/完工/签字 PNG）。第二单由另一名员工接车，因为生产限流为"每主体每分钟 10 次上传"，不为测试放宽。
- 四个接口各覆盖正常、边界、错误、未授权：跨订单文件、未关联文件、缺失订单、越权角色、跨店、同店未派工技师、匿名、未知/重复 query、非法 id。
- 签名验证：下载逐字节等于上传字节、`Content-Type=image/png`、租期 5 秒且 `expires_at` 有界；去签名、篡改 `X-Amz-Signature`、等待 6.1 秒重放均 403。
- 数据库不变量：14 条接车附件关联、5 条施工证据关联、19 个 CLEAN 文件全部归属本店员工、两张订单且无跨用户/跨店订单、订单状态分别为 `PENDING_VERIFY` 与 `RECEIVED`。
- 执行器新增 `--check-only` 证据校验与 `--docker` 隔离执行，接入 CI 构建证据步骤、上传产物与覆盖汇总适配器（`JOURNEYS` 新增 `pickup_work_files`）。

## 实际验证

结论：PASS，282.656 秒。可提交脱敏记录：`docs/testing/reports/R9_A4D9_PICKUP_WORK_FILES_2026_10_21.json`。

- 业务请求 39 个、4 个接口四类覆盖，0 失败；验收说明见 `docs/testing/PICKUP_WORK_PRIVATE_FILES_ACCEPTANCE.md`。
- 9 项检查（19 个 CLEAN 上传、附件关联精确、文件归属本店、去签名/篡改/过期拒绝、租期有界、仅两张订单、订单状态）全部为真。
- 自有 MySQL / MinIO / ClamAV / 初始化容器与网络实际移除，源码哈希执行前后一致。
- 回归：同一 maven 调用的 `PickupHttpTest`(6)、`ServiceWorkHttpTest`(7)、`PrivateFileAccessTest`(4) 全部通过。
- 覆盖核对：23 套所选记录、352 项契约中 238 项具备完整四类历史证据、缺口 114 项；全 API 验收仍为 false。

## 下一阶段与边界

下一步继续按缺口清单优先处理车主/商家剩余业务域（社区图片治理、AI 与首页读接口、运营与考核读接口），并保持每阶段独立运行、独立上传。

本阶段身份与图片元数据为隔离夹具，支付未参与；正式微信身份、真实资金、生产扫描鲜度、真机与外部云存储仍未验收。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。
