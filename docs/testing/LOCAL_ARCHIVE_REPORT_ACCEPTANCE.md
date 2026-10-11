# R3c2 本人档案报告与私有导出验收

日期：2026-10-11。范围为车主微信小程序及必要后端；不包含 PC 后台、公开分享、真实身份接入或真机验收。所有报告仅按现有 OWNER 车辆档案权限生成，不保存服务器副本。

## 验收证据

| 范围 | 检查 | 结果/证据 |
| --- | --- | --- |
| 访问控制 | 匿名访问返回 401；演示 OWNER/商家令牌不能访问本人档案报告；本人车辆和已撤销会话由数据库服务复核 | 后端 `ArchiveAccessTest`、`JdbcArchiveTest`；PR #116/#117 全量 backend CI 通过 |
| 报告数据 | 手动/拍照档案和 source.user_id 与本人一致的完工档案；排除历史语音、删除记录、他人施工来源；日期首尾包含、未知日期计数，读取无审计写入 | `JdbcArchiveTest.reportUsesTheSameOwnerVisibleSourcesAndDateRulesWithoutWriting`，Testcontainers 测试由 GitHub backend CI 执行 |
| 记录数量 | 正好 500 条可读；第 501 条返回 409，不返回部分结果 | 收口阶段扩展 `JdbcArchiveTest.reportRejectsRevokedSessionsAndMoreThanFiveHundredRows`；待收口 PR #118 backend CI |
| PDF 传输 | 普通 HTTP 固定 PDF MIME/文件名/no-store；云托管使用版本 1 的固定 JSON 信封，解码字节与 PDF 相同；错误响应不返回部分 PDF | `ArchiveControllerReportTest`；匿名/角色拒绝见 `ArchiveAccessTest` |
| PDF 大小与内容 | 中文提取、嵌入 Noto Sans SC OFL 字体；恰好 3 MiB 接受，超出 1 字节拒绝 409；客户端校验大小与 `%PDF-` 文件头 | `ArchiveReportPdfRendererTest`；Maven 容器运行 `ArchiveControllerReportTest` **3/3 通过**，含新增3 MiB边界；微信 `archive-report.test.js` |
| 微信文件生命周期 | 直下/云托管分别使用 downloadFile/request；校验 HTTP、MIME、文件名、版本、长度、规范 Base64、魔数；隐藏/退出/换账号/切车/新任务取消旧任务并删除迟到文件；阅读器打开期间保留，返回后清理 | 小程序 **331/331**；`archive-report.test.js`、`archive-report-flow.test.js`、`api-runtime.test.js` |
| 构建/CI | mp-weixin 原生构建、共享样式和非测试身份桥检查、H5 兼容构建；规格/后端/微信 PR 分阶段 CI | 本机两种构建均通过；PR #115、#116、#117 各六项 CI 全绿。新增 JDBC 500/501 边界依赖 Testcontainers，收口 PR 的 GitHub backend CI 待验证 |

## 未验收项

Windows 本机 Docker Desktop 的 `npipe:////./pipe/dockerDesktopLinuxEngine` 当前返回 permission denied，Testcontainers 真 MySQL/500与501边界未在本机执行；使用 GitHub backend CI 结果作为数据库测试证据。真实微信设备上的页面交互、系统 PDF 阅读器视觉、打开/返回生命周期仍留 R9，不将 H5 构建或离线测试算作真机通过。

## 边界

导出不含照片原件、签字图、文件对象键、姓名、手机号或未获授权的档案；不生成六项健康评分/趋势。商家与技师不获得车主完整档案权限。限时分享/撤销另列 R3c3。
