# R5 券迁移与恢复

先备份并按版本应用已有V001–V037、[V038](../sql/migrations/V038__coupon_inventory_and_claims.sql)、[V039](../sql/migrations/V039__coupon_transactions.sql)，再运行含券代码的服务。原44表基线不重写，完整库88表。两次重放的检查见`scripts/verify_mysql_schema.sh`；V038/V039不填生产券、库存/阈值或历史合同，不自动授予can_coupon。

## 权限与版本

发券管理仅店长；服务券核销本店店长/店员；精选曝光独立can_coupon。运营授权流程见[运营账号](OPERATOR_ACCOUNTS.md)。新交易合同R5B_CASH_V1、独立服务记录R5B_SERVICE_V1，用户已确认规则。旧coupon_id/WAIT_COUPON没有来源不能转为可信优惠/免佣。

## 恢复检查

- 原ORDER_EXPIRY_INTERVAL_MS关闭过期待付并释放券/名额；新COUPON_RECOVERY_INTERVAL_MS默认30000毫秒，恢复已提交的关闭/付款/退款与券状态。
- 查看order_coupon_allocation的state、reserved_until、recovery_after/recovery_error以及对应唯一coupon_transaction_event。错误只保存固定FACTS_UNAVAILABLE，不保存原始SQL、供应商正文或授权码。
- 支付/退款供应商失败继续走既有PAYMENT_QUERY_RECOVERY/REFUND_RECOVERY租约与回执；未确认退款不能人工直接返券。身份、金额、付款/退款来源不一致时核对原始事实，不能直接改USED→AVAILABLE猜测结果。
- 恢复不重发券、不更改已发布权益或原有效期、不改变现金付款状态、不提升旧无合同数据。合法全退返券不补库存，因为是原发放权益返还而非新增发行。
- order_coupon_allocation永久creation_key/request_digest保护长时间创建重试；不要删除合同/事件来“重置”重试或限领。

## 授权码

SERVICE码只由本人显式生成，数据库授权表只存摘要；本人幂等私有回执包含原生成码，以便结果不确定时同键重试。审计和运维输出不能复制授权码或回执正文。核销先预览门店/项目并确认交付；错码数据库限流，等待Retry-After。重新生成须读取最新版本，旧码即失效。

## 发布边界

本阶段上传源码和文档、运行CI是开发交付，未部署正式环境。正式短信、微信身份/资金、供应商识别、真机与发布/回滚最终R9。回滚代码前先停止新券交易，保留新合同/事件；不可把新用券单交给不了解合同的旧版本消费或直接删除新表。真实生产发布仍需R9专门验收。
