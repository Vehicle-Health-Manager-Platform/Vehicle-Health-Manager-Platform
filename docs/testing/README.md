> 2026-10-23：[扫码接车原生阶段与复现](../progress/R9_A4D30_NATIVE_PICKUP_SCAN.md)。系统扫码返回辅助，随后真实HTTP核对本店PAID订单；不代表手机相机或接车提交。

> 2026-10-23：[取车对比原生阶段记录](../progress/R9_A4D29_NATIVE_DELIVERY.md) · [隔离复现步骤](WECHAT_DELIVERY_COMPARE_ACCEPTANCE.md)。只读对比与核销前导航独立验证，不代表实际核销或手机真机。

> 2026-10-23：[资金查询真实联调与微信原生验收](../progress/R9_A4D27_LOCAL_FINANCE_ACCEPTANCE.md)：24业务请求/110断言、8项开发者工具检查、15表完整内容不变，实际清理成功；[复现](WECHAT_LOCAL_FINANCE_ACCEPTANCE.md)。正式资金及手机真机仍待验。

> [金融恢复实际验收](../progress/R9_A4D6_FINANCIAL_RECOVERY_EXECUTION.md)：支付/退款恢复及佣金六重点接口，签名本机替身、真实HTTP和MySQL；正式资金仍待验。

> [接口覆盖核对](API_COVERAGE.md)：当前352项契约与36套所选历史证据逐项核对，317项完整四类、缺35项；生成索引不等于重跑业务接口，全量验收仍未通过。

# 测试资料

> 2026-10-18最新：[R8验收](LOCAL_R8_COMPLETION_ACCEPTANCE.md)已补齐[PR155](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/155)实际结果：2117后端/868前端、六项CI及[main复验](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/39045134951)成功。下方待CI/仅本地文字为历史记录。

> 2026-10-17最新：[R8剩余统一验收](LOCAL_R8_COMPLETION_ACCEPTANCE.md)：180次相关后端、868前端及构建/130表重放记录；本地Compose外部网络失败单列，完整后端和Compose最终以对应PR CI为准。

> [R8c1b3a公开问答后端验收](LOCAL_R8C1B3A_PUBLIC_QA_ACCEPTANCE.md)：原来源/审核撤回真实库复验、840前端/微信/H5及V059/124表记录；实际公开页面下一b3b。

> [R8c1b2b页面与恢复验收](LOCAL_R8C1B2B_PRIVATE_QA_UI_ACCEPTANCE.md)：840前端（新26）含实际页面脚本/恢复/权限/确认测试、最终微信/H5；后端/DDL未改，沿b2a38及V058/122表。

> [R8c1b2a私有问答后端验收](LOCAL_R8C1B2A_PRIVATE_QA_ACCEPTANCE.md)：真实库/权限/HTTP执行结果与814前端、微信/H5、V058/122表记录；实际页面及恢复下一b2b，整R8门禁另行执行。

> [R8c1b1精选及引用导入验收](LOCAL_R8C1B1_CURATED_EXPERIENCES_ACCEPTANCE.md)：73不同后端/811前端、最终微信/H5与V057/117表重放通过；整R8门禁及统一上传仍待后续。

> [R8c1a受控详情本地验收](LOCAL_R8C1A_EXPERIENCE_DETAIL_ACCEPTANCE.md)：54不同后端/796前端、最终微信/H5通过；无新迁移或正式分享，整R8完成前不上传。

> [R8b3c2只读价格比较验收](LOCAL_R8B3C2_PRICE_COMPARISON_ACCEPTANCE.md)：45不同后端/785前端、最终微信/H5通过；最新事件防回退及软删除凭证保护已复验，整R8完成前不上传。

> [R8b3c1价格来源本地验收](LOCAL_R8B3C1_PRICE_SOURCE_ACCEPTANCE.md)：44不同后端/771前端、最终微信/H5及V056/116表通过；整个R8完成前不上传。

> [R8b3b本店服务关系验收](LOCAL_R8B3B_CUSTOMER_RELATIONS_ACCEPTANCE.md)：43不同后端/757前端、最终微信/H5通过；无新迁移，整R8完成前不上传。

> [R8b3a经营看板本地验收](LOCAL_R8B3A_BUSINESS_DASHBOARD_ACCEPTANCE.md)：65不同后端/745前端、微信/H5及V055/115表通过；整R8完成前不上传。

> [R8b2b月结页面与范围读取验收](LOCAL_R8B2B_MONTHLY_ASSESSMENT_UI_ACCEPTANCE.md)：51不同后端/728前端、最终微信/H5、115表及原请求恢复通过。

