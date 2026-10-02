# 单测试号三角色小程序

这是 S0 的 uni-app 3（Vue3）工程。一个微信小程序测试号用于预览车主、商家和技师三个角色入口；原始交付文档中的角色职责不变。PC 运营后台由其他团队负责。

## 开发与构建

在仓库根目录安装依赖后运行：

```bash
npm ci
npm run dev:miniapp
npm run build:miniapp
```

**微信开发者工具须导入编译产物目录，不要导入整个仓库或 `apps/miniapp/src`。**首次预览建议只运行 `npm ci`、`npm run build:miniapp`，确认仓库内生成 `apps/miniapp/dist/build/mp-weixin/app.json` 和 `project.config.json`，再在开发者工具的“导入项目”中把项目目录选为 `apps/miniapp/dist/build/mp-weixin`，点击“编译”。如果已经把仓库根目录导入，出现“在项目根目录未找到 app.json”，请关闭该错误项目并按上述路径重新导入。

需要热更新时，在终端持续运行 `npm run dev:miniapp`，改为导入 `apps/miniapp/dist/dev/mp-weixin`；终端停止后，先重新运行开发命令。测试号 AppID 已写入 `src/manifest.json`，编译后也会出现在产物的 `project.config.json`。它是公开项目标识，**AppSecret 不应放入此工程、前端环境变量、构建产物或 Git**。

如果需要联调后端，将 `.env.example` 复制为本目录 `.env.local` 并设置 `VITE_API_BASE_URL` 为可从小程序访问的 HTTPS 服务地址。没有服务端地址时，登录按钮会明确提示不可用；后端已实现 `/api/auth/wx-login` 和受保护的技师绑定接口。技师首次登录返回待绑定状态时，页面可输入商家发放的员工码。真实登录仍需轮换后的私有 AppSecret、已迁移数据库和可访问的后端。真机请求域名和 HTTPS 需按微信要求配置；开发者工具的域名校验设置只用于开发预览。

`src/pages/index` 是测试入口，`src/pages/owner`、`merchant`、`technician` 为三个角色模块。入口切换不代表登录或授权。车主和技师调用 `wx.login` 的边界在 `src/services/wechat-auth.js`；商家按原文使用账号密码与短信，待后端阶段接入。

正式采用三个独立 AppID 时，应先建立 `(app_id, openid)` 身份映射，再分别配置、构建与真机验证三个目标小程序。测试号构建不等于支付、提审或正式发布通过。
