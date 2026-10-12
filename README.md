# 汽车健康管家平台

面向车主、汽车服务门店和技师的汽车服务平台。当前产品交付重点是**微信小程序**及其共用后端：车主用小程序管理车辆、档案和服务订单；门店与技师在小程序中完成门店运营和履约；必要的运营审核能力也通过受限的小程序入口提供。

**PC 运营后台不在本项目交付范围内。** 仓库中的旧 Vue/Vite 网页项目和 PC 管理端只作为历史原型及兼容性构建目标，不代表本阶段正在开发桌面运营产品。

**2026-10-11 合并状态：** R1c 至 R3c2 阶段 PR #85–#118 已按依赖顺序合并到 `main`；各阶段六项 CI 均通过。该合并记录为历史基线，后续分支进度见下方实施更新。真实短信/身份、正式资金、供应商识别和真机验收仍按下方边界单独跟踪。

**2026-10-12 实施更新：** R3c3 档案摘要限时分享与撤销已在后端 [#122](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/122) 和微信小程序 [#123](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/123) 分阶段上传，尚未合并。支持登录车主持链接只读查看固定摘要、1小时/24小时/7天有效期和车主撤销；不含车牌/VIN、备注、照片、签字或PDF。微信341项测试及mp-weixin构建通过；原生分享与真机仍待验收。R3c3详见[执行记录](docs/progress/R3C3_ARCHIVE_SHARE_EXECUTION.md)。

**R4a1实施更新：** 本人保养日期/里程、保险与年检日期目标到达提醒已在后端[#125](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/125)和微信[#126](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/126)上传。含来源替换/停用、提醒列表及首页摘要；不预设周期，提前提醒后补。后端本地定向8项、微信351项及微信/H5构建通过。其后R4a2三类本人字段也已实施（见下方），六指标/健康分数未完成；详见[执行](docs/progress/R4A1_ARCHIVE_REMINDERS_EXECUTION.md)。

**R4a2实施更新：** 后端[#128](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/128)、微信[#129](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/129)补齐本人保养项目/单位/里程、保险资料及年检资料的结构化字段，支持修正/确认清空并标注尚未核验。旧档案/报告/分享摘要兼容，不自动改目标或登记里程。后端定向8项、微信361项、微信/H5构建通过；下一步[R4a3健康来源与指标口径](docs/progress/R4A3_HEALTH_DATA_NEXT_PLAN.md)，完整证件/健康评分及真机仍欠。[执行与验收](docs/progress/R4A2_STRUCTURED_ARCHIVE_EXECUTION.md)。

## 项目范围与角色

| 使用者 | 微信小程序能力 |
| --- | --- |
| 车主 | 管理本人车辆；手动录入车辆、维护档案与私有图片；浏览标准服务、比较门店报价、预约和查看订单；确认服务、提出异议；查看完工档案；核对支付与退款状态；导出本人档案 PDF。 |
| 门店店长 | 维护门店资料、员工和员工码、服务项目与营业时段；查看本店订单、报价和佣金账单；处理接车、派工、履约防护、服务报工、异议及退款申请。 |
| 门店店员 | 在受限权限内协助门店接车和履约。员工管理、员工码、门店资料、定价等管理能力仍限店长。 |
| 技师 | 通过门店员工码绑定后查看派给本人的工单、接受/拒绝任务、提交服务过程与完工证据；不能查看不属于自己的客户和工单。 |
| 平台运营 | 通过小程序受限入口处理入驻审核、配额、标准目录和经验内容审核等运营事务。没有 PC 运营后台交付。 |

业务读写由后端按有效会话、角色、本人或本店归属进行复核。小程序角色入口和本地测试身份不等于正式身份认证或生产授权。

## 微信小程序界面演示

以下为微信开发者工具模拟器中的小程序截图。派工画面使用合成订单和技师数据；截图用于展示界面与操作流程，真机及生产环境验收状态见下方交付说明。点击图片可查看原图。

| 车主入口 | 门店派工确认 | 运营审核入口 |
| --- | --- | --- |
| [<img src="docs/ui/screenshots/owner-entry.png" alt="微信小程序车主入口，展示车辆档案与预约流程介绍及微信登录按钮" width="240">](docs/ui/screenshots/owner-entry.png) | [<img src="docs/ui/screenshots/merchant-dispatch.png" alt="微信小程序门店派工确认弹窗，使用合成订单和技师数据" width="240">](docs/ui/screenshots/merchant-dispatch.png) | [<img src="docs/ui/screenshots/operator-login.png" alt="微信小程序运营审核登录表单，包含账号、密码和短信验证码输入框" width="240">](docs/ui/screenshots/operator-login.png) |
| 车主工作入口与登录提示 | 确认派工及技师接单说明 | 受限运营审核登录表单 |

截图来源与更新方式见[演示图集说明](docs/ui/screenshots/README.md)。

## 已交付阶段

截至 2026-10-12，main主要业务推进至R3c2，分支新增R3c3分享、R4a1到达提醒和R4a2本人结构化资料，尚未合并。每阶段的接口契约、测试证据和未验收项见对应文档；**CI 通过、PR 合并、生产部署和真机验收是不同状态**。

| 阶段 | 交付内容 |
| --- | --- |
| R1a–R1b | 门店入驻申请与审核、区域/品类配额、员工维护与员工码、门店资料及履约角色隔离。 |
| R1c | 服务项目、品牌、车型等标准目录治理，发布/停用、排序、权限和历史引用保护。 |
| R2a | 订单完成事件可靠归档；完工快照与经验草稿保持私有，评价不改写已归档服务快照。 |
| R2b1–R2b2 | 支付结果核对与恢复；双方同意取消时全额退款，暂不支持部分退款；测试支付渠道不发生真实扣款或退款。 |
| R2c | 佣金应计与退款冲销、门店店长只读账单；账单不代表真实资金扣佣或结算。 |
| R3a1 | 车主录车入口、本人车辆/车牌匹配、VIN 格式检查与手动确认回退。未接入真实 VIN 或行驶证 OCR 服务。 |
| R3c1 | 车主接车二维码、本店授权扫码、接车八图和独立油表照片；旧七图历史记录仍可读取，不伪造缺失油表证据。 |
| R3c2 | 车主本人档案 PDF 导出、日期区间、HTTP 与云托管传输、原生下载/打开及任务取消和临时文件清理。小程序测试 331/331；mp-weixin 与 H5 构建通过；后端 CI 验证 500/501 条数及 PDF 3 MiB 边界。 |

| R3c3（分支） | 登录车主持链接只读摘要、限时分享与撤销、来源权限和访问审计。 |
| R4a1（分支） | 本人档案关联日期/保养里程目标、到达提醒、替换/停用与首页摘要；完整证件字段及评分仍欠。 |

| R4a2（分支） | 本人保养、保险、年检结构化资料与修正/清空，来源/未核验状态；不扩展公开分享权限。 |

对应阶段记录入口：[当前进度](docs/progress/CURRENT_STATUS.md)、[下一步规划](docs/progress/NEXT_STEPS.md)、[三端功能缺口](docs/progress/ROLE_GAP_ANALYSIS.md)、[测试与验收目录](docs/testing/README.md)、[接口文档目录](docs/api/README.md)。

### 当前仍待完成或验收

- R3c3 分享与 R4a1 目标提醒已编码上传，仍待按依赖合并与原生/真机验收。
- R4a2本人三类资料已编码上传；驾驶证/违章完整资料与官方核验仍欠。R4a3健康测量来源、六指标趋势/评分及五类提醒继续规划。
- 商家发现/公开评价、AI方案到有效服务预约、券/邀请/积分、三端进度及取车对比、关联档案/通知、经营考核/社区补齐依次推进，详见[三端总规划](docs/progress/THREE_ROLE_BUSINESS_PLAN_2026-10-12.md)。
- VIN/行驶证 OCR、里程识别、照片或语音档案识别：尚无已开通供应商，本地流程提供手动录入或确认回退。
- 车主真实微信登录、正式手机号绑定、门店账号激活及正式短信：供应商/生产配置和最终验收仍待完成。
- 微信支付与退款的真实资金流、佣金结算：现有隔离测试渠道和账单不能作为真实资金完成证明。
- 真机相机、扫码和系统 PDF 阅读器的视觉及生命周期验收；本地单测、H5 页面和模拟器路由验证不能替代真机验收。
- 正式环境的 HTTPS/通讯域名、微信主体权限、数据库与对象存储部署及发布验收。
- PC 运营后台不实施。

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
