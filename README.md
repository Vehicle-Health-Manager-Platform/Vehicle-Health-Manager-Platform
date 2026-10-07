# 汽车健康管家平台

面向车主、商家和技师的微信小程序及配套后端，车主另有 H5 兜底目标，运营使用 PC 网页。本团队负责小程序和共用接口；运营 PC 后台由协作团队负责。

**当前推进 S2 服务预约与订单，并继续补齐 S0 验收。** 本人车辆、通用档案、首页摘要和拍照录入已实现；开发者工具中的真实微信登录已联调，H5 实际车辆/无图档案链路已通过真实后端验收。本机私有图片与 H5 归档/预览已联调；真机、正式部署、交易、AI 及完整业务仍待验收，M0/M1 尚未通过。详见[当前进度](docs/progress/CURRENT_STATUS.md)及[下一步规划](docs/progress/NEXT_STEPS.md)。

## 已实现的能力

| 范围 | 当前能力 | 验证与边界 |
| --- | --- | --- |
| 微信小程序 | uni-app 3 / Vue3 单测试号三角色入口，车主五个原生 Tab，身份页加载、错误与重试状态 | 小程序与 H5 构建、75 项离线测试通过；车主真实登录按钮已进入首页。真实相机与有数据业务流程仍待验收。 |
| 车主与技师身份 | 微信身份持久化、角色授权、技师绑定、员工码发放/回收、手机号绑定、限流、刷新凭证轮换与退出撤销 | 开发者工具真实 code、车主按钮、技师绑定、刷新和退出已通过；真机网络与手机号授权未验收。 |
| 商家身份 | 密码加一次性短信码、账号/商家状态复核、可刷新与撤销的会话 | 服务商未定，没有生产短信发送器，验证码请求当前返回 503。 |
| 写入完整性 | 24 小时幂等、权限复核、业务/成功响应/成功审计同事务提交 | MySQL 容器验证通过；已接入车辆、档案、图片上传、商家报价、时段与订单写操作；其他业务继续逐项接入。 |
| 私有上传基础 | 10 MiB 文件校验、扫描后存储、JDBC 元数据与失败补偿；真实 MinIO/ClamAV 适配器、内部短时签名及独立账号初始化 | PR #12/#13 的上传与小程序图片操作、PR #15 的业务档案关联已合入 main；官方病毒库的本机上传/签名/归档及 H5 交互已通过；正式部署和真实相机仍待验收。测试合成病毒签名不代表生产官方病毒库验收。 |
| 数据库与 CI | 当前分支包含 V001–V008 与车辆档案图片关联表；网页、小程序、后端、生成文件/OCR、MySQL 和 Compose 共六项 CI | 本机新测试卷已完成 V001–V008；[拍照 PR 最终 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37257482023)和[登录修复首次 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37317893587)均六项全绿。整合后的结果以对应提交 CI 为准。 |
| S1 本人车辆与手动录入 | 档案Tab车辆列表、品牌→车系→年款→配置选择、幂等添加、归属隔离、脱敏与成功审计 | [PR #14](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/14) 已实现；39项离线测试、H5 11项替身交互和模拟器10条路由通过。车型库只读取现有有效数据，生产来源未确定；完整F02/M1未验收。[接口契约](docs/api/VEHICLE_MANUAL.md)。 |
| S1 本人车辆档案手动录入 | [PR #15](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/15) 新增七类通用记录、按车分页、至多五张私有图片关联及本人/CLEAN校验 | [CI 37203770229](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37203770229) 六项全绿，后端132项无失败/跳过；小程序43项Node测试、微信/H5构建通过。无图真实有数据业务已通过本地联调，本机图片归档已通过，真机仍待验收。[接口契约](docs/api/ARCHIVE_MANUAL.md)。 |
| S1 首页车辆与档案摘要 | [PR #16](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/16) 复用本人接口，显示当前车辆、真实档案总数及最近记录，并在首页与档案 Tab 共用车辆选择 | 48项离线测试与小程序/H5构建已通过；H5 实际交互接真实本地后端的两车/摘要已通过，微信完整业务 UI 和真机仍待验收。健康评分和提醒没有数据规则，本步不展示推测值。 |
| S1 本人档案拍照录入 | [PR #17](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/17) 在档案 Tab 增加拍照入口，保存来源为 `input_type=1`，至少一张本人 CLEAN 图片；列表和首页摘要包含拍照记录 | [CI 37256441618](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37256441618) 六项全绿，后端 133 项、小程序 52 项通过；本机真实私有接口及 H5 图片交互已通过；微信真实相机/真机仍待验证。[接口契约](docs/api/ARCHIVE_MANUAL.md)。 |

[PR #23](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/23) 商家报价与选品已接入：车主价格排序、本店维护、版本与幂等审计；本机 H5 15 项、后端 154 项、小程序 65 项通过；首次完整 CI 六项全绿，商家登录使用明确的合成会话桥接。真实短信仍待接入。[接口](docs/api/MERCHANT_QUOTES.md) · [复现](docs/testing/LOCAL_MERCHANT_QUOTES_ACCEPTANCE.md)。

[PR #24](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/24) 已实现商家时段、本人待支付预约、列表/详情、取消与自动到期释放。后端 170 项、小程序 71 项、本机 H5 12 项与模拟器 16 条路由通过；支付、券与退款后续接入。[契约](docs/api/RESERVATION_ORDERS.md) · [复现](docs/testing/LOCAL_RESERVATIONS_ACCEPTANCE.md)。最终交付状态见执行记录。

[PR #25](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/25) 新增支付基础与默认关闭的隔离测试渠道：本人支付记录、签名通知去重、PAID与容量联动、迟到付款异常。H5 24项通过；测试支付明确未真实扣款，正式微信、退款/券仍待接入。[契约](docs/api/PAYMENT_FOUNDATION.md) · [复现](docs/testing/LOCAL_PAYMENTS_ACCEPTANCE.md)。

## 从源码构建微信小程序

使用 Node.js 22，在包含根 `package.json` 的仓库目录运行：

```powershell
npm ci
npm run build:miniapp
Test-Path .\apps\miniapp\dist\build\mp-weixin\app.json
```

最后一行应返回 `True`。微信开发者工具导入 **`apps/miniapp/dist/build/mp-weixin`**，再点击编译。源码根目录没有 `app.json`，直接导入根目录会导致“模拟器启动失败”。源码与产物可以放在任何磁盘；产物位于执行构建的那份仓库下，不提交到 Git。

开发时运行 `npm run dev:miniapp`，保持终端运行，并导入 `apps/miniapp/dist/dev/mp-weixin`。后端联调地址通过 `apps/miniapp/.env.local` 的 `VITE_API_BASE_URL` 配置，详见[小程序构建与联调说明](apps/miniapp/README.md)。AppSecret 只配置在后端私有环境，不能进入前端或构建产物。

## 后端与验证

后端使用 Java 17、Spring Boot 3.x 和 Maven；数据库使用 MySQL。按[后端说明](backend/README.md)配置私有 `JWT_SECRET`、数据库与微信变量，再运行：

```powershell
cd backend
mvn spring-boot:run
```

默认端口为 8080，健康检查为 `GET /actuator/health`。数据库须按顺序应用 V001–V008：新建 Compose 数据卷自动初始化，**已有数据卷须人工补迁移**。上传适配器默认关闭；启用、私有桶初始化及 ClamAV 配置见[上传适配器说明](docs/api/UPLOAD_ADAPTERS.md)。

在仓库根目录验证：

```powershell
npm test --workspace @autocare/miniapp
npm run build
npm run build:h5 --workspace @autocare/miniapp
```

后端完整测试在 `backend` 目录运行 `mvn test`，需要可运行的 Docker 执行真实 MySQL、MinIO 和 ClamAV 容器测试。完整六项检查由 [CI](.github/workflows/ci.yml) 定义；构建与容器测试通过不等于真机或生产部署通过。

## 工程目录

| 目录 | 用途 |
| --- | --- |
| `apps/miniapp/src` | 当前微信小程序源码与车主 H5 构建目标 |
| `backend` | 三角色共用身份及业务后端 |
| `apps/owner`、`apps/merchant`、`apps/technician` | 旧 Vue/Vite 网页原型，不是小程序工程 |
| `apps/admin` | 协作团队的运营 PC 网页骨架 |
| `deploy`、`services/ocr` | 部署配置及 OCR 服务骨架 |
| `docs` | 需求、接口、SQL、设计、进度与验证证据 |

## 下一步

1. 本地车辆与无图档案联调已完成，22 项真实检查见[复现说明](docs/testing/LOCAL_BUSINESS_ACCEPTANCE.md)；第二个真实微信身份仍待验收。
2. 本机 MinIO/ClamAV、图片归档、签名与重试已通过 26 项后端及 11 项 H5 检查，见[复现说明](docs/testing/LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)。
3. 2026-10-06 用户决定先完成本机功能开发，再处理公网、域名、云托管与真机。标准项目、商家报价、预约与本人订单已实现；支付基础与测试通知已接入；下一步设计商家本店订单视图，正式支付能力后置联调。识别服务和评分规则另行明确。真实短信仍依赖服务商。

现在可用本机模拟器继续开发，无需先购买公网服务器或域名。正式发布连接自建后端时需备案 HTTPS 通讯域名；微信云托管提供指定免配域名调用方式，当前尚未适配。详见[网络环境说明](docs/operations/MINIAPP_NETWORK_ENVIRONMENTS.md)与[中文下一步计划](docs/progress/NEXT_STEPS.md)。

## 项目文档

- [原始 v1.0 交付文档（归档）](docs/reference/DELIVERY_V1.md)、[MVP 规格说明](docs/SPEC.md)、[需求决策](docs/DECISIONS.md)
- [分阶段开发计划](汽车健康管家平台分阶段开发计划.md)、[工程目录说明](docs/STRUCTURE.md)
- [当前进度](docs/progress/CURRENT_STATUS.md)、[执行记录](docs/progress/S0_EXECUTION_LOG.md)、[外部依赖](docs/progress/S0_DEPENDENCIES.md)
- [API 文档](docs/api/README.md)、[写入完整性](docs/api/WRITE_INTEGRITY.md)、[上传核心](docs/api/PRIVATE_UPLOAD_CORE.md)、[真实上传适配器](docs/api/UPLOAD_ADAPTERS.md)

PR #12–#17 已合并；#18 的预检内容整合至 #19，保留提交历史。后续从最新 `main` 创建独立 PR，完整 CI 通过后合并。真机、公网和手机号仍待验收；正式主体、支付和提审条件待确认。

登录联调与环境验收：[运维清单](docs/operations/AUTH_INTEGRATION_RUNBOOK.md)、[开发与上线网络说明](docs/operations/MINIAPP_NETWORK_ENVIRONMENTS.md)。
