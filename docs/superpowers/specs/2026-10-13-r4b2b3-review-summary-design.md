# R4b2b3 可核对来源的授权评价总结

2026-10-13，依赖#144。用户要求规划后实施。

## 选择与范围

自由改写可能产生无依据结论；单纯摘录不构成AI选取。采用AI从服务器生成的完整原句候选中选取最多3条，严格返回公开号和确切quote，最终显示AI摘录要点及对应全文，不能输出模型自行生成的评价、星级或多数结论。AI失败/未配置/输出无效时提供明确标注的固定顺序完整原句摘录。不能将降级称作AI成功。不是统计代表性分析或平台质量认证。

使用现有DeepSeekClient接口，不更换模型或实际调用正式服务验收。所有请求用户主动触发。仅最近20条当前获批且可信非测试、另有AI授权的文本入选，最多每来源2条完整句候选、每条≤300码点；句子按中文句号/问叹号/换行分隔，保留原句，不从否定句截出相反含义。每条模型输出必须逐字匹配候选，禁止未知来源、部分句、额外字段/重复JSON键/尾随内容和重复条目，最多3条。

## 独立作者授权

已有R4B2_TEXT_V1仅授权人工审核和展示，不自动允许向第三方传输。V035新增review_summary_consent：review_id主键、consented、version、text_version（当前批准文本版本）、consent_version=R4B2_SUMMARY_V1。无旧数据回填。

GET/PUT /api/order/{id}/review-summary-consent。GET data={order_id,version,consented,effective,can_consent,text_version}；effective要求当前文本仍获批、资格/开关有效及授权绑定当前文本版本。PUT严格{consented:boolean,expected_version:integer,expected_text_version:integer,consent_version:'R4B2_SUMMARY_V1'}，UUID幂等键；单独确认明确将确切获批公开文本发送至第三方DeepSeek以选取要点。授予要求当前有效批准来源；撤销在文本失效/改稿/开关关闭时仍允许，仅核对本人订单/评价。AI授权每次变更版本递增；改稿/撤销/重新审批改变文本版本，旧授权立即失效，必须重新确认。星级、文字公开和AI授权三个用途分别处理。

历史幂等响应不等于当前授权，界面写后GET。新键旧版本409；同键不重新授权。批准/撤销/AI授权保持订单→评价→文本锁序，变更/审计/幂等同事务；审计只有授权元数据，不含正文。

## 总结API与生命周期

POST /api/service/merchant/{id}/review-summary，只读严格空JSON，无query，无幂等键，no-store。有效车主/门店，REVIEW_SUMMARY_ENABLED默认false，同时要求REVIEW_TEXT_PUBLICATION_ENABLED。data固定merchant_id/status/method/sample_count/eligible_count/highlights/sources。status=AI_READY/FALLBACK/EMPTY/DISABLED/SOURCE_CHANGED；method=AI_EXTRACTIVE_V1/EXACT_EXTRACT_V1/null；sample_count≤20为当前选取来源数，eligible_count是符合AI授权的全部文本数，不是评分样本数。highlights={public_id,quote}，sources={public_id,public_text,published_date}，只列被引用的仍有效文本；无样本或来源变化返回空投影，来源变化时两计数0以免混淆。

先重复读快照取得授权/来源，仅向AI发送随机公开号及完整原句候选（不发送作者、订单、商家、日期、评分或私人原文）。外部IO在数据库事务之外；2个进程内并发许可，超额请求直接原文降级。AI返回后重新取得全新数据库快照，复核请求者会话/门店以及选中来源/文本哈希/批准版本/AI授权版本/总样本数，变化则SOURCE_CHANGED不返回旧文字。在途已传第三方的内容不能收回，确认文案说明；服务不持久化或缓存模型结果，不写正文审计。最长沿用现有45秒上游超时，页面传输60秒；真实上游表现另验收。

## 页面、验证与后续

本人文本页入口到AI用途独立授权页，查看确切当前批准公开文字后确认；界面分别标记授权和是否当前有效。门店总结页主动生成、显示方法/限定样本/引用全文、空态及降级/来源变化提示；离开或换账号清空结果、迟到回调失效，刷新前清空旧内容，不入本地缓存。

真实MySQL：默认无授权、绑定版本、撤销与来源失效、并发/审计回滚、请求者失权；模型替身：只发送允许字段、严格完整句校验、无依据输出降级、生成途中撤销后拒绝旧结果；mock HTTP严格角色/正文/no-store；小程序确认取消/同键重试/生命周期/协议白名单。schema76表、微信/H5、生成一致性、独立GitHub PR及六项CI为阶段门槛。无迁移旧总结、无实际供应商/正式资金/真机调用，最后R9。下一R4c结构化AI方案到有效项目/报价/预约。
