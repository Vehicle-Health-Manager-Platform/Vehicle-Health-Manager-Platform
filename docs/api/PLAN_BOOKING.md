# 本车方案预约复核 API（R4c3）

[规格](../superpowers/specs/2026-10-13-r4c3-plan-booking-design.md)、[计划](../superpowers/plans/2026-10-13-r4c3-plan-booking-plan.md)、[验收](../testing/LOCAL_PLAN_BOOKING_ACCEPTANCE.md)，关联F05/F06。无新增迁移，77表保持。

## 只读上下文

`POST /api/order/plan-booking-context`，当前有效OWNER，no-store、禁止query，严格JSON六字段，重复键/未知键/尾随内容拒绝400；无需Idempotency-Key，单层ApiResponse。无业务写入、审计或第三方调用。

```json
{"merchant_project_id":1,"standard_project_id":2,"vehicle_id":3,"date":"2026-10-14","page":1,"selected_slot_id":null}
```

编号为正安全整数；page1..1000000；date为规范日期，按北京时间当天至30天内；selected_slot_id为null或正安全整数。

同一重复读快照返回data四字段：

- `quote`：当前[预约报价](RESERVATION_ORDERS.md)投影merchant_project_id、quote_version_id、version、merchant_id、standard_project_id、merchant_name、address、project_name、price。当前有效报价必须映射请求标准项目，否则404。
- `evidence`：复用[本地车辆证据](VEHICLE_PLAN_EVIDENCE.md)，本人未删除车辆与当前有效车型链、健康来源、获批同款摘要及本项目适用性；不返回VIN/车牌/里程或他人身份。
- `slots`：该商家/项目/日期的当前可用20条分页{items,total,page,page_size:20}，复用原时段投影；关闭/删除/开始/容量满不可用，过期未支付占位不算占用。
- `selected_slot`：独立于分页检查请求slot归属该商家/项目、同一天、开启且未来开始、尚有容量；无效为null。有效投影与slots条目一致，project_name可为空（名称由quote确定）。避免第二页时段被首屏过滤误丢，也不允许其他项目/商家/日期ID被当作选择。

会话失效401、其他角色403、有效目录/本人车辆/项目映射失效404；数据库未配置/故障503，不暴露SQL；成功及本控制器业务错误no-store。只读快照不是预约锁，读后报价/容量/规则仍可能变化。

## 页面交接、复核与未知结果

方案选择本车且主动读取证据后，逐项CTA一次性交接{actor,project,vehicle}到`/pages/ai/plan-booking?project_id=标准项目号`。交接在内存，绑定账号，15分钟有效且取出即清理，错误项目/过期/跨账号不可恢复。URL不包含车辆号、问题、证据、商家或报价版本；无本车则走原项目详情手动路径。

页面读有效报价，支持价格排序/分页；选商家读取本车/报价/时段。适用性UNKNOWN提示人工核对，NOT_APPLICABLE禁提交；来源为运营声明，不证明技术必做。换报价/日期或刷新清除时段和确认；分页签名变化丢弃旧选择重读第一页。提交确认当前报价与本车提示后，以所选slot复核上下文；价格/版本/商家项目信息、登记车型或规则版本/结论/来源变化、slot无效均要求重新选择/确认。

复核后仅提交原`/api/order/create`的merchant_project_id、quote_version_id、vehicle_id、slot_id与UUID幂等键。该接口继续原子复核本人车辆、当前有效项目/商家、报价版本与时段容量。车型适用性是本页面的来源提示，未改成全站创建资格；复核后规则变化也不形成事务锁或技术必要性承诺。

创建返回409时重新读取当前上下文、清除时段/确认；网络/暂不可用/协议/服务结果不确定时冻结选择，保留原body/key直接重试取得回执，不另生成订单。重试历史回执不改写报价或状态；如请求此前未到达，原创建接口仍原子拒绝旧报价/满额时段。未知回执期间不能换日期/报价；离开提示先核对我的订单。隐藏清除私有展示、忽略迟到结果并保留待确认内存请求；卸载/账号切换清理。没有确定回执不宣称成功。

## 交付界限

不持久化问题、证据或车辆交接，不新增表/规则回填，不调用正式渠道。下一R4d按有来源技术规则、读数有效期/可比趋势与档案补全拆分推进，可信阈值前不宣称健康分/技术必做；供应商识别、正式短信/微信身份/资金、真机及发布最后R9。
