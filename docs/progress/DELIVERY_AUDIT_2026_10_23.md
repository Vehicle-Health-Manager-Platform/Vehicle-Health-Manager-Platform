# 交付文档对照核查（2026-10-23）

## A4d36更新（2026-10-24）

[里程识别网关](R9_A4D36_MILEAGE_GATEWAY.md)新增默认关闭的后端权限、有界转发与人工确认候选接口，Java22/Python73分层检查通过；真实模型及小程序接线仍待验。当前353项契约、317项完整四类历史证据、36套来源和36项结论，全API仍false；下文旧352/35及各阶段结果保留历史口径，当前以生成索引和剩余验收表为准。

## A4d32实施后的更新

[A4d28交接资料](R9_A4D28_MINIAPP_HANDOVER.md)、[A4d29取车对比](R9_A4D29_NATIVE_DELIVERY.md)、[A4d30扫码](R9_A4D30_NATIVE_PICKUP_SCAN.md)、[A4d31 PDF](R9_A4D31_NATIVE_MATERIAL_UPLOAD.md)及[A4d32档案图片](R9_A4D32_NATIVE_IMAGE_UPLOAD.md)已分别形成阶段证据。图片原生五项、页面真实PNG上传、归属扫描与签名下载核对通过，无新增档案或辅助材料；选择器结果仍为工具辅助。当前历史覆盖仍317/352、36套来源、35项结论，全API仍false。正式识别、供应商、资金、手机及发布门禁继续按[剩余验收](../delivery/REMAINING_ACCEPTANCE.md)推进；下文“下一步整理手册”等保留为当时基线。

## A4d27实施后的更新

以下正文保留A4d25核查基线，当前执行结果见[A4d27](R9_A4D27_LOCAL_FINANCE_ACCEPTANCE.md)：F20已补小程序单日单渠道本地资金记录，24个真实HTTP/MySQL用例及8项微信开发者工具检查通过，渠道账单/差额仍未知。正式资金、正式身份、手机真机及生产交付仍待验。云托管登录已纠正为已有控制器、正式可信链路待验。

现为352项契约、36套历史来源、317项完整四类证据、35项结论登记；全API验收false。35项包括已替代/内部/开发端点等，不能等同35项未开发功能。PR217的8项CI已联网核实全部成功，仍未合并。下一步收口当前微信小程序手册、配置及剩余验收表；原文后续“财务尚未实现”“未联网”“316/36”等仅描述当时基线。

## 核查结论

项目处于“主要软件流程已具备，R9 接入、验收及交付收尾仍未完成”的阶段，尚不能按原交付文档宣告可正式上线运营。

核查基线为本地分支 `codex/r9-a4d25-openapi-reproducibility`，提交 `93145b6`。依据为根目录《汽车健康管家平台全栈开发交付文档.md》§2、§9、§12，以及现行代码、契约、测试与阶段报告。本次未联网核对 GitHub，未验证生产部署、真实供应商、真机及当前全量后端 HTTP；仓库中的远程 CI/合并记录只能作为历史记录。未修改业务代码。

不提供单一“项目完成百分比”：页面数量、接口历史覆盖率和正式业务可用性是不同口径。352 项契约中，316 项有四类历史执行证据（约89.8%）；这不代表项目完成89.8%，也不代表当前版本全量重跑通过。

## 范围差异

[范围决策](../DECISIONS.md)记录，2026-10-09 用户已取消 PC 运营后台交付，H5 仅作为辅助验证；必要运营功能转至小程序受限入口。严格按原文，PC/H5 产品交付形态存在差异；按当前确认范围，不应恢复成开发欠账。正式身份、外部服务和真机后置是执行顺序调整，不能视为免除交付。

## F01–F20 功能核查

以下“软件已实现”仅表示已找到源码、契约及相关历史验证，不等于正式上线验收完成。

