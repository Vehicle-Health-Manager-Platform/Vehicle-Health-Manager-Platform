# R3c2 收口验收阶段执行记录

日期：2026-10-11。当前分支 `codex/r3c2-archive-report-acceptance` 从微信阶段分支 `codex/r3c2-archive-report-miniapp` 建立；该分支在 PR #117 全六项 CI 通过后继续。当前阶段新增边界回归并整理全链路验收文档，作为单独堆叠 PR 上传；不合并任何 PR。

## 本阶段收口工作

- 在 JDBC 档案测试中验证恰好 500 条可生成报告，第 501 条返回 409，且没有审计写入。
- 在报告控制器测试中验证 PDF 恰好 3 MiB 可返回，超出 1 字节即 409 且不返回部分 PDF。
- 新增[验收记录](../testing/LOCAL_ARCHIVE_REPORT_ACCEPTANCE.md)，对照授权/来源/日期语义、HTTP 与云托管响应、PDF 字体/内容/大小、微信任务及临时文件生命周期逐项列出证据。
- 收口 `ArchiveControllerReportTest`：Maven 容器 **3/3 通过**，包括 PDF 3 MiB 接受、超 1 字节返回 409。
- 汇总 CI：PR #115（规格）、#116（后端）、#117（微信）各六项全绿；#117 包含 backend 7m10s 与 compose-smoke 1m55s。`JdbcArchiveTest` 的真实 MySQL 500/501 行边界新增断言等待本阶段 PR CI。

## 本地限制与剩余门槛

本机未安装 Maven；解除沙箱的 Docker 读取限制后，Maven 容器内控制器单测可运行，但 Testcontainers 仍无法连到 Windows Docker Desktop 命名管道。本阶段 JDBC 新增边界测试需由 GitHub `backend` CI 验证后才能记为通过。车主微信真机操作、系统 PDF 阅读器显示及返回清理仍保留 R9。

通过本阶段全部 CI 后，R3c2 的代码/API/文档阶段收口完成；随后规划 R3c3 限时分享授权与撤销。正式短信、真实微信登录/资金和 PC 后台不进入本阶段。
