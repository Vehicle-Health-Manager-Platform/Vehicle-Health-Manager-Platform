# R5c2 公开向量检索与可核验片段

默认仍使用KEYWORD。明确开启KNOWLEDGE_VECTOR_ENABLED=true时，必须已配置KNOWLEDGE_ENABLED=true、KNOWLEDGE_EMBEDDING_ENABLED=true和实际模型profile/URL/token，且清理模式为false。非法组合拒绝启动。变量已接入根.env.example及Compose；私有模型/许可准备与停止、维护步骤见[索引运维](../operations/KNOWLEDGE_EMBEDDING_LIFECYCLE.md)。当前采用有界MySQL精确余弦排序，未接入Milvus，不宣称领域质量或生产容量。

## 公开查询与明确降级

GET /api/knowledge/search仍只接受vehicle_id、q（1..80字）及可选关键词cursor，保持JWT/当前会话/当前车主/精确车型校验和no-store。启用后，当前范围最多101候选/100有效范围，先核验所有当前可检索资料的来源、许可、有效期与独立嵌入授权，以及当前profile成功任务；不接受部分语料悄悄漏掉未索引资料。AI检索范围还要求独立ai_allowed。

模型IO在事务外。查询前后以新事务/控制锁核验当前车型、合法资料集合revision/source_hash/公开引用；索引继续核验完整分块、偏移/内容/hash、维度与有限向量。公共查询的模型执行使用独立单槽、12秒截止（不含前后SQL核验时间，非整条HTTP耗时承诺），后台索引仍30秒；私有模型忙或超时不排队/重试，改走当前授权的关键词检索。

成功VECTOR只返回state=AVAILABLE、1..5份去重资料，next_cursor=null，不返回分数、内部profile或许可凭证。仅表示向量相似度排序，不表示内容相关性/真实性或诊断已确认。KEYWORD保留旧结构，可选fallback_reason：

- INDEX_NOT_READY：当前合法资料未全部获准嵌入、索引未成功/配置不匹配、范围为空或语料变化。
- VECTOR_UNAVAILABLE：模型忙/超时/拒绝、向量/分块损坏或范围重验失败。
- QUERY_LIMIT：AI问题超过80字或含换行，保留原完整问题做关键词检索，不截断假装向量。
- CURSOR_PAGING：继续原关键词游标分页，不将游标当向量名次。

默认未启用时无新增降级字段。401/403/404身份/车辆错误不转为关键词成功；数据库错误也不伪装可用结果。关键词仍可能返回UNKNOWN/NO_MATCH/UNAVAILABLE，没有证据不等于车辆健康或无故障。

## 精确摘录和独立详情

VECTOR引用沿用reference(UUID)、revision、model_id、title等公开字段，增加完整三字段chunk（0..63）、start_offset、end_offset。分块为UNICODE_256_OVERLAP32_V1，偏移为UTF16单位；excerpt精确等于该块，最多256 Unicode字，保留边界空白/换行，不用正文开头替代命中位置。客户端校验三字段完整性、偏移范围/摘录长度和用途形状；VECTOR不能携带关键词游标、降级原因或无位置引用。

GET /api/knowledge/documents/{reference}?vehicle_id=...&chunk=...返回当前来源该块的许可摘录及相同位置，body等于excerpt。chunk只允许规范整数0..63，未知/重复参数400；不存在块、来源/用途失效404。仍核验当前车主/车型、发布信任链、检索/摘录及独立嵌入许可，不读取旧索引副本充当正文。无chunk保持原前800字详情。片段为空白则拒绝；不提供任意起止位置参数。

应用内引用为/pages/community/knowledge?reference=UUID&chunk=N，复制和深链接保留chunk；新账号/车型重新核验，旧响应不能覆盖新上下文。资料详情引用可在关闭向量查询后继续核验合法正文片段；关闭检索功能不等于撤销资料许可，停止资料后旧引用立即失效。

## AI参考

POST /api/knowledge/answer输入保持vehicle_id/question（1..1000字）；KNOWLEDGE_AI_ENABLED和上游配置/用途确认独立。只发送本次问题和当前允许AI的实际许可摘录，不发送私有车辆档案/许可凭证。VECTOR来源保留精确片段，KEYWORD来源不携带向量位置。

调用后在新事务中再次核验当前车主/车型、所有实际提供来源的revision/hash、AI许可和精确片段；VECTOR另核验当前运行配置和所选资料成功任务。失效丢弃回答，不把旧回答改成KEYWORD返回。reply/provided_sources/method沿用旧字段，可带上述KEYWORD降级原因（不含分页原因）。provided_sources仅说明实际提供给AI的资料，不等于模型每句话已有事实支持；真实上游AI和授权领域效果未验。

## 测试与验收

见[阶段报告](../progress/RECOGNITION_R5C2_PUBLIC_VECTOR.md)。真实BGE、模拟AI/身份、真实MySQL/HTTP和浏览器夹具分开记录。相似度样例不计领域准确率。没有新增路由/迁移，仍362操作/65迁移/134表。

复现新增数据库/权限套件：mvn -f backend/pom.xml -Dtest=JdbcKnowledgeVectorRetrievalTest,LicensedKnowledgeHttpTest,KnowledgeEmbeddingConfigurationTest test。实际模型运行器沿用services/embedding/README.md命令并加Java参数，执行34项内部套件及5项公开HTTP套件（含继承，非39项新增）；公开套件使用实际Spring自动索引/JWT/HTTP/MySQL/BGE，AI为明确模拟。

本轮还执行默认12组、相关Java回归、浏览器和完整后端核心测试/覆盖率核对；最终结果以机器证据为准。统一验收剩余项见[收口清单](../delivery/FINAL_ACCEPTANCE_2026_10_28.md)，不将历史317项来源换成当前全API通过。