- **F01 注册登录：软件已实现，正式链路待验。** 微信换码、手机号绑定、角色身份、会话刷新退出存在；商家/运营短信、真实手机号、技师绑定及多角色真机登录仍需正式接入和验证。证据：[认证目录](../../backend/src/main/java/com/autocare/platform/gateway/identity)、[会话验收](R9_A4D18_AUTH_SESSIONS_EXECUTION.md)。
- **F02 四种录车：部分完成。** 手动车型可用；车牌匹配查询本人已有车辆，不等于新车牌自动解析车型。VIN 格式校验、行驶证入口和人工确认流程存在，真实行驶证 OCR、VIN 解码仍缺失。证据：[录车服务](../../apps/miniapp/src/services/vehicle-entry.js)、[识别服务](../../services/ocr/src/server.py)。
- **F03 档案录入：部分完成。** 手工、附图、结构化档案、自动归档已具备；录音/试听、音频/PDF受控上传、候选整理及确认也已实现，不能继续沿用旧审计“语音入口未做”的判断。但语音转写、照片内容自动提取及真实识别尚未完成。证据：[本地材料](../../apps/miniapp/src/services/local-input-materials.js)、[上传](../../apps/miniapp/src/services/input-material-upload.js)、[候选确认](../../apps/miniapp/src/services/input-candidates.js)。
- **F04 首页驾驶舱：软件已实现，真实来源待配置验收。** 五类提醒、六指标、趋势及参考评分已存在；缺规则/读数显示未知，人工填写不能当成官方核验。证据：[健康驾驶舱](../../backend/src/main/java/com/autocare/platform/order/HealthDashboard.java)、[R4契约](../api/R4_COMPLETION.md)。
- **F05 AI 管家：部分完成。** 文本对话、档案上下文、项目建议、商家与预约衔接已实现；录音及附件采集/确认已补齐，实际 ASR、图片/PDF理解仍需接入。DeepSeek适配已存在，正式回答依赖上游配置；专业资料当前为 KEYWORD 检索，真实授权语料、嵌入与Milvus效果未验收。证据：[AI控制器](../../backend/src/main/java/com/autocare/platform/ai/AiChatController.java)、[知识检索](../../backend/src/main/java/com/autocare/platform/order/LicensedKnowledge.java)。
- **F06 服务聚合报价：软件已实现。** 目录、商家、距离排序、报价、公开评价及授权总结、方案预约已有；实际经营资料仍待运营配置。证据：[发现验收](R9_A4D2H1_SERVICE_DISCOVERY_EXECUTION.md)、[报价验收](R9_A4D2H2_MERCHANT_QUOTES_EXECUTION.md)。
- **F07 预约支付：预约已实现，正式支付未完成。** 时段、价格快照、券抵扣、支付状态及恢复已有；`WECHAT`支付明确返回503，仅有隔离 `LOCAL_TEST` 资金适配。证据：[支付渠道](../../backend/src/main/java/com/autocare/platform/order/PaymentChannels.java)。
- **F08 订单进度核销码：软件已实现。** 本人订单、进度、施工证据、核销与评价已有；新增取车对比后端、车主页面及商家核销前入口均已完成。扫码、照片和页面渲染仍待真机。证据：[取车对比](../../backend/src/main/java/com/autocare/platform/order/DeliveryCompare.java)、[车主页阶段](R9_A4D23_DELIVERY_COMPARE_PAGE.md)、[商家入口阶段](R9_A4D24_DELIVERY_COMPARE_MERCHANT.md)。
- **F09 券：软件已实现，正式通知待接。** 发行、库存、券池、领取、抵扣、释放、返券及服务券独立核销已有；站内提醒不能代替微信订阅送达。证据：[券契约](../api/COUPON_TRANSACTIONS.md)。
- **F10 邀请：软件已实现，真实新客及传播待验。** 资格、归因、月度循环、双向奖励与恢复已有；四档新增人数1/2/3/4，对应累计1/3/6/10，不能误判规则错误。正式身份、官方小程序码和原生传播效果待验。证据：[邀请契约](../api/INVITATIONS.md)、[奖励验收](R9_A4D14_INVITATION_REWARDS_EXECUTION.md)。
- **F11 积分：软件已实现。** 来源政策、签到、流水、余额、原子兑换与恢复已有；真实政策、有效事件来源和券库存需配置。证据：[积分销项](R8A_POINTS_COMPLETION.md)。
- **F12 车友圈：受控软件范围已实现。** 自动经验来源、同款内容、精选、私有问答、独立公开授权/审核/撤回及图片治理已有；真实内容、原生分享和图片人工脱敏效果仍待验。自动生成不等于自动公开。证据：[社区销项](R8_SOFTWARE_COMPLETION.md)、[图片验收](R9_A4D15_COMMUNITY_IMAGES_EXECUTION.md)。
- **F13 商家入驻：软件已实现。** 资料附件、审核、区域品类配额、员工管理已有；正式短信、激活、真实商家入驻闭环待验。证据：[入驻契约](../api/MERCHANT_ONBOARDING.md)、[材料验收](R9_A4D19_MERCHANT_ONBOARDING_FILES_EXECUTION.md)。
- **F14 接车：部分完成。** 环车照片、仪表/油量、损伤坐标、里程比较、车主确认/异议和派工阻断已有；真实仪表里程OCR、微信送达及原生拍摄未验收。证据：[接车契约](../api/PICKUP_INSPECTION.md)、[识别候选](../../apps/miniapp/src/services/input-candidates.js)。
- **F15 施工防护：软件已实现。** 防护照片归档和缺防护阻断报工已有服务端控制，真机拍摄仍待验。证据：[施工服务](../../backend/src/main/java/com/autocare/platform/order/ServiceWork.java)。
- **F16 商家订单：主要软件已实现，正式退款未完成。** 接单、派工、状态流转、核销、退款申请/处理及恢复已有；`WECHAT`退款明确返回503。证据：[退款渠道](../../backend/src/main/java/com/autocare/platform/order/RefundChannels.java)、[资金恢复验收](R9_A4D6_FINANCIAL_RECOVERY_EXECUTION.md)。
- **F17 选品定价：软件已实现。** 目录、报价、上下架、时段与历史订单快照已有，生产目录及真实门店供给待配置。证据：[报价验收](R9_A4D2H2_MERCHANT_QUOTES_EXECUTION.md)。
- **F18 技师报工：软件已实现。** 本人工单/接单、过程/故障件/完工照片、方案、配件、工时、质检签字已有；真实微信绑定、拍摄及签字交互待验。证据：[施工契约](../api/SERVICE_WORK.md)。
- **F19 考核等级：软件已实现。** 40/40/20政策、月结快照和修订已有，真实样本、来源和运营政策待验。证据：[月结契约](../api/ASSESSMENT_SNAPSHOTS.md)、[考核读取验收](R9_A4D10_ASSESSMENT_READS_EXECUTION.md)。
- **F20 运营：小程序受限入口已有，PC按决策取消。** 商家/目录/车型、券池、内容、价格与经营看板已有；财务按日按渠道对账报表尚未实现。证据：[范围决策](../DECISIONS.md)、[现行契约结论](../api/CONTRACT_RECONCILIATION.md)。

