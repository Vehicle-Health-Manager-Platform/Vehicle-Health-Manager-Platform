# 汽车健康管家平台

面向车主、汽车服务门店和技师的汽车服务平台。当前交付产品是 **微信小程序及共用后端**：车主管理车辆、档案与订单，门店和技师完成接车、派工、施工与核销，必要的运营审核通过受限的小程序入口提供。

**PC 运营后台不在交付范围内。** 仓库保留的 Vue/Vite 网页与 PC 管理端属于历史原型和兼容构建目标。

## 当前进度

更新日期：**2026-10-12（Asia/Shanghai）**。

- 今天的规划及业务 PR **#121–#139 已按依赖顺序合并到 main**，业务推进至 **R4b1 商家详情与区域选择**；每支堆叠 PR 均在前序合并后将 base 改回 main，再以合并提交保留依赖历史。
- 已确认的三张微信开发者工具模拟器截图随 **[#120](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/120)** 合并，图集见下方。
- 最新 R4b1 收口 **[#139](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/139)** 六项 CI 全绿：后端 **633/633**、小程序 **388/388**；微信 mp-weixin 与 H5 兼容构建通过。[CI 证据](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/38245718489)。这是已完成阶段的测试基线；后续 main 和文档更新的检查结果以 GitHub Actions 当前运行记录为准。
- 下一单元为 **R4b2a 位置与距离**，随后 R4b2b 公开评价。真实短信、微信身份、资金、识别供应商与真机验收安排在最终接入阶段。

合并、CI、生产部署与真机验收分别记录；本次合并没有发布生产服务。历史阶段记录中的“待合并”和“README 未更新”描述反映当时状态，当前状态以本节及[合并记录](docs/progress/MERGE_README_2026-10-12.md)为准。

## 三端功能与权限

| 使用者 | 已实现的微信小程序能力 | 权限边界 |
| --- | --- | --- |
| 车主 | 本人车辆与录车入口；手动档案和私有图片；结构化保养/保险/年检；六类当前读数与历史；目标提醒；本人 PDF 与限时摘要分享；标准服务、区域筛选、商家报价与预约；订单、接车确认/异议、支付核对、取消退款、本人评价和经验卡片。 | 仅本人车辆、档案和订单；公开经验另行授权审核；分享接收须有效车主登录，不公开私有标识或文件。 |
| 门店店长 | 门店入驻与资料；员工启停和员工码；选品报价与预约时段；本店订单、接车检查/扫码、派工、防护、报工和核销；异议与取消退款处理；只读佣金账单。 | 仅本店；员工、员工码、资料修改和定价管理限店长；账单区分测试与真实渠道，不代表已结算。 |
| 门店店员 | 共用门店登录入口，协助本店接车、订单与履约。 | 员工管理、员工码、资料修改、定价及佣金账单等店长能力不开放。 |
| 技师 | 员工码绑定；查看派给本人的工单、接受/拒绝任务、施工报工、故障件与完工证据、质检和签字。 | 有效绑定与本人任务授权；解绑、停用即时复核，不开放车主全档案。 |
| 受限运营 | 入驻审核与区域/品类配额；标准项目、品牌和车型治理；经验内容审核。 | 使用独立权限的小程序入口；不建设 PC 运营后台。 |

后端按有效会话、角色、资源归属和业务状态复核每次操作；本地演示身份不等于正式微信身份验收。

## 已合并业务阶段

| 阶段 | 已实现内容 | 说明与文档 |
| --- | --- | --- |
| 核心履约 A 系列 | 服务目录、报价/时段预约、订单；接车检查与车主确认/异议；派工与技师确认；施工防护/报工/质检签字；核销、本人评价和归档。 | [履约接口](docs/api/ORDER_FULFILLMENT.md) · [施工报工](docs/api/SERVICE_WORK.md) |
| R1a–R1b | 入驻申请/审核、区域品类配额、员工维护/启停、员工码、门店资料和履约角色隔离。 | [入驻](docs/api/MERCHANT_ONBOARDING.md) · [员工与资料](docs/api/MERCHANT_STAFF.md) |
| R1c | 标准项目、品牌、车型治理；发布/停用、排序和历史引用保护。 | [标准目录](docs/api/CATALOG_GOVERNANCE.md) |
| R2a | 核销同事务生成可靠归档事件，无评价也可生成本人施工档案；迟到评价不改写冻结快照。 | [服务档案](docs/api/SERVICE_ARCHIVES.md) |
| R2b1–R2b2 | 支付核对与持久恢复；双方同意取消后全额退款，暂不开放部分退款；退款通知/查询/重试恢复。 | [支付恢复](docs/api/PAYMENT_RECOVERY.md) · [取消退款](docs/api/ORDER_REFUNDS.md)；订单关闭与资金成功分别展示。 |
| R2c | 3% 佣金规则、可信批准后三自然月免佣、冻结快照、核销应计、成功退款冲销、本店只读账单。 | [佣金](docs/api/COMMISSIONS.md)；未知政策/旧单/未接入券单保留待核对，暂无真实扣佣结算。 |
| R3a1 | 录车四入口、本人车牌匹配、VIN 格式检查与手动确认回退。 | [录车](docs/api/VEHICLE_ENTRY.md)；真实 VIN/行驶证 OCR 供应商未接入。 |
| R3c1 | 车主接车二维码、本店授权扫码、接车八图和独立油表照片。 | [接车检查](docs/api/PICKUP_INSPECTION.md)；旧七图记录兼容读取，不补造证据。 |
| R3c2 | 本人档案 PDF、日期区间、HTTP/云托管传输、原生下载/打开与临时文件清理。 | [验收记录](docs/testing/LOCAL_ARCHIVE_REPORT_ACCEPTANCE.md)；真实微信 PDF 阅读器另验收。 |
| R3c3 | 登录车主持链接查看固定脱敏摘要；1 小时/24 小时（默认）/7 天有效期、撤销、过期和访问审计。 | [分享契约](docs/api/ARCHIVE_SHARES.md)；不含车牌/VIN、备注、照片、签字或 PDF；转发后其他已登录车主也可持链接查看。 |
| R4a1 | 本人保养日期/里程、保险/年检日期目标；到达提醒、来源替换/停用、提醒列表与首页摘要。 | [提醒契约](docs/api/ARCHIVE_REMINDERS.md)；不预设保养周期或推断法定年检日期，提前提醒后补。 |
| R4a2 | 本人保养、保险、年检结构化资料；修正与确认清空、来源和未核验状态。 | [结构化资料](docs/api/ARCHIVE_STRUCTURED.md)；兼容旧档案/报告/分享，不自动修改提醒或登记里程。 |
| R4a3 | 机油、刹车片、轮胎、动力电池、冷却液、低压电瓶六类当前原始读数；值/单位/日期/方法/来源、修正和清空。 | [读数契约](docs/api/HEALTH_OBSERVATIONS.md)；动力电池与低压电瓶分开，未录入或失效显示未知，没有健康评分。 |
| R4a4 | 本人不可变读数历史、新测量与修正链、原值保留、清空保留履历、版本游标分页和来源失效脱敏。 | [历史契约](docs/api/HEALTH_OBSERVATION_HISTORY.md)；旧数据只迁移当前快照，不编造过去记录；原车主历史不随车辆转移公开。 |
| R4b1 | 原生省市区选择、区域报价过滤/恢复全部、最小商家详情、有效在售项目和最新报价分页、衔接既有预约。 | [商家发现](docs/api/MERCHANT_DISCOVERY.md)；预约重新取最新报价/时段并核验版本与容量；暂无距离或公开评分。 |

阶段执行与验收：[当前进度](docs/progress/CURRENT_STATUS.md) · [下一步](docs/progress/NEXT_STEPS.md) · [三端缺口](docs/progress/ROLE_GAP_ANALYSIS.md) · [测试目录](docs/testing/README.md) · [API 目录](docs/api/README.md)。

## 未完成事项与后续顺序

| 顺序 | 阶段 | 需要补齐的功能 |
| --- | --- | --- |
| 1 | R4b2a 位置与距离 | 门店坐标来源/坐标系、车主主动定位、拒绝后手选区域、可信坐标的直线距离与稳定排序；未知坐标不猜距离。 |
| 2 | R4b2b 公开评价 | 完成订单评价公开资格、授权/撤销/脱敏、测试评价隔离、只读评价列表与可核对汇总；AI 总结另按授权来源实施。 |
| 3 | R4c AI 方案到预约 | 结构化必做/推荐/可选方案，映射有效标准项目、门店报价与时段预约；失败可回退手动选服务。 |
| 4 | R5 券 | 门店券模板/库存/有效期、券池与领券、我的券；订单占用/消费/取消释放/退款返券、独立核销及佣金联动。 |
| 5 | R6 邀请 | 车主邀请与海报、店员推广码、新用户有效归因、月度循环与双方奖励、失败恢复。 |
| 6 | R7 三端体验 | 车主施工进度/取车证据对照；门店/技师必要关联档案与工作台；站内事件通知、人工留言、草稿/弱网/登录回跳。 |
| 7 | R8 积分/经营/社区 | 积分任务与兑换；可复算考核、门店经营/客户与价格数据；授权审核的同款经验和问答补齐。 |
| 最后 | R9 正式接入与交付 | OCR/VIN/里程与照片语音识别；短信、真实微信身份/手机号/员工绑定与门店激活；真实支付退款、对账结算、订阅；性能、设备、部署、提审发布。 |

此外，**可信测量来源、车型适用性与有效期、健康算法/评分与曲线、完整驾驶证/违章资料、五类与提前提醒**仍有缺口，不因读数录入或提醒页面完成而销项。供应商尚未开通时继续可独立实施的业务，真实身份和资金安排最后接入。R7a/R7b 可在前序等待外部条件时提前推进。

详细拆分：[R4b2 位置与评价](docs/progress/R4B2_LOCATION_REVIEWS_NEXT_PLAN.md) · [三端总规划](docs/progress/THREE_ROLE_BUSINESS_PLAN_2026-10-12.md) · [原始交付差异审计](docs/progress/DELIVERY_GAP_AUDIT_2026-10-12.md)。差异审计保留审计时点，最新实现状态请结合当前进度阅读。

### 仍需独立验收

- 真实相机、扫码、私有图片直连预览/证书、系统选择器与 PDF 阅读器，以及原生分享和区域弹窗；模拟器辅助结果与离线测试不能替代真机操作证据。
- 正式微信主体/权限、HTTPS 与通讯域名、数据库/对象存储/扫描设施；真实短信、身份、资金和供应商识别。
- 性能目标、安全与备份恢复、发布回滚、监控和真实微信提审上线。

本期不实施 PC 运营后台、部分退款、第二次异议与超时自动处理。其余边界以三端规划和具体规格为准。

## 测试与数据库基线

| 检查 | 最新已完成阶段结果 | 覆盖内容 |
| --- | --- | --- |
| backend | 633/633，CI SUCCESS | Java 测试、真实 MySQL/上传容器与相关 HTTP 权限/事务边界；各阶段替身与真实链路证据分别记录。 |
| miniapp | 388/388，CI SUCCESS | 状态流、契约、账号隔离与迟到请求等离线用例；mp-weixin 与 H5 构建。 |
| web | CI SUCCESS | 历史 Vue/Vite 项目兼容构建。 |
| schema-and-ocr | CI SUCCESS | 追踪表、SQL/OpenAPI 生成一致性和 OCR Python 语法；不代表真实 OCR 识别效果。 |
| schema-mysql | CI SUCCESS | MySQL 8.0 初始化、迁移重放及合成种子数据；V031 后 71 表。 |
| compose-smoke | CI SUCCESS | CI 隔离 Compose 启动与连通性，不代表生产部署。 |

数据库基线 **44 表**，顺序应用至 **V031 后共 71 表**；OpenAPI **156 个操作**。R4b1 无新增迁移。已有数据卷需按版本升级；V031 历史迁移只保存可信当前快照，不能恢复不存在的历史数据。

最新阶段验收：[分享](docs/testing/LOCAL_ARCHIVE_SHARE_ACCEPTANCE.md) · [提醒](docs/testing/LOCAL_ARCHIVE_REMINDERS_ACCEPTANCE.md) · [结构化档案](docs/testing/LOCAL_STRUCTURED_ARCHIVE_ACCEPTANCE.md) · [当前读数](docs/testing/LOCAL_HEALTH_OBSERVATIONS_ACCEPTANCE.md) · [读数历史](docs/testing/LOCAL_HEALTH_HISTORY_ACCEPTANCE.md) · [商家发现](docs/testing/LOCAL_MERCHANT_DISCOVERY_ACCEPTANCE.md)。

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

- 一个阶段完成后同步规格、接口、进度和测试文档，再将该阶段推送为独立 GitHub PR。
- 堆叠 PR 按依赖顺序合并；合并时先把当前 PR 的 base 调整到 main，确认变更范围与检查结果后再合并。
- 记录测试时区分离线替身、真实后端/数据库、开发者工具和真实微信设备证据；不得用前者替代后者。
- 变更 API、数据库或权限时补充正反向测试，覆盖本人/跨用户、本店/跨店、重复提交、状态竞争和失败回滚等实际边界。
- PR 中不提交个人交付文档、密钥、数据库口令、真实用户数据、临时构建产物或测试凭据。

欢迎从 [当前进度](docs/progress/CURRENT_STATUS.md) 和 [下一步规划](docs/progress/NEXT_STEPS.md) 开始了解项目。
