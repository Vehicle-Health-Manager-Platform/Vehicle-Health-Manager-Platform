# 本地车辆与无图档案真实联调

日期：2026-10-06；后端源码基线：`0237ababf35c254eddfdbbdca8c5bea4a65090c0`。

## 已验证的范围

H5 发布产物的实际按钮/选择器 → 微信开发者工具的真实 `wx.login` code → 本机真实后端 → MySQL。业务响应没有使用替身。浏览器桥接微信取码和同源转发，不代表 H5 自身具有小程序微信登录能力，也不代表微信模拟器完整业务 UI 或真机已验收。

22 项检查：发布产物原生请求 API、真实会话、四级车型选择、两车保存及脱敏、两条无图档案保存及手动来源、切车隔离、首页总数/最近记录、跨 Tab 共用选择、按车分页、合成他人车辆读写拒绝、两种写入丢响应后原键原文重试、同键异文拒绝，以及业务记录/成功审计增量一致。

成功运行新增 2 辆车、2 条档案、2 条车辆成功审计和 2 条档案成功审计。GET/POST 合成他人车辆均返回 404；同键异文返回 400。车型、车牌、标题和隔离车主均为明确标识的合成资料；实际会话来自真实微信身份。第二个真实微信车主的隔离操作仍待验收。

## 运行前提

- Node.js 22、Docker、微信开发者工具与仓库依赖可用。
- 按[登录运维清单](../operations/AUTH_INTEGRATION_RUNBOOK.md)启动 `vehicle-auth-local` 隔离项目；MySQL 容器固定为 `vehicle-auth-local-mysql-1`，完成 V001–V005。后端 `127.0.0.1:18080` 必须连接这套测试库。
- 后端私有环境配置真实微信/JWT/数据库参数。密钥、code、令牌不可记录或提交。
- `apps/miniapp/.env.local` 配置 `VITE_API_BASE_URL=http://127.0.0.1:18080`。文件和编译产物不提交。
- 微信自动化服务为 `ws://127.0.0.1:9420`，导入本工作树产物。仅本地调试产物可关闭 URL 校验；源码仍启用校验。

## 可执行步骤

在仓库根目录运行：

```powershell
npm run build:h5 --workspace @autocare/miniapp
npm run build:miniapp
# 在开发者工具 CLI 路径调用：
& '<开发者工具安装目录>\cli.bat' auto --project "$PWD\apps\miniapp\dist\build\mp-weixin" --port 11927 --auto-port 9420 --trust-project
node scripts/local_business_harness.cjs --allow-local-test-writes
```

最后一条保持运行。它只监听 `127.0.0.1:4317`，检查固定容器的 Compose 项目标识和保留 ID 冲突，再导入 `scripts/local_business_fixture.sql`；没有授权参数时拒绝运行。不要单独将 SQL 应用于其他数据库。测试服务不用于生产部署。

使用 gstack `/browse`，把 `$browse` 设为其可执行文件路径：

```powershell
$env:BROWSE_PARENT_PID='0'
& $browse goto 'http://127.0.0.1:4317/#/pages/owner/index'
& $browse eval apps/miniapp/test/local-business-browser-flow.js
```

从无浏览器会话的车主登录页开始；已有会话先通过退出登录结束，再回到车主入口。成功输出 `passed: 22`、检查名称与聚合结果，不输出账号、code 或令牌。测试会保留合成车辆/档案供人工复核；再次运行使用不同合成里程和车牌。停止服务用 Ctrl+C，不删除数据库卷。

失败注入先让后端提交，再返回截断的 JSON 正文，使应用收到连接失败；用户点击保存重试必须沿用原键、原文。不能仅在请求发送前制造失败来证明写入幂等。每次核对数据库与审计的前后增量，不用绝对数量掩盖重复。

## 本次发现与修复

H5 默认摇树优化无法发现服务工厂通过 `runtime: () => uni` 动态调用的 API，发布产物中 `uni.request` 缺失；离线替身测试没有发现。`manifest.json` 关闭 H5 摇树优化，保留现有运行时接口，微信构建配置不变。真实发布产物检查和真实请求通过；代价是 H5 包含更多框架 API，后续如优化体积须重新跑本验收。

## 下一步

私有 MinIO/ClamAV、真实图片 CLEAN 归档与短时预览、拍照录入。微信真机、手机号、第二个真实车主、商家短信和生产车型来源继续独立验收，见[下一步规划](../progress/NEXT_STEPS.md)。
