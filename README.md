# 汽车健康管家平台

面向车主、汽车服务门店和技师的汽车服务平台。当前交付产品是 **微信小程序及共用后端**：车主管理车辆、档案与订单，门店和技师完成接车、派工、施工与核销，必要的运营审核通过受限的小程序入口提供。

**PC 运营后台不在交付范围内。** 仓库保留的 Vue/Vite 网页与 PC 管理端属于历史原型和兼容构建目标。

## 当前进度

更新日期：**2026-10-17（Asia/Shanghai）**。

- R4–R7 与此前 R8 增量已通过 [PR #154](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/154) 进入 main；本轮统一补齐 R8 剩余软件范围，代码、迁移、测试、配置与文档一起交付。
- **R8 按已确认的软件范围完成**：公开问答实际页面与恢复、独立脱敏图片授权/审核/撤回、许可资料治理、车型关键词检索与可追溯 AI 参考均已实现。[整阶段销项](docs/progress/R8_SOFTWARE_COMPLETION.md)。
- 本轮本地前端 **868/868**、集中相关后端 **180 次执行**及微信/H5/四 Web 构建、MySQL 迁移通过。全量后端和实际 Compose 以本次统一 PR 最终提交的六项 CI 为准；本地 Compose 因 Docker Hub 网络失败未完成。[本轮验收](docs/testing/LOCAL_R8_COMPLETION_ACCEPTANCE.md)。
- 暂无正式授权资料与嵌入服务：已完成配置、KEYWORD 检索和引用闭环，**不声称 Milvus 向量效果或真实语料验收完成**。供应商识别、正式短信/微信身份/资金、真机及发布交付继续最后 R9。

R5–R7 的历史串联 PR #151–#153 已在确认全部提交进入 main 后关闭，实际汇总合并记录为 #154。上传与代码合并不代表生产部署或真机验收。历史阶段文档中的“仅本地”“等待整 R8 后上传”等描述保留为当时记录，由最新上传授权及[汇总交付说明](docs/progress/R8_PARTIAL_UPLOAD_MERGE_2026_10_17.md)覆盖。

## 三端功能与权限

| 使用者 | 已实现的微信小程序能力 | 权限边界 |
| --- | --- | --- |
| 车主 | 本人车辆、结构化档案、六类健康读数与来源规则、历史趋势、五类提醒；本人 PDF/限时摘要分享；服务发现、方案预约、订单、接车确认、施工证据、支付核对与退款；优惠券、邀请奖励、积分与兑换；诊断留言、运营精选、私有/另行授权公开问答、公开图片副本及许可专业资料检索。 | 仅本人资源；健康来源不足显示未知；历史档案逐订单逐条授权；公开内容另行授权审核。 |
| 门店店长 | 入驻、资料与员工管理；选品报价/时段、订单、接车、派工、施工与核销；券发行和服务券核销；佣金账单、月结成绩、经营看板与最小客户服务关系。 | 仅本店；客户页不显示电话、车牌、VIN 或跨店记录；经营净额不代表已结算资金。 |
| 门店店员 | 本店接车、订单与履约；员工推广归因和本人考核明细。 | 不开放店长的员工管理、定价、账单及客户管理权限。 |
| 技师 | 员工码绑定、本人派工接单/拒绝、施工报工、故障件与完工证据、质检签字和任务工作台。 | 有效绑定及本人任务范围；仅已接单且本单获授权时读取指定历史档案，撤销或工单失效后拒绝新读取。 |
| 受限运营 | 入驻/内容审核、标准目录与车型治理、来源健康规则、券池、邀请/积分/考核政策、经营/价格数据、运营精选、问答和图片审核、独立许可资料管理。 | 小程序独立权限入口；私有问答只限原提问者与当前获派人员；公开审核仅访问另行授权内容，许可资料使用独立权限。 |

后端按当前会话、角色、资源归属和业务状态复核操作。新增权限和关键业务开关默认关闭；本地测试身份不能作为正式微信身份验收。

## 已合并业务阶段

| 阶段 | 已实现内容 | 说明与文档 |
| --- | --- | --- |
| 核心履约与 R1 | 目录、报价/时段预约、订单、接车与异议、派工、施工证据、质检签字、核销与本人评价；门店入驻、员工管理、区域品类配额和目录治理。 | [履约](docs/api/ORDER_FULFILLMENT.md) · [施工](docs/api/SERVICE_WORK.md) · [入驻](docs/api/MERCHANT_ONBOARDING.md) · [目录治理](docs/api/CATALOG_GOVERNANCE.md) |
| R2 | 可靠施工归档；支付核对与持久恢复、取消后全额退款；来源佣金政策、冻结快照、核销应计与退款冲销。 | [档案](docs/api/SERVICE_ARCHIVES.md) · [支付恢复](docs/api/PAYMENT_RECOVERY.md) · [退款](docs/api/ORDER_REFUNDS.md) · [佣金](docs/api/COMMISSIONS.md)；无正式资金结算。 |
| R3 | 录车入口与手动回退、接车八图及油表照片、本人档案 PDF、限时脱敏摘要分享与撤销。 | [录车](docs/api/VEHICLE_ENTRY.md) · [接车](docs/api/PICKUP_INSPECTION.md) · [分享](docs/api/ARCHIVE_SHARES.md)；识别供应商和真机另验收。 |
| R4 | 结构化人工档案、六指标读数与不可变历史、精确车型来源规则、参考评分/趋势、有来源技术必做、五类与提前提醒；区域/位置/距离、公开评价及授权 AI 总结、车型适用证据、AI 方案到当前报价/时段预约。 | [R4 收口](docs/api/R4_COMPLETION.md) · [位置](docs/api/MERCHANT_LOCATION.md) · [公开评价](docs/api/PUBLIC_RATINGS.md) · [方案预约](docs/api/PLAN_BOOKING.md)；未配置或证据不足显示未知，不预置技术阈值。 |
| R5 | 门店券模板、库存与有效期、运营券池、本人领券；现金订单抵扣/占用/消费/释放/退款返券、独立服务券核销与佣金联动、持久恢复。 | [券库存与领取](docs/api/COUPON_INVENTORY.md) · [券交易](docs/api/COUPON_TRANSACTIONS.md)；每单一券，金额券保持正实付，服务券独立核销。 |
| R6 | 车主邀请、海报和员工推广；有效新客归因、每月累计 1/3/6/10 人循环、双方配置奖励、员工考核归因、有来源当月人工诊断资格。 | [邀请](docs/api/INVITATIONS.md) · [奖励](docs/api/INVITATION_REWARDS.md) · [诊断资格](docs/api/INVITE_DIAGNOSIS_ELIGIBILITY.md)；不虚构有效新客或正式发券证据。 |
| R7 | 车主施工进度与证据对照、门店/技师任务工作台、逐单逐条档案授权；持久站内通知、非实时诊断留言/分配/答复、确认及账号隔离恢复。 | [车主证据](docs/api/OWNER_SERVICE_EVIDENCE.md) · [工作台](docs/api/SERVICE_WORKSPACE.md) · [通知](docs/api/SERVICE_NOTIFICATIONS.md) · [诊断](docs/api/ASYNC_DIAGNOSIS.md) |
| R8a | 有来源任务配置、每日签到/首次完整人工档案/首次成功 AI 对话的可信事件与积分账本、原子扣分兑券、本人目录/确认/恢复。 | [积分账本](docs/api/POINT_LEDGER.md) · [兑换](docs/api/POINT_EXCHANGES.md) · [本人兑换](docs/api/OWNER_POINT_EXCHANGES.md)；未配置显示未知，不补发历史或测试行为。 |
| R8b | 来源月度考核、40%/40%/20% 规则、不可变月结快照和修订；经营看板、本店最小客户关系、具体报价版本的来源价格区间与只读比较。 | [月结](docs/api/ASSESSMENT_SNAPSHOTS.md) · [经营](docs/api/BUSINESS_DASHBOARD.md) · [客户](docs/api/CUSTOMER_RELATIONS.md) · [价格](docs/api/PRICE_COMPARISONS.md)；偏离只标记需核对。 |
| R8c | 受控同款经验与来源精选、私有及另行授权公开问答的前后端；独立图片副本治理；许可来源、版本/车型/用途/有效期配置、关键词检索及调用后重验的 AI 参考。 | [公开问答](docs/api/PUBLIC_COMMUNITY_QUESTIONS.md) · [图片](docs/api/PUBLIC_IMAGES.md) · [专业资料](docs/api/LICENSED_KNOWLEDGE.md)；暂无正式语料或向量效果验收。 |

阶段记录与索引：[当前进度](docs/progress/CURRENT_STATUS.md) · [R8 总计划](docs/progress/R8_IMPLEMENTATION_PLAN.md) · [测试目录](docs/testing/README.md) · [API 目录](docs/api/README.md)。

## 未完成事项与后续顺序

1. **正式授权资料与检索效果**：取得授权语料及嵌入服务后，再独立验收内容许可、Milvus 向量检索与真实资料效果；当前空目录显示未知，不补虚构内容。
2. **R9 正式接入与交付**：供应商 OCR/VIN/里程/照片语音识别，正式短信、微信身份/手机号/员工绑定和门店激活，真实支付退款、对账结算、订阅消息，性能、安全备份、真机、部署及提审发布。

当前范围见[R8 软件销项及外部条件](docs/progress/R8_SOFTWARE_COMPLETION.md)，此前上传记录见[PR154 汇总](docs/progress/R8_PARTIAL_UPLOAD_MERGE_2026_10_17.md)。完整驾驶证/违章资料及可信来源仍需按原始交付需求逐项复核；已实现提醒框架不代表正式数据供应商接入。

### 仍需独立验收

- 真机相机、扫码、私有图片、证书、系统选择器、PDF 阅读器及原生分享；开发者工具截图和离线测试不能替代真机证据。
- 正式微信主体、权限、HTTPS/通讯域名、数据库/对象存储/扫描设施及真实短信、身份和资金。
- 性能、安全与备份恢复、发布回滚、监控和真实微信提审上线。

本期不实施 PC 运营后台、部分退款、第二次异议与超时自动处理。其余边界以具体阶段规格为准。

## 测试与数据库基线

以下历史结果为 PR #154 最终业务提交的[完整 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/39028173826)结果，不是本轮新增代码的全量验收。本轮见[验收记录](docs/testing/LOCAL_R8_COMPLETION_ACCEPTANCE.md)及统一 PR 最终 CI：

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| backend | 2,008/2,008，零失败/错误/跳过 | Java、真实 MySQL/上传容器、HTTP 权限与事务边界；CI 任务上限为 45 分钟。 |
| miniapp | 840/840 | 状态流、确认恢复、账号隔离、迟到请求与实际页面脚本；微信和 H5 构建。 |
| web | SUCCESS | 历史 Vue/Vite 项目兼容构建。 |
| schema-and-ocr | SUCCESS | 追踪/SQL/OpenAPI 生成一致性、OCR Python 语法及原始 Compose 迁移挂载检查；不代表真实识别效果。 |
| schema-mysql | SUCCESS | MySQL 8.0 初始化与迁移重放，保留旧业务凭证。 |
| compose-smoke | SUCCESS | 实际隔离 Compose 初始化、业务表和服务连通性。 |

原始数据库基线 **44 表**；顺序应用到 **V061 后共 130 表**。当前 OpenAPI **350 个操作**、小程序 **108 个页面**，Compose **61 份 SQL 挂载（初始化与 V002–V061）**。迁移源和目标须唯一、编号对应且按序；可运行 `python scripts/verify_compose_migrations.py` 检查。

已有数据库卷须备份后按版本升级，不会自动执行新增初始化脚本；不得通过重新初始化生产数据库替代迁移。测试、合并、生产部署和真机验收分别记录。

## 微信小程序界面演示

以下为微信开发者工具模拟器中的小程序截图。派工画面使用合成订单和技师数据；截图用于展示界面与操作流程，真机及生产环境验收状态见下方交付说明。点击图片可查看原图。

| 车主入口 | 门店派工确认 | 运营审核入口 |
| --- | --- | --- |
| [<img src="docs/ui/screenshots/owner-entry.png" alt="微信小程序车主入口，展示车辆档案与预约流程介绍及微信登录按钮" width="240">](docs/ui/screenshots/owner-entry.png) | [<img src="docs/ui/screenshots/merchant-dispatch.png" alt="微信小程序门店派工确认弹窗，使用合成订单和技师数据" width="240">](docs/ui/screenshots/merchant-dispatch.png) | [<img src="docs/ui/screenshots/operator-login.png" alt="微信小程序运营审核登录表单，包含账号、密码和短信验证码输入框" width="240">](docs/ui/screenshots/operator-login.png) |
| 车主工作入口与登录提示 | 确认派工及技师接单说明 | 受限运营审核登录表单 |

截图来源与更新方式见[演示图集说明](docs/ui/screenshots/README.md)。

## 技术架构

- **微信客户端**：uni-app 3、Vue 3、JavaScript；目标构建为微信小程序 mp-weixin。H5 用于兼容构建和部分本地联调，不是 PC 运营产品。
- **共用后端**：Java 17、Spring Boot 3、Maven 模块化单体。
- **数据与文件**：MySQL；私有上传适配器支持 MinIO 与 ClamAV。存储和扫描通过显式配置开启，不能把未配置的开发环境当作生产安全部署。
- **登录与会话**：服务端执行角色及归属校验。微信登录、手机号和门店短信能力需私有凭据及正式环境配置；敏感凭据不得写入前端、README 或 Git。
- **AI 服务**：车主 AI 接口可配置外部服务凭据；未配置时返回明确降级状态，不影响其他业务。
- **CI**：GitHub Actions 分别检查旧网页构建、微信小程序测试/构建、后端测试、生成文件与 OCR Python、MySQL schema 和 Compose smoke。CI 通过不代表已完成真机、真实资金或生产部署验收。

## 仓库目录

| 路径 | 内容 |
| --- | --- |
| apps/miniapp | 当前微信小程序源码、页面、服务、状态流和离线测试。 |
| backend | 车主、门店、技师及受限运营业务共用的 Spring Boot 后端。 |
| docs/api | API 契约、权限和数据边界说明。 |
| docs/progress | 阶段计划、执行记录、当前进度和功能缺口。 |
| docs/testing | 本地复现、自动化结果、验收证据和未验收边界。 |
| docs/sql | MySQL 初始化脚本、版本化迁移和测试数据说明。 |
| deploy/compose | 本地/CI 使用的 Docker Compose 设施。 |
| deploy/cloudrun | 微信云托管后端部署示例和环境变量模板。 |
| services/ocr | OCR 服务骨架；接入真实供应商前，识别能力不能视为已交付。 |
| apps/owner、apps/merchant、apps/technician | 历史 Vue/Vite 网页原型，非当前微信小程序源码。 |
| apps/admin | 历史 PC 运营网页原型，非本项目交付目标。 |

## 开发环境

- Node.js 22：小程序、工作区依赖与前端构建。
- Java 17：Spring Boot 后端。
- Docker Desktop 或兼容 Docker Engine：运行 Compose 和后端真实 MySQL/MinIO/ClamAV Testcontainers 测试。
- 微信开发者工具：导入编译产物并进行原生小程序验证。

### 构建与测试微信小程序

在仓库根目录执行：

~~~powershell
npm ci
npm test --workspace @autocare/miniapp
npm run build:miniapp
Test-Path .\apps\miniapp\dist\build\mp-weixin\app.json
~~~

最后一条命令应返回 True。微信开发者工具导入目录：

~~~text
apps/miniapp/dist/build/mp-weixin
~~~

源码在 apps/miniapp/src，**不要将仓库根目录或 src 目录直接当作开发者工具项目导入目录**。持续开发可运行：

~~~powershell
npm run dev:miniapp
~~~

然后导入 apps/miniapp/dist/dev/mp-weixin。构建产物位于本仓库的 apps/miniapp/dist，不提交到 Git。

H5 兼容构建及工作区历史网页构建：

~~~powershell
npm run build:h5 --workspace @autocare/miniapp
npm run build:legacy-web
~~~

H5 和旧网页构建只用于兼容性/回归，不代表微信原生交互或 PC 产品验收。

### 启动后端

后端说明、私有环境变量和业务测试边界见 [backend/README.md](backend/README.md)。本机运行可在仓库根目录准备私有配置后启动：

~~~powershell
cd backend
mvn spring-boot:run
~~~

后端默认端口为 8080，健康检查为 GET /actuator/health。启动数据库身份和业务仓储需要 MySQL 环境变量以及已应用的数据库结构；完整后端测试运行：

~~~powershell
cd backend
mvn test
~~~

完整测试需要可用 Docker，以实际运行 MySQL 等容器测试。没有 Docker 的测试结果不应标记为完整后端测试通过。

### 本地 Compose

仓库的 Compose 配置是本地开发/CI 基础设施和历史网页方案的过渡部署，不是已验收的生产部署。按 [deploy/README.md](deploy/README.md) 操作；典型启动命令如下：

~~~powershell
Copy-Item .env.example .env
# 编辑 .env：替换全部占位口令和密钥，只存放在本机私有文件
docker compose --env-file .env -f deploy/compose/compose.yml up --build -d
~~~

新建数据库卷会运行初始化 SQL；已有数据卷不会自动应用新迁移。升级前备份数据库，再按 docs/sql/migrations 中的版本顺序执行迁移。Compose 中 CI 专用演示身份配置不得用于生产。

## 配置与安全

1. 根目录 .env.example 和各模块 .env.example 是变量名/占位值模板；把真实密钥只放在被 Git 忽略的本地私有文件或部署平台密钥管理中。
2. JWT_SECRET 必须是足够长度的随机私有值。WECHAT_APP_SECRET、短信、AI 和对象存储凭据不得放进小程序变量、源码、构建产物或日志。
3. UPLOAD_STORAGE_ENABLED 和 UPLOAD_SCAN_ENABLED 默认关闭。启用私有文件上传前，需配置独立最小权限存储账号、私有桶、ClamAV 扫描和数据库迁移；不要用 MinIO root 账号作为应用上传账号。
4. PAYMENT_LOCAL_TEST 等测试支付配置只限独立隔离环境。当前测试渠道不真实扣款/退款，禁止连接生产资金。
5. 启用微信云托管身份入口前，必须关闭服务公网访问并核验网关注入身份头的部署条件。详细步骤见 [云托管说明](deploy/cloudrun/README.md)。
6. 每次写入仍须在服务端复核当前会话、角色、目标资源归属和状态；前端页面隐藏或按钮禁用不能替代后端授权。

## 数据库与接口文档

初始化 SQL 与数据库迁移位于 docs/sql；OpenAPI 与生成文件由 schema-and-ocr CI 校验。修改接口后应同步更新：

- API 文档与 OpenAPI 定义：docs/api。
- 数据追踪、初始化 SQL 和迁移：docs/progress/S0_TRACEABILITY.csv、docs/sql。
- 阶段计划、执行和验收记录：docs/progress、docs/testing。
- 页面入口、错误状态和用户提示：对应小程序页面及其状态流测试。

浏览入口：[API 文档](docs/api/README.md) · [运维文档](docs/operations/README.md) · [用户指南](docs/user-guides/README.md) · [项目进度](docs/progress/README.md)。

## 贡献与交付约定

- 每个阶段整理规格、接口、进度和验收文档；按用户确认的批次，将全部代码、迁移、测试与文档一起上传。
- 合并前核对目标分支、提交包含关系和当前检查结果；汇总 PR 覆盖的历史串联 PR 须标注实际交付记录，避免重复待处理。
- 记录测试时区分离线替身、真实后端/数据库、开发者工具和真实微信设备证据；不得用前者替代后者。
- 变更 API、数据库或权限时补充正反向测试，覆盖本人/跨用户、本店/跨店、重复提交、状态竞争和失败回滚等实际边界。
- PR 中不提交个人交付文档、密钥、数据库口令、真实用户数据、临时构建产物或测试凭据。

欢迎从 [当前进度](docs/progress/CURRENT_STATUS.md)、[R8 计划](docs/progress/R8_IMPLEMENTATION_PLAN.md) 和 [下一步公开问答页面](docs/progress/R8C1B3B_PUBLIC_QA_UI_NEXT_PLAN.md) 开始了解项目。
