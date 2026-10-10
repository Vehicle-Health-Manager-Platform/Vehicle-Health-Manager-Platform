# 微信原生私有图片与系统按钮诊断

日期：2026-10-10；SDK 3.17.2；隔离产物 `.cache/r0-native/mp-weixin`、自动化端口 9422、回环后端 18080/TLS 下载 9443。

## 分层结果

| 层级 | 结果 | 判定边界 |
| --- | --- | --- |
| 环境/证书 | 1 项通过 | 独立回环 SAN 证书有效，启动本任务自己的 TLS helper |
| 三类私有图片授权 | 3 项通过 | 评价、施工档案、本人 PNG 签字实际接口授权 |
| 默认严格 TLS 客户端 | 3 项通过 | 新自签证书未受信任时被拒绝，未关闭校验 |
| 显式信任本机公钥下载 | 3 项通过 | 实际 HTTPS 200 和非空图片，保留签名 host/path/query |
| 微信 getImageInfo | 3 项通过 | 评价/施工 PNG 1×1；签名 PNG 为实际画布尺寸；当前调试例外模式，不证明正式 TLS |
| 页面实际预览 | 未通过 | 预览层加载，未看到图片/笔迹；不以成功回调或解码替代视觉结果 |
| 官方 Native 确认/取消 | 未通过 | 无图片覆盖层重测，两命令均未触发真实回调，截图仍显示弹窗 |
| 实际选择器点击 | 未验收 | 当前本机官方 Native API 未提供文件选择命令，不能据此声称相机/选择器通过 |

10 项传输检查与 3 项解码结果单列，失败/待验项不混入“全通过”数字。报告 `diagnosticsComplete=true`、`acceptanceComplete=false`；来源施工 21/21、核销评价档案 29/29 单列。小程序 231/231，微信/H5 构建和普通微信无测试桥通过，后端及最终六项 CI 见阶段 PR。

最终独立用例（UTC）：签名 `01:23:43–01:24:56`、档案 `01:25:20–01:26:32`、评价 `01:26:59–01:28:17`，均为 2026-10-10；各次 `cleaned=true`、`serverStopped=true`。已实际查看三张独立截图，均仍为加载图标，没有已渲染图片；签名解码尺寸 329×179，未在预览层看到笔迹。

## 复现与证据

1. 忽略目录准备独立 `native-preview-cert.pem`/`native-preview-key.pem`，回环 SAN，有效期三天；旧证书私钥缺失，未覆盖原证书。无系统信任安装、无 `NODE_TLS_REJECT_UNAUTHORIZED=0`。
2. 在当前隔离微信项目运行 `node scripts/native-preview-e2e.cjs --allow-local-test-writes --preview-kind=review`；对 `archive`、`signature` 分别重开**同一隔离项目**后运行，避免旧预览覆盖层影响截图。
3. 没有 `wx.closePreviewImage` 能力，故每次只做一类视觉检查，等待 10 秒再截图；其余类别仍做下载与解码。此前连续预览的截图可能受旧覆盖层污染，不作为三类独立视觉证据。
4. 重开隔离项目后运行 `node scripts/native-modal-probe.cjs`，确认与取消各等待 1 秒，实际回调均为 false。截图显示原生测试弹窗仍存在。
5. 报告 `test-results/wechat-native-preview-{review|archive|signature}.json`、`wechat-native-modal-probe.json` 和合成截图仅留忽略目录；令牌、核销码、私有 URL 不记录。

本机视觉加载失败原因尚未确认；Node 显式信任证书下载、微信 getImageInfo 成功不能推导系统预览已信任证书或图片已渲染。隔离项目当前 URL 校验例外也是明确限制。没有改正式 TLS/域名设置、没有将本机 public origin 改为 HTTP。

## 清理和仍欠事项

每次恢复本次实际安装的 mock、清空身份、撤销六个短会话、删除内容匹配源 PNG；停止本脚本拥有的 TLS 子进程。结束只关闭本项目并移除临时 URL 校验例外，保留既有容器及服务器合成审计。

原生私有图片视觉、实际系统点击仍欠；正式短信供应商、真实员工绑定、正式公网证书/合法域名及 iOS/Android 真机见 [前置清单](../progress/R0_IDENTITY_DEVICE_PREREQUISITES.md)。合成笔迹不算人工签名，LOCAL_TEST 未真实扣款。R0 尚未完整完成，PC 运营后台取消。
