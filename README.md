# 汽车健康管家平台

面向车主、商家和技师的微信小程序及配套后端，车主另有 H5 兜底目标，运营使用 PC 网页。本团队负责小程序和共用接口；运营 PC 后台由协作团队负责。

**当前处于 S0 基础建设阶段。** 既有开发分支已整合至 `main`，身份、导航和上传基础能力已实现；车辆档案、交易、AI 等完整业务仍待交付，M0/M1 尚未验收通过。详见[当前进度](docs/progress/CURRENT_STATUS.md)及[下一步规划](docs/progress/NEXT_STEPS.md)。

## 已实现的能力

| 范围 | 当前能力 | 验证与边界 |
| --- | --- | --- |
| 微信小程序 | uni-app 3 / Vue3 单测试号三角色入口，车主五个原生 Tab，身份页加载、错误与重试状态 | 小程序与 H5 构建、27 项离线测试通过；模拟器验证过 9 条路由。业务 Tab 内容仍待接口接入。 |
| 车主与技师身份 | 微信身份持久化、角色授权、技师绑定、员工码发放/回收、手机号绑定、限流、刷新凭证轮换与退出撤销 | 自动化验证通过，真实微信与手机号授权未联调。 |
| 商家身份 | 密码加一次性短信码、账号/商家状态复核、可刷新与撤销的会话 | 服务商未定，没有生产短信发送器，验证码请求当前返回 503。 |
| 写入完整性 | 24 小时幂等、权限复核、业务/成功响应/成功审计同事务提交 | MySQL 容器验证通过；首次接入仅为 local 里程示例，正式业务需逐项接入。 |
| 私有上传基础 | 10 MiB 文件校验、扫描后存储、JDBC 元数据与失败补偿；真实 MinIO/ClamAV 适配器、内部短时签名及独立账号初始化 | 真实容器与 TCP 测试通过；PR #12 的车主 HTTP 上传/访问与失败核对已通过 CI，尚未合入 main；PR #13 已接入小程序图片操作；业务档案关联、真实图片联调和私有部署仍待交付。测试合成病毒签名不代表生产官方病毒库验收。 |
| 数据库与 CI | main 为 V001–V003、42 张表，PR #12 追加 V004 至 44 表；网页、小程序、后端、生成文件/OCR、MySQL 和 Compose 共六项 CI | PR #12 后端 107 项测试无失败/错误/跳过；各提交验证结果查看 [GitHub Actions](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/workflows/ci.yml)。 |

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

默认端口为 8080，健康检查为 `GET /actuator/health`。本分支数据库须按顺序应用 V001、V002、V003、V004：新建 Compose 数据卷自动初始化，**已有数据卷须人工补迁移**。上传适配器默认关闭；启用、私有桶初始化及 ClamAV 配置见[上传适配器说明](docs/api/UPLOAD_ADAPTERS.md)。

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

1. 图片后端 PR #12 与小程序图片操作 PR #13 按依赖顺序合并；真实私有环境和微信图片联调条件到位后记录验收。
2. 优先打通 S1 本人车辆列表/手动录入、档案录入/查询的小程序页面、受保护接口和持久化。
3. 外部条件齐备后并行完成真实微信、手机号、短信及私有上传环境联调；继续补齐健康监控和 S0/M0 验收。

## 项目文档

- [原始 v1.0 交付文档（归档）](docs/reference/DELIVERY_V1.md)、[MVP 规格说明](docs/SPEC.md)、[需求决策](docs/DECISIONS.md)
- [分阶段开发计划](汽车健康管家平台分阶段开发计划.md)、[工程目录说明](docs/STRUCTURE.md)
- [当前进度](docs/progress/CURRENT_STATUS.md)、[执行记录](docs/progress/S0_EXECUTION_LOG.md)、[外部依赖](docs/progress/S0_DEPENDENCIES.md)
- [API 文档](docs/api/README.md)、[写入完整性](docs/api/WRITE_INTEGRITY.md)、[上传核心](docs/api/PRIVATE_UPLOAD_CORE.md)、[真实上传适配器](docs/api/UPLOAD_ADAPTERS.md)

后续每一步从 `main` 创建独立 PR，验证后推送 GitHub 并更新进度。真实微信联调仍需私有凭据、已迁移数据库、HTTPS 与合法域名；正式主体、支付和提审条件待确认。
