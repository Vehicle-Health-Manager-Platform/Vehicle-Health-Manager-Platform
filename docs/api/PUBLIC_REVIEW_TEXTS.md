# 独立公开评价文本 API（R4b2b2）

2026-10-13。见[规格](../superpowers/specs/2026-10-13-r4b2b2-public-review-text-design.md)及[验收](../testing/LOCAL_PUBLIC_REVIEW_TEXTS_ACCEPTANCE.md)。独立文本不复制私人评价/照片、不改变星级授权与平均分。`REVIEW_TEXT_PUBLICATION_ENABLED=true`方可预览/投稿/审核/公开；默认false。本人GET和撤销不受开关限制。compose与CloudRun示例提供变量，不自动开启生产。

所有接口no-store、单层ApiResponse；有效身份、严格JSON（拒绝重复键/尾随/未知字段）、safe整数版本0..9007199254740991；路径正整数；分页page1..1000000、size1..50、默认1/20，仅允许单值page/page_size。除列表分页外无query。UUID幂等键用于PUT和审核POST；预览POST只读不需要。

## 车主

- `GET /api/order/{id}/public-review-text`：本人有效会话、未删除订单和匹配评价；非本人/无评价404。data固定order_id/version/status/public_text/reason_code/visible/can_submit/unavailable_reason。无记录NONE/0/null；状态PENDING/APPROVED/REJECTED/WITHDRAWN。visible表示当前实际展示，与审核状态分别列出。reason_code仅驳回时固定码，unavailable_reason为null/DISABLED/PUBLIC_SOURCE_UNVERIFIED。
- `POST /api/order/{id}/public-review-text/preview`：`{"public_text":"单独填写的文字"}`，1..300 Unicode码点。返回确切规范脱敏public_text及redaction_version=TEXT_REDACTION_V1；不存原输入、不审计，不读取私人原评价正文。须满足公开资格。
- `PUT /api/order/{id}/public-review-text`：SUBMIT正文`{"action":"SUBMIT","expected_version":0,"public_text":"预览结果","consent_version":"R4B2_TEXT_V1"}`；必须已是当前规则规范脱敏文本，未规范422，提交后PENDING。WITHDRAW正文`{"action":"WITHDRAW","expected_version":2}`，清除当前文字、哈希和公开编号，保留版本/不含正文的审核及审计履历；资格失效或开关关闭时仍可撤销。

旧版本409，同键原正文不重复变更，历史幂等响应不代表当前状态，客户端写后GET刷新。原文不写幂等记录，投稿只保存确切规范预览；撤销不能抹除已被查看的信息。修改已批准/待审文字重新确认授权、重新待审。

## 脱敏和人工审核

TEXT_REDACTION_V1先NFKC规范化，拒绝非法Unicode、格式字符及不允许的控制字符；常见URL/邮箱、17字符VIN、常见中文车牌、7位以上数字（允许空格/短横线）替换为`[已隐藏]`。它会有误匹配和遗漏，不能保证覆盖人名/地址/混写等语义隐私，车主须检查预览，运营必须人工检查；不是自动合规认证。图片未开放。

- `GET /api/admin/review-texts`：有效独立运营身份且can_review；每项review_id/version/public_text/can_approve，仅当前PENDING授权脱敏文字，无私人正文/姓名/订单/用户号。来源失效不能批准，可驳回。
- `POST /api/admin/review-texts/{id}/moderate`：`{"expected_version":1,"decision":"APPROVE","reason_code":null}`，或REJECT及固定PRIVACY/ABUSE/OFF_TOPIC/UNVERIFIABLE。审核不能编辑正文；批准绑定文本哈希和版本、产生随机公开id。返回review_id/version/status。相同待审版本唯一审核，撤销/新稿后旧审核（含缓存键）409；运营权限/会话和批准资格重放时重新校验。

can_review沿用现有内容审核权限，入驻/目录权限不能替代。审核状态和记录、幂等及不含文本审计同事务；缺失审计则全部回滚。

## 公开列表

`GET /api/service/merchant/{id}/review-texts`：有效车主与门店，返回merchant_id/items/total/page/page_size；每项仅public_id（随机UUID）、public_text、published_date（UTC）。按投稿时刻/内部评价id降序，内部id不返回。列表与total同一重复读快照；分页变更可刷新。

仅当前APPROVED、R4B2_TEXT_V1、当前版本与批准记录匹配、文本哈希一致，且复用[公开星级](PUBLIC_RATINGS.md)实时可信非测试WECHAT履约过滤。作者/订单/门店/付款异常、争议/退款、撤销或修改后新请求不展示；在途快照与已查看内容无法收回。合成WECHAT测试夹具不证明正式资金接入。开关关闭返回空列表。

下一阶段授权文本总结应逐次复核这些来源，不对私人原评价或撤销文字生成公开总结。
