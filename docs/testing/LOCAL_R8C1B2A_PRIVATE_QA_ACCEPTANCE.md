# R8c1b2a 私有问答后端本地验收

2026-10-17，本子阶段后端本地验收通过。V058/122表；整R8完成前不上传，不声明整个b2或整R8门禁通过。

## 后端

最终执行日志`.cache/r8c1b2a-backend.log`，完整编译新后端后使用真实MySQL运行：

- JdbcCommunityQuestionsTest：16项独立问答真实库测试，无继承业务测试。
- JdbcOperatorAdminCommunityTest：13项（新独立权限5/继承原身份8）。
- 原精选/价格/经营/考核四个精确登录测试各1，共4。
- CommunityQuestionHttpTest：4项；CommunityQuestionNoDatabaseTest：1项。

最终38/38不同后端全部通过（新26/原12），无失败、错误或跳过，BUILD SUCCESS，2026-10-17 14:17:32（北京时间）结束。使用本地缓存Java17/Maven/真实MySQL，API1.44及沿前阶段的本机Ryuk策略，不改CI或跳过数据库。

覆盖默认UNKNOWN和独立权限/无邀请诊断混用；来源窗口与到期端点/暂停/无有效处理人员；草稿CAS/发布冻结/停止及永久政策创建回执；本人最小车型及主动文字、不含私人标识/内部证据或运营来源说明；最小队列无正文/车型，当前获派/重分配/撤权及旧回复回放拒绝；车辆转移后原本人历史与新输入限制；创建原data跨政策停止/车辆转移/通用回执过期恢复，同键不同body拒绝及当前会话复核；事件和审计失败回滚；只读无事件/回执副作用；消息分页；双分配单CAS胜者、同键并发单提问、不同管理者发布唯一版本；观察到真实控制行LOCK WAIT后推进到到期时间，拒绝新输入。

HTTP覆盖角色、本人/管理选择器、重复/未知query、精确JSON字段/重复/尾随/整数/确认/分类/键/CAS、单层no-store和依赖错误不含SQL。无数据库车主context及管理政策503。独立权限CLI显式小写/无HTTP、审计失败回滚和旧会话撤销，16字段登录及四种旧权限精确形状。

## 前端与环境

`.cache/r8c1b2a-miniapp.log`814/814通过（新3）：当前16字段/已知旧14字段、拒绝缺项或伪造类型/任意权限；新两位在令牌前发布，不继承精选/诊断，旧版本登录和退出清除。仅兼容权限消费，问答页面与本机恢复下一b2b。

微信构建/全局原生样式/无测试身份桥通过（`.cache/r8c1b2a-mp.log`）；H5通过（`.cache/r8c1b2a-h5.log`）。不声明视觉浏览器/真机验证。

真实MySQL完整初始化、V058两次显式重放、122表、原积分/券凭证保留、无问答/受理种子、两权限默认0和控制行存在通过（`.cache/r8c1b2a-schema.log`）。323操作/1139引用全部解析，16项实际私有路由与契约逐项匹配；运营16字段、原100唯一路由/页面文件/原首启动、58唯一有序SQL挂载、Compose config及原README/141追踪MD+CSV/44表init基线保持通过。最终104份变更/新增Markdown的767个相对链接、git diff --check及八份最新测试XML核对通过。实际Compose启动/四Web/完整后端仍整R8统一门禁，不能由config替代。

设计/生成时的错误均在首次编译前修复：克隆替换误引用空旧创建列表、文字校验错误类名；另有PowerShell花括号读取语法/本机不存在.m2，改目录搜索和实际缓存卷。没有把失败读取算通过；生成后的版本下限0与现有严格CAS解析一致。

见[执行](../progress/R8C1B2A_PRIVATE_QA_EXECUTION.md)、[API](../api/PRIVATE_COMMUNITY_QUESTIONS.md)、[操作](../operations/PRIVATE_COMMUNITY_QUESTIONS.md)、[下一页面](../progress/R8C1B2B_PRIVATE_QA_UI_NEXT_PLAN.md)。HEAD仍d4c7763，源码/迁移/测试/文档保留本地，整R8完成后才同批上传。
