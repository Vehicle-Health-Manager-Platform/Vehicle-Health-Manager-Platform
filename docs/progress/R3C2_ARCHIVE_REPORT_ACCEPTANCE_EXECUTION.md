# R3c2 收口验收阶段执行记录

日期：2026-10-11。收口验收 PR [#118](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/118) head `codex/r3c2-archive-report-acceptance`、最初 base `codex/r3c2-archive-report-miniapp`。PR #115–#118 各六项 CI 全绿；#118 的 backend、schema-mysql、compose-smoke 全部成功，新增 500/501 条边界由 Testcontainers backend CI 验证。**合并结果：**R1c–R3c2 的阶段 PR #85–#118 已按依赖顺序合并到 main；#118 merge commit 为 `7688c554c3ce3b94363e7ec167b8e2eed68a93dd`。

## 本阶段收口工作

- 在 JDBC 档案测试中验证恰好 500 条可生成报告，第 501 条返回 409，且没有审计写入。
- 在报告控制器测试中验证 PDF 恰好 3 MiB 可返回，超出 1 字节即 409 且不返回部分 PDF。
- 新增[验收记录](../testing/LOCAL_ARCHIVE_REPORT_ACCEPTANCE.md)，对照授权/来源/日期语义、HTTP 与云托管响应、PDF 字体/内容/大小、微信任务及临时文件生命周期逐项列出证据。
- 收口 `ArchiveControllerReportTest`：Maven 容器 **3/3 通过**，包括 PDF 3 MiB 接受、超 1 字节返回 409。
- 汇总 CI：PR #115（规格）、#116（后端）、#117（微信）、#118（收口）各六项全绿。#118 backend 7m19s、compose-smoke 1m56s；MySQL 500/501 行边界已在 backend CI 通过。

## 本地限制与剩余门槛

本机未安装 Maven；Maven 容器内控制器单测 3/3 通过，但本机 Testcontainers 无法连接 Windows Docker Desktop 命名管道。新增 MySQL 边界已由 GitHub backend CI 通过。车主微信真机操作、系统 PDF 阅读器显示及返回清理仍保留 R9。

R3c2 的代码/API/文档阶段收口完成；后续 R3c3 规划限时分享授权与撤销，先明确分享范围、接收方式、有效期、撤销立即失效和访问审计，再按规格→后端→微信→验收逐段上传。正式短信、真实微信登录/资金和 PC 后台不进入本阶段。
