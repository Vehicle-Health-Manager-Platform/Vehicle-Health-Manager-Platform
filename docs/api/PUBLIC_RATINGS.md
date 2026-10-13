# 独立公开星级 API（R4b2b1）

> 2026-10-13：新增当前公开星级报价排序，实时复用本契约来源资格/授权，无样本null，见[R4完整契约](R4_COMPLETION.md)。

2026-10-13。完整规格见[设计](../superpowers/specs/2026-10-13-r4b2b1-public-ratings-design.md)。所有接口有效车主身份、no-store、单层ApiResponse，数据库未配置/失败503且不泄露底层错误。

## 本人授权

`GET /api/order/{id}/review-publication`：无query，本人未删除订单及匹配评价。data字段：order_id、version（无记录0）、consented（已授权）、visible（授权且当前资格有效）、can_publish、unavailable_reason（null/TEST_ORDER/PUBLIC_SOURCE_UNVERIFIED）。未提交评价或非本人404。

`PUT /api/order/{id}/review-publication`：UUID Idempotency-Key，无query，严格正文 `{ "publish": true, "expected_version": 0 }`。publish为boolean，版本为0..9007199254740991整数；额外字段、重复键、尾随JSON拒绝。公开资格及旧版本409；每次变更递增版本，成功审计和授权同事务，审计不含私有正文/图片。

同键同正文返回原成功响应而不重复变更，含已撤销后的旧授权重放，因此客户端写后必须GET刷新。资格及会话在重放前复核。新键旧版本409；撤销不要求付款/门店仍有效，仅核对本人评价归属；停用账号/删除订单仍不能访问。原订单评价不可变、不自动公开，经验摘要授权独立。

## 公开投影与实时资格

`GET /api/service/merchant/{id}/ratings?page=1&page_size=20`：page1..1000000、size1..50；仅允许这两字段且不能重复，默认1/20。data含merchant_id/page/page_size/items/summary。

items每行只有rating（1..5）与submitted_date（原评价UTC日），按原提交时间/id倒序；内部标识不返回。同日同分可重复，分页期间变更可能使列表移动，应刷新。summary全有效样本 `{count:2,rating_sum:7,average_rating:"3.50"}`；零样本为0/0/null，不造默认星级。列表和汇总同一重复读快照、同一SQL谓词，无缓存；平均数非质量认证。

必须同时满足：未删除COMPLETED订单、评价作者/门店有效、匹配核销与历史本店店员、评价/核销非测试、唯一WECHAT成功付款、CNY/金额匹配且正数、渠道单号/付款时间/唯一PAID事件及哈希完整、无支付异常/开放争议/任何退款记录、独立consented=1。不将本地测试评价迁入正式样本；测试使用合成WECHAT数据库夹具不代表正式渠道已经接入。资格恢复仍按原独立授权重新核对，未授权者不会自动公开。

撤销提交后开始的新请求不再包含该星级；在途快照和已查看信息无法收回。私有正文、照片、姓名、车型及用户/评价/订单编号均不进入公开投影。公开文本的单独授权、脱敏与审核为下一R4b2b2，AI总结再后续。
