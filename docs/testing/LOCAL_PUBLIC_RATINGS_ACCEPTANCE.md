# 公开星级本地验收

1. 完整迁移至V033；旧评价没有授权行，不出现在公开列表，空态0/0/null。
2. 登录本人已完成且可信非测试履约的合成测试订单，原评价页进入独立授权；取消确认不写入，确认后仅评分与UTC日期进入公开列表，无正文/照片/身份标识。
3. 第二评价分别评分5和2，汇总count2/sum7/average3.50；size1两页顺序稳定，不混其他门店。
4. 撤销后重新查询不再计入；重放旧授权幂等键不重新公开，旧版本新键409；刷新看到实际授权状态。
5. 测试支付、金额不符、作者停用、门店停用、退款/争议/支付异常、失效付款事件立即排除；本人仍可在付款/门店失效时撤销。
6. 不同账号无法管理他人授权；撤销会话后缓存写也不能重放；并发同版本只有一次变更；审计失败授权/幂等均回滚，原键可重试。
7. 小程序确认时离开页面、切换账号、网络失败重试、成功后刷新失败、列表迟到响应均不泄露旧状态；重新进入读取最新状态。

测试命令：`npm test --workspace @autocare/miniapp`；Maven `-Dtest=JdbcPublicRatingsTest,PublicRatingsHttpTest,JdbcOrderReviewsTest,OrderReviewsHttpTest test`；`npm run build:miniapp`；miniapp `build:h5`；`bash scripts/verify_mysql_schema.sh`；生成脚本与diff校验。

真实MySQL证据与mock HTTP/离线小程序替身分别记录在[执行记录](../progress/R4B2B1_PUBLIC_RATINGS_EXECUTION.md)。合成WECHAT数据库记录用于逻辑测试，正式资金、微信身份、真机与发布仍R9；公开正文/图片尚未实现。
