# A7.2b 施工档案回写执行记录

2026-10-09，开始于 71fcc6e。范围与实施授权来自用户“规划好下一步，然后开始实施”。

## 规格阶段

已核对 SPEC、已有手动档案、评价、核销和施工报工实现。触发为评价后，数据库可靠任务异步回写；历史不补写，保留原档案，施工图片使用本人档案授权，测试来源隔离。规格完成自查：无占位项，日期采用 UTC，消费开关与发布次序明确。

- [规格](../superpowers/specs/2026-10-09-a7-service-archive-design.md)
- [中文计划](../superpowers/plans/2026-10-09-a7-service-archive.md)

后端、页面与联调正在实施，测试和 GitHub 状态在实际验证后追加，不把既有测试当新功能验收。

规格 PR [#53](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/53)，fa7c6c5，六项 CI 通过（37890361237）；仅已有实现回归。

## 后端阶段（验证中）

已编码 V017、评价同事务任务、独立恢复消费、系统审计、本人施工档案列表和私有图片授权、测试 AI 历史隔离。消费者默认关闭，Compose 和示例环境支持显式开启。新增真实 MySQL 测试覆盖来源、回滚恢复、并发、权限及测试隔离；本机编译通过，数据库测试运行中。OpenAPI 生成 102 操作，生成文件与 diff 检查通过。后端先上传草稿供 CI，验收完成后转为待审阅。

后端草稿 [#54](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/54)，3b2d368；真实 MySQL CI、生成文档、网页和小程序已通过，最终六项结果见 Checks。本机 Docker 需 api.version=1.44，Ryuk 镜像损坏导致首次数据库启动失败；隔离测试随后由 JUnit 生命周期管理容器，不把启动失败计作业务测试通过。

## 页面阶段

已接入 input_type=4 的完整来源校验、实际配件/分钟工时/施工时间、测试提示、来源订单导航及档案专用私有图片路径。首页最近档案同步显示测试标注；手动/拍照入口保持原协议，不允许伪造自动档案。小程序 196/196 通过（新增 3 项协议/权限路径验证），微信与 H5 构建通过。首次构建遇到本机 terser 解析问题，按锁文件 npm ci 后重建成功，无依赖版本变更。真实 H5 与 HTTP 留待下一联调阶段。
