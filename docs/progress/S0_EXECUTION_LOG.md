# S0 逐步执行记录

每完成一项可独立验证的工作，记录结果、证据、未完成范围和紧接着的一步。这里记录的是本团队负责的小程序与共用接口工作；运营 PC 后台由协作团队负责。

## 2026-10-02 · S0-5.1 单测试号三角色工程预览

- **已完成**：`apps/miniapp` 的车主、商家、技师三个角色入口可编译；微信小程序和 H5 构建通过。微信开发者工具 CLI 已在端口 11927 打开正确的编译产物，并成功执行 `preview`；用户确认项目可以打开。
- **证据**：[PR #1](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/1) 已合并；[main CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36977349309) 六项作业通过。CLI 预览输出 AppID 与 145.4 KB 小程序包；本机产物包含 `app.json` 和 `project.config.json`。
- **尚未完成**：三角色业务页面、后端微信身份服务、手机号/技师工号绑定、真机流程和正式提审均未验收；测试入口切换不赋予业务权限。
- **下一步**：推进 S0-7.1 的微信登录服务端边界和身份数据契约；先完成无密钥的配置、失败处理与自动化验证，再用已轮换的私有 AppSecret 联调。随后补齐车主五 Tab 和三角色的加载、空、错误、无权限状态。

## 2026-10-02 · S0-7.1a 微信临时登录凭证交换适配器

- **已完成**：后端新增服务端 `code2Session` 适配器，使用 `WECHAT_APP_ID`/`WECHAT_APP_SECRET` 私有配置请求微信官方接口；校验临时 code，分类处理无配置、无效 code、频率限制及上游故障；结果仅保留 `openid` 和可选 `unionid`。`.env.example` 只存公开测试 AppID 与不可用密钥占位值。
- **验证**：新增本地 HTTP 伪服务测试，覆盖请求参数、成功响应、`session_key` 隔离及失败分类。当前 Windows 环境无 Maven，Docker daemon 未启动，无法在本机运行测试；[GitHub CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36979821415) 的后端测试、小程序构建和整条流水线均已通过。
- **尚未完成**：`/api/auth/wx-login` 控制器、微信身份落库、手机号与员工码绑定、JWT 签发和真实微信联调均未实现；聊天中披露过的 AppSecret 须先轮换并仅用私有环境变量提供。
- **下一步**：完成 S0-7.1b：定义并实现 `openid` 到用户身份的持久化和角色授权规则，接入 `/api/auth/wx-login` 与 JWT；分别验证首次登录、重复登录、无效 code、角色越权和失败响应。真实联调待密钥轮换及后端可达地址。

## 2026-10-02 · S0-7.1b 单测试号身份与角色契约

- **已完成**：[微信身份设计](../api/WECHAT_IDENTITY_DESIGN.md) 明确同一 `openid` 下车主与技师可对应不同平台主体；技师必须绑定有效员工并校验商家状态，入口和请求 `role` 均不直接授权。定义了车主手机号未绑定时的权限边界、失败响应、JWT 声明及迁移顺序。
- **验证**：对照现有 `user`/`staff_account` 表结构、前端 `wx-login` 请求、后端本地 JWT 与 S0 业务接口草案逐项核对；本项是设计契约，尚无可运行的身份落库或角色授权实现。
- **下一步**：S0-7.1c 先增加技师微信绑定的版本化迁移与数据库验证，再实现仓储、`wx-login`、受限绑定凭证和真实业务 JWT；随后补手机号绑定与刷新/撤销机制。真实联调仍待密钥轮换及后端可达地址。

## 2026-10-02 · S0-7.1c 技师微信绑定迁移

- **已完成**：新增 V002 `staff_wechat_identity`，按 AppID 保存绑定历史；数据库生成列与唯一索引约束同一 AppID 下一个微信身份及一个员工账号各只有一条有效绑定。V001 生成基线保持不变。
- **验证**：MySQL CI 脚本增加 V002 重复执行、唯一索引、重复有效绑定拒绝及解绑后保留历史的检查。本机 Docker daemon 未启动；[GitHub CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36980400681) 的 MySQL 作业和整条流水线均已通过。
- **尚未完成**：数据库迁移尚未接入后端运行时；员工角色/商家状态校验、绑定 API、微信登录接口和 JWT 签发仍待实现。
- **下一步**：S0-7.1d 接入数据库仓储，服务端以 `openid` 查找/创建车主并校验技师有效绑定；实现受保护的绑定流程和 `/api/auth/wx-login`，补齐并发与越权测试。真实微信联调待私有凭据和后端可达地址。

## 2026-10-02 · S0-7.1d 身份仓储与服务端角色授权

- **已完成**：后端接入 MySQL 身份仓储；`/api/auth/wx-login` 用服务端交换得到的 `openid` 查找/创建车主，并在有效技师绑定、员工及商家状态通过时签发短时业务 JWT。未绑定技师只获得短时绑定凭证，`/api/auth/technician/bind` 校验员工码及唯一绑定约束后签发技师 JWT。小程序预览入口可识别待绑定状态并提交员工码。受保护请求重新核对数据库状态，禁用或解绑后拒绝旧 token。新建 Compose 数据卷自动执行 V002；既有数据卷须单独补迁移。
- **验证**：[PR #2](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/2) 的 [CI 运行 36982849238](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36982849238) 六项作业全部通过。后端共 14 项测试，失败/跳过均为 0；其中 2 项 MySQL 容器测试实际执行，验证并发首次登录、员工码绑定唯一约束、商家禁用及解绑。MockMvc 覆盖首次/重复车主登录、无效角色和 code、微信限流、未绑定凭证的业务越权、技师绑定及旧 token 失效。本机小程序构建通过，微信开发者工具端口 11927 已打开本 worktree 的持续监听开发产物；未使用真实 AppSecret 联调。
- **尚未完成**：手机号绑定、员工码的管理发放与限流、刷新/撤销令牌、商家登录和真实微信登录联调。先前披露的 AppSecret 尚须轮换，并仅以私有环境变量配置；现有数据库需先应用 V002。
- **下一步 S0-7.1e**：实现经验证的车主手机号绑定与技师员工码发放/回收及限流；补齐刷新与撤销策略，然后使用轮换后的 AppSecret、已迁移数据库和 HTTPS 后端进行真实小程序联调。

## 2026-10-02 · S0-7.1e 手机号、员工码与会话生命周期

- **已完成**：车主通过微信手机号按钮独立 code 在服务端换取号码，携带当前 `openid` 校验并检查水印 AppID 后落库；员工码由无 HTTP 入口的运维命令高熵生成、轮换和回收，轮换/回收同步解除技师绑定。V003 增加持久化会话与共享限流表；刷新凭证只存摘要并按事务轮换，退出后撤销业务 JWT；技师绑定和手机号授权加入数据库限流。小程序预览页增加手机号授权和退出操作。
- **验证**：[PR #3](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/3) 的 [CI 运行 37001116839](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37001116839) 六项作业全部通过。后端 23 项测试无失败、无跳过，其中 6 项 MySQL 容器测试实际执行，覆盖并发刷新只能成功一次、旧凭证重放、退出撤销、手机号唯一绑定、共享限流和员工码轮换。伪微信 HTTP 测试验证稳定版 access_token、手机号独立 code、`openid` 与水印；MockMvc 验证角色边界和响应。小程序开发产物持续编译并已在微信开发者工具端口 11927 打开。
- **尚未完成**：真实微信登录和手机号授权联调尚待轮换后的私有 AppSecret、符合[微信官方主体资质与额度](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/getPhoneNumber.html)的小程序账号、已迁移数据库及 HTTPS 后端；商家账号身份与完整业务页面尚未实现。已有数据库须按顺序补 V003，不能依赖 MySQL 初始化目录自动补迁移。
- **下一步 S0-7.1f**：完成真实微信登录和手机号授权联调，补齐商家账号身份、车主五 Tab 与三角色基础业务状态；在具备外部条件前，继续实现可离线验证的页面和接口。

## 2026-10-03 · S0-7.1f-1 车主五 Tab 导航骨架

- **已完成**：小程序新增首页、服务、AI、档案、我的五个原生 Tab，车主微信登录成功后可进入首页；未登录时各 Tab 提示先验证身份。已登录页面仅展示对应能力的待接入说明，不填充虚构车辆、订单或健康数据。会话只在本次应用运行的内存中共享；“我的”可返回身份页退出并撤销服务端令牌。
- **验证**：本机 `npm run build:mp-weixin --workspace @autocare/miniapp` 和 `npm run build:h5 --workspace @autocare/miniapp` 均通过；小程序生成的 `app.json` 包含五个 Tab 路由。[PR #4 CI 37042304336](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37042304336) 六项作业通过。真实微信授权与业务数据仍未联调。
- **下一步**：补齐可由真实接口结果驱动的加载、空、错误和无权限状态；商家账号身份按账号密码与短信二次验证契约实现。真实微信联调仍需轮换后的私有 AppSecret、已迁移 V003 的数据库、HTTPS 后端及可用的手机号测试账号。

## 2026-10-03 · S0-7.1f-2 商家账号身份核心

- **已完成**：新增商家账号密码与一次性短信码双重校验接口及小程序输入入口；复用 `staff_account`、`sms_code`、`auth_session` 和共享限流表。只有已审核通过商家下的有效 `MERCHANT` 员工可取得商家 JWT，受保护请求和刷新均复核数据库状态；短信码按账号隔离并只存 BCrypt 摘要。
- **验证**：后端 MockMvc 覆盖密码、短信码、重复消费、角色边界与禁用后旧令牌失效；MySQL 容器测试覆盖账号隔离、单次消费、重发失效及发送失败事务回滚。小程序和 H5 构建通过；[PR #5 CI 37044187253](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37044187253) 六项作业通过，后端 28 项测试无失败、无跳过。
- **尚未完成**：短信服务商未确定，生产环境无 `MerchantSmsSender` 实现，验证码请求返回 503；商家账号可信创建与密码重置、三角色业务页面状态、真实微信及短信联调仍待完成。
- **下一步**：选择短信服务商后接入私有发送实现；同时继续真实接口驱动的加载、空、错误和无权限状态。真实微信联调需外部凭据、数据库、HTTPS 和测试账号条件齐备。

## 2026-10-03 · 文档与计划核对

- **已核对**：PR #3 已合入仍对 `main` 开放的 PR #2 分支；PR #4、#5 保持堆叠。更新[当前进度](CURRENT_STATUS.md)、[下一步规划](NEXT_STEPS.md)和[依赖清单](S0_DEPENDENCIES.md)，区分 CI、模拟器、真机与真实服务联调。OpenAPI 生成器补齐已实现认证接口及其 Bearer 边界；原始 43 个核心接口追踪基线保持不变。
- **开发者工具现状**：此前编译产物预览成功；最近截图的启动失败来自把源码仓库根目录导入开发者工具，当前提交的模拟器复核尚未完成。操作步骤见[小程序 README](../../apps/miniapp/README.md)。
- **下一步**：先完成 S0-7.1f-3 的真实状态处理，同时复核当前产物导入；外部条件齐备后再执行 S0-7.1f-4 身份联调。

## 2026-10-03 · S0-7.1f-3 三角色身份页面状态

- **已完成**：将身份请求、状态操作和页面展示分开，三角色统一处理加载、断网、超时、401/403、429 与 503；成功响应校验角色和必要字段。修复车主身份页缺少 `ownerSession` 引用，跨页面共享内存会话；阻止重复提交，短信实际发送成功后才提示发送，商家登录成功清空密码与验证码。失败退出保留会话，身份被拒绝时可主动重新登录；手机号重试需要新授权 code。未实现业务页面继续显示待接入。
- **本机验证**：16 项 Node 离线测试无失败、无跳过，已加入小程序 CI；小程序/H5 构建通过。gstack browse 在本机 H5 实际页面使用测试专用 uni 响应替身，22 项交互断言通过，覆盖五 Tab 会话、失败重试、短信未配置、401/429 和技师 403 后重新绑定。页面无 JavaScript 错误，仅 uni-app 的 vue-router 导入弃用警告。
- **模拟器验证**：微信开发者工具 CLI 在端口 11927 打开当前仓库的 `apps/miniapp/dist/build/mp-weixin`，自动化端口 9420 验证测试入口、三角色和五 Tab 共 9 条路由；保存并检查车主身份页截图，模拟器启动成功。复现方式与验证脚本见[小程序 README](../../apps/miniapp/README.md)。截图在本机被 Git 忽略的 `test-results` 目录中。
- **边界与下一步**：真实微信、手机号与短信仍未联调；业务列表空状态随读接口接入。外部条件齐备后执行 S0-7.1f-4；条件未齐备时先推进 S0 受保护写请求的幂等与审计。此步分支 `codex/s0-7-1f-page-states` 以文档 PR #6 为基线，尚未合入 `main`。
