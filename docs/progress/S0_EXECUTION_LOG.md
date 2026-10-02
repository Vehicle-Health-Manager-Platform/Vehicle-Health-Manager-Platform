# S0 逐步执行记录

每完成一项可独立验证的工作，记录结果、证据、未完成范围和紧接着的一步。这里记录的是本团队负责的小程序与共用接口工作；运营 PC 后台由协作团队负责。

## 2026-10-02 · S0-5.1 单测试号三角色工程预览

- **已完成**：`apps/miniapp` 的车主、商家、技师三个角色入口可编译；微信小程序和 H5 构建通过。微信开发者工具 CLI 已在端口 11927 打开正确的编译产物，并成功执行 `preview`；用户确认项目可以打开。
- **证据**：[PR #1](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/1) 已合并；[main CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36977349309) 六项作业通过。CLI 预览输出 AppID 与 145.4 KB 小程序包；本机产物包含 `app.json` 和 `project.config.json`。
- **尚未完成**：三角色业务页面、后端微信身份服务、手机号/技师工号绑定、真机流程和正式提审均未验收；测试入口切换不赋予业务权限。
- **下一步**：推进 S0-7.1 的微信登录服务端边界和身份数据契约；先完成无密钥的配置、失败处理与自动化验证，再用已轮换的私有 AppSecret 联调。随后补齐车主五 Tab 和三角色的加载、空、错误、无权限状态。

## 2026-10-02 · S0-7.1a 微信临时登录凭证交换适配器

- **已完成**：后端新增服务端 `code2Session` 适配器，使用 `WECHAT_APP_ID`/`WECHAT_APP_SECRET` 私有配置请求微信官方接口；校验临时 code，分类处理无配置、无效 code、频率限制及上游故障；结果仅保留 `openid` 和可选 `unionid`。`.env.example` 只存公开测试 AppID 与不可用密钥占位值。
- **验证**：新增本地 HTTP 伪服务测试，覆盖请求参数、成功响应、`session_key` 隔离及失败分类。当前 Windows 环境无 Maven，Docker daemon 未启动，无法在本机运行测试；本次推送后以 GitHub CI 的后端作业结果作为执行验证。此项在 CI 通过前为“代码完成，验证待确认”。
- **尚未完成**：`/api/auth/wx-login` 控制器、微信身份落库、手机号与员工码绑定、JWT 签发和真实微信联调均未实现；聊天中披露过的 AppSecret 须先轮换并仅用私有环境变量提供。
- **下一步**：完成 S0-7.1b：定义并实现 `openid` 到用户身份的持久化和角色授权规则，接入 `/api/auth/wx-login` 与 JWT；分别验证首次登录、重复登录、无效 code、角色越权和失败响应。真实联调待密钥轮换及后端可达地址。
