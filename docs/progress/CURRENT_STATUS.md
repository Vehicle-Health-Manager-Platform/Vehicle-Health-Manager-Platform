# 当前进度

> 更新日期：2026-10-03。需求基线为原始 v1.0 交付文档；此页区分“代码与 CI 已验证”“真实环境已联调”“已合入 main”。

## 仓库与交付状态

| 范围 | 当前状态 | 证据与边界 |
| --- | --- | --- |
| 需求、页面、API 与数据追踪 | 基线已建立 | [Spec](../SPEC.md) 和 [追踪表](S0_TRACEABILITY.md)覆盖 F01–F20、63 个具名页面、43 个核心 API、15 项验收；追踪项不等于功能已交付。 |
| 微信小程序与车主 H5 构建 | CI 已验证，真机未验收 | 单测试号三角色工程可编译；车主五个原生 Tab 已在 [PR #4](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/4) 实现。Tab 内仍是明确的待接入状态，车辆、订单、AI 等数据接口尚未连接。[PR #4 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37042304336) 六项通过。 |
| 车主与技师微信身份 | 自动化验证通过，真实微信未联调 | [PR #2](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/2) 实现身份持久化、角色授权与绑定；[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 实现手机号授权、员工码、限流及会话轮换/撤销。测试使用伪微信响应。 |
| 商家账号身份 | 核心与 CI 已验证，短信未接通 | [PR #5](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/5) 实现密码加一次性短信码、商家状态复核及小程序入口。[PR #5 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37044187253) 六项通过，后端 28 项测试无失败、无跳过。服务商未定，生产环境没有短信发送实现，验证码请求返回 503。 |
| 数据库 | V001–V003 在 CI 验证 | 基线 39 表，V002 增至 40 表，V003 增至 42 表；已有数据库是否应用 V002、V003 尚未确认。不能把新数据卷的自动初始化视作已有库迁移。 |
| S0 基础设施与横向能力 | 部分 CI 验证，M0 未通过 | Compose 冒烟已覆盖既有基础服务；幂等事务与成功审计核心已验证，私有上传、正式业务接入、完整安全审计、健康监控及真实部署验收仍待完成。运营 PC 后台由协作团队负责。 |
| 写入幂等与成功审计核心 | CI 与真实 MySQL 容器验证通过 | [PR #8](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/8) 实现 24 小时幂等、权限复核、并发去重与业务/响应/审计原子提交；首次接入仅为 local 里程写入示例。后端 45 项测试无失败、无跳过，其中本步 12 项 MySQL 测试实际执行；[CI 37100103549](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37100103549) 六项通过。[接口与接入范围](../api/WRITE_INTEGRITY.md)。 |
| 私有上传核心 | 核心阶段 CI 与 MySQL 容器验证通过 | [PR #9](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/9) 实现有界文件校验、扫描通过后存储、元数据归属检查与失败删除补偿。该阶段后端 62 项测试无失败、无跳过，本步 13 项离线与 4 项 MySQL 测试实际执行；[CI 37101550905](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37101550905) 六项通过。该阶段仅提供内部核心，后续真实适配器见下一行，[核心边界说明](../api/PRIVATE_UPLOAD_CORE.md)。 |
| 真实上传适配器与内部签名 | CI 真实容器验证通过，私有部署/小程序未联调 | [PR #10](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/10) 提供 MinIO、ClamAV INSTREAM、权限与对象复核后的短时签名，以及独立账号初始化和可选部署。后端 82 项测试无失败/错误/跳过，本步 6 项真实容器、7 项 TCP 协议、4 项签名权限、3 项配置测试执行通过；[CI 37121267310](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37121267310) 六项通过。ClamAV 容器使用测试专用合成签名，不能计为生产官方病毒库验收；没有 HTTP 上传/签名路由，[接入说明](../api/UPLOAD_ADAPTERS.md)。 |
| 三角色身份页面状态（S0-7.1f-3） | 离线与模拟器验证通过 | 统一加载、失败与重试状态；修复车主会话引用；16 项 Node 测试和 H5 22 项页面交互检查通过。微信开发者工具在当前编译产物中验证测试入口、三角色和五 Tab 共 9 条路由；真实登录、短信和业务列表空状态不在此次验证结果内。 |

截至本次更新，[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 已合入 PR #2 的分支；PR #2 仍对 `main` 开放，PR #4–#10 为堆叠 PR，PR #8 以 PR #7 为基线，PR #9 以 PR #8 为基线，PR #10 以 PR #9 为基线。**上述功能尚未全部进入 `main`。** 计划中的合并与基线调整见 [下一步规划](NEXT_STEPS.md)。

## 开发者工具与真实联调

2026-10-03 用户确认调整推进重点：先完成小程序所需上传基础能力，随后优先打通 S1 车辆档案的页面、后端、数据持久化与联调。健康监控及其他 S0/M0 未满足项继续保留；当前车辆与档案业务仍未实现，尚不能标记 M0/M1 通过。具体拆分见[下一步规划](NEXT_STEPS.md)。

微信开发者工具已重新导入当前仓库的 `apps/miniapp/dist/build/mp-weixin`，模拟器启动和 9 条路由复核通过，车主身份页截图已检查。用户此前的“项目根目录未找到 `app.json`”来自导入源码仓库根目录；其他仓库仍需按[构建与导入说明](../../apps/miniapp/README.md)自行构建并导入其产物。本步使用未配置真实后端的产物做导航验证，没有真实微信登录或手机号联调。

真实微信登录和手机号授权尚需轮换后的私有 AppSecret、已应用 V003 的数据库、可访问的 HTTPS 后端，以及具备手机号能力的测试账号。商家短信联调还需选定服务商并配置私有发送实现。外部条件状态见 [S0 依赖清单](S0_DEPENDENCIES.md)，逐步完成证据见 [S0 执行记录](S0_EXECUTION_LOG.md)。
