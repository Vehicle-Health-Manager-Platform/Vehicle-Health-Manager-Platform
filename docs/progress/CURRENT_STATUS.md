# 当前进度

> 更新日期：2026-10-05。需求基线为原始 v1.0 交付文档；此页区分“代码与 CI 已验证”“真实环境已联调”“已合入 main”。

## 仓库与交付状态

| 范围 | 当前状态 | 证据与边界 |
| --- | --- | --- |
| 需求、页面、API 与数据追踪 | 基线已建立 | [Spec](../SPEC.md) 和 [追踪表](S0_TRACEABILITY.md)覆盖 F01–F20、63 个具名页面、43 个核心 API、15 项验收；追踪项不等于功能已交付。 |
| 微信小程序与车主 H5 构建 | CI 已验证，真机未验收 | 单测试号三角色工程可编译；PR #14–#16 已接入本人车辆、手动档案和首页真实摘要并合入 main。当前分支新增档案拍照入口；订单、AI及完整 F02–F04 仍待实现。 |
| 车主与技师微信身份 | 自动化验证通过，真实微信未联调 | [PR #2](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/2) 实现身份持久化、角色授权与绑定；[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 实现手机号授权、员工码、限流及会话轮换/撤销。测试使用伪微信响应。 |
| 商家账号身份 | 核心与 CI 已验证，短信未接通 | [PR #5](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/5) 实现密码加一次性短信码、商家状态复核及小程序入口。[PR #5 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37044187253) 六项通过，后端 28 项测试无失败、无跳过。服务商未定，生产环境没有短信发送实现，验证码请求返回 503。 |
| 数据库 | main 的 V001–V005 在 CI 验证 | V004 增至 44 表，V005 增加档案文件关联表；已有数据库是否按顺序应用迁移尚未确认。不能把新数据卷的自动初始化视作已有库迁移。 |
| S0 基础设施与横向能力 | 部分 CI 验证，M0 未通过 | Compose 冒烟已覆盖既有基础服务；幂等事务与成功审计核心已验证，私有上传、正式业务接入、完整安全审计、健康监控及真实部署验收仍待完成。运营 PC 后台由协作团队负责。 |
| 写入幂等与成功审计核心 | CI 与真实 MySQL 容器验证通过 | [PR #8](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/8) 实现 24 小时幂等、权限复核、并发去重与业务/响应/审计原子提交；最初接入为local里程示例，PR #14已接入正式车辆创建。后端 45 项测试无失败、无跳过，其中本步 12 项 MySQL 测试实际执行；[CI 37100103549](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37100103549) 六项通过。[接口与接入范围](../api/WRITE_INTEGRITY.md)。 |
| 私有上传核心 | 核心阶段 CI 与 MySQL 容器验证通过 | [PR #9](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/9) 实现有界文件校验、扫描通过后存储、元数据归属检查与失败删除补偿。该阶段后端 62 项测试无失败、无跳过，本步 13 项离线与 4 项 MySQL 测试实际执行；[CI 37101550905](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37101550905) 六项通过。该阶段仅提供内部核心，后续真实适配器见下一行，[核心边界说明](../api/PRIVATE_UPLOAD_CORE.md)。 |
| 真实上传适配器与内部签名 | CI 真实容器验证通过，私有部署/小程序未联调 | [PR #10](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/10) 提供 MinIO、ClamAV INSTREAM、权限与对象复核后的短时签名，以及独立账号初始化和可选部署。后端 82 项测试无失败/错误/跳过，本步 6 项真实容器、7 项 TCP 协议、4 项签名权限、3 项配置测试执行通过；[CI 37121739136](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37121739136) 六项通过。ClamAV 容器使用测试专用合成签名，不能计为生产官方病毒库验收；该阶段没有 HTTP 上传/签名路由，PR #12 接入见下一行；[接入说明](../api/UPLOAD_ADAPTERS.md)。 |
| 车主 HTTP 上传与图片访问 | 已合入 main，真实环境未联调 | [PR #12](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/12) 提供正式车主单图片上传、本人短时访问、持久化幂等/成功审计与失败核对；新增 V004，两张表，迁移后共 44 表。[CI 37173318127](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37173318127) 六项通过，后端 107 项测试无失败/错误/跳过；本步新增 15 项 MySQL、9 项真实 HTTP、1 项真实适配器测试全部执行。小程序图片入口及真实私有部署未联调，[接口说明](../api/UPLOAD_HTTP.md)。 |
| 小程序档案图片操作（B） | 已合入 main，真实环境未联调 | [PR #13](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/13) 接入选图、上传、同键重试和每次新签名预览；27 项 Node 测试无失败/跳过，小程序/H5 构建通过，实际 H5 使用 uni 替身通过 13 项交互断言。微信模拟器九条路由加载通过，未计为微信真实图片联调。[CI 37181271501](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37181271501) 六项通过；依赖已合并的 PR #12。 |
| S1 本人车辆列表与手动录入 | 已合入 main，车型数据待落实 | 正式车主本人列表、有效目录分页、四级车型选择、幂等/审计创建、脱敏与同车主重复阻断；39项Node测试、H5 11项替身交互、模拟器10条路由通过。首版 [CI 37182892331](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37182892331) 六项通过、后端124项全执行；最终代码fd9494f的 [CI 37183855984](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37183855984) 后端125项无失败/错误/跳过，含6项HTTP、11项MySQL与1项无库边界新增测试；完整六项结果以链接为准。[契约](../api/VEHICLE_MANUAL.md)。生产车型来源未确定，档案及完整F02/M1未验收，依赖PR #13。 |
| S1 本人车辆档案手动录入与查询 | 已合入 main，真实环境未联调 | 七类通用记录、本人车辆分页、图片 ID 归档、CLEAN 与归属复核、幂等和审计已实现。[CI 37203770229](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37203770229) 六项全绿，后端132项无失败/错误/跳过；小程序43项Node测试及微信/H5构建通过。真实微信、私有图片与真机尚未联调。[契约](../api/ARCHIVE_MANUAL.md)。 |
| S1 首页车辆与档案摘要 | 已合入 main，真实环境未联调 | 复用本人车辆与档案接口，首页可切换车辆并显示真实记录总数和最近记录；与档案 Tab 共用内存选择。48项离线测试与微信/H5构建通过，H5实际交互、微信模拟器、真实微信及私有环境未复核。完整 F04 的评分、提醒、券与同款经验仍未实现。 |
| S1 本人档案拍照录入 | 当前分支已实现，待 CI/PR | 拍照模式必须至少一张本人 CLEAN 图片，`input_type=1` 入库；列表与首页摘要包括拍照和手动记录。52项小程序测试与微信/H5构建通过；后端新增 MySQL 用例待 CI，真实微信相机、私有图片与真机未联调。[契约](../api/ARCHIVE_MANUAL.md)。 |
| 三角色身份页面状态（S0-7.1f-3） | 离线与模拟器验证通过 | 统一加载、失败与重试状态；修复车主会话引用；16 项 Node 测试和 H5 22 项页面交互检查通过。微信开发者工具在当前编译产物中验证测试入口、三角色和五 Tab 共 9 条路由；真实登录、短信和业务列表空状态不在此次验证结果内。 |

本次整合已将 PR #2–#10 的分支提交及原有 `main` 历史全部纳入主分支，保留 Git 提交历史；原始交付文档归档至 [需求基线](../reference/DELIVERY_V1.md)，生成脚本与链接同步调整。后续从 `main` 创建独立 PR。完整 CI 结果以 [GitHub Actions](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/workflows/ci.yml) 对应整合/主分支提交为准，具体合并记录见 [S0 执行记录](S0_EXECUTION_LOG.md)。

## 开发者工具与真实联调

2026-10-03 用户确认调整推进重点：先完成小程序所需上传基础能力，随后优先打通 S1 车辆档案的页面、后端、数据持久化与联调。健康监控及其他 S0/M0 未满足项继续保留；车辆与手动档案分别在 PR #14/#15 完成自动化验证，真实联调和完整 F02–F04 仍待完成，尚不能标记 M0/M1 通过。具体拆分见[下一步规划](NEXT_STEPS.md)。

微信开发者工具已重新导入当前仓库的 `apps/miniapp/dist/build/mp-weixin`，模拟器启动和 9 条路由复核通过，车主身份页截图已检查。用户此前的“项目根目录未找到 `app.json`”来自导入源码仓库根目录；其他仓库仍需按[构建与导入说明](../../apps/miniapp/README.md)自行构建并导入其产物。本步使用未配置真实后端的产物做导航验证，没有真实微信登录或手机号联调。

真实微信登录和手机号授权尚需轮换后的私有 AppSecret、已应用 V003 的数据库、可访问的 HTTPS 后端，以及具备手机号能力的测试账号。商家短信联调还需选定服务商并配置私有发送实现。外部条件状态见 [S0 依赖清单](S0_DEPENDENCIES.md)，逐步完成证据见 [S0 执行记录](S0_EXECUTION_LOG.md)。
