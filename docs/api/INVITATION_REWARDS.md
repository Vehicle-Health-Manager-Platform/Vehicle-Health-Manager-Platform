# R6b2 邀请奖励记录与可靠发券

## 真实只读接口

- `GET /api/invite/rewards?page=1&page_size=20`：OWNER 当前会话；本人作为新客或邀请者的奖励事件。
- `GET /api/merchant/invite/rewards?page=1&page_size=20`：MERCHANT/STAFF 当前会话；仅本人 staff_account 和原推广门店的事件。店长不会看到其他员工记录。

固定20条，page 1–1000000；重复或额外 query 拒绝400。没有 recipient_id、user_id、门店切换或发奖入口。401/403/503，全部 no-store；数据库未配置返回503。GET 不创建任务、不发券、不扣库存、不写审计。

响应 `ApiResponse.data={items,total,page,page_size}`。每条字段：`record_id` 不透明UUID，`source_type` OWNER/STAFF，`state`，`qualified_at` UTC，`completed_at` UTC/null，`policy_revision` 冻结发布版本/null，`rule_version=R6B_REWARD_V1`，`worker_enabled`，`rewards`。

奖励字段固定 `role` INVITER/INVITEE、`own`、`terms` 原发布条款、`user_coupon_id`。只有本人已到账券显示持券编号；对方和员工查看的新客持券编号永远null。不显示新客手机号、openid、user_id 或员工映射账号。

## 状态与事务

- READY：保存资格当时的政策，等待后台。
- WAIT_STOCK：同模板双方需要两份库存，不足时双方均不发。
- WAIT_SOURCE：原券停止、门店/项目/报价不可用或条款/发布版本不一致。
- WAIT_ACCOUNT：受益人或原推广员工不可用。
- UNKNOWN_POLICY：资格时没有生效政策，或历史没有政策快照；奖励明细空，不用后来政策补算。
- EXPIRED：原券到期，停止发放，不延长有效期。
- INVALID_EVIDENCE：资格/身份/归因/手机号证据与冻结事件不一致，停止发放。
- COMPLETE：持券、coupon_issue、库存流水、审计和完成时间已同一事务提交。

OWNER 双方各一券，STAFF 只发 INVITEE。每事件与角色唯一；不同新客可复用模板。公共领取使用 origin=PUBLIC 的生成唯一列，仍每人每模板一次；邀请来源 origin=INVITE 的 reward_item_id 唯一，不占用公共领取机会。旧可信 coupon_issue 默认保留 PUBLIC；无凭证的历史 user_coupon 仍 UNKNOWN。

资格确认事务锁住当时生效的 PUBLISHED 政策并冻结版本/条款/受益人。发布和停止与此读取串行，停止后不接收新资格，但已冻结事件保留原政策。未发券时原券停止仍阻止新发行；已发券继续既有R5消费与退款协议。

## 内部后台

`INVITE_REWARD_ENABLED=false` 默认关闭；`INVITE_REWARD_SWEEP_MS=60000` 默认一分钟。开启后每轮最多100个到期任务，等待/数据库失败延迟300秒再执行。对用户/员工锁采用NOWAIT，避免反向等待资格及商家写入；失败回滚整个发券事务，下一轮再试。没有 HTTP 发券触发器。

资格状态仍由正式微信身份及同app服务端手机证据确定，`INVITE_VERIFIED_IDENTITY_ENABLED` 默认false。UNKNOWN/TEST/旧账号不产生真实新客奖励。定向合成测试调用完整事务，不构成正式身份/真机验收。

金额券走正现金预约、实付佣金和全额退款返还原券；服务券走独立核销，不创建支付，参见[券交易](COUPON_TRANSACTIONS.md)。
