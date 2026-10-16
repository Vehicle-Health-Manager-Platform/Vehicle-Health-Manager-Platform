# R7 需求销项

依据[原交付文档](../reference/DELIVERY_V1.md)、[全阶段规划](R7_IMPLEMENTATION_PLAN.md)及用户已确认的逐条授权和独立运营诊断规则。这里只销项R7；原141追踪、44表基线、根README保留，未将R8/R9需求提前算完成。

## R7a 本人施工证据

车主订单详情进入`order/work-evidence`，展示接车、防护、施工、故障件、完工、质检六组真实来源及时间线；前后图由用户手动查看，不虚构同角度配对。本人归属、文件来源/扫描状态/原上传者及签名前后权限复核；隐藏、切号和过期清理。

实现：`OwnerServiceEvidence`、对应controller、`owner-work-evidence.js`及页面。无新增表。访问/签名竞态与前端生命周期证据见[R7a执行](R7A_OWNER_EVIDENCE_EXECUTION.md)和[API](../api/OWNER_SERVICE_EVIDENCE.md)。

## R7b 工作台与逐条档案

门店/技师角色页进入`workspace/tasks`八类真实任务，再进入本单最小资料及既有动作页。车主`order/archive-grants`逐订单逐记录预览、授权、撤销；指纹绑定原资料版本，CAS防覆盖。未授权仅车型和本单接车/施工资料；技师须当前派工已接单。撤销、转派、解绑、失效后拒绝新读/签名。

实现：`ServiceWorkbench`、`ServiceTaskScope`、`OrderArchiveGrants`、workspace及授权页面；V044授权/审计表。[R7b执行](R7B_WORKSPACE_EXECUTION.md)记录最小字段、跨店/同店其他技师、撤权和并发验证。

## R7c 可靠消息与非实时诊断

- `ServiceNotifications`及订单收件箱：17类订单事件，唯一来源、可靠任务投递、当前身份/订单关系、分页/未读/首次已读。退款不发给技师，受理与渠道成功分开。V045来源与V047来源版本去重，见[订单补充执行](R7C3_ORDER_NOTIFICATIONS_EXECUTION.md)。
- `DiagnosisServicePolicies`、`DiagnosisCases`及独立controller：可受理/离线留言/暂停三态来源配置；车主提交重验R6当月资格，只共享主动文字及车型。独立DIAGNOSE/DIAGNOSE_MANAGE，管理队列无正文，当前分配处理人回复，状态/版本/事件和永久创建意图同事务。V046五表，见[诊断执行](R7C2_DIAGNOSIS_EXECUTION.md)。
- `DiagnosisNotifications`及独立诊断消息页：5类事务事件，捕获当时本人/处理/管理用途并在读取时复核；队列用途不会因以后分配而升级历史正文入口。V048两表及4API，见[诊断消息执行](R7C3_DIAGNOSIS_NOTIFICATIONS_EXECUTION.md)。
- 诊断显式草稿及未知写跨页面/重启原键恢复，技师文字草稿/原报工签字恢复，配置有界原键恢复，合法登录回跳和共享履约写冻结原请求已完成。精确边界见[恢复说明](R7_RECOVERY_MATRIX.md)，收尾补缺见[设计](R7_COMPLETION_DESIGN.md)。

## 交付与后置范围

累计V048、104表，85注册页面、256 OpenAPI操作；新增迁移不回填旧消息或默认开启诊断权限。既有卷先备份后按V044–V048顺序显式迁移；Compose初始化挂载不替代生产升级。

全阶段统一验证的最终结果及环境阻断只以[统一验收](../testing/LOCAL_R7_COMPLETION_ACCEPTANCE.md)为准，子阶段测试数量不相加。代码、迁移、测试和文档同批提交；上传/CI、合并和发布分开记录。R8积分/经营考核/社区来源检索接续，R9供应商、正式短信/微信身份/资金、真机与发布最后实施。
