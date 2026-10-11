# R3c2 后端阶段执行记录

日期：2026-10-11。后端阶段 PR [#116](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/116) head `codex/r3c2-archive-report-backend`、base `codex/r3c2-archive-report-spec`（规格 PR #115）；#115 以 R3c1 验收分支为 base。PR #115 和 #116 六项 CI 全部成功，#116 的 `backend`、`schema-mysql`、`compose-smoke` 均通过。PR #114 仍 OPEN；本阶段只实现后端和 API 契约，不合并 PR。

## 实施内容

- 新增 OWNER `GET /api/archive/report`，拒绝未知/重复参数，校验完整日期对、真实日期和安全车辆 ID。
- 在只读 `REPEATABLE_READ` 事务里复核 OWNER 会话、账号状态和本人未删除车辆；按现有 `ArchiveService.list` 过滤手动/拍照档案和 `source.user_id` 为本人的完成施工档案。载入最多 501 条以拒绝超限；日期筛选时统计未提供发生日期的记录。事务提交后才将 allow-list DTO 绘制成 PDF。
- 加入 PDFBox 3.0.5 和 Noto Sans SC Regular TTF 子集嵌入；保留 OFL-1.1 字体许可证。PDF 显示脱敏摘要、登记里程标签、档案字段/附件数量及施工摘要，不含原图、签名图、个人姓名/电话、对象键或来源 ID。字体不能表示的补充平面字符使用 `?`，避免 emoji 显示成空方框。
- 普通响应提供固定文件名的 `application/pdf`；云托管协商版本化 Accept 后返回固定 Base64 JSON 信封。成功/错误响应 no-store，超过 500 条或 3 MiB 返回 409，无报告副本或写入副作用。
- 更新 `docs/api/ARCHIVE_MANUAL.md`、OpenAPI 生成源与生成文件。

## 验证结果

- 最终版本 Maven 容器中 `ArchiveReportFilterTest`、`ArchiveReportPdfRendererTest`、`ArchiveControllerReportTest`：**6 项通过**。覆盖参数拒绝/最后一个有效日历日、PDF 可解析/中文提取/嵌入字体、标准 PDF 和 Base64 信封的 MIME/文件名/no-store/字节内容。
- Maven 容器中 `ArchiveAccessTest`：**2 项通过**，匿名和 demo/非车主访问拒绝。
- 排除所有标注 `@Testcontainers` 的类后运行其余后端测试：**63 个测试类、246 项测试全部通过**；之后补入最后一个有效日历日的筛选测试，并重跑最终 6 项报告定向测试。
- PDF 页面本机渲染预览确认中文字形和主要版面可读；测试确认不支持的 emoji 已降级为可读 `?`。此项不是微信原生 `openDocument` 真机验收。
- `JdbcArchiveTest` 已新增车主 A/B、手动/拍照/本人施工归属、历史语音排除、软删除、日期未知、无写入副作用及 500/501 边界断言。当前 Windows Docker Desktop 未向 Maven Linux 容器提供 Testcontainers 可用的 Docker daemon；本机启动在创建 MySQL 前失败。该测试和后端全套 CI 尚待阶段 PR 检查，不能记为已通过。
- `scripts/generate_openapi.py` 已运行，报告接口已进入生成的 `docs/api/openapi.json`。

## 下一阶段

微信小程序阶段已在后端分支上继续实施并上传 PR [#117](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/117)：档案页筛选、HTTP/云托管下载、请求生命周期清理与原生文档打开见[R3c2微信阶段记录](R3C2_ARCHIVE_REPORT_MINIAPP_EXECUTION.md)。本机小程序331项及 mp-weixin/H5 构建通过；PR #116 六项 CI 全绿，#117 CI 运行中。真实微信设备视觉仍保留 R9。
