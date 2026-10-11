# R2b2 订单取消与全额退款契约

2026-10-11，微信小程序及必要后端。用户确认已接车/异议订单双方同意后全额退款，本期不开放部分退款。正式微信退款 SDK、真实资金验收在 R9。

## 资格与状态

未付款沿用预约取消接口。已付款 PAID、RECEIVED、IN_SERVICE、PENDING_VERIFY、DISPUTED 统一由本人车主申请、本店 MERCHANT 店长同意；STAFF、TECHNICIAN 无退款权。COMPLETED/CLOSED 不开放新申请。仅一笔有可信 PAID 事件的成功原支付、金额等于订单 pay_amount、CNY、有原支付号/渠道流水/到账时间且无支付异常允许申请和批准。旧退款或软删除退款存在时拒绝新流程，避免再次退。

REQUESTED 不改变订单或停止履约，车主申请即记录本次同意。店长只能按当前 revision 决定；申请后订单状态改变则不得接受，需撤回重新申请。拒绝/撤回后可复用同一退款号重提，revision 递增。ACCEPT 同事务写双方同意、PENDING 退款、CLOSED/REFUND_CANCELLED 订单、释放预约容量并写关闭轨迹和审计；回滚则全部不发生。关闭之后原施工/核销/争议恢复接口按既有状态门槛拒绝，保留争议历史。

PENDING 只表示已授权处理，不能当作到账。供应商 FAILED 保持订单关闭并继续核对；同一供应商退款流水可补报 SUCCEEDED，成功不可被后续失败反转。原支付保持 SUCCEEDED，不伪造支付失败。批准后出现另一笔迟到账异常不丢弃原支付已批准退款的可信回执。

## 微信业务接口

所有业务接口要求 JWT、有效会话与资源归属，响应 Cache-Control:no-store；统一 `{code:0,data:...}`。写入申请/撤回/决定要求 UUID Idempotency-Key，重试用原键；同键不同请求409，历史重放为原快照，页面随后刷新。

| 方法/路径 | 身份 | 严格请求 |
| --- | --- | --- |
| GET /api/order/{id}/refund | 本人OWNER | 无 |
| POST /api/order/{id}/refund | 本人OWNER | `{reason}`，去空白后1–256字 |
| POST /api/order/{id}/refund/withdraw | 本人OWNER | `{revision}`正整数，仅REQUESTED |
| GET /api/merchant/orders/{id}/refund | 本店MERCHANT | 无 |
| POST /api/merchant/orders/{id}/refund/decision | 本店MERCHANT | `{revision,decision,note?}`，ACCEPT/REJECT，拒绝必须原因 |
| POST /api/merchant/orders/{id}/refund/reconcile | 本店MERCHANT | `{}`，无需幂等键，不接受金额或成功状态 |

详情严格4字段：order_id、order_status、can_request、refund（可空）。can_request 仅表示订单/申请状态允许尝试，提交仍须原支付及渠道资格核验。refund严格14字段：refund_id、refund_no、payment_id、amount（两位小数字符串）、currency、status、revision、from_status、reason、decision_note、owner_consented_at、merchant_consented_at、refunded_at、test_mode。不返回 actor ID、租约、内部错误、供应商原文或支付密钥。

40001参数错误；40100会话无效；40300角色无权限；40400本人/本店资源不存在（跨店不泄露）；40900订单/支付/退款版本或凭据冲突；50300数据库/渠道未配置或暂不可用。客户端不得提交金额、资金状态或渠道流水。

## 隔离测试供应商协议

沿用 PAYMENT_LOCAL_TEST_ENABLED、local-payment-test profile、至少32字节测试密钥与仅本机HTTP源 PAYMENT_LOCAL_TEST_QUERY_URL；正式prod禁用测试渠道，WECHAT尚503。REFUND_RECOVERY_ENABLED 默认false，启用必须有有效测试供应商源；间隔默认60000ms。

GET /refunds/{refund_no} 先查询：404为不存在，202为供应商已知处理中，200为签名终态。仅明确404且本地不是FAILED才POST同一路径，Idempotency-Key固定refund_no；不能因超时重生成号。POST体是服务器冻结refund_id/refund_no/payment_id/payment_no/channel_payment_no/amount/currency。202不声称成功。FAILED后只查询，不再次提交。

POST /api/refunds/callback/LOCAL_TEST 仅固定匿名通知路径，HMAC验签后才消费。时间戳/nonce/签名头 X-Test-Timestamp、X-Test-Nonce、X-Test-Signature。HMAC-SHA256消息为 `timestamp\nnonce\n原文\n`；5分钟时间窗，原文最多16KiB，严格拒绝重复/未知字段、尾随JSON。200查询与通知体同样严格：event_id(UUID)、refund_id、refund_no、payment_id、channel_refund_no、status(SUCCEEDED/FAILED)、amount、currency(CNY)、occurred_at。事件规范化哈希唯一且原退款/原支付/全额绑定；200通知应答 `{code:"SUCCESS"}`，数据库失败503可重放。该协议不是微信官方退款协议。

持久租约60秒、每批20条；数据库领取事务提交后执行HTTP，总响应含body上限3秒，无重定向。重启/多实例按租约重领；处理未知仍PENDING。同号查询恢复避免业务重复提交，但正式供应商仍必须兑现唯一退款号幂等语义。内部last_error仅存固定类别，不存原文。失败查询延迟300秒；普通重试60秒。

## 数据迁移与后续

V025复用refund，新增同意/版本/恢复字段、唯一退款号与受控订单唯一生成列，新增refund_event后共64表。重复迁移安全；历史refund_no留NULL，不臆造同意或可信原支付。Compose新卷挂载V025，已有卷须按序手动迁移，初始化目录不会补迁移。

下一单元R2c：3%佣金、前三月免佣、台账和退款冲销。券免佣/返券待R5。当前不生成不存在的佣金或券账。原生页面、真机、正式微信 SDK/资金收退款未验收。
