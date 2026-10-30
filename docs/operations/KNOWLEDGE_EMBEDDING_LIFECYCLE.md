# R5c1 自动索引运行与维护

2026-10-29增量：已实现默认关闭的有限瞬时重试和事务审计，配置及边界见下文。清理巡检不再刷新失败时间，避免退避永不到期；FAILED文案保持“生成失败”，具体重试以服务端配置为准。实现及最终测试见[本轮报告](../testing/STAGE_ACCEPTANCE_2026_10_29.md)。

R5c2增量：[公开向量检索与精确片段协议](../api/KNOWLEDGE_VECTOR_RETRIEVAL.md)。KNOWLEDGE_VECTOR_ENABLED=false默认关闭；开启必须同时启用知识及索引、配置私有模型且关闭维护清理。索引配置并不自动打开公开VECTOR。下方公开KEYWORD描述为R5c1阶段范围，其余索引操作仍适用。

默认关闭。此阶段接通MySQL持久任务的自动发现、受限执行、运营状态和清理；公开知识检索/AI仍KEYWORD。用户无需改变原操作；运营人员在资料列表点“刷新并核对资料”查看本地私有索引状态。索引成功不代表资料真实性、诊断或领域效果合格。

## 配置与启动

后端变量已接入根.env.example和默认Compose，均为服务端变量，不放入VITE客户端配置：

- KNOWLEDGE_EMBEDDING_ENABLED=false：启用自动发现/执行；必须同时KNOWLEDGE_ENABLED=true。
- KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED=false：独立全配置清理模式，不能与索引启用同时true。默认两项false不查索引、不访问模型。
- KNOWLEDGE_EMBEDDING_PROFILE_JSON：来自实际固定镜像验收报告runtime.profile的完整8字段JSON，不手写模型假指纹。包括model/revision/dimension/query_prefix/normalization/chunk_policy/encoder_policy/runtime_sha256，拒绝重复键、未知/缺失字段、尾随JSON和错误策略。
- KNOWLEDGE_EMBEDDING_URL、KNOWLEDGE_EMBEDDING_TOKEN：固定/embed私有服务，token32..512可见ASCII。
- KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP=false：只在明确内网/本机测试时true，HTTP仅embedding/localhost/127.0.0.1；其余要求HTTPS。不跟重定向。
- KNOWLEDGE_EMBEDDING_DELAY_MS=5000：1000..300000毫秒，固定延迟，不堆积轮询任务。非法启用参数阻止启动。

先按[固定资源与离线真实模型指南](../../services/embedding/README.md)完成模型/依赖校验、镜像构建和验收。模型服务EMBEDDING_*开关与上述后端KNOWLEDGE_EMBEDDING_*变量分开控制；模型健康仅表示配置核验，必须另验实际/embed输出。

本地显式Compose覆盖为deploy/compose/embedding-test.yml，额外要求EMBEDDING_MODELS_DIR（已核验模型绝对路径）、profile JSON及私有token。使用仓库原Compose启动步骤，追加该覆盖文件；例如：

```powershell
docker compose --env-file .env -f deploy/compose/compose.yml -f deploy/compose/embedding-test.yml up -d --build backend embedding
```

覆盖只用于测试，不能加入默认/生产启动命令。模型仅内部网络，无宿主端口，非root、只读模型/rootfs、2GiB内存/swap上限、CPU2、PID128和256MiB tmpfs。退出时停止自有项目的backend/embedding服务，按项目名核验资源归属后清理；不要删除共享数据库卷。

本轮验证过覆盖文件解析及资源/网络结构，未执行完整Compose栈构建启动；Java/MySQL真实模型联调使用独立隔离运行器。不能将配置解析通过称为整栈部署通过。

## 资料到任务与错误处理

每轮最多发现20份资料、清理100个任务、执行1任务，文档ID/keyset轮转。只有当前发布、精确车型、有效期、来源事件/审计信任链及独立嵌入许可均通过的资料才能入队。旧13字段资料不推导许可；发布/许可凭证/用途确认仍是原表单和原幂等写入。

数据库控制锁和持久租约在应用实例间限制一个活跃索引任务；专用调度线程与其他业务定时器隔离。网络不在数据库事务内，整体30秒，单次私有调用25秒/模型20秒。生成前后复核来源，任一后续批失败不写部分块。控制锁入队仅一个独立事务，不嵌套持锁事务。

