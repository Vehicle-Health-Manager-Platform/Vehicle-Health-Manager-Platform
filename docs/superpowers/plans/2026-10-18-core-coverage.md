# A4a实施计划

1. 固定核心类清单和测试集合，配置独立JaCoCo Maven profile。
2. 实现可复跑门禁、环境/命令/测试结果报告与门禁自身异常测试。
3. 运行真实核心套件测基线；按具体未覆盖风险补测，不缩范围或降低70%阈值。
4. 接入全量CI报告与artifact，整理中文覆盖范围、执行证据和后续A4b/A4c计划。
5. 提交推送代码/测试/CI/文档，创建依赖PR159的阶段PR；远程CI未结束保持草稿。

开始基线2576060；前序PR159前端/生成/数据库通过，后端运行中。

1–4已完成：431项真实核心执行、14项门禁/运行器测试通过；LINE93.43%、METHOD89.91%，各组六组双门槛通过。5已上传[PR160](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/160)，当前head全量CI待完成，保持草稿。A4b/A4c保留，未计作本轮完成。
