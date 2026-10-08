# 商家本店订单契约

商家登录后使用有效 `MERCHANT` Bearer 会话。接口只查询当前会话所属商家，且每次读取时重新核对员工、商家和会话状态。响应设置 `Cache-Control: no-store`。

## 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/merchant/orders` | 本店订单分页，按 `created_at DESC,id DESC` |
| GET | `/api/merchant/orders/{id}` | 本店订单详情 |
| POST | `/api/merchant/orders/{id}/actions` | 履约状态操作，只接受动作名 |

列表可传 `page`（默认 1，最大 1000000）、`page_size`（默认 20，最大 100）、`status`（全部 8 个履约状态，见[状态机契约](ORDER_FULFILLMENT.md)）、`date`（严格 `YYYY-MM-DD`）。`date` 采用北京时间预约日期，转换为 UTC 左闭右开区间查询；历史及未来订单都可查。空列表返回 `items: []`、`total: 0`。重复、空值及未知查询参数返回 400；未知 `status` 同样返回 400。

返回订单号、状态、应付金额、创建/到期/关闭时间、关闭原因、项目/商家/预约白名单快照、支付摘要、`has_payment_exception` 和 `allowed_actions`。详情增加下单报价快照。历史快照缺失时返回 `null`；历史报价或项目下架、软删除不影响已保存订单查询。`has_payment_exception` 汇总该订单全部支付尝试的待核对异常，即使当前选中的支付摘要没有异常也为 `true`。

`allowed_actions` 是 `[{action, to_status}]`，由服务端状态矩阵与当前状态算出，供界面渲染操作按钮；它**不含前置条件判定**，按钮可见不代表一定能执行。前端只能提交动作名，不能提交目标状态。

订单投影仅允许项目名称和服务内容、商家名称和地址、预约时段及报价版本和价格。客户手机号、用户/车辆 ID、完整车牌、VIN、渠道交易号和签名不返回。其他商家的订单详情统一 404。登录失效返回 401，身份不符返回 403；数据库不可用返回 503。

## 状态操作

`POST /api/merchant/orders/{id}/actions` 正文为 `{"action":"RECEIVE"|"START_SERVICE"|"FINISH_SERVICE"|"COMPLETE"}`，可选 `note`（去空白后 1–200 字），必须带 UUID `Idempotency-Key`。成功返回本店订单详情投影加 `action`、`from_status`、`changed`；`changed=false` 表示此前已生效、本次为幂等重放。

矩阵判定、前置校验、行锁、幂等与双审计（`order_status_transition` 与 `audit_log`，同事务）全部在服务端完成。当前状态不支持该动作返回 `40905`；前置未满足返回 `43001`/`43003`/`43004`/`43005`/`43006`，其中 `43006` 表示核销校验尚未接入（A7 接入后不再出现）。完整规则、动作与目标状态映射、错误码见[订单履约状态机契约](ORDER_FULFILLMENT.md)。

此阶段**不包含**接车单内容（五向照片、里程比对、损伤标注）、车主确认、派工、报工与核销的业务实现，也不含退款；这些在缺口清点的阶段 A3–A6 落地。测试渠道支付在界面标注"未真实扣款"，不能作为收款凭据。
