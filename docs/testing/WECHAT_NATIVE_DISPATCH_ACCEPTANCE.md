# R0.1b2a 微信原生确认、派工与本人接单验收

日期：2026-10-09。分支 `codex/r0-native-dispatch`，依赖 #70 / `87dace4`。范围为微信小程序，PC 运营后台已取消。

## 完成与边界

本阶段交付**原生页面业务验收脚本**，系统弹窗采用官方结果辅助；不是完整原生弹窗/文件/履约链路验收。`nativeModalClickAccepted=false` 明确写入报告。

| 检查 | 结果 |
| --- | --- |
| 最终业务检查 | **18/18**：3 项隔离/支付/七图 HTTP 准备 + 15 项原生页面业务检查；系统弹窗结果 mock，HTTP 与数据库真实执行 |
| 未确认阻断 | 商家原生派工提交被拒，数据库未创建派工 |
| 车主确认 | 取消结果不确认；确认结果后真实写入 `owner_confirm=1` |
| 商家派工 | 原生选择本店已绑定技师，取消结果不派工；确认结果后为选中技师创建唯一派工 |
| 权限隔离 | 他店商家、同店未被派技师均不能读取目标详情，页面不展示详情内容 |
| 本人接单 | 本人列表可找到工单；取消结果保留 `RECEIVED`；确认结果后数据库 `IN_SERVICE` / `ACCEPTED`，原生刷新及商家详情一致 |
| 登录与清理 | 清空后工单页要求技师登录；本轮五个短会话注销并查询确认无未注销会话 |
| 文案修复 | “已接车”不再直接声称“车主已确认”；改为车主确认后才能派工，实际原生文本已核对 |
| 自动测试 | 小程序 **229/229**，失败/取消/跳过 0；新增按钮模板空白匹配回归 |
| 普通微信构建 | 通过，公共样式及普通产物无身份桥门禁通过 |
| 最终 CI | 以阶段 PR 当前 head 的六项检查与日志为准 |

本机结果保存在忽略的 `test-results/wechat-native-dispatch.json`，报告不含 token、微信 code、文件 URL 或本人资料。准备上传是固定合成 1px PNG 经真实存储扫描，不算工具选图或相机验收。测试支付使用真实签名 LOCAL_TEST 回调，**未真实扣款**，原生详情中测试标识已核对。

## 环境与安全约束

- 本机 `vehicle-auth-local` 隔离栈，回环 `http://127.0.0.1:18080`；镜像 `vehicle-auth/backend:a7-experience-publication`，V019/61 表。
- 运行 JAR 与工作树 JAR SHA256 同为 `261a877db7c9fb5d6f475e90c8fb7770f81bcc3cd09227f4461c7d0e6cfac4cb`；验证后才准备来源。付款 profile 与 LOCAL_TEST 配置须显式启用。
- 合成账号范围：商家 `9301201/9301202`，技师 `9301231/9301232`，绑定 `9301331/9301332`，车主/车辆 `9301290`，时段 `9301301`。对账号标识、车型归属、店铺和绑定归属作碰撞/有效性检查；拒绝不匹配数据。
- 每次创建独立 `native-r0-<随机片段>` 订单；不重置原 A6/A7 订单、评价、经验卡片或审计。诊断失败的合成来源与文件保留在隔离库/对象存储用于追溯，所有本轮短会话仍注销；不伪造真实退款或删除来源审计。
- 五个 15 分钟会话只在脚本和独立测试包内存中，不写私有 UI 凭据文件；凭据从已有隔离容器配置读取，不打印、不上传。正常客户端无测试登录接口。
- Devtools RPC `ws://127.0.0.1:9422`；独立产物 `.cache/r0-native/mp-weixin`。回环 HTTP 例外仅在该产物私有配置，收口移除并仅关闭该测试项目。

## 系统弹窗与真实原生操作的区别

原生按钮实际 `Element.tap`，先按实际元素位置滚动到可见区域，等待原生选中/成功状态后才继续；派工按钮模板首尾空白在比较时 trim，内部空白仍保留。

截图确认真实“确认派工”弹窗出现。官方 SDK 0.12.1 的 `Native.confirmModal/cancelModal` 使用 `Tool.native`，当前工具返回空结果但弹窗未关闭；本机保留的 `Tool.invokeNativeMethod` 也返回 `timeout waiting for automator response`。没有将这些结果记为成功。

采用官方 `App.mockWxMethod` 的 `result` 对象模式，仅提供 `showModal` 的确认/取消结果。原生页面处理回调、幂等请求、服务端权限/前置条件、数据库及审计仍真实执行。每次辅助决定后恢复方法，最终清理再恢复一次；不 mock 业务 request、response、页面方法或 data。函数 mock 不作为本阶段成功证据。

因此三项取消检查证明**系统取消回调下业务不写入**，不证明模拟器/手机中手指点击取消通过。实际弹窗点击、手机真机、正式商家短信和技师真实微信绑定仍待独立验收。旧 `App.evaluate` 失败是协议名称问题：SDK evaluate 使用 `App.callFunction`，本次纯函数探测返回 4；不再据此声称 SDK evaluate 不支持。

## 复现

先按 [b1 验收](WECHAT_NATIVE_PUBLICATION_ACCEPTANCE.md) 的步骤建立隔离构建及自动化 9422；该脚本不依赖 b1 私有 UI 会话文件：

```powershell
node scripts/native-dispatch-e2e.cjs --allow-local-test-writes
npm test --workspace @autocare/miniapp
npm run build:miniapp
```

脚本自行核对环境、创建新合成订单、真实测试付款及七图接车，再由原生页面完成后续业务。所有连接/RPC/页面/元素/HTTP 均限时，失败退出非 0；检查 `completed=true`、`cleaned=true`，但 `nativeModalClickAccepted` 应仍为 false。整包变更导致旧编译上下文时，仅清当前项目 compile 缓存。

结束删除 `.cache/r0-native/mp-weixin/project.private.config.json`，关闭该独立测试项目；不退出整个 IDE、不清账号缓存、不停止其他容器。

## 下一阶段

先完成原生选图、真实上传、防护及报工 PNG 签字，再接核销/评价/档案；同时继续跟踪弹窗实际点击的工具兼容性。R0.1b2 和 R0 全阶段均未完整完成。
