# R2c 佣金台账与微信商家账单规格

## 范围与方案

承接R2b2#102。交付微信小程序和必要后端，按交付文档§1.6：平台成交3%、新入驻前3个月免佣、券核销不抽成；本期是应计台账，无提现/资金划拨或结算成功写入。正式资金、微信身份和真机仍R9。

选择“业务事件同事务+不可变快照+追加台账”：核销/可信退款通知与入账原子提交，唯一业务键去重。异步台账队列可减少同步写但增加缺账恢复窗口；按当前门店政策实时计算会改变历史费用。本期采用第一种，与现有核销审计事务一致。

## 计费口径

- 免佣起点为可信入驻审核通过时间，按Asia/Shanghai增加3个自然月，月底按日历截断；不是90天。免佣区间[effective_from,waived_until)，正好到期下单按3%。
- 下单时选择已生效R2C_V1政策并冻结；免佣看下单时间，完成核销时才产生应计。订单跨免佣边界不会重新计算。金额基数为原支付对应order.pay_amount，CNY；3% BigDecimal HALF_UP保留2位，免佣0.00。
- 新商家批准同事务初始化3.00政策。V026仅从唯一、同门店、同版本、同审核时间/运营人的可信APPROVED记录补政策，不用merchant.created_at猜历史入驻时间，不覆盖已有政策。
- 旧政策未标R2C_V1/无可信来源、旧订单没有下单快照、带coupon_id尚无券归属规则：分别WAIT_POLICY/WAIT_LEGACY/WAIT_COUPON。成交/核销不阻断，台账金额null并计待核对数，不能用0.00伪装免佣。未知历史不自动重算。
- 商家资料commission_rate不作为计算源，客户端无改率/金额能力。本期不新增政策管理入口；无法自动取可信来源的历史资料保留明确待核对。券金额归属/免佣在R5联动。

## 原子事件与退款

新增order_commission_snapshot（一单一个，不改历史）和merchant_commission_entry（追加事件）。下单写快照与订单同事务；核销通过原可信原支付/完整施工门槛后，唯一ACCRUAL或WAIT_REVIEW与核销/归档/审计同事务。到账渠道/test_mode绑定原支付，禁止把测试应计混入真实金额。

退款成功复用R2b2已验签和原支付全额绑定的事务：有原ACCRUAL按原台账负数REFUND_REVERSAL并关联原entry；无应计记0.00 REFUND_NO_CHARGE；未知原应计仍待核对，不猜金额。失败/处理中的退款不冲销。唯一订单事件和唯一refund_id去重，重复/乱序成功不重复冲销。写台账/审计失败则退款通知事务回滚，供应商重放可恢复。

当前R2b2仅允许完成前取消，全额退款通常无原应计；不开放COMPLETED取消来演示冲销。冲销原语按可信原应计实现并单独测试，未来完成后售后仍需独立授权规格。

固定锁顺序沿用门店→时段→订单→原支付→退款/台账；计费无外部IO，无后台自动改旧快照。本期不造已收佣金或结算凭据，所有金额只称应计。

## 微信与API

GET /api/merchant/commissions，仅本店MERCHANT有效会话，STAFF/TECHNICIAN/OWNER拒绝；仅page/page_size，1..1000000/1..50，未知/重复参数400。响应no-store，未配置数据库503，身份失效401，角色403。

详情严格merchant_id、policy、summary、page、page_size、total、items。policy包含state、rate_percent、effective_from、waived_until、rule_version；未配置显式null。summary分real/test两套accrued/reversed/net/pending_count，基于全店台账，不受分页影响；金额2位字符串，冲销非正，应计非负，净值等于应计+冲销。测试汇总单独展示“测试应计，未真实收费”，不称已结算。

items只含entry_id/order_id/payment_id/refund_id/kind/base_amount/rate_percent/exemption/amount/currency/occurred_at/test_mode/rule_version/reference_entry_id/booked_at/waived_until。不泄露车主/员工/文件/签名/渠道凭据。kind=ACCRUAL、WAIT_REVIEW、REFUND_REVERSAL、REFUND_NO_CHARGE，未知类型响应视为协议错误。WAIT_REVIEW金额null，其余按事件符号；冲销引用原entry、退款条目有refund_id。

微信门店入口→本店账单页：显示政策和免佣期、真实/测试分开汇总、待核对、分页条目/原订单入口，明确只读应计。角色错误/失效/未知/网络失败提示；身份变化、隐藏/退出丢弃旧响应，失败分页保留已读记录并允许重试。

## 迁移、验证与交付

V026重复安全，新增2表后66表；政策新字段记录规则版本与来源，不给旧政策伪造R2C_V1。Compose新卷挂载V026并检查当前字段/表；已有卷须手动顺序迁移。

真实MySQL验证3%舍入、自然月月底/边界、下单快照不变、未知政策/旧单/券、真实核销同事务应计/重复/并发/回滚、退款不提前冲销/零应计/原金额冲销/重复/回滚、分测试汇总/跨店/会话；MockMvc严格HTTP/权限/缺库与微信契约/分页/隐藏隔离；微信与兼容H5构建。逐规格→后端→微信→验收文档上传，不自动合并部署，按前序落main改base。