## 剩余工作及建议顺序

1. **先做无需外部服务的收尾：**明确本地按日按渠道财务查询口径并实现报表；本地收支和渠道对账需分开，缺账单时差额应显示未知。收口现有页面、最终三角色手册、遗留问题清单、配置清单与交付版本；修正下文指出的进度材料错误。最新A4d25阶段也将财务口径列为下一项。
2. **正式身份：**生产短信、真实微信/手机号/工号及商家激活，完整多角色登录和归属验收。现有认证代码应复用，不应笼统重新开发登录。
3. **识别与多模态：**行驶证OCR、VIN解码、新车牌车型来源、仪表OCR、语音ASR、档案图片提取及AI图片/PDF理解。现有候选确认流程复用；上传成功不等于识别成功。目前OCR `/recognize` 为501，候选默认适配返回 `UNAVAILABLE`，仍有实际适配开发工作。
4. **知识与AI：**正式模型配置、授权专业资料、嵌入及向量检索、来源引用和召回/回答效果验证。已有KEYWORD检索不等于原文要求的向量RAG。
5. **正式资金：**微信支付创建/回调验签/主动查询、退款与回调、资金异常恢复、渠道账单对账及必要结算。已有3%佣金与前三个月免佣账本不等于真实结算。
6. **微信消息与传播：**关键状态和到期订阅消息、官方邀请小程序码、分享、真实新客奖励验收。
7. **最终发布门禁：**性能达标复验、无高危安全结果、iOS/Android真机三条旅程、相机/录音/扫码/签字/文件/分享、HTTPS及合法域名、生产存储与病毒库、备份回滚、监控告警、线上地址与告警负责人，最后提审发布。

## 验收及交付门禁

