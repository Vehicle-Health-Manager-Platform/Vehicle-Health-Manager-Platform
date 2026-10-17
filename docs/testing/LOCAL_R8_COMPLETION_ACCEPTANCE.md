# R8 剩余软件闭环本地验收

2026-10-17。承接PR154，范围和外部例外见[R8软件销项](../progress/R8_SOFTWARE_COMPLETION.md)。本记录写于统一上传前；最终全量后端、实际Compose及合并结果以对应GitHub PR最终head为准。

## 已完成验证

- 前端`npm test --workspace @autocare/miniapp`：868项通过，无失败/跳过；相对840基线新增28项，覆盖实际页面脚本的完整确认、角色/账号隔离恢复、来源未知、渲染前禁止批准、权限撤回/隐藏/选车迟到清理、许可与AI用途隔离。
- 微信`npm run build:miniapp`和H5构建通过；正常微信包无原生测试身份桥，共享样式可用；四个历史Web兼容构建通过。构建不等于真机验收。
- 9类集中后端回归180次执行通过，无失败/错误/跳过：JdbcLicensedKnowledgeTest、LicensedKnowledgeHttpTest、JdbcOperatorAdminKnowledgeTest、JdbcPublicImagesTest、PublicImageRendererTest、PublicImageHttpTest、PublicCommunityQuestionHttpTest、JdbcPublicCommunityQuestionsTest、JdbcCouponTransactionsTest。含继承的旧回归，不累加此前阶段重复运行作新用例。
- MySQL完整初始化、迁移至V061、重复迁移及旧业务合成凭证保留通过，累计130表；新增源无正式语料/同意种子，can_knowledge默认关闭。
- 原始Compose配置校验61个源与目标均唯一、编号对应、完整有序；350个OpenAPI操作、1247个schema引用可解析；108个页面唯一，首启动仍merchant/commissions。
- gstack browse使用独立任务缓存和BROWSE_PARENT_PID=0后可运行；IPv4本地H5检查了资料、图片、公开问答和运营资料的未登录/无权限渲染。仅有已有vue-router导入弃用警告，无新运行错误。实际已登录流程由脚本setup行为和真实HTTP/DB测试覆盖，不声称浏览器已完成真机或已登录端到端。

## 问题与修复记录

1. b3b新增页面误占首启动，恢复原顺序；旧840回归加新行为测试通过。
2. 图片源指纹包含LocalDateTime时默认mapper报错，改为明确UTC时间文本；来源变化后须重新授权，不因恢复CLEAN而认定原确认仍有效，补新UUID断言。
3. 第二轮有一次本地MySQL连接中断，随后完整相关回归通过；不修改断言或跳过事务测试。
4. 公开批准事件复用该行published_at，避免跨秒写入使刚批准记录无法核验；来源失效后审核人不得新读取副本。
5. 浏览器CLI父进程看门狗及IPv6本地服务导致空白/超时，改用独立状态、持久看门狗设置和IPv4回环服务后复核；失败空白截图不计成功。
6. 最后补充模型索引篡改测试时，与已有车型2重复插入；修正为复用既有合成车型，保留篡改拒绝与恢复后读取断言。修正后最终9次边界执行全部通过：图片车型/PNG篡改1、无数据库503路径1、图片HTTP4及渲染3，无失败/错误/跳过。
7. 本机python3指向Windows应用别名而退出49，仅在忽略缓存中桥接现有Python；未改变系统配置。实际Compose随后因Docker Hub鉴权域名连接失败未完成，属于外部网络阻碍，不计通过。GitHub原完整Compose门禁保留。

## 最终门禁

统一上传全部代码/迁移/测试/文档后，须在同一最终head核验web、miniapp、backend、schema-and-ocr、schema-mysql、compose-smoke六项成功再合并。相关180次执行不替代全量后端，历史PR154的2008项也不是本轮全量结果。正式授权语料、Milvus/嵌入效果和R9外部交付另行验收。
