# R0.1a 微信原生连接、匿名保护与真实车主登录验收

日期：2026-10-09。范围基线：[微信交付路线](../progress/WECHAT_DELIVERY_ROADMAP_2026-10-09.md) R0；当前实现分支 `codex/r0-native-connection`，直接依赖 #68。本文记录本次实际执行，不把匿名路由当成登录后业务闭环通过。

## 1. 实测结果

| 检查 | 结果与证据 |
| --- | --- |
| 微信 WebSocket 握手与 RPC | 已成功，`ws://127.0.0.1:9420`；CLI HTTP 服务 `11927`，两者用途分开 |
| 完整产物/路由一致 | `app.js`、`common/vendor.js` 存在；编译 `app.json` 的 32 路由与源码 `pages.json` 一致 |
| 原生匿名基线 | **46/46**：产物 1 + 连接 1 + 路由 32 + 登录保护 5 + 实际 HTTP 5 + 计算样式 1 + 截图 1 |
| 登录保护 | 商家订单、技师工单、车主订单、本人卡片显示对应登录按钮；运营审核页实际跳至独立运营登录 |
| 微信运行时→真实后端 | 健康 `200/UP`；车辆/商家订单/技师工单/运营待审匿名请求均 `401`，无响应替身 |
| 真实车主原生页面流程 | **6/6**：初始要求微信登录→按钮完成实际 `wx.login` 与后端会话→进入首页 Tab→本人车辆加载成功→退出→订单页重新要求登录 |
| 小程序回归 | **225/225**，含新增 8 项连接/RPC 生命周期测试；失败/取消/跳过 0 |
| 构建 | 微信构建与公共样式产物门禁通过；已有辅助 H5 构建通过 |
| 公共布局修复 | 运营登录页原生计算 `padding-left=16px`、`padding-top=20px`；修复后截图人工查看通过 |

真实车主脚本不注入账号会话、微信 code、token 或响应；通过原生按钮调用现有业务代码。真实微信的注册/登录会产生正常本机身份与会话记录，结束通过原生退出清理本次登录；未删除用户、车辆或其他业务数据。没有进行手机号授权或商家/运营短信登录。

## 2. 环境与边界

- Windows 本机，Node `24.16.0`（CI 为 Node 22）；微信开发者工具已安装，文件版本 `2.02.0`。
- 小程序 AppID `wxc17878d92f30a01f`，uni-app 构建输出 `apps/miniapp/dist/build/mp-weixin`，使用当前工作树产物。
- 隔离后端 `vehicle-auth-local-backend`，镜像 `vehicle-auth/backend:a7-experience-publication`；本机接口 `http://127.0.0.1:18080`。
- 运行 JAR 与工作树 JAR SHA256 相同：`261a877db7c9fb5d6f475e90c8fb7770f81bcc3cd09227f4461c7d0e6cfac4cb`。数据库未由本阶段迁移/补写。
- 原生匿名回归的 HTTP 检查使用自动化调用真实 `wx.request`，不是页面内登录后写业务验收。车主流程另外使用原生页面业务代码访问实际接口。
- 回环 HTTP 仅用于开发者工具；测试时在忽略的产物 `project.private.config.json` 临时设 `urlCheck=false`。源码及正式生成 `project.config.json` 的 `urlCheck=true` 保留，收口恢复私有例外。
- 本机后端配置仅经构建进程环境变量设置，不创建测试登录接口、不提交 `.env`、凭据、截图或构建产物。

## 3. 实际发现及修复

### 公共 CSS 仅出现在部分原生页面

复现：运营登录页按钮可读取、导航成功，但标题/间距/绿色按钮样式缺失。其 `login.wxss` 只有输入框 scoped 样式；公共 `reservations.css` 出现在其他单个页面的 wxss，而未全局可用。19 页重复使用 `<style src="../../styles/reservations.css">`，编译去重后的页面样式分布不满足原生页面独立加载。

