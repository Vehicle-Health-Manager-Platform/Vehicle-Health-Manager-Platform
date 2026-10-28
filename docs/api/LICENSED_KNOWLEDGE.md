# 有来源许可资料、检索与AI参考

最新R5c2已在原路由接入[默认关闭的公开VECTOR/明确降级、片段详情与AI引用复核](KNOWLEDGE_VECTOR_RETRIEVAL.md)，R5a新增独立embedding_allowed许可，R5c1新增自动索引。以下为R8c3原始范围，method固定KEYWORD等描述保留历史含义；默认配置仍兼容旧形状。

R8c3，V061；全库130表，运营登录17字段（15个独立布尔权限加id/role）。用户确认暂无授权资料/嵌入服务，先实现配置和检索闭环。**method固定KEYWORD，未实现或验收Milvus向量效果，不创建正式语料种子。**

## 配置与独立权限

`KNOWLEDGE_ENABLED=false`和`KNOWLEDGE_AI_ENABLED=false`默认关闭；can_knowledge默认0，不继承can_catalog/can_review等权限。运营body严格13字段：title（120）、publisher（120）、document_version（80）、source_reference（500，公开）、license_reference（500，仅运营）、license_confirmed（boolean）、model_id（当前有效精确车型）、valid_from/valid_to（UTC整秒，起点包含/终点不包含）、search_allowed/excerpt_allowed/ai_allowed（独立boolean）、body（10000字符，可换行）。严格字段/重复键/尾随内容校验，JSON正文最多64000字符。

运营填写的是其已核对的来源和许可声明，系统不代替权利人许可核验。不爬取URL、不导入无授权手册；来源说明只是文本。发布须许可/检索/摘录均明确true、有效期未过；AI用途可单独false。发布冻结，正文/来源修订须新建资料版本；STOPPED为终态，不复活旧UUID。

## API

- GET `/api/admin/knowledge/models?page=1&page_size=20`：独立can_knowledge下分页选择当前有效品牌/车系/年款/配置，不授予目录写权限。
- GET/POST `/api/admin/knowledge/documents`：分页读取/创建草稿。
- PUT `/api/admin/knowledge/documents/{id}`：13字段加expected_revision，仅DRAFT修订。
- POST `/api/admin/knowledge/documents/{id}/status`：`{expected_revision,status:PUBLISHED|STOPPED}`，CAS、事件和审计原子提交。
- GET `/api/knowledge/search?vehicle_id=ID&q=关键词&cursor=ID`：车主当前本人车型检索，q为1–80字符，cursor可省。返回vehicle_id/state/method/items/next_cursor，状态AVAILABLE/UNKNOWN/NO_MATCH/UNAVAILABLE，不预设正式语料存在。
- GET `/api/knowledge/documents/{reference}?vehicle_id=ID`：当前授权资料引用详情，仅返回最多800字符许可摘录，不提供运营正文原件或内部许可凭证。
- POST `/api/knowledge/answer`：严格`{vehicle_id,question}`，question最多1000字符。独立可选AI参考，无数据库业务写入，不发积分。

管理写入要求UUID Idempotency-Key；创建保留永久原键/body回执，其他原请求客户端限23小时。读和非持久AI请求不要求此头。全部no-store；scope、车型、许可、原发布事件与审计每次重验。未知/重复query拒绝。

## 可追溯检索

对当前车型、发布时间窗和PUBLISHED资料做参数化查询；NFKC/小写化关键词、英文数字词和中文相邻双字，最多8个词。只查询匹配资料，每页最多20个基础候选，失效源逐条过滤，稀疏页按next_cursor继续。没有相似度/热门度或虚构命中数量。资料摘要仅reference/revision/model_id/title/publisher/document_version/source_reference/valid_from/valid_to/excerpt/ai_allowed；不包含license_reference、运营账号或车主信息。

引用绑定原公开UUID、资料版本及冻结源哈希；停止、过期、车型/归属变化、载荷篡改、最新事件/审计软删或旧行回退后不可读。用户已复制的摘录不声称能远程删除。

## AI边界

最多3条当前允许AI使用的800字符摘录；仅向既有服务器AI客户端发送本次问题和最小公开资料，不读取车辆私有档案。系统指令与不可信资料分角色，模型不参与许可/权限判定。响应为vehicle_id/reply/model/provided_sources/method；provided_sources表示实际提供给AI的资料，不能证明模型每句话均正确或无幻觉。来源调用后再次验证，任一失效则整条回答不返回；未配置/无来源/服务失败明确提示，保留手动检索。

实际页面：受限运营`/pages/operator/knowledge`与车主`/pages/community/knowledge`。管理完整确认、独立账号恢复、权限撤回和迟到清理；AI调用前确认问题及用途，切号/选车/隐藏不显示旧答案。普通AI聊天的既有接口和私有上下文协议不变，通过独立入口使用许可资料。
