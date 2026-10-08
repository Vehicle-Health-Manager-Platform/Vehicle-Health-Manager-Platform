# A5.2 派工与本人接单后端执行记录

日期：2026-10-08。状态：已编码，本机相关测试与 GitHub 收口进行中。A5.3 商家/技师界面、A5.4 完整安全竞争验收、A5.5 端到端联调后续交付。

## 基线与变更

基于 A5.1 最终提交 `c22e381` 建立 `codex/a5-dispatch-backend`；前序 [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36) 尚未合并，阶段 PR 以该分支为基线。A4 PR #35 同样未合并，不自动合并或部署。

- 六个后端接口：本店候选技师、派工详情/首次派工、技师本人工单列表/详情/接单，见[契约](../api/TECHNICIAN_DISPATCH.md)。候选为本店有效且当前 AppID 已有效绑定的技师，归属是员工 ID。
- 派工保持 `RECEIVED` 并写 `assigned_at`/派工人；本人接单写 `accepted_at`，派工 `ACCEPTED`、订单 `IN_SERVICE`，订单迁移与成功审计、幂等响应同事务。缺接车/车主确认拒绝；防护不阻断 A5。
- 严格技师 actor 与数据库会话/员工/绑定/商家复核，成功缓存重放仍校验。本人查询最小投影，不包含车主 PII、车辆 ID、核销码、付款流水或私有图片。
- 停止商家通用 START_SERVICE（43004），商家 `allowed_actions` 不再投影它；保留状态矩阵及包内履约测试入口。业务 HTTP 的施工开始只走本人接单。
- 身份写操作统一商家→技师员工→绑定锁顺序；锁后使用当前读取重新检查员工/员工码。派工/接单沿用商家→时段→订单顺序，资源锁先于幂等记录。
- 新增 V012 扩充既有派工 `assigned_by`、`accepted_at`，可重复执行，50 表和订单唯一键不变。异常历史派工返回冲突，不覆盖、不补假证据。
- OpenAPI 生成器同步六个严格接口与响应，操作数 85→89（两项已有草案被正式覆盖）；构建/结构脚本与中文文档同步。

## 迁移与回滚

新 Compose 卷按 V001–V012 初始化；已有数据库先备份并盘点派工异常，再执行 `docs/sql/migrations/V012__technician_dispatch.sql`，确认两列和唯一键后上线应用。数据库脚本仅扩列，不填历史值。回滚应用保留新增列、派工数据及审计，不能自动把施工中订单回退或改派。

本次本机验证使用独立 Testcontainers MySQL，未升级共享业务数据库或重启共享后端；主机环境的实际补迁移在部署/联调时按上述顺序执行。

## 实际验证

首次运行相关 65 项后端测试，新增真实 MySQL 13 项、接口 7 项、无数据库 2 项及身份/接车/状态回归通过；一条旧商家 HTTP 故障用例仍请求已停用的 START_SERVICE，导致预期 503 与实际 409 不符，已改为 FINISH_SERVICE 故障注入并独立断言旧入口 43004。修正后相关 24 项重跑通过（商家 HTTP 5、身份 6、真实派工 13，失败/跳过均 0）。提交复核又补强历史员工跨店校验，最终 13 项真实派工测试正在复核。

新测试覆盖：资格/当前 AppID、跨店/跨人、缺接车/确认、重复及同键异体、成功缓存权限撤销、身份字段不一致、历史异常、派工/接单审计与缓存故障回滚、两人派工竞争、本人并发接单、解绑竞争与迁移重复执行。测试数据均合成，未使用客户身份、图片或真实付款。

本机命令：

- Maven/JDK17 容器执行 `mvn -q -Dapi.version=1.44 -Dtest=TechnicianAssignmentsHttpTest,TechnicianAssignmentsNoDatabaseTest,JdbcTechnicianAssignmentsTest,JdbcIdentityRepositoryTest,OrderStatusTest,MerchantOrdersHttpTest,JdbcOrderFulfillmentTest,JdbcPickupInspectionTest test`。本机 Docker 29 使用 API 1.44 兼容参数、`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` 与 `TESTCONTAINERS_RYUK_DISABLED=true`，测试容器由生命周期清理；不修改仓库 CI 环境。
- 修正后重跑 `MerchantOrdersHttpTest,JdbcIdentityRepositoryTest,JdbcTechnicianAssignmentsTest`，复核最终身份锁代码。
- `npm test --workspace @autocare/miniapp`：120 项通过，失败/跳过均 0；只更新旧入口拒绝提示，不计新界面验收。
- 生成文件与严格六接口检查通过，操作数 89；13 个新增/修改文档本地链接检查通过，git diff --check 通过。迁移脚本与 GitHub 六项 CI 后补最终结果。

## 下一步与外部验收

A5.3 实现独立技师会话、商家派工页面、技师工作台/详情与本人接单交互；按原计划处理页面代次、旧账号响应、确认回调、断网幂等重试和刷新。A5.4/A5.5 补完整竞争、安全和真实接口页面联调，之后独立设计争议处理，再推进 A6 防护/报工。

真实相机、真机、测试证书下的直连图片、正式微信/短信/收款/退款与公网环境仍待各自验收。此次数据库测试和接口替身测试不等于真实技师微信登录或页面联调通过。
