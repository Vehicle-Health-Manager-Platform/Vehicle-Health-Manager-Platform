# 实际配置与第三方依赖索引（2026-10-29）

从当前后端源码中静态提取的环境变量读取点。私有 .env 不参与；表内没有密钥值。默认值、取值范围和启用前置以链接源码及对应业务手册为准，不根据变量名推断功能已启用。本清单覆盖字面量读取，不承诺覆盖动态拼接名称。

## 服务端读取点

| 变量 | 定义或读取位置 |
| --- | --- |
| `AI_SERVICE_PLAN_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `ASR_ALLOW_PRIVATE_HTTP` | [AsrJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobsConfiguration.java) |
| `ASR_CLEANUP_INTERVAL_MS` | [AsrJobWorker.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobWorker.java) |
| `ASR_ENABLED` | [AsrJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobsConfiguration.java) |
| `ASR_JOB_INTERVAL_MS` | [AsrJobWorker.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobWorker.java) |
| `ASR_SERVICE_TOKEN` | [AsrJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobsConfiguration.java) |
| `ASR_URL` | [AsrJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/AsrJobsConfiguration.java) |
| `CATALOG_GOVERNANCE_ENABLED` | [ServiceCatalogConfiguration.java](../../backend/src/main/java/com/autocare/platform/service/ServiceCatalogConfiguration.java) |
| `CLAMAV_CONNECT_TIMEOUT_MS` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `CLAMAV_PORT` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `CLAMAV_SCAN_TIMEOUT_MS` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `COMMUNITY_PUBLICATION_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `COUPON_RECOVERY_INTERVAL_MS` | [CouponRecovery.java](../../backend/src/main/java/com/autocare/platform/order/CouponRecovery.java) |
| `DEEPSEEK_API_KEY` | [DeepSeekClient.java](../../backend/src/main/java/com/autocare/platform/ai/DeepSeekClient.java) |
| `DEEPSEEK_BASE_URL` | [DeepSeekClient.java](../../backend/src/main/java/com/autocare/platform/ai/DeepSeekClient.java) |
| `DEEPSEEK_MODEL` | [DeepSeekClient.java](../../backend/src/main/java/com/autocare/platform/ai/DeepSeekClient.java) |
| `DEEPSEEK_THINKING` | [DeepSeekClient.java](../../backend/src/main/java/com/autocare/platform/ai/DeepSeekClient.java) |
| `DOCUMENT_ALLOW_PRIVATE_HTTP` | [DocumentJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobsConfiguration.java) |
| `DOCUMENT_CLEANUP_INTERVAL_MS` | [DocumentJobWorker.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobWorker.java) |
| `DOCUMENT_ENABLED` | [DocumentJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobsConfiguration.java) |
| `DOCUMENT_JOB_INTERVAL_MS` | [DocumentJobWorker.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobWorker.java) |
| `DOCUMENT_SERVICE_TOKEN` | [DocumentJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobsConfiguration.java) |
| `DOCUMENT_URL` | [DocumentJobsConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/DocumentJobsConfiguration.java) |
| `EXPERIENCE_CARD_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `EXPERIENCE_PUBLICATION_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `INPUT_MATERIAL_UPLOAD_ENABLED` | [UploadHttpConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadHttpConfiguration.java) |
| `INVITE_REWARD_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `INVITE_REWARD_SWEEP_MS` | [InvitationRewards.java](../../backend/src/main/java/com/autocare/platform/order/InvitationRewards.java) |
| `INVITE_VERIFIED_IDENTITY_ENABLED` | [WechatAuthController.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/WechatAuthController.java)、[ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `JWT_SECRET` | [SecurityConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/SecurityConfig.java)、[ArchiveConfiguration.java](../../backend/src/main/java/com/autocare/platform/vehicle/ArchiveConfiguration.java) |
| `KNOWLEDGE_AI_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_ALLOW_PRIVATE_HTTP` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_CLEANUP_ENABLED` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_DELAY_MS` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_ENABLED` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_PROFILE_JSON` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_RETRY_DELAY_SECONDS` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_RETRY_ENABLED` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_RETRY_LIMIT` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_TOKEN` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_EMBEDDING_URL` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `KNOWLEDGE_ENABLED` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java)、[ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `KNOWLEDGE_VECTOR_ENABLED` | [KnowledgeEmbeddingConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/KnowledgeEmbeddingConfiguration.java) |
| `LICENSE_OCR_ALLOW_PRIVATE_HTTP` | [LicenseRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/LicenseRecognitionConfiguration.java) |
| `LICENSE_OCR_ENABLED` | [LicenseRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/LicenseRecognitionConfiguration.java) |
| `LICENSE_OCR_SERVICE_TOKEN` | [LicenseRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/LicenseRecognitionConfiguration.java) |
| `LICENSE_OCR_URL` | [LicenseRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/LicenseRecognitionConfiguration.java) |
| `MERCHANT_ONBOARDING_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `MERCHANT_STAFF_ENABLED` | [MerchantStaffConfiguration.java](../../backend/src/main/java/com/autocare/platform/merchant/MerchantStaffConfiguration.java) |
| `MILEAGE_OCR_ALLOW_PRIVATE_HTTP` | [MileageRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/MileageRecognitionConfiguration.java) |
| `MILEAGE_OCR_ENABLED` | [MileageRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/MileageRecognitionConfiguration.java) |
| `MILEAGE_OCR_SERVICE_TOKEN` | [MileageRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/MileageRecognitionConfiguration.java) |
| `MILEAGE_OCR_URL` | [MileageRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/MileageRecognitionConfiguration.java) |
| `MINIO_PUBLIC_ENDPOINT` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `MYSQL_DATABASE` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `MYSQL_HOST` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `MYSQL_PASSWORD` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `MYSQL_POOL_SIZE` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `MYSQL_PORT` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `MYSQL_USER` | [IdentityDatabaseConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/IdentityDatabaseConfig.java) |
| `OPERATOR_ACCOUNT` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_ANALYTICS` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_ASSESSMENT` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_CATALOG` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_COMMUNITY_CURATE` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_COUPON` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_INVITE` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_KNOWLEDGE` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_ONBOARD` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_POINTS` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_PRICE_MONITOR` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_CAN_REVIEW` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_PASSWORD` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `OPERATOR_PHONE` | [OperatorAdminCli.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/OperatorAdminCli.java) |
| `ORDER_EXPIRY_INTERVAL_MS` | [ReservationExpiry.java](../../backend/src/main/java/com/autocare/platform/order/ReservationExpiry.java) |
| `PAYMENT_LOCAL_TEST_ENABLED` | [PaymentChannels.java](../../backend/src/main/java/com/autocare/platform/order/PaymentChannels.java)、[RefundChannels.java](../../backend/src/main/java/com/autocare/platform/order/RefundChannels.java) |
| `PAYMENT_LOCAL_TEST_QUERY_URL` | [PaymentChannels.java](../../backend/src/main/java/com/autocare/platform/order/PaymentChannels.java)、[RefundChannels.java](../../backend/src/main/java/com/autocare/platform/order/RefundChannels.java) |
| `PAYMENT_LOCAL_TEST_SECRET` | [PaymentChannels.java](../../backend/src/main/java/com/autocare/platform/order/PaymentChannels.java)、[RefundChannels.java](../../backend/src/main/java/com/autocare/platform/order/RefundChannels.java) |
| `PAYMENT_QUERY_RECOVERY_ENABLED` | [PaymentConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/PaymentConfiguration.java) |
| `PAYMENT_QUERY_RECOVERY_INTERVAL_MS` | [PaymentRecovery.java](../../backend/src/main/java/com/autocare/platform/order/PaymentRecovery.java) |
| `POINTS_AI_VERIFIED_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `POINTS_EARNING_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `POINTS_EXCHANGE_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `POINTS_RECOVERY_DELAY_MS` | [PointLedger.java](../../backend/src/main/java/com/autocare/platform/order/PointLedger.java) |
| `POINTS_VERIFIED_IDENTITY_ENABLED` | [WechatAuthController.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/WechatAuthController.java) |
| `PUBLIC_IMAGES_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `REFUND_RECOVERY_ENABLED` | [PaymentConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/PaymentConfiguration.java) |
| `REFUND_RECOVERY_INTERVAL_MS` | [RefundRecovery.java](../../backend/src/main/java/com/autocare/platform/order/RefundRecovery.java) |
| `REVIEW_SUMMARY_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `REVIEW_TEXT_PUBLICATION_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `SERVER_PORT` | [application.yml](../../backend/src/main/resources/application.yml) |
| `SERVICE_ARCHIVE_ENABLED` | [ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java) |
| `SERVICE_ARCHIVE_INTERVAL_MS` | [ServiceArchiveJobs.java](../../backend/src/main/java/com/autocare/platform/order/ServiceArchiveJobs.java) |
| `SERVICE_NOTIFICATION_DELAY_MS` | [DiagnosisNotifications.java](../../backend/src/main/java/com/autocare/platform/order/DiagnosisNotifications.java)、[ServiceNotifications.java](../../backend/src/main/java/com/autocare/platform/order/ServiceNotifications.java) |
| `UPLOAD_ALLOW_INSECURE` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `UPLOAD_CLEANUP_INTERVAL_MS` | [UploadHttpService.java](../../backend/src/main/java/com/autocare/platform/file/UploadHttpService.java) |
| `UPLOAD_HTTP_CONCURRENCY` | [UploadHttpConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadHttpConfiguration.java) |
| `UPLOAD_SIGNED_URL_SECONDS` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `UPLOAD_STORAGE_TIMEOUT_MS` | [UploadAdapterConfiguration.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdapterConfiguration.java) |
| `VIN_DECODER_PROVIDER` | [VinRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/VinRecognitionConfiguration.java) |
| `VIN_DECODE_ENABLED` | [VinRecognitionConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/VinRecognitionConfiguration.java) |
| `WECHAT_APP_ID` | [UploadAdmissionFilter.java](../../backend/src/main/java/com/autocare/platform/file/UploadAdmissionFilter.java)、[AuthTokens.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/AuthTokens.java)、[CloudRunAuthController.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/CloudRunAuthController.java)、[WechatAuthController.java](../../backend/src/main/java/com/autocare/platform/gateway/identity/WechatAuthController.java)、[SecurityConfig.java](../../backend/src/main/java/com/autocare/platform/gateway/SecurityConfig.java)、[WechatCode2SessionClient.java](../../backend/src/main/java/com/autocare/platform/gateway/wechat/WechatCode2SessionClient.java)、[WechatPhoneClient.java](../../backend/src/main/java/com/autocare/platform/gateway/wechat/WechatPhoneClient.java)、[MerchantStaffConfiguration.java](../../backend/src/main/java/com/autocare/platform/merchant/MerchantStaffConfiguration.java)、[ReservationConfiguration.java](../../backend/src/main/java/com/autocare/platform/order/ReservationConfiguration.java)、[ServiceNotificationController.java](../../backend/src/main/java/com/autocare/platform/order/ServiceNotificationController.java)、[ServiceWorkController.java](../../backend/src/main/java/com/autocare/platform/order/ServiceWorkController.java)、[ServiceWorkspaceController.java](../../backend/src/main/java/com/autocare/platform/order/ServiceWorkspaceController.java)、[TechnicianAssignmentsController.java](../../backend/src/main/java/com/autocare/platform/order/TechnicianAssignmentsController.java) |
| `WECHAT_APP_SECRET` | [WechatCode2SessionClient.java](../../backend/src/main/java/com/autocare/platform/gateway/wechat/WechatCode2SessionClient.java)、[WechatPhoneClient.java](../../backend/src/main/java/com/autocare/platform/gateway/wechat/WechatPhoneClient.java) |


## 配置入口与依赖

- [本地启动](LOCAL_SETUP.md)：使用同一个虚拟环境安装OCR协议测试和PDF导出测试依赖，再运行统一入口；Docker模式无需宿主JDK。根 `.env.example` 不会被裸Java自动加载。
- [小程序环境/页面索引](MINIAPP_REFERENCE.md)和[网络配置](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)：所有VITE变量公开，禁止放服务端token；云通道参数必须成对配置。
- MySQL执行全部65个迁移。新库初始化与已有库升级不同；Redis/RabbitMQ容器健康不代表所有消费链路通过。
- [私有存储与扫描](../../deploy/README.md)：MinIO应用级权限、ClamAV及独立上传开关；上传、扫描、业务附件绑定分别验证。
- [OCR](../../services/ocr/README.md)、[ASR](../../services/asr/README.md)、[图文/PDF](../../services/documents/README.md)、[嵌入](../../services/embedding/README.md)：各自有模型资源、许可、服务token、预算和开关；配置文本AI不会自动启用这些服务。
- [公开检索与AI引用](../api/KNOWLEDGE_VECTOR_RETRIEVAL.md)：VECTOR未就绪时明确KEYWORD降级；实际模型和模拟上游分别记录。
- [微信/短信/正式支付待办](EXTERNAL_INTEGRATIONS.md)：所需资料、负责人、步骤与验收标准；LOCAL_TEST不是正式渠道验证。
- [本地Compose](../../deploy/README.md)与[云托管部署](../../deploy/cloudrun/README.md)：本阶段没有部署生产。

## 变更核对

修改配置需同时核对源码、根 `.env.example`、Compose显式注入、云环境模板和相关手册。并非每个后端参数都会自动透传至Compose；启用前使用私有覆盖文件注入并核对容器实际配置。禁止把宿主变量、模板占位值或功能开关存在当成服务已验收。

2026-10-29静态发现变量：103；本轮新增3项VIN_LOCAL配置，合计106（清单及后续增项见下方）。有限重试默认关闭，范围和启用前置见[索引维护](../operations/KNOWLEDGE_EMBEDDING_LIFECYCLE.md)。

## 2026-10-30 新增来源及前端项

服务端新增 `VIN_LOCAL_DATA_PATH`、`VIN_LOCAL_DATA_SHA256`、`VIN_LOCAL_ALLOW_SYNTHETIC`（默认false），读取位置 `VinRecognitionConfiguration`，Compose已透传，但授权文件须另行只读挂载。原 `VIN_DECODER_PROVIDER` 现在支持 `LOCAL_LICENSED`；原默认NHTSA不变。见[详细格式与验收](../operations/VIN_LOCAL_SOURCE.md)。

前端新增 `VITE_VIN_PROVIDER`（默认NHTSA，必须与后端来源一致）、`VITE_CAR_LIFE_PARTNERS`（默认空，仅固定合作服务key和公共HTTPS目标）。前端VITE变量公开，不得放置凭据。三份环境模板以生成的MINIAPP_REFERENCE为准。
