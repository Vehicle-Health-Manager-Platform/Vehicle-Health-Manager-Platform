# R2c 佣金应计与微信账单契约

依据交付文档§1.6：平台成交3%、新入驻前三自然月免佣、券核销不抽成。本期交付微信店长只读应计台账，无真实资金扣费/划拨或结算写接口。

## 规则与来源

入驻审核通过事务写R2C_V1政策：rate_percent=3.00、effective_from为该次可信批准时间、waived_until为Asia/Shanghai加3自然月（月底截断，非90天）。仅认可同商家/同版本/同时间/同运营人的唯一APPROVED记录；merchant.created_at和可变commission_rate不作为入驻或计费来源。V026可重复补该类可信政策，不覆盖现有政策，不给旧未知政策伪造版本。

创建订单同事务冻结政策、下单时间、原pay_amount和费用；下单早于waived_until免佣，到期这一秒按3%。金额为CNY实际支付基数，BigDecimal HALF_UP到分；核销前不记应计。核销可信原支付/施工/核销事务内追加ACCRUAL并写审计，归档与核销同时提交；任何写失败全部回滚。同订单完成事件唯一，重放与并发不二次记账。

未知政策WAIT_POLICY、缺少下单快照的历史订单WAIT_LEGACY、coupon_id有值但券归属尚未接入WAIT_COUPON：完成后写WAIT_REVIEW，金额null，待核对订单数增加，不用0代替未知。核销仍可完成，历史不按新政策补收费；政策变更不重算已冻结订单。R5实现券金额来源与免佣联动。

可信退款成功通知同事务追加：已有ACCRUAL则REFUND_REVERSAL=-原amount并引用原entry；无应计则REFUND_NO_CHARGE=0；原WAIT_REVIEW仍记录null待核对。每个订单event_key(COMPLETION/REFUND)唯一，refund_id唯一。PENDING/FAILED不冲销，重复/乱序成功不再冲销，写失败导致退款终态/事件/审计回滚，重放恢复。

当前R2b2只允许完成前取消，通常无原应计。原金额冲销原语有独立真库验证；不增加完成后退款业务入口来制造应计后退款路径。历史不确定金额的解决、完成后售后和真实资金在后续独立阶段。

## 只读API

`GET /api/merchant/commissions?page=1&page_size=20`

仅MERCHANT有效会话读取本店。STAFF/TECHNICIAN/OWNER403；资源由会话merchant_id确定，无客户端商家筛选权。page=1..1000000，page_size=1..50；只接受这两个单值参数，未知/重复/空值/小数40001。未配置数据库50300、会话失效40100、角色40300。响应Cache-Control:no-store，信封`{code:0,data:...}`。

顶层7字段：merchant_id、policy、summary、page、page_size、total、items。

policy严格5字段：state(CONFIGURED/UNCONFIGURED)、rate_percent、effective_from、waived_until、rule_version。未配置时后4项null，不假定0%。配置版本R2C_V1、3.00。

summary严格real/test两组，各含accrued、reversed、net、pending_count。金额两位小数字符串，accrued非负、reversed非正、net=accrued+reversed；pending_count为不同待核对订单数，未知金额不参与已知汇总。统计全店而非当前页；测试渠道全部落test，未真实扣费。净应计不等于已收或已结算。

items每条16字段：entry_id、order_id、payment_id、refund_id、kind、base_amount、rate_percent、exemption、amount、currency、occurred_at、test_mode、rule_version、reference_entry_id、booked_at、waived_until。

- kind=ACCRUAL/WAIT_REVIEW/REFUND_REVERSAL/REFUND_NO_CHARGE；金额依次非负/null/非正/0.00，退款条目refund_id非空，冲销引用原entry。
- exemption=NEW_MERCHANT/NONE/WAIT_POLICY/WAIT_LEGACY/WAIT_COUPON/REFUND_BEFORE_COMPLETION；rate_percent可null，不能把未知变0%。
- 记录按occurred_at DESC,id DESC。分页读取各自一致快照，新记录到达可刷新；客户端去重保持已读页，失败分页保留原记录重试。
- 不暴露车主、员工、文件、核销码、渠道流水、签名、原始政策运营ID。

## 微信页与迁移

门店入口→本店佣金账单（店长）→政策、真实/测试分开汇总、待核对和分页记录/原订单。只读，不提交金额或佣金政策；身份变化与页面隐藏清空并丢弃旧响应。原生/真机操作待验收。

V026新增order_commission_snapshot/merchant_commission_entry共66表，并补政策版本/来源字段。新卷Compose挂载V026且冒烟断言V022–V026共15字段；已有卷按序手动迁移，init目录不会补迁移。没有自动改本机现有业务库。正式微信支付、短信和真实身份/设备仍R9；PC运营后台不实施。
