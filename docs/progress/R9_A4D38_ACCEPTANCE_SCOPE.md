# A4d38 匿名验收范围与CI修复

2026-10-24，基线4704dcf / PR228，分支codex/r9-a4d38-acceptance-scope。此阶段修复验收运行器；OCR环境准备因失败CI优先处理而顺延，微信小程序范围不变。

## 根因与证据

PR228最终head的GitHub CI运行40052396963：web、miniapp、schema-and-ocr、schema-mysql、database-recovery、performance-baseline六项成功；backend失败，compose-smoke跳过。Java clean verify真实2201项测试、0失败/错误/跳过并BUILD SUCCESS，随后首个匿名/车主Newman步骤在0.033秒内报Review changed anonymous acceptance scope，尚未构建或启动隔离HTTP栈。不能把该失败归为Java测试或真实OCR失败。

A4d36新增受保护里程网关，生成OpenAPI/Postman已为353项操作、339个匿名探针；run_api_acceptance.py仍以338审定范围并在日志打印338。历史覆盖317及历史报告中的338保留原事实，不修改历史证据。

## 最小修复与回归

仅修复scripts/run_api_acceptance.py的审定数量为339，日志使用实际集合长度；数量变化仍需显式审查，未删除探针或允许任意数量。测试通过main入口读取完整真实契约/生成资产，在构建边界停止；增加或删除一个受保护接口必须在构建前拒绝。

先修复测试夹具Mock角色不可JSON序列化问题，再在旧运行器上证实两项断言失败：当前339项无法到达构建边界，移除里程网关后的338项反而被允许。修复后55项相关Python测试通过。这些运行器单测不启动Docker或业务请求，与真实HTTP证据分开。

## 验证状态

真实隔离HTTP复验通过：匿名339请求/1017断言（包含POST /api/vehicle/mileage-recognition的401及三项断言），车主61请求/204断言，无失败。prepared和owner集合默认关闭均零请求，匿名阶段业务表行数不变，车主数据库结果核对通过，源码稳定；backend/MySQL/network三项资源清理通过。当前源码重新构建jar，独立MySQL应用迁移及真实HTTP执行，身份与数据均为合成；并非替身HTTP通过。耗时213.531秒，见[脱敏报告](../testing/reports/R9_A4D38_ACCEPTANCE_SCOPE_2026_10_24.json)。

OpenAPI生成核对353项、交接索引、覆盖与结论对账通过。55项相关Python完整测试通过；未修改Java或小程序源码，不重复声称本轮运行2201项Java或微信构建。2201项属于前置远程CI事实。没有业务后端/小程序功能改动，不新增真实OCR验收结论。全API验收仍false，历史317/353及36套/36结论不变。

## GitHub交付与下一步

依赖PR228的独立草稿PR，完成后补记链接，不自动合并部署。下一阶段继续准备可重现的OCR依赖/本地模型与镜像，执行授权图片效果和微信全链路；本机缺Paddle/PaddleOCR，不能称模型已准备完成。