默认FAILED仍不自动重试。显式设置KNOWLEDGE_EMBEDDING_RETRY_ENABLED=true后，仅UNAVAILABLE/WORKER_LOST可由后台有限重排；必须同时配置并启用索引，维护清理模式不能启用重试。KNOWLEDGE_EMBEDDING_RETRY_LIMIT为1–3，默认2（不含首次调用）；KNOWLEDGE_EMBEDDING_RETRY_DELAY_SECONDS为60–3600，默认60，后续逐次翻倍。非法启用参数阻止启动。

每轮最多扫描20个失败候选；在数据库控制锁中复核当前来源/版本/模型、许可和剩余配额，排队与system审计同事务提交。审计失败不消费配额、不调用模型；多实例及重启不重置配额。INVALID_VECTOR/LIMIT_EXCEEDED、撤回/停用/过期/取消或残留块不一致的任务不自动重试。达到配额仍为FAILED，修复根因后可按原流程发布新资料版本/配置；禁止手工改任务状态或删审计增加预算。显式维护重建是独立操作，不等于自动重试预算。

刷新只查询，不触发重排；重试发生后等待后台再核对状态。任务保留期间须保留KNOWLEDGE_EMBEDDING_RETRY审计，软删除不会释放配额。审计只有任务/状态/固定错误码/次数/策略，没有原文、向量、token或供应商诊断。活跃租约最长180秒；超期任务转WORKER_LOST后再从实际失败时间退避。

## 撤回、停用和跨配置清理

当前profile中的资料停用/过期/许可或审计失效时，现有内部rank立即拒绝；后台清理删除其向量和文本块并取消任务，安全元数据保留。公开资料/业务表和原审计不由索引修改。

不同运行profile不会互相取消任务，正常索引维护只处理当前profile。切换版本或删除旧配置数据时：先停止所有索引实例，再设置KNOWLEDGE_EMBEDDING_ENABLED=false、KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED=true，以仅清理模式启动后端，按最多100任务/轮等待所有配置的块清空和任务取消。确认后关闭清理模式，再用唯一目标profile启动索引。清理期间不要运行其他索引实例；同实例互斥不能代替运维跨实例停机协调。

重新启用相同profile时，仅因FEATURE_DISABLED取消且当前来源仍有效、无残留块的任务可重新排队；SOURCE_CHANGED、失败和旧失联租约不会自动复活。清理未保证同步完成；删除验收须核验全库knowledge_embedding_chunk数量和相关任务状态，备份/历史日志的保留政策另行办理。

## 运营状态与接口

复用GET /api/admin/knowledge/documents及既有独立can_knowledge权限。列表每行可选embedding对象，仅state/error_code/profile_hash/updated_at；旧响应兼容，写回执原结构不变。没有新增公开路由或迁移。默认两开关关闭不添加状态字段。

状态：DISABLED关闭；NOT_ALLOWED当前来源未获准；NOT_QUEUED等待发现；QUEUED排队；RUNNING生成；SUCCEEDED任务完成；FAILED失败（按配置有限重试）；CANCELLED取消。只显示当前profile/来源任务，不显示向量/内部模型响应/凭据。运营页不展示原始profile hash；更新时间表示任务最后写入时间，不是最近巡检或质量评估时间。对未知字段、错误码/状态组合和时间格式，前端拒绝展示旧结果。状态表示任务元数据，不替代每次查询的完整性/许可重验。

## 复现与交接

```powershell
mvn -f backend/pom.xml "-Dtest=KnowledgeEmbeddingConfigurationTest,JdbcKnowledgeEmbeddingRuntimeTest,JdbcKnowledgeVectorIndexTest,JdbcKnowledgeEmbeddingRetriesTest,KnowledgeEmbeddingRetryPolicyTest,EmbeddingHttpClientTest,EmbeddingFoundationTest,KnowledgeEmbeddingHttpJourneyTest" test
python scripts/run_local_delivery.py
```

真实模型运行器支持原--java-maven/--maven-repository选项，现增加实际生命周期生成/状态/撤回检查，共32个Java执行项（4项实际模型方法及28项继承回归，其中本轮新增1项生命周期方法）；身份和许可仍为合成夹具。调度线程的实际Spring定时触发另以受控回调测试，不冒称完整真实模型Compose自动旅程已跑。

[R5c1历史报告](../progress/RECOGNITION_R5C1_EMBEDDING_LIFECYCLE.md)、[2026-10-29测试报告](../testing/STAGE_ACCEPTANCE_2026_10_29.md)、[统一交接](../delivery/STAGE_HANDOVER_2026_10_29.md)。公开VECTOR和有限重试均已有实现；授权语料质量、手机及生产监控仍独立验收。正式微信、短信、支付、生产办理继续由外部负责人处理。
