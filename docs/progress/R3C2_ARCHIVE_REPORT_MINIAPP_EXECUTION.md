# R3c2 微信小程序阶段执行记录

日期：2026-10-11。当前代码位于 `codex/r3c2-archive-report-miniapp`，以 R3c2 后端分支为基础，形成依赖 PR #116 的微信小程序阶段；上传后补录 PR 链接和 CI 结果。PR #114 仍 OPEN；规格 PR #115 以 #114 分支为 base 且六项 CI 全绿；后端 PR #116 以 #115 分支为 base且六项 CI 全绿（backend、schema-mysql、compose-smoke 均成功）。范围为车主微信小程序及必要服务调用，不实施 PC 页面，不合并 PR。

## 已实现

- 车辆档案页新增“档案报告”卡片，可导出全部可见记录或选择首尾均包含的日期区间；日期未选齐/不合法时不会发起请求。
- 普通 HTTPS 使用原生 `downloadFile` 并携带车主 Bearer，只接受 HTTP 200、PDF MIME、固定 `Content-Disposition` 文件名、1–3 MiB 临时文件和 `%PDF-` 魔数；409 明确提示缩小区间。
- 微信云托管继续使用 `request/callContainer`，通过版本化 Accept 协商严格五字段 JSON 信封；校验版本、文件名、MIME、长度、规范 Base64、3 MiB 上限和 `%PDF-` 魔数后才写入 `USER_DATA_PATH`。
- 退出/换账号、换车、页面隐藏或替换导出会取消原生任务、作废迟到响应并清理临时文件。`openDocument` 打开期间保留文件，返回档案页后清理；打开失败和过期响应也清理。
- API runtime 明确透传 `downloadFile`、`openDocument`、用户文件目录能力；新增下载协议、云托管信封、运行时装配和生命周期单测。

## 本地验证

- `npm test --workspace @autocare/miniapp`：**331 项通过，0 失败**。
- `npm run build:mp-weixin --workspace @autocare/miniapp`：通过；含原生共享样式检查及普通微信包不得包含测试身份桥检查。
- `npm run build:h5 --workspace @autocare/miniapp`：通过。H5 仅作为兼容构建检查，不计为微信真机验收。
- Windows 受限沙箱内 Vite 对 Terser 包执行 `realpath` 时得到 EPERM；依赖树确认 `terser@5.51.2` 已安装。解除该读取限制后，mp-weixin 和 H5 两项构建均完成。为了锁定干净安装行为，Terser 已加入 miniapp workspace 的直接开发依赖和根锁文件。
- 原生真实页面交互、设备 PDF 阅读器显示及系统返回生命周期仍需真实微信设备复核；它们不由离线测试或构建代替，留在 R9 验收。
- PR #116 六项 CI 均通过，包含 `backend`、`schema-mysql`、`compose-smoke`。本机 Windows Docker daemon 未向 Maven Testcontainers 提供可用套接字，因此本地未运行 Testcontainers；GitHub CI 已通过后端和 MySQL schema 检查。

## 下一步

完成本阶段中文文档与代码提交，推送并创建基于 `codex/r3c2-archive-report-backend` 的堆叠 PR；创建后核对 #117 checks。后续收口验收单独检查访问隔离、双传输、PDF 字体/可解析性、500/501 条数、3 MiB边界及云托管/任务生命周期，再上传收口阶段 PR；不自动合并。真机视觉列入 R9，随后进入 R3c3 限时分享与撤销。