> [R8b2a月结后端本地验收](LOCAL_R8B2A_MONTHLY_SNAPSHOT_ACCEPTANCE.md)：47不同后端/709前端回归、115表重放、不可变修订和全退原月剔除通过。

> [R8b1考核政策本地验收](LOCAL_R8B1_ASSESSMENT_POLICY_ACCEPTANCE.md)：43不同后端/709前端、微信/H5、V053/112表及独立权限/来源/恢复通过。

> [R8a3c本人兑换本地验收](LOCAL_R8A3C_OWNER_EXCHANGE_ACCEPTANCE.md)：62不同后端/701前端、最终微信/H5、111表及旧凭证保留通过，无新迁移。

> [R8a3b原子兑换验收](LOCAL_R8A3B_ATOMIC_EXCHANGE_ACCEPTANCE.md)，98不同后端/689前端、微信/H5和111表升级保留验证通过；失败修复及去重计数详见正文。

> [R8a3a兑换目录本地验收](LOCAL_R8A3A_EXCHANGE_CATALOG_ACCEPTANCE.md)；31后端/686前端、微信/H5及110表通过。实际兑换后续，整R8完成前不上传。

> [R8a2本地验收](LOCAL_R8A2_POINTS_LEDGER_ACCEPTANCE.md)；整个R8完成前保留本地，不做中间上传。

> [R8a1本地验收：46后端/671前端/105表](LOCAL_R8A1_POINTS_POLICY_ACCEPTANCE.md)，整个R8完成前无中间上传。

> 整R7已同批上传[PR153](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/153)，[统一验收](LOCAL_R7_COMPLETION_ACCEPTANCE.md)保留本地实测和网络限制，最终完整CI结果以PR当前head正文为准。

> 整R7：[最终统一验收](LOCAL_R7_COMPLETION_ACCEPTANCE.md)，664前端和构建/104表通过；完整其余后端运行中，真实上传镜像/Compose实际烟测受本地网络阻断，最终远程六项不得提前写成功。

> R7c3 第三步：[诊断消息验收](LOCAL_R7C3_DIAGNOSIS_NOTIFICATIONS_ACCEPTANCE.md)，127不同后端覆盖、648前端、微信/H5及V048/104表通过；整R7门禁仍待执行。

> R7c3 第二步：[补充订单消息验收](LOCAL_R7C3_ORDER_NOTIFICATIONS_ACCEPTANCE.md)，182相关后端覆盖、642前端、微信/H5和V047/102表通过。

> R7c3 第一步：[本机诊断草稿/恢复验收](LOCAL_R7C3_RECOVERY_ACCEPTANCE.md)，641 前端及微信/H5通过，整 R7 门禁待后续。

> 当前R7本地实施：[R7a本地阶段验收](LOCAL_R7A_OWNER_EVIDENCE_ACCEPTANCE.md)；整个R7完成后才统一上传全部代码和文档。

> 整 R6 的最终本地结果、环境限制及远程六项门禁见[统一验收](LOCAL_R6_COMPLETION_ACCEPTANCE.md)。阶段局部结果不替代最终 CI。

> R6b1本地奖励配置与独立权限验收见[记录](LOCAL_R6B1_REWARD_POLICY_ACCEPTANCE.md)，整R6完成前不上传中间更新。

> R6a 注册/归因/手机号证据、并发与月度循环见[邀请验收](LOCAL_R6A_INVITATIONS_ACCEPTANCE.md)，正式身份与真机分享不算本地通过。

> 整R5交易/服务核销/恢复与库存兼容验证见[R5b验收](LOCAL_R5B_COUPONS_ACCEPTANCE.md)，远程完整套件证据以最终PR正文为准。

> 券库存/领取R5a真实MySQL并发、回滚、权限与前端/构建验证见[验收](LOCAL_R5A_COUPONS_ACCEPTANCE.md)。

> R4完整收口的真实数据库、HTTP、499项小程序/构建与schema验证见[验收](LOCAL_R4_COMPLETION_ACCEPTANCE.md)。

> R4c3验收见[LOCAL_PLAN_BOOKING_ACCEPTANCE.md](LOCAL_PLAN_BOOKING_ACCEPTANCE.md)。

> R4c2验收见[LOCAL_VEHICLE_PLAN_EVIDENCE_ACCEPTANCE.md](LOCAL_VEHICLE_PLAN_EVIDENCE_ACCEPTANCE.md)。

