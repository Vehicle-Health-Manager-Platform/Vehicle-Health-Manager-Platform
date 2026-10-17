# R8b3b 本店最小服务关系

2026-10-16，用户已确认：店长仅查看本店最小订单关系、车型和核验服务时间；历史档案仍R7逐单逐条授权，无新增长期档案或联系用途。没有新迁移，仍115表。

## API与范围

- GET `/api/merchant/customer-relations?page=1&page_size=20`：店长本店服务关系分页。
- GET `/api/merchant/customer-relations/{id}?page=1&page_size=20`：本店关系下订单分页，id是该关系当前最小有效本店订单锚点。

严格Bearer与no-store，仅当前本店MERCHANT店长；STAFF、技师、车主、运营身份不授予此读取权，can_analytics不能替代。query仅单值page/page_size，固定20；额外user_id/merchant_id/vehicle_id、重复或越界参数400，身份401、角色403、关系跨店/锚点失效404、无库或读取失败503。没有写、搜索全站客户、联系、标签或导出接口。

关系来源为本店未删除订单，状态PAID/RECEIVED/IN_SERVICE/PENDING_VERIFY/DISPUTED/COMPLETED，创建时间不晚于observed_at，且车主账号有效、订单车辆当前仍归该车主且未删除。未支付或已关闭订单不单独建立持续关系。店内内部按车主归并，不输出全局客户ID；锚点失效需从列表重新读取，不能用同客户其他非锚点订单绕过。

这是服务订单状态记录汇总，不是付款核验、有效新客业绩或全渠道客户总数。与经营金额口径分开，不把订单状态或身份来源当资金成功证据。

## 最小响应

列表data为observed_at/relationships，后者items/total/page/page_size。每关系精确字段：

- relationship_order_id：本店订单锚点；order_count：上述范围本店订单数。
- last_order_at：最近本店下单时间；last_service_at：最近有接车来源凭证的时间，否则null。
- identity_source：原R6永久来源VERIFIED_WECHAT/TEST/UNKNOWN。未知或旧规则不冒认正式，TEST明确标记；VERIFIED也不代表已成为有效新客。
- latest_vehicle：最近订单车型，brand/series/year/config_name，缺项null。

详情data为observed_at/relationship/orders。orders同固定分页且total等于order_count。每单精确order_id/order_status/created_at/service_at/project_name/vehicle/service_context_available。服务时间须本店未删除接车记录、到店时间不早于下单且不晚于接车完成、完成不晚于读取时点，并有同单/门店/人员/时点ORDER_CHECK_IN到RECEIVED流转；不从预约时间或updated_at推测。

单REPEATABLE_READ事务内核对当前账号/范围、计数、分页和摘要。响应不含姓名、手机号、openid、user_id、vehicle_id、车牌、VIN、金额、历史档案、文件地址或授权源哈希。

## 原逐单授权继续生效

service_context_available只提示RECEIVED/IN_SERVICE/PENDING_VERIFY且无OPEN异议的工单可进入原服务资料接口，不意味着已授权任何档案。点击后重新请求[原R7工作台与逐条授权协议](SERVICE_WORKSPACE.md)，服务器仍复核当前账号/车辆/工单、原逐条授权及源版本，技师仍须当前已接单。撤销、改源、转派或工单失效拒绝新读取；客户页不缓存或复制档案正文。

车主由原本人订单详情进入“本单历史档案授权与撤销”，不因客户关系自动授予任何长期权限。页面隐藏/切号/失权清旧数据，迟到响应不回填。详见[操作](../operations/CUSTOMER_RELATIONS.md)。
