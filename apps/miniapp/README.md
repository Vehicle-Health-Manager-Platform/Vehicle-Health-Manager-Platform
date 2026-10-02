# 单测试号三角色小程序

这是 S0 的 uni-app 3（Vue3）工程。一个微信小程序测试号用于预览车主、商家和技师三个角色入口；原始交付文档中的角色职责不变。PC 运营后台由其他团队负责。

## 从源码生成可导入的小程序

源码位于 `apps/miniapp/src`。微信开发者工具需要 uni-app 编译生成的 `app.json` 等文件；源码仓库根目录没有这些文件。C 盘或其他盘都可以构建，产物总是在**执行构建的那份仓库**的 `apps/miniapp/dist` 下。

在包含根 `package.json` 的仓库目录运行（项目 CI 使用 Node.js 22）：

```powershell
npm ci
npm run build:miniapp
Test-Path .\apps\miniapp\dist\build\mp-weixin\app.json
```

最后一行应返回 `True`；同目录还应有 `project.config.json`。在微信开发者工具中关闭之前导入的错误项目，选择“导入项目”，把**项目目录**设为这份仓库的 `apps/miniapp/dist/build/mp-weixin`，再点击“编译”。不要选仓库根目录、`apps` 或 `apps/miniapp/src`。若开发者工具显示“在项目根目录未找到 app.json”，先确认导入路径和上面的 `Test-Path` 结果；这条报错不代表 uni-app 源码编译失败。

需要热更新时，在仓库根目录持续运行 `npm run dev:miniapp`，改为导入 `apps/miniapp/dist/dev/mp-weixin`；终端停止后，先重新运行开发命令。测试号 AppID 已写入 `src/manifest.json`，编译后也会出现在产物的 `project.config.json`。它是公开项目标识，**AppSecret 不应放入此工程、前端环境变量、构建产物或 Git**。

PR #2 的身份代码、已合入其分支的 PR #3、PR #4 的五 Tab 与 PR #5 的商家身份尚未全部进入 `main`。若在另一份仓库只构建 `main`，不会得到这些尚未合入的页面和接口；先取得相应分支代码，或待 PR 按[合并计划](../../docs/progress/NEXT_STEPS.md)落地后更新 `main` 再构建。

如果需要联调后端，将 `.env.example` 复制为本目录 `.env.local` 并设置 `VITE_API_BASE_URL` 为可从小程序访问的 HTTPS 服务地址。没有服务端地址时，登录按钮会明确提示不可用；后端已实现 `/api/auth/wx-login`、技师绑定、车主手机号绑定、刷新与退出。技师首次登录返回待绑定状态时，页面可输入商家发放的员工码；车主登录成功后可通过微信手机号按钮授权并绑定。商家入口已有账号密码与短信码表单，但服务商未定，验证码请求当前返回 503。真实登录仍需轮换后的私有 AppSecret、V002/V003 迁移和可访问的后端；手机号能力需要微信账号主体资质及额度。真机请求域名和 HTTPS 需按微信要求配置；开发者工具的域名校验设置只用于开发预览。

`src/pages/index` 是测试入口，`src/pages/owner`、`merchant`、`technician` 为三个角色模块；车主另有首页、服务、AI、档案、我的五个原生 Tab。入口切换不代表登录或授权。车主和技师调用 `wx.login` 的边界在 `src/services/wechat-auth.js`；商家使用账号密码加短信码。五 Tab 的业务内容、商家和技师工作流仍待真实接口接入。

正式采用三个独立 AppID 时，应先建立 `(app_id, openid)` 身份映射，再分别配置、构建与真机验证三个目标小程序。测试号构建不等于支付、提审或正式发布通过。
