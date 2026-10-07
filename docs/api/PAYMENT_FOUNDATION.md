# 支付基础契约

2026-10-07。[已确认规格](../superpowers/specs/2026-10-07-payment-foundation-design.md)与[OpenAPI](openapi.json)。本步仅隔离测试渠道，正式微信扣款、退款执行和券未接入。

## 本人接口

- POST `/api/payments/create`：OWNER、UUID幂等键，严格正文 `order_id, channel`。WECHAT未配置返回503；LOCAL_TEST默认404。有效待支付订单才可创建；金额取订单快照，支付到期不延长订单。同订单仅一笔进行中记录，失败后新键可再发起，原键继续返回原收据。
- GET `/api/payments/{id}`：OWNER本人，支付ID/编号、订单ID、渠道、测试标识、状态、精确金额、币种CNY、到期/成功时间与异常标识。不返回原始渠道流水号或签名。
- 本人订单列表增加PAID过滤，列表/详情包含可空 `payment_summary`。支付成功不重复加容量；已支付不可走未支付取消。

## 隔离测试通知

仅POST `/api/payments/callback/LOCAL_TEST` 不需要Bearer，仍必须测试签名。需 `local-payment-test` profile、`PAYMENT_LOCAL_TEST_ENABLED=true` 和至少32字节的私有 `PAYMENT_LOCAL_TEST_SECRET` 同时有效；不能组合prod/production。默认关闭，不替代微信验签。

正文最多16KiB，禁止重复JSON键；严格字段 `event_id,payment_id,channel_payment_no,status,amount,currency,order_no,occurred_at`。签名头 `X-Test-Timestamp` 为Unix秒，`X-Test-Nonce` 为UUID，`X-Test-Signature` 为64位十六进制HMAC-SHA256；输入 `timestamp + 换行 + nonce + 换行 + 原始UTF-8正文 + 换行`，时间窗正负300秒。

通知先验签再校验支付归属渠道、订单号、金额/币种。有效通知、事件、订单/支付更新、异常与系统审计同事务提交。提交后返回200 `{ "code": "SUCCESS" }`；失败不返回确认。相同事件规范化摘要相同可重签重放，异文409；其他格式/金额4xx，事务故障503。私有签名及原文不输出/入库。

## 状态及异常

有效待支付订单收到当前进行中支付成功通知转PAID，保留名额；失败仅标记支付FAILED，订单仍按原期限到期。取消/到期同事务关闭进行中支付。已关闭、到期、旧失败或另一支付已成功时收到成功，保留渠道成功事实与待核对异常，不恢复订单/容量。重复成功不再写成功转换审计，失败不能回退成功。

异常尚未退款，页面明确提示；LOCAL_TEST始终标注未真实扣款，不作为收款凭据。历史缺失字段不伪造有效渠道请求。V008新增事件/异常表并补支付字段，可重复迁移，预计48表；已有库先备份再按序迁移。

本机复现与真实扣款边界见[验收说明](../testing/LOCAL_PAYMENTS_ACCEPTANCE.md)。
