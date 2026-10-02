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
| S0 基础设施与横向能力 | 部分 CI 验证，M0 未通过 | Compose 冒烟已覆盖既有基础服务；私有上传、24 小时幂等、审计、完整健康监控及真实部署验收仍待完成。运营 PC 后台由协作团队负责。 |

截至本次更新，[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 已合入 PR #2 的分支；PR #2 仍对 `main` 开放，PR #4、#5 仍为堆叠 PR。**上述功能尚未全部进入 `main`。** 计划中的合并与基线调整见 [下一步规划](NEXT_STEPS.md)。

## 开发者工具与真实联调

微信开发者工具曾成功预览小程序编译产物。用户最近一次截图中的“项目根目录未找到 `app.json`”是导入源码仓库根目录造成的，不能作为编译失败或真实微信联调结果。按[小程序构建与导入说明](../../apps/miniapp/README.md)重新导入 `apps/miniapp/dist/build/mp-weixin` 后，还需在当前环境复核模拟器启动。

真实微信登录和手机号授权尚需轮换后的私有 AppSecret、已应用 V003 的数据库、可访问的 HTTPS 后端，以及具备手机号能力的测试账号。商家短信联调还需选定服务商并配置私有发送实现。外部条件状态见 [S0 依赖清单](S0_DEPENDENCIES.md)，逐步完成证据见 [S0 执行记录](S0_EXECUTION_LOG.md)。
