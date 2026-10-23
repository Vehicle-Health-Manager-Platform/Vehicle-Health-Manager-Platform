# 运营本地资金记录

终端：微信小程序 `pages/operator/finance`，从运营工作区“资金记录”进入。PC不在本期范围。

`GET /api/admin/reconciliation?date=2026-10-23&channel=LOCAL_TEST`

只有这两个query字段，必填且不可重复。date为2000–2099合法日期，channel为WECHAT或LOCAL_TEST，不提供ALL。匿名401、角色不符403、当前运营会话失效401、缺can_analytics权限403、参数错误40001、未来日或超过10000条40900、数据库/来源不可用50300。成功及控制器错误no-store。该聚合权限复用经营数据can_analytics，内容不含用户/商户/订单/支付流水明细，不是新增财务写权限。

统一信封 `code=0,data=投影`：

- `date/channel/test_mode/timezone/currency/source_version`：绑定查询，timezone=Asia/Shanghai，currency=CNY，source_version=R9_LOCAL_FINANCE_V1。
- `window_from/window_to/observed_at`：UTC整秒时间，窗口为北京时间所选日开始至次日开始的半开区间，当天截止观察时刻；小程序展示转换为北京时间。
- `payments/refunds`：`state=KNOWN|UNKNOWN, success_count, anomaly_count, other_count, amount`。完整成功计success_count，未成功状态计other_count；成功证据损坏、未知状态计anomaly_count。已删除源不作为有效成功，关联缺失不充当零。金额两位十进制字符串；有异常或未分类来源时对应amount=null，success_count仍可显示已核验子集数量。
- `unclassified_count`：所选日渠道不明支付、孤立/渠道不明退款数量。无法证明归属哪一渠道，两渠道查询均提示此缺口并使金额未知，不能当作正式渠道收入为零。
- `local_net`：`state,amount`，支付减退款；只有两边都KNOWN时返回金额，可为负数。不是渠道差额、佣金、应结算款或实际到账。
- `channel_statement`：`state=UNAVAILABLE,net_amount=null,difference=null`，本阶段无渠道账单，严禁输出“已对平”。

成功支付按paid_at归日，退款按refunded_at归日；缺失成功时间以created_at计入异常。未成功记录按created_at归日。当前时间之后的成功记录在对应未来日期尚不可查，不混入当前窗口。CNY金额使用BigDecimal，不用浮点。

成功证据：支付与非删除订单金额一致、CNY正数、唯一成功支付、唯一具合法hash的已接受事件、非空本地及渠道流水、正确时间先后、无支付异常；退款还需同订单同支付同金额、双方同意、唯一成功退款与事件、关闭原因REFUND_CANCELLED及正确时间顺序。只说明本地事实相互一致，不证明真实供应商签名或实际到账。

只读repeatable-read事务包含权限和来源读取；不锁写、不调用供应商、不生成快照、不写审计/幂等/资金事实。无需数据库迁移。单日支付+退款+未分类数量超过10000拒绝，不截断冒充完整统计。没有分页流水或导出功能。

正式接入后另做渠道账单来源、账单完整性、手续费/差额口径与真实资金验收；当前旧接口名称reconciliation不改变上述边界。