> R4c1验收见[LOCAL_AI_SERVICE_PLAN_ACCEPTANCE.md](LOCAL_AI_SERVICE_PLAN_ACCEPTANCE.md)。


> R4b2b3要点/用途授权验收见[LOCAL_REVIEW_SUMMARIES_ACCEPTANCE.md](LOCAL_REVIEW_SUMMARIES_ACCEPTANCE.md)。


> R4b2b2 公开文本验收见[LOCAL_PUBLIC_REVIEW_TEXTS_ACCEPTANCE.md](LOCAL_PUBLIC_REVIEW_TEXTS_ACCEPTANCE.md)。


> R4b2b1 公开星级验收见 [LOCAL_PUBLIC_RATINGS_ACCEPTANCE.md](LOCAL_PUBLIC_RATINGS_ACCEPTANCE.md)。


- [R4b2a 门店位置与距离验收](LOCAL_MERCHANT_LOCATION_ACCEPTANCE.md)：真实MySQL、HTTP边界、离线定位、迁移/构建与真机待验范围。

- [R4a2 结构化档案验收](LOCAL_STRUCTURED_ARCHIVE_ACCEPTANCE.md)：后端定向8项、微信361项、新页面产物和未验收范围。


- [R4a1 目标提醒验收](LOCAL_ARCHIVE_REMINDERS_ACCEPTANCE.md)：后端8项定向、微信351项、原生产物与未验范围。


按交付文档 §9 与 Spec §5 保存功能、接口、兼容、性能与安全测试方案和结果。当前证据入口如下：

- [逐步执行记录](../progress/S0_EXECUTION_LOG.md)：各步离线、容器、模拟器和真实联调结果；历史“待 CI”状态以随后补充证据为准。
- [真实登录验收清单](../operations/AUTH_INTEGRATION_RUNBOOK.md)：前置条件、只读 HTTPS 预检和逐角色验收矩阵。
- [开发与上线网络说明](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)：模拟器、手机调试和常规真机的网络边界。
- [CI 定义](../../.github/workflows/ci.yml)：后端、Web、小程序、生成文件/OCR、MySQL 结构和 Compose 六项检查。
- [A4 车主决定执行记录](../progress/A4_OWNER_DECISION_EXECUTION.md)：确认/异议的 HTTP、真实 MySQL、并发、回滚及阶段 GitHub 验证结果。
- [A5.2 派工后端执行记录](../progress/A5_2_DISPATCH_BACKEND_EXECUTION.md)：六个接口的 HTTP、真实 MySQL 权限/幂等/竞争/回滚验证结果。
- [A5.3 派工界面执行记录](../progress/A5_3_DISPATCH_UI_EXECUTION.md)：商家派工与技师工单的小程序协议测试、构建与未验收项；真机与真实技师登录属 A5.5。
- [A5.4 派工安全与竞争执行记录](../progress/A5_4_DISPATCH_VERIFICATION_EXECUTION.md)：员工码/停用/会话撤销与派工接单的真实 MySQL 并发、无死锁、载荷与历史异常证据。
- [A5 本机派工与本人接单端到端验收](LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)：合成身份接真实后端容器与隔离 MySQL 的 37 项检查、复现步骤与未验收项。
- [A5.6 争议处理与恢复执行记录](../progress/A5_6_DISPUTE_RESOLUTION_EXECUTION.md)：商家处理记录、车主复核恢复的 HTTP、真实 MySQL、无死锁、投影无隐私与阶段 GitHub 验证结果。
- [A5.6 本机争议处理与恢复端到端验收](LOCAL_DISPUTE_ACCEPTANCE.md)：在派工闭环之上新增 32 项争议检查（异议建单、`43007`/`43008`、追加与重放、时间线投影、不接受保持、接受恢复并恢复派工/接单），端到端 69/69。
- [R1a 商家入驻本机端到端验收](LOCAL_MERCHANT_ONBOARDING_ACCEPTANCE.md)：真实后端容器与隔离 MySQL 上的 **92/92**——申请/待审/详情/资质受控访问/配额/批准开店/驳回重提/满额/幂等/越权/不安全文件/审计最小化，资质走真实私有上传与 ClamAV；含写接口单层信封缺陷的定位与修复。
- [R1b 门店员工与门店资料本机端到端验收](LOCAL_MERCHANT_STAFF_ACCEPTANCE.md)：真实后端容器与隔离 MySQL 上的 **136/136**——员工维护严格正文、停用即时阻断会话与刷新、技师停用连带撤销微信绑定、员工码经真实绑定接口走完签发/轮换/撤销（明文不落库）、门店资料十字段读白名单与五字段写审计、零残留；含「店员根本登录不上」等两处小程序真实缺陷的定位与修复。

