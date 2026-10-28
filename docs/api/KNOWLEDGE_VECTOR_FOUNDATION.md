# 授权嵌入与向量索引基础（R5a）

R5c1增量：[默认关闭自动任务与运营状态、清理步骤](../operations/KNOWLEDGE_EMBEDDING_LIFECYCLE.md)。既有运营列表新增可选embedding状态，不改写回执；公开接口仍KEYWORD。以下无自动任务描述为R5a历史范围。

R5b增量：[私有真实中文嵌入协议/配置/运行](../../services/embedding/README.md)、[实际模型与Java/MySQL结果](../progress/RECOGNITION_R5B_REAL_EMBEDDING.md)。下文R5a替身范围保留历史含义；公开接口仍KEYWORD，无自动调度或VECTOR入口。

当前交付是**内部索引基础和运营授权字段**。现有车主`/api/knowledge/search`和`/api/knowledge/answer`仍为KEYWORD，没有新的公开向量接口、默认provider、Spring索引bean或自动调度；不会因勾选许可自动建立索引。真实中文embedding、tokenizer、召回效果和向量降级页面尚未接入。

## 运营授权与旧资料

原知识资料13字段继续接受、原样保存及计算source_hash，原幂等创建回执也保持原正文；不回填嵌入许可。新增两字段必须一起出现：

```json
{"embedding_allowed":false,"embedding_policy_version":"KNOWLEDGE_EMBEDDING_V1"}
```

这两个字段只是完整知识payload的一部分，不是可单独PATCH的接口。embedding_allowed必须布尔，策略版本严格固定，额外/不完整字段拒绝。运营页默认false；编辑旧/新草稿、保存完成、清空及身份切换均重新确认，预览完整包含嵌入许可和策略。许可含义仅本地私有嵌入索引，不授权把全文交给外部第三方模型。search_allowed、excerpt_allowed或ai_allowed都不推导嵌入许可。

现有独立can_knowledge权限、资料发布冻结、许可凭证/有效期/精确车型、source_hash、最新事件与审计持续生效。内部许可依据及嵌入标志不会出现在车主公开摘录。来源停止/过期/篡改/车型停用后，旧索引不能继续引用。

## 内部类型和数据

- `EmbeddingProfile`：模型ID、40位精确revision或64位资源摘要、1..1024维、查询前缀、L2_V1和UNICODE_256_OVERLAP32_V1分块政策。规范JSON的SHA-256作为配置指纹，任一项变化不混用索引。配置不是模型真实性证据。
- `KnowledgeChunks`：最多10000 Unicode码点，256码点窗口/32码点重叠，最多64块，逐块保存UTF16起止、原文及SHA-256。保留完整覆盖和代理对，拒绝孤立代理及非法控制。**字符窗口不等于模型token预算**，真实adapter必须另外执行固定tokenizer检查。
- `EmbeddingProvider`：接收配置和文本列表，返回配置指纹及矩阵；没有哈希向量或关键词替代实现。拒绝错配置、数量/维度不符、零向量、NaN/Infinity；归一化避免极大/极小有限值溢出。测试中的合成基向量仅验证数学/管道。
- `EmbeddingExecutor`：每批最多4段，总调用最多30秒，单线程直接交接、无等待队列和重试。超时取消Future，provider忽略中断时仍占槽，后续请求拒绝而不增生线程；关闭会中断worker，不能承诺强制杀死任意Java provider，真实进程级隔离在R5b另验。
- V065新增knowledge_embedding_job与knowledge_embedding_chunk，65迁移/134表，旧knowledge_document不改写。任务唯一键为资料ID/版本/来源hash/配置指纹；180秒租约，完成CAS，完整矩阵在同一事务入库，无部分成功。

`KnowledgeVectorIndex`为包内显式构造的基础组件，提供enqueue/workOne/cleanup/rank，当前没有可由用户调用的HTTP操作。enqueue仅接受经过已有发布信任链和嵌入许可校验的资料；生成前后重新验证，provider在事务外执行。失败不自动重试；重新修订资料或使用新的配置形成独立键。

内部rank先核对当前车主/车辆/车型，事务外生成查询向量，返回前在知识控制锁下再次验证当前身份、车型及资料信任链。最多扫描512块，超过明确503，不截断声称完整召回；按每份资料最佳块聚合，以分数降序/资料ID降序/块序升序确定排序。成功任务缺少全部或部分块、块来源/维度/数值损坏时拒绝，不产生无来源引用。

cleanup只分页处理当前实例的配置，撤回或关闭后删除其候选块并留安全元数据。即使尚未清理，rank也立即拒绝失效来源。旧模型配置清理、全库保留/删除排程、管理状态页和自动索引调度属于后续接线；不能把本阶段方法称作完整生产数据删除能力。

## 本地验证

沿用[本地环境](../delivery/LOCAL_SETUP.md)的Java17/Maven/MySQL Testcontainers配置：

```sh
mvn -f backend/pom.xml -Dtest=EmbeddingFoundationTest,JdbcKnowledgeVectorIndexTest,KnowledgeEmbeddingHttpJourneyTest,LicensedKnowledgeHttpTest test
npm test --workspace @autocare/miniapp
python scripts/run_local_delivery.py
```

测试运行在新建隔离数据库；不要导入生产资料。provider均为明确替身，实际TCP/JWT/MySQL验证的是权限、索引生命周期及原表单契约，不代表模型或领域召回率。没有新增默认启用模型的环境变量；既有KNOWLEDGE_ENABLED仍控制原资料检索。部署只需按顺序应用V065并更新前后端，不能通过增加环境变量假装索引已开始运行。

生产办理仍后置。升级前停写并备份，DDL失败不能靠SQL ROLLBACK恢复；按[恢复手册](../operations/DATABASE_RECOVERY_RUNBOOK.md)在新库还原并校验。当前恢复运行器边界为V064→V065，132→134表，是否实际运行看本轮报告。

[测试与问题报告](../progress/RECOGNITION_R5A_VECTOR_FOUNDATION.md)、[设计](../superpowers/specs/2026-10-27-knowledge-vector-foundation-design.md)、[实施](../superpowers/plans/2026-10-27-knowledge-vector-foundation.md)。R5b将核验经许可的模型精确资源/依赖、真实token预算和私有受限推理，再接入调度、状态与明确的KEYWORD降级；当前不宣称VECTOR检索完成。
