# R0.1b2b 原生文件、防护、报工与签字执行记录

日期：2026-10-10；计划始于 2026-10-09。分支 `codex/r0-native-work-files`，依赖 #71 / `07705bc`。

## 已交付

- [实施计划](../superpowers/plans/2026-10-09-r0-native-work-files.md)：隔离合成订单来源、原生真实上传、防护、完整报工、画布 PNG 签字及权限拒绝。
- `scripts/native-work-files-e2e.cjs`：复用派工脚本的隔离来源准备，实际微信文件系统生成测试图片；真实页面上传并经后端扫描，施工至 `PENDING_VERIFY`，检查取消无写入和证据访问隔离。
- `createWxMockScope`：只恢复本次成功安装的官方 mock。开发者工具恢复从未 mock 的 API 会令原 API 不可用；新增两项回归检查，失败恢复仍保持追踪以便重试。派工脚本同步修正弹窗恢复条件。
- 无后端、迁移或正式产品页面变更；测试身份仅在既有隔离验收构建中使用。

## 验证

本机 **21/21**，结果 `completed=true`、`cleaned=true`；小程序 **231/231**。正式微信构建、全局样式与无测试桥检查通过。完整范围见 [验收记录](../testing/WECHAT_NATIVE_WORK_FILES_ACCEPTANCE.md)。GitHub CI 以阶段 PR 最终结果为准。

测试结束已恢复实际安装的 mock、清空测试身份、撤销五个短会话、删除内容匹配的本阶段源 PNG；再次检查源文件列表为空，`chooseMedia`、`chooseImage` 仍可用。仅关闭本阶段开发者工具项目并移除其临时 `urlCheck=false` 配置；容器及隔离审计来源保留。

## 范围限制与下一步

相册选择与系统确认/取消通过官方结果 mock 辅助，复选框通过官方 change 事件；真实 `wx.uploadFile`、画布触摸绘制、PNG 导出、扫描、接口与数据库未 mock。私有访问接口通过不代表图片渲染或正式 TLS 验收。合成笔迹不是人工签名；LOCAL_TEST 未真实扣款。

下一单元 **R0.1b2c：微信原生核销→本人评价→施工档案/来源订单**；复用本阶段施工来源并继续逐阶段中文文档及 GitHub 上传。手机真机、真实相机/选择器、弹窗实际点击、正式短信/员工绑定与证书图片预览另验收。PC 运营后台取消。

本 PR base 为仍未合并的 `codex/r0-native-dispatch`；合并时须先处理前序，再改回 main、复核差异和 CI。本阶段未授权合并或部署。