小程序测试在仓库根目录运行 `npm test --workspace @autocare/miniapp`；登录预检离线测试运行 `python -m unittest scripts/test_check_auth_readiness.py -v`；后端在 `backend` 目录运行 `mvn test`，完整集成测试需 Docker。

2026-10-05 已验证开发者工具真实微信登录、本机后端和数据库，以及车主按钮进入首页；真机手机号、真实私有图片/相机和商家短信仍未验收。未执行项不以 CI 或响应替身测试代替。

2026-10-06：[本地车辆与无图档案](LOCAL_BUSINESS_ACCEPTANCE.md) 22 项真实检查通过；[私有图片联调](LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md) 26 项后端与 11 项 H5 交互通过，使用真实服务/官方库及明确的合成 PNG。物理相机、手机可信 HTTPS、第二个真实身份、手机号与短信仍独立验收。

- [本机标准服务项目验收](LOCAL_SERVICE_CATALOG_ACCEPTANCE.md)：合成项目准备、真实微信会话/H5 页面和接口检查。

2026-10-07：车主端 AI 管家在本机完成真实链路验证——上游直连 HTTP 200（`deepseek-flash`），
平台端到端 **19 项全部通过**（[AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md) 第 6 节）。
复现脚本 `scripts/verify_ai_chat_local.py`，只用在本机、用 `JWT_SECRET` 自签令牌，**不是可用的鉴权途径**。
真机提问与上游限流场景仍未验收。

2026-10-07：修复图片链路在统一传输层改造中丢失原生方法的问题——`chooseImage` / `uploadFile` /
`previewImage` 未随 `runtime` 一起透传，导致拍照、上传、预览全部不可用，且 `uploadFile` 的失败被
误报成「无法连接图片服务，请检查网络后重试」，排查方向被带偏。取图升级为官方推荐的 `chooseMedia`
（旧基础库自动回退 `chooseImage`），区分用户取消与权限被拒，并在开发者工具中如实降级为相册。
`apps/miniapp/test/api-runtime.test.js` 用**真实装配**做契约测试，防止同类漏方法再次发生。
真机验收见[真机相机拍照验收](REAL_CAMERA_ACCEPTANCE.md)，**尚未在真机执行**。

- [R6b2 本地奖励验收](LOCAL_R6B2_REWARD_ACCEPTANCE.md)：本地阶段，整R6完成后统一上传。

- [R6b3 本地验收](LOCAL_R6B3_ACCEPTANCE.md)：代码/文档本地保存，整R6验收后统一上传。

- [R7b 本地验收](LOCAL_R7B_WORKSPACE_ACCEPTANCE.md)：本地完成，整R7完成后代码与文档统一上传。

- [R7c1 本地验收](LOCAL_R7C1_NOTIFICATIONS_ACCEPTANCE.md)：本地阶段，整R7完成后代码与文档统一上传。

- [R7c2 本地验收](LOCAL_R7C2_DIAGNOSIS_ACCEPTANCE.md)：本地完成，整R7完成后代码与文档统一上传。

- [A4d15 社区图片真实验收](../progress/R9_A4D15_COMMUNITY_IMAGES_EXECUTION.md)：11接口/85请求，12不变量，元数据、审核、撤回与旧ID失效；真实存储与扫描引擎、明确合成身份/支付/扫描库边界。

- [A4d16 客户关系与价格比较](../progress/R9_A4D16_RELATIONS_PRICE_READS_EXECUTION.md)：4接口/58请求，门店隐私、稳定锚点、UNKNOWN判定与13表内容不变。

- [A4d17 券清单与草稿修订](../progress/R9_A4D17_COUPON_CATALOG_EXECUTION.md)：4个新增证据接口，9接口旅程/96请求，发布冻结、领取去重与退池/停用。

- [A4d18 认证会话](../progress/R9_A4D18_AUTH_SESSIONS_EXECUTION.md)：3个新增证据接口，45请求、失败回滚、双请求刷新单次成功及退出失效，公开证据不含凭证。

- [A4d25 OpenAPI生成一致性](../progress/R9_A4D25_OPENAPI_REPRODUCIBILITY.md)：修复4操作漂移，非写入检查、9项新增回归、完整schema链路85条命令通过。
