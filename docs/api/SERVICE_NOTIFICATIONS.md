# R7 站内订单通知接口

原六类履约事件及[R7c3补充设计](../progress/R7C3_ORDER_NOTIFICATIONS_DESIGN.md)，共十七类：ORDER_PAID、ORDER_CHECK_IN、ORDER_ASSIGN、ORDER_TECH_ACCEPT、ORDER_SERVICE_FINISH、ORDER_COMPLETE、ORDER_CLOSED；REFUND_REQUESTED、REFUND_WITHDRAWN、REFUND_REJECTED、REFUND_ACCEPTED、REFUND_SUCCEEDED、REFUND_FAILED；DISPUTE_OPENED、DISPUTE_HANDLED、DISPUTE_REJECTED、DISPUTE_RESOLVED。业务事务内保存来源，后台独立投递；读页面不生成通知，无历史回填，不发正式短信/微信。退款受理不等于成功，成功/失败只取已有可信隔离渠道结果，正式资金仍未接入。

## 本人当前收件箱

GET /api/owner/notifications、GET /api/merchant/notifications、GET /api/tech/notifications。

只允许page（1至1000000）和page_size（固定20）；未知或重复query拒绝。角色由当前令牌和数据库会话确定，禁止客户端指定接收人。

返回ApiResponse.data：unread_count和notifications分页items/total/page/page_size。列表项严格为notification_id、kind、order_id、order_no、order_status（当前）、occurred_at（事件发生UTC）、read_at（UTC或null）。不输出事件接收人ID、自由正文、payload、联系方式、文件或对象key。固定提示由客户端kind映射，不能渲染历史payload。

车主只读事件发生时本人且当前仍属于本人的订单；门店只读事件原门店且当前仍属于本店的订单，当前店长/店员共享已读；技师只读事件原技师且当前仍为本人派工的订单，并重新核对门店和有效绑定。转派后原技师不读，接手者不回填旧事件。当前有效范围同时约束列表、total和unread_count。业务已关闭仍可查看本人事件事实，不能据此取得施工或档案权限。

## 单条已读

PUT /api/owner/notifications/{id}/read、PUT /api/merchant/notifications/{id}/read、PUT /api/tech/notifications/{id}/read。

无query，必填Idempotency-Key UUID，正文严格空对象{}；拒绝客户端接收人、read_at及额外字段。ID为1至9007199254740991的整数。返回data为notification_id/read_at。

首次写入与审计/幂等同事务；相同键重放仍重验当前权限；新键读取已读消息不重复审计或改变原read_at。未知结果用原键重试，明确失权清空页面并重新登录/读取。消息已读未决写仍只在页面实例内存保存，重新进入可重新读取真实 read_at；不宣称已读动作具有跨重启原键恢复。诊断文字草稿与原键恢复见[诊断](ASYNC_DIAGNOSIS.md)。

业务响应和控制器错误均no-store；400参数/正文错误、401会话/绑定失效、403身份不符、404消息不在当前范围、503数据库/写入不可用。无匿名或跨身份降级读取。

## 事件与运维边界

当前V047共102表，service_notification_outbox以(kind,order_id,source_key)唯一，旧事件默认ONCE；退款申请/撤回/决定绑定退款ID和revision，异议绑定追加记录ID，退款渠道结果绑定退款ID/结果，不因不同渠道回调ID重复发同一结果。退款仅捕获车主和门店，不给技师；关单/异议沿用当时三方接收人，notification沿原(event_id,recipient_type,recipient_id)去重。投递事件UUID与来源匹配且DONE的通知才进入新接口，旧无来源notification不能被自动提升为可信消息。

调度默认5000ms，每批最多20，逐事件事务并用SKIP LOCKED避免重复消费；插入所有接收人和DONE同提交。失败固定DELIVERY_UNAVAILABLE，5秒起有界退避至300秒，持续待恢复，不保存异常正文。当前总OpenAPI252操作，订单通知仍六项API，不增加输入/输出字段。诊断留言与本机恢复已在R7c2/c3第一步落地，诊断事件投递另行接续；不通过订单ID伪装诊断会话。正式渠道仍R9。
