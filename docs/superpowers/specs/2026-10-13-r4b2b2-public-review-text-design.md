# R4b2b2 独立公开文本、脱敏与审核

2026-10-13，依赖#143，用户已要求规划后实施。

## 选择与边界

直接公开私人评价会扩大授权；只靠自动替换无法识别人名、地址等语义隐私。采用单独编写最多300字公开文本、服务端规则脱敏预览、车主确认确切预览、运营can_review人工审核后展示。仅返回获批文字、随机公开编号和UTC发布日期，不返回私人评价、照片、姓名、订单/评价/用户编号。星级授权与评分汇总独立，提交/撤销文字不改变星级。

规则版本TEXT_REDACTION_V1：NFKC规范化，拒绝非法Unicode/控制或格式字符；隐藏常见URL、邮箱、7位以上数字序列（允许空格/短横线）、17字符VIN及常见中文车牌。规则不能保证覆盖人名、地址、混写或其他上下文隐私，必须人工审核；UI明确提示不能填身份/联系信息。服务器仅存脱敏后的确切授权文本，运营不查看原私人正文。拒绝理由固定PRIVACY/ABUSE/OFF_TOPIC/UNVERIFIABLE；批准不能编辑文字。审核中/已批准的文字修改必须重新授权并重新审核。

## 数据与契约

V034当前文本表review_id主键，public_text、consent_version、status（PENDING/APPROVED/REJECTED/WITHDRAWN）、version、reason_code、public_id、submitted_at、published_at；单行版本防旧操作覆盖。审核记录唯一(review_id,request_version)，含decision/reason_code/operator_id/decided_at/public_id，无文本副本。历史不回填，撤销清空当前文本及公开编号，保留版本和不含正文的审计/审核记录。

- GET /api/order/{id}/public-review-text：本人状态order_id/version/status/public_text/visible/can_submit/unavailable_reason/reason_code；无记录status NONE、version0、text null。
- POST /api/order/{id}/public-review-text/preview：只读严格{public_text}，校验本人及可信非测试资格，返回public_text/redaction_version，不持久化正文、不审计、no-store。
- PUT /api/order/{id}/public-review-text：严格oneOf SUBMIT={action,expected_version,public_text,consent_version}（R4B2_TEXT_V1），WITHDRAW={action,expected_version}；UUID幂等键。提交必须已是规则脱敏规范文本，否则422要求重做预览，不将未脱敏输入写入幂等记录。文字提交PENDING；撤销资格失效时仍允许。版本变更及审计同事务。
- GET /api/admin/review-texts：有can_review的运营，严格page/page_size，当前PENDING脱敏文本、review_id/version，不返回私人内容/作者身份。
- POST /api/admin/review-texts/{id}/moderate：严格{expected_version,decision,reason_code}；APPROVE理由null，REJECT固定码；UUID幂等。同一待审版本只能处理一次，批准生成新随机公开id。旧版本或撤销后409；重放不得恢复旧公开状态，界面写后刷新。
- GET /api/service/merchant/{id}/review-texts：有效车主会话、有效门店、严格分页，items={public_id,public_text,published_date}、total/page/page_size/merchant_id；仅当前APPROVED、授权版本匹配、当前文本与当前批准记录匹配、实时可信来源。排序submitted_at/review_id降序。

资格复用R4b2b1唯一实时SQL：有效本人作者与门店、可信非测试WECHAT成功付款/核销/完成订单，无异常/争议/退款。测试数据不公开。状态分别呈现审核状态和当前可见性，来源恢复按保留授权重新校验。REVIEW_TEXT_PUBLICATION_ENABLED默认false，示例和compose透传；关闭时不提交/预览/审核、不公开，本人读取及撤销仍可用。

## 一致性和验收

所有路由no-store，严格JSON重复键/尾随/额外字段和query拒绝；整数safe范围，分页1..1000000、size1..50。公开列表/total同一重复读快照。同键请求可能返回历史响应但不变更；身份/来源/撤销版本在重放前复核。本人写锁有效本人用户→订单→评价→文本；运营锁运营身份→订单→评价→文本，避免与车主形成反向锁序。撤销提交后新查询隐藏，已在途和已查看内容无法收回。

页面：本人独立文本编辑/预览/确认/提交/撤销、运营文本审核、门店公开文本；离开/换账号清空草稿与预览，异步旧响应失效、失败同键同规范正文重试，输入改变撤销旧预览，成功后读取实时状态。公开纯文本渲染，无HTML/图片；人工批准有确认。无正式短信/资金接入、AI生成或公开照片。本阶段结束上传代码、规格、迁移、API、验收、执行文档并检查六项CI；下一R4b2b3授权文本总结。
