# R0.1b1 微信原生授权、审核与同款摘要验收

日期：2026-10-09。实现分支 `codex/r0-native-publication`，直接依赖 #69。仅覆盖微信小程序运营审核闭环；不开发 PC 后台。

## 实测结果

| 检查 | 结果 |
| --- | --- |
| 隔离来源、临时运营权限、运行 JAR | 固定合成来源与工作树 JAR 一致 |
| 原生业务检查 | **11/11**，清理成功；实际页面、HTTP 与数据库，无响应替身 |
| 授权与批准 | 本人撤回旧授权→勾选重新送审→运营批准→另一同款车主看到摘要 |
| 撤回 | 本人撤回后，另一车主重新查询不再出现摘要 |
| 驳回 | 重新明确授权→运营选择固定理由驳回→本人看到状态与理由 |
| 退出保护 | 清空测试身份后审核页跳独立运营登录页 |
| 数据准备 HTTP 回归 | 本次 **42/42**；含可靠任务查询重试；首轮 41/41、末轮 42/42，次数随查询重试变化，均完整通过。历史 43 项记录属于此前运行 |
| 小程序自动测试 | **228/228**，失败/取消/跳过 0；新增 3 项构建隔离检查 |
| 构建 | 普通微信构建及“不含身份桥”扫描通过；辅助 H5 构建通过 |

原生结果保存于忽略的 `test-results/wechat-native-publication.json`，仅记录断言名称、时间、完成与清理状态；不上传凭据、截图或构建产物。最终 GitHub CI 以阶段 PR 当前 head 为准。

## 环境与证据边界

- 微信开发者工具，RPC `ws://127.0.0.1:9422`，CLI HTTP `11927`；独立测试产物 `.cache/r0-native/mp-weixin`。
- 后端 `vehicle-auth-local-backend`，镜像 `vehicle-auth/backend:a7-experience-publication`，回环接口 `http://127.0.0.1:18080`，V019/61 表；运行 JAR SHA256 为 `261a877db7c9fb5d6f475e90c8fb7770f81bcc3cd09227f4461c7d0e6cfac4cb`。
- 来源订单 `9207641`、车主/车辆 `9207590`、同款读者/车辆 `9207591`，均为已验证的隔离合成数据。运营账号只允许本轮 `pub-` 前缀账号；待审必须只有该合成卡片，避免操作其他记录。
- 运营短信使用既有合成 OTP。会话通过隔离构建的内存桥注入，不能算正式短信/真实账号登录验收；页面请求、业务状态和数据库写入是真实执行。
- 当前 SDK 的 `App.evaluate` 返回 `unimplemented`。插件仅在显式测试开关、固定独立输出、回环后端、无云托管变量时启用；运行时还要求 `platform=devtools`。普通微信产物递归扫描桥标记，发现即构建失败。
- 原生 `checkbox` 的 `tap` 未触发 change，因此按工具支持的 `Element.triggerEvent` 发送 checkbox-group change；驳回理由 picker 同样发送 change。随后由真实按钮提交。未修改页面数据、业务方法或 HTTP 响应；这些检查不能代表手机上手指勾选/选择器交互通过。
- 收口清空内存身份、撤回合成授权、注销本轮车主/读者/运营会话并停用临时账号，删除私有 UI 凭据文件及临时 urlCheck 例外；保留隔离业务审计。生产发布开关仍默认关闭。

## 复现

先按已有 [HTTP 验收记录](LOCAL_EXPERIENCE_PUBLICATION_ACCEPTANCE.md) 准备且核对本机隔离环境。构建命令在 `apps/miniapp` 目录执行：

```powershell
$env:R0_NATIVE_TEST_BUILD='1'
$env:UNI_OUTPUT_DIR=Join-Path (Resolve-Path '../..').Path '.cache/r0-native/mp-weixin'
$env:VITE_API_BASE_URL='http://127.0.0.1:18080'
$env:VITE_WECHAT_CLOUD_ENV_ID=''
$env:VITE_WECHAT_CLOUD_SERVICE=''
& './node_modules/.bin/uni.cmd' build -p mp-weixin
```

在仓库根目录、另一终端执行；无需把开关带进普通构建：

```powershell
'{"setting":{"urlCheck":false}}' | Set-Content -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json' -Encoding utf8
& '<工具安装目录>/cli.bat' auto --project "$PWD/.cache/r0-native/mp-weixin" --port 11927 --auto-port 9422 --trust-project
node scripts/local_experience_publication_e2e.cjs --allow-local-test-writes --keep-ui
node scripts/native-publication-e2e.cjs --allow-local-test-writes
Remove-Item -LiteralPath '.cache/r0-native/mp-weixin/project.private.config.json'
npm test --workspace @autocare/miniapp
npm run build:miniapp
```

必须在短会话过期前运行。脚本连接、页面、元素均有限时等待；失败退出非 0。自动化清理仍执行，但应核对 `cleaned=true`，清理失败不能记为完成。只操作该项目，不退出整个 IDE 或停止其他容器。

## 收口前问题与修正

1. 运营 JWT 的角色实际为 `OPERATOR`，修正测试的大小写期望。
2. 元素 tap 仅发起事件，立即导航会取消页面状态更新；改为等当前页授权/撤回结果后再导航。
3. 审核成功后页面显示成功信息，列表 `loaded=false`；改等“已批准/已驳回”再校验数据库，而非等待尚未加载的空列表文字。
4. 清理校验撤回响应和数据库，客户端清空失败也继续注销服务端会话；失败报告不能打印 token 或私有内容。

## 未完成

商家/技师完整原生履约、工具选图/上传/签字/私有预览、正式短信及手机真机仍待独立阶段。R0 全阶段未完成，真实资金未验收。
