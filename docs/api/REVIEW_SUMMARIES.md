# 授权评价原文要点（R4b2b3）

2026-10-13，[规格](../superpowers/specs/2026-10-13-r4b2b3-review-summary-design.md)、[验收](../testing/LOCAL_REVIEW_SUMMARIES_ACCEPTANCE.md)。复用已批准公开文字和实时可信履约SQL；独立第三方AI用途，不推断已有文字/星级授权。REVIEW_SUMMARY_ENABLED与REVIEW_TEXT_PUBLICATION_ENABLED均true才允许授予或生成；默认false。本人读/撤销仍可用。无需旧总结迁移，不缓存/持久化模型结果。

## 本人用途授权

`GET /api/order/{id}/review-summary-consent`：有效本人会话、未删除本人订单及匹配评价；data={order_id,version,text_version,consented,effective,can_consent}。无授权version0；text_version是当前公开文字版本，无文字0。effective只有绑定当前批准文本、当前用途版本R4B2_SUMMARY_V1、当前资格和开关有效时true。

`PUT /api/order/{id}/review-summary-consent`：UUID Idempotency-Key，严格 `{ "consented":true,"expected_version":0,"expected_text_version":2,"consent_version":"R4B2_SUMMARY_V1" }`。版本0..9007199254740991整数，拒绝未知/重复键、尾随JSON及query。授予必须当前获批非测试可信来源和文本版本匹配；旧版本/资格409。撤销只比较用途版本，不要求旧文字仍有效，可在改稿/来源失效/关闭时执行。授权变化/版本/幂等/元数据审计同事务，无正文审计。

独立确认明确允许确切批准文字原句发送至第三方DeepSeek选取要点，并向登录车主展示引用；已发送至第三方或已查看内容无法收回。改稿、文字撤销或重新审批改变文本版本，旧用途失效。旧幂等请求不再变更但可能返回历史状态，客户端写后GET刷新。用途撤销不撤销文字或星级展示。

## 主动只读总结

`POST /api/service/merchant/{id}/review-summary`：有效车主/门店、严格空JSON `{}`，无query/幂等键，no-store。data固定merchant_id/status/method/sample_count/eligible_count/highlights/sources。

- AI_READY/AI_EXTRACTIVE_V1：上游从服务器候选中选取完整原句，通过严格校验。
- FALLBACK/EXACT_EXTRACT_V1：未配置、失败、无效输出或并发已满时提供固定顺序原文摘录，不能宣称AI成功。
- EMPTY/DISABLED/SOURCE_CHANGED：method null、两计数0、highlights/sources为空；无有效用途来源、功能关闭或生成期间来源变化。

sample_count≤20是本次最近授权来源，eligible_count是全部符合AI用途的文字数量，不是星级评分样本。highlights最多3条{public_id,quote}，sources仅被引用全文{public_id,public_text,published_date}。公开id随机UUID、日期UTC。来源/计数来自相同数据库快照；生成后必须与全新快照一致，否则丢弃旧内容。请求者生成中失权返回401、门店停用404。

候选最多每来源前2条原句，按中文句号/问叹号/换行分隔，保留标点和否定；无法分句时保留完整原文。模型只收到公开号→完整句候选，不发送作者、订单、商家、日期、评分或私人评价。严格JSON输出只能{highlights:[{public_id,quote}]}，每条必须逐字匹配完整候选，未知/部分句/改写/重复条目/额外字段/重复键/尾随内容均降级。不生成模型判断、星级或多数结论；原句包含主观说法，应结合全文判断，不等于平台质量认证。

外部IO在数据库事务外；每进程最多2路上游请求，额外请求直接摘录降级；沿用现有上游45秒超时、页面60秒传输超时。生成期间已发出的数据不能召回；新请求每次重新复核用途与批准版本/文本哈希/当前可信来源，无持久化旧总结。正式上游效果、网络兼容/真机在R9验证，合成WECHAT与模型替身不充作真实资金/模型验收。

下一R4c结构化AI方案到有效标准项目、报价及预约。