修复：将已有样式集中导入 `App.vue`，移除 19 个页面重复引入；公共类输出至 `app.wxss`，页面专属 scoped 样式保留。新增构建产物门禁 `check-native-styles.cjs`，缺公共选择器时微信构建失败。不是新增视觉方案，也没有改业务权限。

验证：原生计算间距、前后截图及完整原生基线回归；辅助 H5 构建通过。

### 模拟器运行上下文与组件查询

- `cli auto` 返回成功不是自动化已就绪证明；须实际连接并断言页面/元素。
- 整包重建后曾出现页面空白、元素 RPC 错误与运营页不跳登录。针对**本项目**执行 `cache --clean compile` 后元素与生命周期恢复。未清理账号/存储、未退出整个 IDE，也未停止其他项目。
- 车主登录页为 uni-app 自定义组件，自动化树中暴露为 `component`；页面直接查询 `button` 或 `role-landing` 无结果。脚本通过组件节点的 `Element.getElements` 查询真实按钮，未替换组件方法或数据。
- 沙箱构建曾报 terser 未找到；同一安装环境在授权本机执行构建后成功，不据此添加依赖或修改锁文件。

以上失败均发生在收口前，最终结果以最后一次实际成功运行与 CI 为准。

## 4. 复现步骤

当前目录为仓库根目录，先保证本机隔离后端可用，微信工具已登录并有该 AppID 权限。仅在授权的本机环境使用下面的回环接口：

```powershell
# 在单独的验收终端设置临时公开客户端配置；不用作正式发布配置。
$env:VITE_API_BASE_URL='http://127.0.0.1:18080'
$env:VITE_WECHAT_CLOUD_ENV_ID=''
$env:VITE_WECHAT_CLOUD_SERVICE=''
npm run build:miniapp

# 仅产物私有配置用于回环 HTTP。结束恢复 urlCheck=true。
'{"setting":{"urlCheck":false}}' | Set-Content apps/miniapp/dist/build/mp-weixin/project.private.config.json -Encoding utf8
& '<工具安装目录>/cli.bat' auto --project "$PWD/apps/miniapp/dist/build/mp-weixin" --port 11927 --auto-port 9420 --trust-project

node apps/miniapp/test/simulator-smoke.cjs --backend-origin http://127.0.0.1:18080
node apps/miniapp/test/simulator-owner-login.cjs
npm test --workspace @autocare/miniapp
```

只在整包重建后确认旧编译上下文的问题时，对当前项目执行 `cli cache --project <当前产物> --port 11927 --clean compile`，再启动自动化；不要清理所有缓存或其他项目。

匿名脚本支持 `--endpoint ws://127.0.0.1:<端口>`、`--timeout-ms <毫秒>`。连接、RPC、页面和按钮等待均有期限，失败退出非 0。测试需从无既有业务会话的模拟器上下文开始，不能通过抹掉用户现有会话来让断言通过。

本机结果在被忽略的 `test-results/wechat-native-smoke.json`、`wechat-native-owner-login.json`；匿名截图在 `wechat-native-operator-login.png`，诊断前图在 `wechat-native-operator-login-before.png`。日志只记录断言与状态，不记录 code/token 或本人车辆内容。

## 5. 尚未完成的 R0 内容

- 商家/技师已登录后的接车→确认/异议→派工→防护→报工/签字→核销→评价/档案原生闭环。
- 运营合成独立身份下的授权→批准/驳回→不同车主同款→撤回及拒绝矩阵。既有本机 HTTP/H5 通过记录不能替代这组原生操作。
- 工具选图真实上传、PNG 签字及私有图片访问；真实相机与手机证书另验收。
- 正式商家/运营短信适配、手机号授权、iOS/Android 真机、正式云托管/HTTPS。
- 正式资金、生产开关/迁移回滚和发布。

**R0.1a 连接/基线与真实车主登录已通过；R0 全阶段和完整履约/审核原生验收尚未完成。** 下一单元继续现有业务原生闭环，不跳到 PC 开发或宣告上线。
