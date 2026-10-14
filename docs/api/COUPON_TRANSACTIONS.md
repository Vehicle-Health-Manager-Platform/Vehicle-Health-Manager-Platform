# R5b 用券交易与服务券核销协议

规则来源：用户2026-10-14明确确认单券不叠加、发行门店指定项目、现金实付佣金/既有免佣期、全退返原券不续期；另确认现金金额券实付至少0.01元，SERVICE独立核销不创建支付。设计见[设计](../progress/R5B_COUPON_DESIGN_2026-10-14.md)。实现：[交易](../../backend/src/main/java/com/autocare/platform/order/CouponTransactions.java)、[控制器](../../backend/src/main/java/com/autocare/platform/order/CouponTransactionsController.java)、[核销](../../backend/src/main/java/com/autocare/platform/order/CouponServiceRedemption.java)、[恢复](../../backend/src/main/java/com/autocare/platform/order/CouponRecovery.java)。

统一ApiResponse，全部no-store。未知/重复query、重复/尾随JSON、额外金额/佣金、非整数ID和非UUID写入键拒绝。POST预览不需要写入键；其余POST写入必须UUID。授权码不放URL，不写审计或日志。

## 现金金额券

- POST `/api/order/coupon-preview`：精确`{merchant_project_id,quote_version_id,vehicle_id,slot_id,selected_user_coupon_id,page}`，所选券可null；页1..1000000/固定20。返回`{quote,vehicle_id,slot,coupons,selected,can_create,rule_version}`。所选券独立复核，允许在其他页。源报价/本人车/时段为同一读取快照；预览不占券或名额。
- 候选条目：`{user_coupon_id,coupon_id,source_known,merchant_id,publication_revision,terms,eligibility,discount_amount,cash_amount}`。历史来源未知时来源/条款/金额为空；可用时原价=优惠+实付，实付>=0.01。原因包括SOURCE_UNKNOWN、SERVICE_ONLY、NOT_AVAILABLE、NOT_STARTED、EXPIRED、WRONG_SCOPE、ISSUER_UNAVAILABLE、PROJECT_UNAVAILABLE、BELOW_MINIMUM、ZERO_CASH。
- POST `/api/order/coupon-create`：精确`{merchant_project_id,quote_version_id,vehicle_id,slot_id,user_coupon_id,publication_revision,rule_version}`，版本为`R5B_CASH_V1`。返回原创建回执`{order_id,order_no,amount_due,status,expires_at}`，status为创建时PENDING_PAYMENT。旧`/api/order/create`仍只接受原四字段，不能额外塞券。
- 同事务复核名额/报价/归属/范围/发布版本/状态/有效期/门槛与正实付，创建订单、冻结合同和券、占用名额并审计。`order.coupon_id`是模板ID，具体本人券实例在新合同`user_coupon_id`，不能拿旧字段推断可靠用券。
- 待付截止取原15分钟/时段开始/券截止最早值。支付必须有唯一可信PAID事实才消费；关闭释放。迟到/取消后的支付沿用REVIEW，不消耗已释放或另单预占的券。已付权益不因后续券到期或停止新领撤回，服务预约可以晚于券截止。
- 仅全额退款SUCCEEDED及唯一退款事件才返原券；REQUESTED/PENDING/FAILED不返。返还不改变有效期；已过期券仍过期，发放库存/限领事实不回滚。返还后另一个明确创建请求可再预占，历史合同保留。
- 永久创建编号和请求摘要保存在合同，独立于24小时通用传输回执。回执过期后相同编号/正文仍返回原订单业务回执，不能因券释放而新建；不同正文拒绝。传输request_id可变化，业务五字段不变；成功后必须GET当前订单，不能把创建回执当现态。

订单读取可多一个可选`coupon_summary`：可信为`{state,source_known,user_coupon_id,coupon_id,name,original_amount,discount_amount,cash_amount,rule_version,valid_to}`；未知仅`{state:UNKNOWN,source_known:false}`。合同状态RESERVED/CONSUMED/RELEASED/RETURNED。商户投影不包含车主身份、VIN/车牌、授权码。

## 独立服务券

- GET `/api/coupon/{id}/service-redemption`：当前本人券来源/资格、授权版本/截止、已核销记录。`id`是user_coupon实例。返回`{user_coupon_id,merchant_id,terms,source_known,eligibility,authorization_revision,authorization_expires_at,redemption}`；不返回码。
- POST `/api/coupon/{id}/service-authorization`：`{expected_revision}`，CAS从0开始。须本人有效AVAILABLE SERVICE及发行方/项目当前有效在售。返回`{user_coupon_id,authorization_revision,expires_at,code,rule_version}`；code为32位小写高熵十六进制，截止=min(10分钟/券截止)，版本`R5B_SERVICE_V1`。数据库只存摘要；生成回执在本人幂等作用域内私有保存，审计不包含码。重新生成使旧码失效。
- POST `/api/merchant/coupons/service-preview`：`{user_coupon_id,authorization_revision,code}`，本店店长/店员核对当前项目/授权及已有记录；不消费券，错误码可能更新安全尝试计数。
- POST `/api/merchant/coupons/service-redeem`：同三字段，写入键必需。本店身份、发行来源、项目、当前码/版本/截止原子复核；写唯一服务核销、USED、消费事件、0.00免佣事实和审计。不同键/原码重试也不会增加核销。已成功记录可在到期后稳定重读/重放，不能重新生成/再次消费。
- GET `/api/merchant/coupons/service-redemptions?page=1&page_size=20`：当前本店记录，最大页长100，无其他query。记录条目`{redemption_id,user_coupon_id,coupon_id,merchant_id,project_id,terms,redeemed_at,commission_amount,rule_version}`，佣金始终`0.00`。

错码每门店/本人券每10分钟最多5次，数据库共享计数。422无效/旧码；429包含Retry-After秒。二维码格式`VHMP:SERVICE:1:本人券ID:授权版本:32位码`，与接车/订单核销码分开。不生成payment或order完成，不虚构施工档案或现金收款。

## 佣金、恢复与旧数据

新现金合同有完整来源和消费事件才按现金实付冻结原R2C_V1佣金政策；保留匹配入驻审批来源的前三个月免佣，其他3%按分四舍五入。未配置原政策仍WAIT_POLICY，旧券订单没有新合同仍WAIT_COUPON/WAIT_LEGACY，不自动升级为免佣或应计。

支付、过期/取消、退款成功在原业务事务转换券，失败整体回滚。后台CouponRecovery依据已提交唯一付款/退款事实恢复，持久recovery_after/error避免坏记录阻塞批次；商户→时段→订单→券/合同锁，唯一事件保证多实例重试不重复。付款PAID与未付关闭互相矛盾时拒绝释放并延期核对。外部支付/退款IO继续在原租约事务外执行；正式资金/真机/供应商验收最后R9。
