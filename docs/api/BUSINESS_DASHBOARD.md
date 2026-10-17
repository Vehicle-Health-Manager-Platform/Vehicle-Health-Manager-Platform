# R8b3a 只读经营看板接口

2026-10-16。用户确认北京自然月、收退款按各自成功时间归月、差额可负、正式/测试分栏、无法核验未知及月结单独冻结。V055只增默认关闭的独立 `can_analytics`，仍115表。整R8完成前不上传。

## 范围和请求

- GET `/api/admin/business/merchants?page=1&page_size=20`：当前有效门店的id/name/address最小分页，独立can_analytics；不继承can_assessment或券等权限。
- GET `/api/admin/business/dashboard?merchant_id=1&period_month=2026-09`：当前有效门店及月份，独立can_analytics。
- GET `/api/merchant/business/dashboard?period_month=2026-09`：服务器绑定本店的店长，普通员工403；拒绝merchant_id/user_id/staff_id等选择器。

全部Bearer、no-store，只读；严格单值白名单query。缺失/额外/重复参数400，身份401，当前权限403，门店404，未来月份409，无库/来源读取失败或候选流超过10000条503。分页固定20。没有写接口、幂等键、回执或浏览器自报角色。

## 响应与时间

`data`精确字段：merchant、period_month、timezone（Asia/Shanghai）、window_from、window_to、observed_at、currency（CNY）、source_version（R8B_BUSINESS_V1）、metrics、cash_difference、assessment。时间为UTC字符串，所属月份按北京定义；区间`[月初,min(次月初,observed_at))`。单REPEATABLE_READ事务在同一观察时点读取聚合；不宣称覆盖系统外全部线下或其他渠道业务。

每指标精确包含formal/test/unclassified_count；分栏含state、count、unknown_count、excluded_count、amount。count为已核验候选数，存在待核对时不代表完整业绩。分栏有未知或指标有未分类来源，state为UNKNOWN；金额为null。无候选且无未知可为已知0，不能将缺源转换成0。计数指标amount恒null；金额为两位精确小数字符串，不能用浮点汇总。

## 八项来源规则

- orders_created：订单创建时间；订单结构、正实付和永久身份来源。VERIFIED_WECHAT正式、TEST测试，其余未分类。
- payments：paid_at归月；唯一成功CNY付款、订单实付、匹配渠道PAID事件、服务器时间及无异常；WECHAT正式、LOCAL_TEST测试。未知渠道不归正式。
- refunds：refunded_at归月；唯一成功全退、原可信付款、金额/币种、双方同意、请求及渠道成功凭证、关闭原因。既有已完成退款记录须有原完成凭证才可核验；看板不开放已完成退款操作，也不改变订单状态机。
- orders_completed：独立核销时间，原付款、门店/付款/测试标记及ORDER_COMPLETE同人同时流转。遗留COMPLETED缺核销不按updated_at猜月，显示未知。
- cash_coupon_uses：完成单去重，原发行/条款/金额/分配/CONSUME事件。可信全退且返原券则从原使用月实时剔除，未返券或破损凭证未知；不改原冻结月结。
- service_coupon_uses：独立核销时间，原发行门店/项目/条款/有效期、SERVICE_REDEEM及0佣金；永久身份分栏，无支付记录不直接视为正式。
- new_customers：原员工门店归因、R6手机号来源/资格/时点；正式有效资格计数，待资格和TEST归因单列剔除，无永久身份或缺源未知。
- commission_accrual：本月ACCRUAL/REFUND_REVERSAL/REFUND_NO_CHARGE，核验冻结政策/费率/免佣期/原完成或退款引用。冲销可负；WAIT_REVIEW及缺凭证未知，读取不补账。

cash_difference的formal/test各含state/amount，仅对应收款和退款均完整已知时返回差额。跨月退款从退款月扣，不回扣原收款月。这是系统记录差额，不是已结资金、账户余额或利润。佣金也是应计，不是已收费。

assessment为空或最小最新冻结版本：snapshot_id/revision/state/total_score/grade/captured_from/captured_to。无事实、政策或客户明细，UNKNOWN不带分数等级；不随看板实时数据重新计算。完整月结权限/接口见[月结读取](MONTHLY_ASSESSMENT_VIEWS.md)。

## 消费与权限

运营/店长两实际小程序页，读取前清除旧数据，切号/失权/隐藏丢弃迟到响应。客户端严格核对字段、月份/门店、服务器窗口、未知规则及精确十进制差额。独立经营权限不授予月结操作或原事实读取；无电话、车辆历史正文、文件地址、订单号或渠道凭据输出。详见[运维](../operations/BUSINESS_DASHBOARD.md)与[本地验收](../testing/LOCAL_R8B3A_BUSINESS_DASHBOARD_ACCEPTANCE.md)。
