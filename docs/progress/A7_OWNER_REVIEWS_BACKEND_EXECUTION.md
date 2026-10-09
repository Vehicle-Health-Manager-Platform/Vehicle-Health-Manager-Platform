# A7.2a 评价后端执行记录

2026-10-09。已实现 OrderReviewInput、OrderReviews、OrderReviewsController，接口与规则见[本人评价 API](../api/ORDER_REVIEWS.md)。

- V016 新建评价/图片关系两表，订单唯一、图片位置/关系唯一；不补历史核销，不修改原始 V001，迁移重复执行保留数据，完整57表。
- 正式 OWNER 当前会话和本人订单授权；COMPLETED、无 OPEN 争议、核销来源和可信付款复核。LOCAL_TEST 标识继承，既有核销不再依赖当前测试付款开关或原技师登录。
- 严格 JSON、ID/评分整数、Unicode 码点长度、最多3张本人安全图；每次成功缓存重放也复核资格与图片。只对本人返回最小记录，不公开、不计算商家评分。
- WriteIntegrityService 同事务保存评价/图片/摘要审计/成功缓存；不同会话并发同/异内容，以及四处触发器失败回滚都有真实 MySQL 用例。

HTTP 契约5项与未配置数据库2项已通过。初次真实 MySQL 未执行，因本机 Docker29 API 默认不兼容；改用既有本地 API1.44、host.docker.internal 与禁用Ryuk参数（只用于本机，不改CI）。最终 MySQL 及 CI 结果收口时记录，不把环境失败计入通过。

规格阶段 [#49](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/49) 六项 CI 全绿；后端阶段基于规格分支堆叠。页面、真实HTTP与真机分别验收。

最终本地复验：评价真实 MySQL **12/12**、核销回归 **15/15**、评价 HTTP **5/5**、未配置数据库 **2/2**，共34个独立用例，失败/错误/跳过均为0。无资格查询的 nullable test_mode 自动拆箱问题已修复，负向资格用例全部通过。Compose 新环境补挂载 V015/V016，实际完整 CI 另由本阶段 PR 验证。
