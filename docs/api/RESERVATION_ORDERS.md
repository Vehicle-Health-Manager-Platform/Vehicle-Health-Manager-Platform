# 本机预约与本人订单契约

2026-10-07。书面规则见[已确认规格](../superpowers/specs/2026-10-07-reservation-orders-design.md)，机器契约见[OpenAPI](openapi.json)。本步为 F07/F08 子集，支付基础现见[支付契约](PAYMENT_FOUNDATION.md)；正式扣款、券与退款尚未接入。

## 接口与角色

| 路由 | 身份 | 正文/查询 |
| --- | --- | --- |
| GET `/api/merchant/slots` | 本店 MERCHANT | 可选 date，分页；默认未结束时段 |
| POST `/api/merchant/slots` | 本店 MERCHANT | standard_project_id, starts_at, ends_at, capacity |
| POST `/api/merchant/slots/{id}/close` | 本店 MERCHANT | 空对象 |
| GET `/api/order/quote/{id}` | OWNER | 在售报价 ID，返回当前价格/版本/商家/项目 |
| GET `/api/order/slots` | OWNER | merchant_id, project_id, date，分页 |
| POST `/api/order/create` | OWNER | merchant_project_id, quote_version_id, vehicle_id, slot_id |
| GET `/api/order/list` | OWNER | 分页，可选 status=PENDING_PAYMENT/PAID/CLOSED |
| GET `/api/order/{id}` | OWNER | 本人订单，含下单快照 |
| POST `/api/order/cancel` | OWNER | order_id |

写请求要求 UUID Idempotency-Key、严格白名单 JSON。本人车辆与订单、本店员工/商家/时段在服务端校验，不信任客户端归属或金额。统一 ApiResponse/no-store。分页默认 1/20，page 最大1000000，page_size 最大100；时间 ISO 8601 带时区，金额两位小数字符串。

## 时段与名额

北京时间同一天、分钟精度，开始晚于服务器时间、结束不超过未来30天，容量1–100。名额属于本店具体项目时段；同店同项目窗口 `[开始,结束)` 不得重叠，相邻可用，关闭时段仍保留窗口。发布后不可改时间/项目/容量，关闭停止新预约并保留已有订单。

车主只看有效在售项目的开放、未开始、有名额时段。名额按实际未过期占位订单读取；创建锁定商家/报价/时段，在事务内清理到期占位后校验容量。等待锁后使用当前读取，避免可重复读旧快照造成超卖。

## 订单与关闭

订单创建保存当前报价版本、十进制金额及项目/商家/价格/预约快照。旧版本提交返回40901并要求重新确认；后续改价/时段关闭不会改已有快照。创建状态 `PENDING_PAYMENT`，金额不含券扣减，到期取创建后15分钟与时段开始时间较早者。

本人只可取消待支付订单，关闭为 `CLOSED/OWNER_CANCELLED`；已经过期按 `PAYMENT_EXPIRED`。再次取消已关闭订单不重复释放或写关闭转换审计，不同新键可记录一次无状态变化请求审计 `ORDER_CANCEL_REPLAY`；同键重放直接复用响应。

后台每30秒查询到期候选并逐条事务处理；系统审计固定 system/0。重复/并发任务只关闭和释放一次，失败整体回滚后重试。创建、取消、名额、快照、成功审计与幂等响应同事务。原创建响应在后续下架/关闭后仍可重放，当前状态查询详情获取。

## 错误与迁移

400无效参数/幂等冲突；401失效会话；403角色无权；404本人/本店资源不可用；40901报价改变、40902时段关闭/开始、40903容量不足、40904时段重叠；503数据库/事务不可用。不泄露SQL或他人订单。

V007 可重复补充时段开放/关闭字段、订单报价版本/预约快照/关闭字段和索引；表数仍46，不改历史迁移或旧订单金额。已有库需备份并按序补迁移，新Compose卷自动初始化。历史缺失快照/原因显示未提供，不生成假历史。

本机实际步骤与证据见[验收说明](../testing/LOCAL_RESERVATIONS_ACCEPTANCE.md)。商家合成登录桥接、真实微信车主、支付与真机边界分别记录。
