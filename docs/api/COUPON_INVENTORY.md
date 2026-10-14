# R5a 券库存与领取协议

实现：[Controller](../../backend/src/main/java/com/autocare/platform/order/CouponController.java)、[Inventory](../../backend/src/main/java/com/autocare/platform/order/CouponInventory.java)、[严格输入](../../backend/src/main/java/com/autocare/platform/order/CouponInput.java)，[V038迁移](../sql/migrations/V038__coupon_inventory_and_claims.sql)。统一 ApiResponse、所有结果 no-store。仅当前角色会话，本人数据不能通过 user_id 查询；未知/重复 query、重复 JSON 字段、尾随 JSON、非整数 ID/版本、浮点金额拒绝。

## GET

- `/api/merchant/coupons`：本店店长列表，店员无权管理。
- `/api/admin/coupons`：独立 can_coupon 运营读取已发布或停止券；不能以目录/审核权限替代。
- `/api/coupon/offers`：当前车主可领券；省略 merchant_id 为运营精选，指定为该门店，不要求入精选池。排除已领、未生效、过期、售罄、无来源、无效门店/项目/下架项目。
- `/api/coupon/mine`：本人全部未删除券，包括旧券；无匹配发行证据返回 `source_known=false, terms=null, publication_revision=null, availability=UNKNOWN`。

分页 `page=1..1000000,page_size=1..100`（默认1/20），返回`{items,total,page,page_size}`。库存条目`{coupon_id,merchant_id,terms,revision,status,stock_left,publication_revision,pool_selected,pool_revision}`；本人券`{user_coupon_id,coupon_id,merchant_id,source_known,terms,status,availability,publication_revision}`。

`terms`精确字段：`name,type,project_id,amount,min_spend,total_quantity,valid_from,valid_to`。金额十进制字符串，最多六位整数/两位小数；DISCOUNT金额正且门槛不小于金额；SERVICE金额/门槛都是`0.00`。库存1..2147483647；名字1..128码点、无控制字符/首尾空白；精确标准项目必须是发行门店有效在售项目。UTC秒格式`YYYY-MM-DDTHH:mm:ssZ`，截止晚于生效，创建/修改/发布时截止须未来；前端选择和显示北京时间。

`availability`是有效期/发行门店资格提示，不能当作交易资格。AVAILABLE/NOT_STARTED/EXPIRED/ISSUER_UNAVAILABLE/UNKNOWN；自身`status`原样呈现。停止领取/移出池不废除已领权益。未知来源不能用于交易。

## 写入（全部须 UUID Idempotency-Key）

- POST `/api/merchant/coupons`：`{terms}`，创建DRAFT，revision=1。
- PUT `/api/merchant/coupons/{id}`：`{expected_revision,terms}`，仅DRAFT，revision+1。
- PUT `/api/merchant/coupons/{id}/status`：`{expected_revision,status}`，仅DRAFT→PUBLISHED或PUBLISHED→STOPPED；发布新增永久权益快照，revision+1；STOPPED不能重启。
- PUT `/api/admin/coupons/{id}/pool`：`{expected_pool_revision,selected}`，pool_revision+1，与商户版本分开。选入必须是有效来源、未到期PUBLISHED，移出允许已停止/失效来源。
- POST `/api/coupon/receive`：`{coupon_id,publication_revision}`，返回`{user_coupon_id,coupon_id,publication_revision}`。每模板终身每人一次，换键不能绕过；最后库存并发一人成功，另一人409。

事务顺序：当前身份/资源锁→幂等记录→状态/CAS/库存/唯一限领→业务事实→审计→回执。重放也检查当前身份、资源及领取来源资格；仅库存耗尽/本人已领不阻断同键成功重试。成功重放不重执行业务，前端继续GET。停止/过期/撤权后的旧领取重放拒绝。审计失败回滚全部发行事实/库存，同键可重试；不同正文的同键拒绝。

## 运营权限

V038为所有既有账号新增`can_coupon=0`，不隐式授权。离线operator-admin支持`operator.action=coupon-permission`、明确`operator.id`和`OPERATOR_CAN_COUPON=true|false`。既有账号锁/审计/撤销会话保留，重新登录返回布尔`user.can_coupon`。小程序兼容旧身份响应，缺该字段按false。

## 范围

订单抵扣/冻结、零金额订单、释放/返券、核销及免佣属于R5b；现在没有使用/核销按钮，不修改支付资金事实。供应商、正式身份资金、真机发布最后R9。
