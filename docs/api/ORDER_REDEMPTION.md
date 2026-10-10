# A7.1 核销接口

规格与计划：[规格](../superpowers/specs/2026-10-09-a7-redeem-design.md)、[中文计划](../superpowers/plans/2026-10-09-a7-redeem.md)。

## 本店核销

`POST /api/merchant/orders/{id}/redeem`，MERCHANT Bearer、UUID `Idempotency-Key`。仅 JSON `{"code":"123456"}`；六位数字字符串，无查询参数、订单目标状态或员工字段。

成功 `data`：`order_id`、`order_status=COMPLETED`、`redeemed_at`（UTC）、`test_mode`、`changed`。同键原请求重放保持原响应；新键对已有完整核销返回 `changed=false`，不重复审计。任何重放仍需有效身份、正确码与证据；完成不可逆。响应全部 `Cache-Control:no-store`。

同店同订单十分钟最多五次错误，第六次及正确码也阻断到下一窗口；42900 附 `Retry-After` 秒数。错误计数独立提交，不因业务回滚消失；权限、格式、前置失败不计错误。正确码不在商家读接口、核销记录、审计或幂等响应出现。

| HTTP / code | 含义 |
| --- | --- |
| 400 / 40001 | 严格输入、正整数订单 ID、UUID 键错误，或同键不同请求 |
| 401 / 40100 | 当前会话、员工或门店已失效 |
| 403 / 40300 | 非商家/本人角色 |
| 404 / 40400 | 外店/不存在订单；测试支付通道未开放 |
| 409 / 40905 | 非待核销、历史完成缺记录、记录与状态不一致 |
| 409 / 43001–43005 | 接车、确认、已接单派工、防护、完整报工或 PNG 质检签字证据缺失/失效 |
| 409 / 43007 | 存在未解决争议 |
| 409 / 43009 | 缺少唯一可信付款、金额/CNY/付款字段/事件不符、存在付款异常 |
| 422 / 42200 | 六位码错误 |
| 429 / 42900 | 共享防猜额度用完 |
| 503 / 50300 | 数据库未配置、事务失败或正式支付未接入；原键重试 |

通用 `actions` 的 COMPLETE 始终返回 43006。核销还须七张接车图槽位完整、彼此独立、属于原接车员工且 CLEAN/未删除/有效类型大小；原时间戳不能代替图片证明。A6 所有图片仍须本人原始上传、CLEAN、未删除、有效类型/大小，关系与质检签字完整；不用当前技师登录会话作为历史施工证明。

付款验证：唯一未删除 SUCCEEDED，金额等于订单 `pay_amount`，CNY，付款时间/支付号/渠道交易号完整，唯一同渠道未删除 PAID 事件有摘要；该订单任何 payment_exception 都阻断。当前只有 LOCAL_TEST，可在既有隔离测试 profile+开关+密钥下完成测试核销；`test_mode=true` 代表**未真实扣款，不可作为收款凭据**。正式微信核销尚未验收，不支持手工把订单置为 PAID 后核销。

## 核销结果

`GET /api/merchant/orders/{id}/redemption`（本店）与 `GET /api/order/{id}/redemption`（本人）。无查询参数。

`data={order_id,redemption:null|{redeemed_at,test_mode}}`。不包含员工、付款内部字段或码；历史无记录时为 null，不伪造完成证明。V015 新增 order_redemption，订单唯一；核销、状态迁移 ORDER_COMPLETE、audit_log 与幂等成功响应同事务。

车主原订单详情：PAID 保持预约码；PENDING_VERIFY 仅展示已有有效码，不重新发码或补历史码；COMPLETED 不再展示码。