- **接口：**352项契约，338项受保护；35套选定历史记录覆盖316项四类证据，36项无完整历史四类证据。清单的全API验收仍为false。15项已替代、1项内部事务完成、1项无成功路径及3项开发演示端点，不应算成20项待开发功能。其余10项外部依赖及标为未实现的6项还需逐项审定，其中云托管登录误标见下文。
- **覆盖率：**已有JaCoCo及70%门禁；2026-10-18核心选定范围431项执行的LINE93.43%、METHOD89.91%，BRANCH68.13%单列。旧文“没有JaCoCo证据”已失效。本轮未重跑后端覆盖率。[覆盖率口径](../testing/CORE_COVERAGE.md)。
- **性能：**已有500并发实测，错误预算通过，延迟未通过。三个场景P95为590.001/683.877/613.913ms，对应测试门限300/300/500ms；报告 `passed=false`。原文§12还要求核心P95<300ms，应保留与§9写入500ms门限的差异，不应放宽验收。报告不是“尚未压测”，也不是“压测已达标”。[实际报告](../testing/reports/R9_A4C2_PERFORMANCE_BASELINE_2026_10_18.json)。
- **恢复：**已有隔离MySQL备份恢复、升级/回退19项历史检查；不应继续写成完全未做。生产环境恢复演练、告警和真实部署仍需独立证据。[恢复报告](../testing/reports/R9_A4C1_DATABASE_RECOVERY_2026_10_18.json)。
- **安全：**已有权限/上传/扫描适配/签名/幂等/归属测试，不能直接等同最终生产安全扫描“无高危”。未确认完整正式安全报告，保留待验。
- **真机和UI：**当前构建及展示映射测试可通过；新取车对比页面阶段明确未做开发者工具/真机渲染验收，不能由构建推定页面正常。全页面视觉收口也不应根据部分页面刷新计为完成。
- **交付包：**源码、SQL、API、部署/专项指南及多批历史报告已有；尚未确认完整当前版本三角色全流程手册、全页面验收、正式域名、监控地址和告警联系人闭环。PC三断点验收按现行范围排除。

## 进度材料中的错误与过期信息

1. **云托管登录误标为无控制器。** [契约结论](../api/CONTRACT_RECONCILIATION.md)与其数据源将 `POST /api/auth/cloud-login`列为 `not-implemented`，声称仅有permitAll占位；实际[CloudRunAuthController](../../backend/src/main/java/com/autocare/platform/gateway/identity/CloudRunAuthController.java)已含该映射，并有 `WECHAT_CLOUD_RUN_ENABLED=true` 条件启用、来源/AppID/openid校验及车主/技师逻辑。[CloudRunAuthTest](../../backend/src/test/java/com/autocare/platform/CloudRunAuthTest.java)和关闭开关测试均存在。因此应改为“已有实现，云托管可信链路与正式环境待验”，而不是再开发控制器。本轮未修改该生成数据源，现行统计仍按316/36报告。
2. **历史审计存在已过期判断。** 2026-10-18审计正文仍称未做语音采集、无覆盖率、无性能实测；这些后续已做，但ASR/识别、性能达标及正式上线仍未完成。取车对比也已在A4d22–24补齐。
3. **汇总入口混合多个时点。** progress/README开头仍指向A2a，testing/README顶部仍称19套来源，后续正文又有不同日期的执行记录。应以当前源码、352项契约、35套来源及A4d25为本次核查基线；不得由“下一步A1/A2”等历史句子推断当前缺口。
4. **原文权限码存在差异。** §12写跨归属全部403，而取车对比等现行代码为隐藏资源存在性返回404；功能上仍拒绝访问，但严格验收需记录并统一约定，不应写成原文字面全部满足。
5. **本地分支不等于主干或线上。** A4d25记录PR216为草稿且依赖PR215，未合并；本次未联网复核实时状态，不能断言最新成果已进入main或发布。

## 本轮实际复核

- `npm run test --workspace @autocare/miniapp`：1024通过，0失败、0跳过。
- `python scripts/generate_openapi.py --check`：352项检查通过。
- `python scripts/reconcile_contract.py --check`：352=316历史证据+36结论，通过；该门禁验证材料一致性，不能发现上文云托管判断本身是否正确。
- `python scripts/summarize_api_coverage.py --check`：35套/316项/缺36项，通过；本命令新发起业务接口0次。
- 三组Python回归：契约结论11项、OpenAPI生成9项、历史覆盖核对32项，共52项通过。
- `npm run build:mp-weixin --workspace @autocare/miniapp`：构建成功，原生共享样式门禁及正常包无测试身份桥门禁均通过。
- 未重跑全量Java、Docker/MySQL业务HTTP、性能、安全扫描及真机，以上对应结论来自历史报告或代码核查。
