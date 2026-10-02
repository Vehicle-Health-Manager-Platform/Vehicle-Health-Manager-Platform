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
