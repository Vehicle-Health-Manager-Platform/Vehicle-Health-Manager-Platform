# R8c1b3a 公开问答后端本地验收

2026-10-17，本子阶段后端本地验收通过。V059/124表；整个R8完成前不上传。

## 后端执行和修复

初次`.cache/r8c1b3a-backend.log`在编译阶段因一处var复合声明失败，未执行测试。拆分声明后`.cache/r8c1b3a-backend-final.log`完整执行49项（40通过、3失败、6错误、0跳过）：

- JdbcPublicCommunityQuestionsTest 30项（新14公开/来源，继承私有16）。
- 新PublicCommunityQuestionHttpTest 4、PublicCommunityQuestionNoDatabaseTest 1。
- 原JdbcPublicReviewTextsTest 9及PublicReviewTextsHttpTest 5，验证共用脱敏函数原300字约束不变。

九项失败/错误均来自生产代码直接JsonNode.equals比较：数据库解析的IntNode与JDBC Long对应LongNode，规范JSON相同却被误拒来源/最后审计，合法批准409。已将源快照/预览/最后审计比较统一为规范JSON序列字符串，哈希已使用相同规范化；保留原批准成功、来源污染/撤回拒绝、CAS及全部隐私断言，不把409当成成功。第一次完整30项复验日志`.cache/r8c1b3a-source-final.log`为29通过/1并发失败：控制行等待前已形成RR快照，普通active_case查询漏看刚提交申请，唯一约束返回503。改有效申请及永久原意图查找为FOR UPDATE当前读，保持[200,409]断言；最新重编译完整30项日志`.cache/r8c1b3a-source-verified.log`为30/30全部通过，无失败、错误或跳过，BUILD SUCCESS，2026-10-17北京时间14:58:27结束。与首轮其他已过19项合并去重49不同后端全部通过（新19/原30），不累计79或109。

覆盖新原文字/身份/app指纹、未知/旧来源拒绝、不能由客户端补证据、同case最近问题对应答复、完整脱敏后截摘要及边界标识、当前本人车辆/目录/同款和原数据最小投影；独立公开consent/hash、永久原创建data、开关关闭/转车/过期恢复与当前身份；审核权限不借私有详情、被污染预览不输出原文；消息事件/原审计/正文篡改、原审批软删除、旧行回退/最后事件软删除/UUID不复活、车型索引/批准时间篡改、CAS/单有效申请并发/批准撤回竞态、审计失败回滚、只读无副作用/新补充不扩大旧范围。继承私有16项原权限/转派/车辆/恢复/事件回滚/并发/真实控制等待后重验同时复验。

HTTP覆盖角色、严格选择器/重复query/JSON字段/尾随/类型/独立确认/键、原文注入拒绝、只读POST预览及no-store、错误不泄露SQL、审核理由和修订；无数据库两读取范围503。使用缓存Java17/Maven/真实MySQL与API1.44，本机沿前阶段Ryuk设置，不改CI或跳过数据库。

## 前端、迁移和静态检查

`.cache/r8c1b3a-miniapp.log`840/840前端回归通过，无新增公开页面或前端业务变更。微信/原生全局样式/无测试身份桥和H5通过（`.cache/r8c1b3a-mp.log`、`.cache/r8c1b3a-h5.log`）。不声明视觉浏览器或真机验收。

真实MySQL V059完整初始化/两次显式重放、124表、原积分和券凭证保留、无公开授权/审核种子通过（`.cache/r8c1b3a-schema.log`）。331操作/1174引用全部解析，八实际公开问答路由逐项匹配，16登录字段、104唯一路由/页面文件/原首启动、59有序SQL挂载与Compose config、原README/141追踪MD+CSV/44表init基线保持通过；最终115份变更/新增Markdown的835个相对链接、git diff --check及五份最新测试XML核对通过。没有以config检查代替整R8实际Compose启动或完整后端门禁。

见[执行](../progress/R8C1B3A_PUBLIC_QA_EXECUTION.md)、[API](../api/PUBLIC_COMMUNITY_QUESTIONS.md)、[操作](../operations/PUBLIC_COMMUNITY_QUESTIONS.md)、[下一b3b页面](../progress/R8C1B3B_PUBLIC_QA_UI_NEXT_PLAN.md)。HEAD仍d4c7763，全部代码、迁移、测试和文档仅本地，整R8完成后才同批上传。
