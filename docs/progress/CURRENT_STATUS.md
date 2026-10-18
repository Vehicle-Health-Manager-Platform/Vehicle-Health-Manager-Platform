# 当前进度

> **2026-10-18 A2b实施**：受控MP3/PDF上传、独立材料表、扫描/用途/车辆授权及页面显式上传已编码；前端899项、后端相关64项及最终定向7项、微信/H5、完整迁移与13项模拟器均通过，正在阶段上传。当前352操作/131表/62挂载，服务默认关闭。下一A3候选确认；正式设施/识别/真机仍后置。[执行](R9_A2B_CONTROLLED_MATERIAL_EXECUTION.md)。

> **2026-10-18 A1/A2a已上传**：预览为[PR156](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/156)，A1设计及A2a本机录音/试听/图片PDF选择、隔离与AI/档案页接入为[PR157](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/157)。最终本地891/891、微信/H5构建及页面装配通过；六项远程CI尚未全部结束，保持草稿。无新增API/迁移；下一A2b受控媒体上传，再A3识别确认和B段真实外部效果，不自动合并/部署。

> **2026-10-18 本轮续做**：用户要求按阶段实施、同步文档并上传GitHub。三角色只读预览及默认入口修复已本地完成，874项小程序测试、普通微信/演示构建及13项模拟器检查通过，正在作为独立阶段交付；接下来A1录入设计及A2a录音/附件本机采集。真实识别、身份资金及发布保持后置。参见[预览验收](../testing/LOCAL_ROLE_PREVIEW_ACCEPTANCE.md)和[执行队列](REMAINING_EXECUTION_ORDER_2026_10_18.md)。阶段PR与最终head CI另记，不把上传或演示当正式验收。

> 2026-10-18核对：R8用户确认的软件范围已通过[PR155](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/155)合并main（`8373341`），代码/迁移/测试/配置/阶段文档均在仓库。最终业务提交2117后端、868前端和六项CI通过；[合并后main六项CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/39045134951)亦全部成功。当前项目与该远端业务文件树完全一致，没有遗漏代码。本次仅补齐此前待上传/待CI的文档状态，详见[仓库核对](REPOSITORY_PROGRESS_SYNC_2026_10_18.md)。无正式授权语料/嵌入服务，Milvus效果及R9仍待独立验收。

## 历史记录（以下状态为当时记录）

> 2026-10-17最新：用户确认范围内的R8剩余软件闭环已实现，见[R8销项](R8_SOFTWARE_COMPLETION.md)、[统一计划](R8_REMAINING_COMPLETION_PLAN.md)和[本地验收](../testing/LOCAL_R8_COMPLETION_ACCEPTANCE.md)。b3b公开页面、c2图片副本治理、c3许可配置/关键词检索/AI引用已接通；180次集中相关后端与868前端通过，V061/130表、350操作/108页/17字段。正式授权语料/嵌入服务暂无，Milvus效果未验收；R9最后。全量后端及实际Compose以本次统一上传PR最终CI为准。下方为历史记录。

> **2026-10-17最新上传指示**：用户要求立即上传当前更新并合并，取代此前整R8完成后上传的限制。正在整理包含全部代码/迁移/测试/文档的main汇总PR，并纳入尚未进入main的R5/R6/R7提交；六项完整CI通过后合并。[本次范围与未完成清单](R8_PARTIAL_UPLOAD_MERGE_2026_10_17.md)。R8仍部分完成，下一b3b、之后c2/c3及整阶段验收；R9最后。下方“仅本地/不得上传”为当时历史记录。

> **2026-10-17 R8c1b3a公开问答后端本地完成**：[执行](R8C1B3A_PUBLIC_QA_EXECUTION.md)、[验收](../testing/LOCAL_R8C1B3A_PUBLIC_QA_ACCEPTANCE.md)。新消息原文/身份/app指纹、具体问题/答复预览和独立公开consent、最小can_review审核/撤回及本人同款读取八API、永久原申请回执与最后凭证防回退已实现；旧/测试记录不补正式来源，独立公开开关默认关闭。修复JSON数值节点比较误拒及RR锁等待后旧快照漏看有效申请，最新完整30/30通过，与其他19项合并49不同后端（新19/原30）。840前端、微信/H5、V059/124表重放及旧凭证保留、331操作1174引用/104页/59挂载/16字段和原基线保持通过。下一[b3b公开页面/确认/恢复及受控引用](R8C1B3B_PUBLIC_QA_UI_NEXT_PLAN.md)，再b3/c1b销项、c2图片/c3许可资料及整R8门禁；不声称公开页面或整个R8完成，所有代码/迁移/测试/文档仅本地，无上传/合并/发布。下方保留历史。

> **2026-10-17 R8c1b2b及私有b2本地完成**：[页面与恢复执行](R8C1B2B_PRIVATE_QA_UI_EXECUTION.md)、[验收](../testing/LOCAL_R8C1B2B_PRIVATE_QA_UI_ACCEPTANCE.md)、[整b2销项](R8C1B2_PRIVATE_QA_COMPLETION.md)。车主新建/历史及角色详情、管理受理政策/最小队列与当前获派四实际页，严格API、完整原文字/目标/版本确认、角色账号问答隔离持久恢复、精确登录落点及切号隐藏/撤权迟到清理已完成。840前端（新26）、最终微信/H5、323操作1139引用/104页面/58挂载/16登录字段及原基线保持通过。无后端/DDL变更，沿b2a38真实库/HTTP及V058/122表重放；不重复累计。下一[b3独立公开授权/审核/撤回](R8C1B3_PUBLIC_QA_NEXT_PLAN.md)，再c2图片/c3许可检索和整R8门禁；整个c1b与R8尚未销项，所有源码/迁移/测试/文档仅本地，HEAD仍d4c7763，无上传/合并/发布。下方保留历史。

> **2026-10-17 R8c1b2a后端本地完成**：[私有问答执行](R8C1B2A_PRIVATE_QA_EXECUTION.md)、[验收](../testing/LOCAL_R8C1B2A_PRIVATE_QA_ACCEPTANCE.md)。独立两权限/受理来源、本人提问/补充/分配/当前获派答复/关闭及CAS/原子事件/永久创建回执已接通；队列无正文，车辆转移不转移原提问者历史，无R7诊断/邀请资格混用。38不同后端（新26/原12）、814前端（新3登录兼容）、微信/H5、V058/122表重放、323操作1139引用/16登录字段/100页/58挂载及原基线保持通过。下一[b2b页面与本机恢复](R8C1B2B_PRIVATE_QA_UI_NEXT_PLAN.md)，整个b2尚未销项；再b3公开授权审核、c2图片和c3许可资料检索及整R8门禁。全部代码/迁移/测试/文档仅本地，HEAD仍d4c7763，无上传/合并/发布。下方保留历史。

> **2026-10-17 R8c1b1本地完成**：[运营精选及引用导入执行](R8C1B1_CURATED_EXPERIENCES_EXECUTION.md)、[验收](../testing/LOCAL_R8C1B1_CURATED_EXPERIENCES_ACCEPTANCE.md)。默认关闭独立精选权限、当前获批来源/哈希与冻结版本、排序/未来窗口/停止、永久创建回执和实际运营页；本人同款精选与手动引用导入已接通，来源说明不公开、无虚构热度。73不同后端（新18/原55）、811前端（新15）、最终微信/H5、V057/117表重放、307操作1076引用/100路由/57挂载/14字段及原基线保持通过。下一[b2私有问答](R8C1B2_PRIVATE_QA_NEXT_PLAN.md)，已确认独立运营非实时答复与默认私有，不重复询问。后续b3公开授权审核、图片、许可资料检索及整R8统一门禁仍待完成；所有代码/迁移/测试/文档仅本地，HEAD仍d4c7763，无push/PR更新。下方保留历史。

> **2026-10-17 R8c1a本地完成**：[受控经验详情执行](R8C1A_EXPERIENCE_DETAIL_EXECUTION.md)、[验收](../testing/LOCAL_R8C1A_EXPERIENCE_DETAIL_ACCEPTANCE.md)。新OWNER单条GET、列表/详情共用当前同款授权/审核/可信来源与目录有效性，旧UUID撤回/重审不复活；同款现有页详情/复制应用内入口、AI摘要引用、精确登录返回和切号/换车/车型/隐藏清理已接通。54不同后端（新9/原45）、最终796前端、最终微信/H5、301操作1048引用/99路由/56挂载与原基线保持通过。无新迁移，仍V056/116表。下一[R8c1b同款热门/结构化问答/应用内分享](R8C1B_COMMUNITY_NEXT_PLAN.md)，还需图片逐项公开授权、许可资料检索及整阶段门禁；全R8完成后同批上传全部源码/迁移/测试/文档，当前HEAD仍d4c7763，无push/PR更新。下方保留历史。

> **2026-10-17 R8b3c2及R8b本地完成**：[只读价格比较执行](R8B3C2_PRICE_COMPARISON_EXECUTION.md)、[验收](../testing/LOCAL_R8B3C2_PRICE_COMPARISON_ACCEPTANCE.md)、[价格监控销项](R8B3C_PRICE_MONITORING_COMPLETION.md)、[R8b销项](R8B_OPERATIONS_COMPLETION.md)。同一观察时点核验当前报价/冻结依据/最后原凭证及窗口，含边界精确比较、未知不造正常、偏离仅需核对；独立权限及两GET/两实际页、回退/软删除证据保护已完成。45不同后端（新15/原30）、785前端、最终微信/H5、300操作1044引用/99路由/56挂载及原基线保持通过。无新迁移，沿V056/116表。下一[R8c1a获批同款经验受控详情与引用](R8C1A_EXPERIENCE_DETAIL_NEXT_PLAN.md)，后续R8c及整阶段门禁完成后统一上传全部源码/迁移/测试/文档；当前HEAD仍d4c7763，无push/PR更新。下方保留历史。

> **2026-10-16 R8b3c1本地完成**：[报价版本价格来源执行](R8B3C1_PRICE_SOURCE_EXECUTION.md)、[验收](../testing/LOCAL_R8B3C1_PRICE_SOURCE_ACCEPTANCE.md)。用户确认按具体版本/内容单位车型范围/来源/窗口及含边界区间；独立权限、六API、真实来源哈希、草稿CAS/发布冻结/停止、永久原键及实际配置页已实现。44不同后端/771前端、最终微信/H5与V056/116表重放通过。当前仅配置，下一[c2只读比较](R8B3C2_PRICE_COMPARISON_NEXT_PLAN.md)，再R8b销项/R8c/整阶段门禁；全部代码和文档仅本地，无push/PR更新。下方保留历史。

> **2026-10-16 R8b3b本地完成**：[本店最小服务关系执行](R8B3B_CUSTOMER_RELATIONS_EXECUTION.md)、[验收](../testing/LOCAL_R8B3B_CUSTOMER_RELATIONS_ACCEPTANCE.md)。用户确认店长本店最小汇总与车型/服务时间、不显示联系或车辆标识、原R7逐单逐条授权；两只读API和两实际页面、来源未知及范围/撤销校验已实现。43不同后端/757前端、最终微信/H5、292操作/96路由通过；无新迁移，仍115表。下一[来源价格监控](R8B3C_PRICE_MONITORING_NEXT_PLAN.md)，再R8b销项/R8c/整阶段门禁；全部R8代码和文档仅本地，无push/PR更新。下方保留历史。

> **2026-10-16 R8b3a本地完成**：[只读经营看板执行](R8B3A_BUSINESS_DASHBOARD_EXECUTION.md)、[验收](../testing/LOCAL_R8B3A_BUSINESS_DASHBOARD_ACCEPTANCE.md)。独立经营权限/三GET/运营与本店店长两页，八指标来源与正式/测试/未知、跨月退款负差额和独立冻结月结已实现；65不同后端/745前端、最终微信/H5及V055/115表重放通过。下一[客户关系/主动授权](R8B3B_CUSTOMER_AUTHORIZATION_NEXT_PLAN.md)，再价格监控/R8c/整阶段门禁；所有R8代码和文档仅本地，无push/PR更新。下方保留历史。

> **2026-10-16 R8b2b/R8b2本地完成**：[月结查看与恢复执行](R8B2B_MONTHLY_ASSESSMENT_UI_EXECUTION.md)、[考核销项](R8B2_ASSESSMENT_COMPLETION.md)。服务器当前版本/历史、店长本店/员工本人范围、七读取API及运营确认/持久恢复和两实际页面已接通。51不同后端/728前端、最终微信/H5及115表重放通过；无新迁移。下一[R8b3经营、客户授权与价格监控](R8B3_BUSINESS_NEXT_PLAN.md)，再R8c及整阶段门禁；整个R8完成前全部代码和文档留本地，无push/PR更新。下方保留历史。

> **2026-10-16 R8b2a本地完成**：[月度快照与计算后端执行](R8B2A_MONTHLY_SNAPSHOT_EXECUTION.md)、[验收](../testing/LOCAL_R8B2A_MONTHLY_SNAPSHOT_ACCEPTANCE.md)。已确认次月结算/全退原月剔除/来源未知/修订留旧；真实资格与双券/正式完成证据、一致快照、CAS/永久恢复及四运营API已实现，47不同后端/709前端回归和V054/115表通过。下一[R8b2b月结页面与本店读取](R8B2B_MONTHLY_ASSESSMENT_UI_NEXT_PLAN.md)，本步不声明页面或整个R8已完成；整R8完成前全部代码和文档留本地，无push/PR更新。下方保留历史。

> **2026-10-16 R8b1本地完成**：[门店考核来源政策执行](R8B1_ASSESSMENT_POLICY_EXECUTION.md)、[验收](../testing/LOCAL_R8B1_ASSESSMENT_POLICY_ACCEPTANCE.md)。独立can_assessment、真实门店、未来完整北京月份、40/40/20固定权重和来源目标/必需证据/等级、CAS/发布冻结/停止及永久原请求恢复已实现。43不同后端/709前端、微信/H5和V053/112表通过；配置不生成成绩，未知不补历史。下一[R8b2月份样本与计算](R8B2_ASSESSMENT_SAMPLES_NEXT_PLAN.md)；整个R8完成前全部代码和文档仅本地，无push或PR更新。下方保留历史。

> **2026-10-16 R8a3c/R8a本地完成**：[本人兑换执行](R8A3C_OWNER_EXCHANGE_EXECUTION.md)、[积分闭环销项](R8A_POINTS_COMPLETION.md)。本人目录/真实资格、冻结报价确认、账号隔离原请求恢复、成功记录/券入口已接通。62不同后端/701前端、微信/H5及111表重放通过。下一[R8b1考核规则来源与配置](R8B1_ASSESSMENT_POLICY_NEXT_PLAN.md)；整个R8完成前不上传。

> **2026-10-16 R8a3b本地完成**：[原子兑换执行](R8A3B_ATOMIC_EXCHANGE_EXECUTION.md)。已核验余额扣分、模板累计限额、本人券/POINTS来源/库存/审计与永久回执同事务，修复真实末券并发间隙锁死锁，补收支兼容。98不同后端/689前端、微信/H5、111表及旧凭证保留通过。下一[R8a3c本人兑换确认与恢复](R8A3C_OWNER_EXCHANGE_NEXT_PLAN.md)；整个R8完成前不上传。

> **2026-10-16 R8a3a本地完成**：[兑换目录执行](R8A3A_EXCHANGE_CATALOG_EXECUTION.md)。已确认双券种、模板跨版本累计限额和返分规则；独立积分运营的真实精选来源/价/限额/窗口、版本冻结及恢复已实现。31不同后端/686前端、微信/H5及110表重放通过。下一R8a3b原子扣分发券，尚未开放兑换；整R8完成前不上传。

> **2026-10-16 R8a2本地完成**：可信会话来源、三任务源事务/IO后证据、唯一冻结事件、可靠积分账本与系统审计、永久原日期签到请求、本人已核验积分/任务/分页明细及恢复已实现。旧余额保留待核对，不导入；获分/正式身份/AI来源开关默认关闭，兑换仍R8a3，正式接入/真机仍R9。54个不同后端、679前端、最终微信/H5及V050/109表重放通过，见[执行](R8A2_POINTS_LEDGER_EXECUTION.md)、[验收](../testing/LOCAL_R8A2_POINTS_LEDGER_ACCEPTANCE.md)。下一[R8a3可信积分兑换](R8A3_POINTS_EXCHANGE_NEXT_PLAN.md)。**整个R8完成后再统一上传全部代码、迁移、测试和文档；当前没有R8 push或PR更新。** 下方保留历史。

> **2026-10-16 R8a1本地完成**：用户已确认三类积分任务和来源配置/未知/不补历史/TEST隔离；独立can_points、版本化任务来源/分值/窗口、草稿CAS/发布冻结/停止、永久创建意图和受限配置页已实现。46个不同后端用例、671前端、微信/H5、V049/105表重放及86路由/261操作通过，见[执行](R8A1_POINTS_POLICY_EXECUTION.md)、[验收](../testing/LOCAL_R8A1_POINTS_POLICY_ACCEPTANCE.md)。下一[R8a2可信任务事件与账本](R8A2_POINTS_LEDGER_NEXT_PLAN.md)；配置不发积分/券、不导入旧余额，兑换尚未开放。**整个R8完成后才统一上传全部代码/迁移/测试/文档，本步没有R8上传或PR更新。** R7已上传PR153最终d4c7763六项成功（后端1230/前端664），未合并或发布。下方保留历史。

> **整R7已统一上传 [PR153](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/153)**：全部功能及收尾代码、V044–V048迁移、测试和文档共168个文件，业务提交c856eb9，R7子阶段无中间上传。664前端、微信/H5/四兼容Web及104表重放通过；上传前本地其余后端快照499项通过，真实上传/Compose受Docker Hub阻断，最终完整六项CI与实际后端数量以PR153当前head正文为准，不将进行中写成功。未合并或发布。见[整R7销项](R7_REQUIREMENT_CLOSURE.md)、[统一验收](../testing/LOCAL_R7_COMPLETION_ACCEPTANCE.md)和[下一R8](R8_IMPLEMENTATION_PLAN.md)。下方保留阶段历史。

> **2026-10-16 整R7收尾核对与补缺已完成**：登录回跳、技师显式草稿/原报工签字恢复、配置有界恢复和共享履约未知正文冻结已接入；664前端、微信/H5、四兼容Web、V048/104表重放通过。完整其余后端运行中，真实上传/Compose实际烟测受Docker Hub阻断，不能称全套本地通过。见[需求销项](R7_REQUIREMENT_CLOSURE.md)、[执行](R7_COMPLETION_EXECUTION.md)、[统一验收](../testing/LOCAL_R7_COMPLETION_ACCEPTANCE.md)和[恢复边界](R7_RECOVERY_MATRIX.md)。全部R7完成后同批上传所有代码/迁移/测试/文档，当前尚未上传；下一[R8积分先行](R8_IMPLEMENTATION_PLAN.md)，最后R9。下方保留阶段历史。

> **R7c3 第三步本地完成**：五类诊断事务消息、独立来源/捕获用途、车主/当前获派处理/管理队列隔离及四API和消息页已实现。127个不同后端覆盖、648前端、微信/H5、V048/104表、85路由/256操作通过，见[执行](R7C3_DIAGNOSIS_NOTIFICATIONS_EXECUTION.md)。下一[整R7销项与统一门禁](R7_COMPLETION_NEXT_PLAN.md)，全部通过后才统一上传所有代码、迁移、测试和文档；当前无R7上传、合并或发布。

> **R7c3 第二步本地完成**：关单、退款与异议新增十一类事务事件，来源版本去重、退款排除技师、受理/渠道成功区分及当前接收人读取已落地。182个不同后端覆盖、642前端、微信/H5及V047/102表重放通过，见[执行](R7C3_ORDER_NOTIFICATIONS_EXECUTION.md)。下一步[诊断专属消息](R7C3_DIAGNOSIS_NOTIFICATIONS_NEXT_PLAN.md)，再整R7统一验收；整个R7完成后才上传全部代码和文档。

> **R7c3 第一步本地完成**：诊断本机显式草稿、账号/业务隔离、权限复核后的跨页面与重启原键恢复已实现，641 前端测试及微信/H5构建通过，见[执行](R7C3_RECOVERY_EXECUTION.md)。下一步补诊断、退款及异议消息，再做整 R7 门禁；整个 R7 完成前不上传。

> **2026-10-15 R7c2本地完成**：用户确认的独立运营诊断、三态来源配置、资格提交复核、本人留言/当前分配回复与关闭、专用事件/持久意图及四个页面已实现。最终114个不同后端用例覆盖/626前端、微信H5、102表迁移重放通过，见[R7c2执行](R7C2_DIAGNOSIS_EXECUTION.md)。下一R7c3持久恢复与跨业务消息接入；整个R7完成前不进行中间上传，全部代码与文档保留本地。正式供应商/身份资金/真机发布仍R9。下方保留阶段历史。

> **2026-10-15 R7c1本地实施**：六类履约事务事件、可靠去重投递、三身份收件箱/未读/已读和消息页已接好，相关140后端覆盖（修复旧接车夹具后重跑16项）、614前端、微信H5、97表迁移和Compose配置检查通过，本地已完成。见[R7c1执行](R7C1_NOTIFICATIONS_EXECUTION.md)。用户已确认[R7c2运营诊断方案](R7C2_DIAGNOSIS_NEXT_PLAN.md)，诊断及持久恢复仍待实施。整个R7完成前不上传。

> **2026-10-15 R7开始**：用户要求整R7完成后才统一上传。按[整R7计划](R7_IMPLEMENTATION_PLAN.md)先实施[R7a本人施工证据](R7A_OWNER_EVIDENCE_EXECUTION.md)，再R7b工作台/最小档案、R7c可靠消息/非实时留言。R7a本人施工证据、逐图权限复核、手动前后对比与到期/切号清理已完成，31后端/595前端和微信H5构建通过；R7b也已本地完成：逐条授权/源版本/CAS及工作台，47相关后端/606前端、微信H5与96表重放通过，见[R7b执行](R7B_WORKSPACE_EXECUTION.md)。当前代码与文档仅本地，下一R7c可靠消息/非实时诊断/持久恢复，整R7完成后再上传。R6已上传PR152/head956758d，967后端/585前端、六项CI成功；下方保留其源码准备历史。

> **2026-10-15 整R6源码收口**：R6a与R6b1/b2/b3的约定业务已实现，最后修复资格跨月时点、海报预览/相册临时图生命周期和运营草稿连续保存。见[整R6销项](R6_REQUIREMENT_CLOSURE.md)、[统一验收](../testing/LOCAL_R6_COMPLETION_ACCEPTANCE.md)。585前端、微信/H5、四兼容Web及95表迁移通过；后端其余全套运行中，真实上传镜像与Compose受本地Docker Hub连接阻断，最终六项远程CI须在同批上传后核验。所有代码、迁移、测试和文档统一交付，最终提交/上传/CI以PR152正文为准。下一R7a车主施工证据，实际留言R7c、考核R8，正式接入/真机发布R9。下方保留阶段历史。

> **2026-10-15 R6b2/R6b3 本地收口**：资格时点冻结政策、事件/受益人唯一发券、公共限领兼容、原子库存/审计与恢复、双方记录；当月诊断解锁配置、首页进度/实际奖励提示、AI资格和本地海报已实现。用户已确认阈值/政策窗口/来源说明及停用跨月重验。87个不同后端用例、582前端、95表迁移与微信/H5构建通过，含真实锁等待跨原券截止回归。见[R6b3执行](R6B3_IMPLEMENTATION_PLAN.md)、[验收](../testing/LOCAL_R6B3_ACCEPTANCE.md)。**下一步整R6全量验收与统一上传**；当前所有代码和文档保留本地，尚未推送。正式渠道/官方分享/资金/真机仍R9，实际留言服务R7。

> **2026-10-15 R6b1 本地实施**：独立邀请运营权限、OWNER双方/STAFF新客奖励配置、真实精选券来源、草稿/发布冻结/停止与唯一发布政策、受限配置页已完成；36个不同后端用例、561前端、92表迁移重放及微信/H5构建通过。见[R6b计划](R6B_NEXT_PLAN_2026-10-15.md)、[配置执行](R6B1_REWARD_POLICY_EXECUTION.md)。按用户新要求，后续所有代码和文档留本地，整R6完成后统一上传。下一R6b2可靠发奖，再R6b3海报/解锁；正式渠道/资金/真机发布仍R9。

> **R6a 最新实施**：首次注册不可变 owner/staff/merchant 归因、随机推广码、同 app 服务端手机号资格证据和北京时间月度累计1/3/6/10循环已实现；V040共91表、73页、9项增长接口。见[执行与下一R6b](R6A_INVITATIONS_EXECUTION.md)、[验收](../testing/LOCAL_R6A_INVITATIONS_ACCEPTANCE.md)。正式新客开关默认关闭，UNKNOWN/TEST不计数，奖励/海报/解锁接续R6b，正式渠道和真机发布仍R9。整阶段代码/迁移/测试/文档完成后同批上传，最终GitHub证据以阶段PR正文为准。

> **R5 远程最终验收**：[PR151](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/151)已统一上传全部代码与文档，head37a2f5a；[CI38524736378](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/38524736378)六项成功，后端811/811、前端535/535。OPEN未合并；下方保留源码准备历史。

> **R5最新收口**：R5a发行/领取与R5b单券现金预约、原子冻结/消费/释放/全退返原券、独立SERVICE授权/核销/免佣、持久恢复均已实施；V039共88表，小程序70页，OpenAPI199操作中R5实际16项。见[R5闭环](R5_REQUIREMENT_CLOSURE.md)和[R5b执行](R5B_COUPONS_EXECUTION.md)，本地验收/最终上传CI分列。下一R6邀请；正式供应商/身份资金/真机发布仍最后R9。

> **2026-10-14 R5a最新实施**：新增券发行库存/原子领取/独立运营池/本人券与真实截止提醒，67页、V038共84表、9项实际券接口；本地后端62项不同用例与前端514项通过，微信/H5构建通过。见[执行](R5A_COUPONS_EXECUTION.md)与[本地验收](../testing/LOCAL_R5A_COUPONS_ACCEPTANCE.md)，远程上传/CI最终head记录在PR正文。下一R5b订单用券/冻结释放/返券/独立核销，正式外部接入仍R9。下方保留历史核对。

> **2026-10-14最新核对**：#141–#149已全部合入main1796f29，合并后六项CI均成功。R4健康来源/人工完整档案/五类提前提醒、服务发现/公开评价及AI预约不再列为未实现。真实剩余为R5券、R6邀请、R7三端证据/工作台/站内消息与恢复、R8积分/经营考核/社区及来源知识检索；最后R9供应商识别、正式短信/微信身份/资金、真机和发布。见[原文与代码清点](DELIVERY_GAP_AUDIT_2026-10-14.md)、[最新阶段规划](REMAINING_DELIVERY_PLAN_2026-10-14.md)和[R5下一步](R5_COUPONS_NEXT_PLAN_2026-10-14.md)。本轮仅核对规划，未新增业务实现；后续每阶段编辑的代码、迁移、测试和文档完成后同批上传。下方保留当时历史。

> **2026-10-13 R4完整收口**：来源可配置六指标规则/本人确认/参考分/可比趋势/技术MUST、完整人工证件档案、五类与提前提醒、公开星级报价排序及症状搜索交接均已实施。按用户最新要求，全部R4完成后才统一上传**代码与文档**，无中间推送；本地后端748项覆盖通过（环境修复定向重跑）、小程序499/499、微信/H5构建、80表重放通过。最终GitHub单次全量及六项CI见[执行](R4_COMPLETION_EXECUTION.md)及统一PR正文。下一 **R5券**；生产来源未配置显示未知，正式识别/身份资金/真机发布仍最后R9。下方保留历史，不用于判断当前R4欠项。

> **2026-10-13 R4c3已实施**：一次性本车/项目交接、独立方案预约页、当前报价/本车证据/时段同快照与提交复核、跨页时段检查、价格/规则变化重确认及未知创建结果同键回执。无迁移、77表；验证和GitHub证据见[执行](R4C3_PLAN_BOOKING_EXECUTION.md)。前序#147六项CI通过未合并；下一R4d有来源技术规则/读数有效期与可比趋势，正式接入/真机发布最后R9。

> **2026-10-13 R4c2已实施**：本人车辆证据、六指标当前来源、有效获批同款摘要及精确项目/车型适用性规则；未知不猜测，独立can_catalog维护、版本/审计/幂等及方案页隔离。V036无规则回填，77表；验证与GitHub状态见[执行](R4C2_VEHICLE_PLAN_EVIDENCE_EXECUTION.md)及PR正文。前序#146六项CI通过、未合并；下一R4c3更细报价/时段衔接，正式识别/身份资金/真机发布仍最后R9。

> **2026-10-13 R4c1已实施**：有效目录结构化候选、车主明确指定/模型推荐可选、第三方问题用途确认、规范问题证据及严格输出校验/降级、生成后会话/目录复核、独立页面和现有项目→报价→预约入口。无迁移、默认关闭；验证/上传证据见[执行](R4C1_AI_SERVICE_PLAN_EXECUTION.md)及阶段PR。下一R4c2车辆证据/适用性/同款获批经验。前序#145六项CI通过、未合并；正式识别/身份资金/真机发布仍R9。


> **2026-10-13 R4b2b3已实施**：独立第三方AI用途授权/撤销与批准版本绑定、完整原句选取/严格来源校验/明确降级、有限样本与事务外IO/生成后来源复核、本人授权与门店主动要点页。模型为替身验收、默认关闭开关；最终测试与上传状态见[执行](R4B2B3_REVIEW_SUMMARIES_EXECUTION.md)及阶段PR。下一R4c结构化AI方案到预约。前序#144六项CI通过，未合并；正式识别/身份资金/真机发布仍R9。


> **2026-10-13 R4b2b2 已实施**：独立公开文本投稿、确切规范脱敏预览授权、can_review人工审核/固定驳回、版本/哈希绑定、撤销清除与实时来源列表及车主/运营/门店页面。开关默认关闭；本地与最终GitHubCI证据见[执行](R4B2B2_PUBLIC_REVIEW_TEXTS_EXECUTION.md)和阶段PR。下一R4b2b3授权公开文本总结。正式身份/资金及真机发布仍R9，公开照片未实现。前序#143六项CI已通过，未合并。


> **2026-10-13 R4b2b1 已实施**：独立公开星级授权/撤销、版本与幂等、实时有效非测试履约过滤、匿名日期星级列表和可复算汇总；规格、API与验收见[执行记录](R4B2B1_PUBLIC_RATINGS_EXECUTION.md)，最终提交与六项CI记录在阶段PR。公开正文/图片未实现，下一R4b2b2文本脱敏与审核；正式身份资金/真机仍R9。前序#142六项CI已通过，未合并。


> **2026-10-13 R4b2a 本地与GitHub六项CI通过（#142）**：门店独立 GCJ02 位置确认/清除、主动附近定位、距离/价格/区域筛选、详情同公式距离与账号/页面坐标清除已实施。后端定向21项、小程序401/401、微信/H5构建、V032完整schema重放通过；GitHub最终检查见[执行记录](R4B2A_LOCATION_DISTANCE_EXECUTION.md)。下一R4b2b公开评价；真实定位授权/真机仍R9。前序规划#141已六项CI通过，尚未合并。

> **2026-10-12 规划复核**：基于已合并至 R4b1 的代码与原始交付文档，新增[剩余功能与阶段交付计划](REMAINING_DELIVERY_PLAN_2026-10-12.md)。这次仅整理缺口和阶段门槛，没有新增业务实现或正式验收；下一业务阶段仍为 R4b2a。供应商识别、正式短信/微信身份/资金、真机与发布交付安排最后 R9。

> **2026-10-12 合并与 README 更新**：#120 图集及今天 #121–#139 已按依赖顺序合并至 main，业务推进至 R4b1。最新阶段后端633/633、小程序388/388且六项CI通过；本次按用户要求更新根README。下一 R4b2a 位置与距离，再 R4b2b 公开评价。正式身份/资金/供应商/真机仍待完成。下方历史“待合并/README未更新”不再代表当前状态。 [合并记录](MERGE_README_2026-10-12.md)。

> **2026-10-12 R4b1已实施分段上传，待合并**：后端#137、微信#138及独立收口。车主最小商家详情、在售项目/最新报价分页、微信原生省市区与报价过滤、恢复全部及既有预约衔接已补。定向后端19/19、微信388/388和微信/H5兼容构建通过；最终最新六项CI/全量测试数见各PR正文。无距离/评分/营业时间/电话公开，原生实际弹窗/真机另验收。下一[R4b2位置与公开评价](R4B2_LOCATION_REVIEWS_NEXT_PLAN.md)。所有README未改，见[执行](R4B1_MERCHANT_DISCOVERY_EXECUTION.md)。下方保留历史。

> **2026-10-12 R4a4 已实施分段上传，待合并**：后端#134、微信#135及独立收口；六类本人不可变历史、修正链/原值保留、清空保留履历、诚实迁移快照、版本游标分页、来源失效脱敏/车辆转移隔离已补。本地后端12/12、微信377/377及微信/H5构建通过；#134全量625/625且六项CI全绿，最终各PR证据见正文。无曲线/健康分/可信测量或真机验收。下一单元[R4b1商家详情与区域选择](R4B1_MERCHANT_DISCOVERY_NEXT_PLAN.md)。所有README未更新。详见[执行](R4A4_OBSERVATION_HISTORY_EXECUTION.md)。下方保留历史。

> **2026-10-12 R4a3 已实施上传，待合并**：后端#131、微信#132；六类本人当前读数、单位/日期/方法/来源、换来源确认/修正清空、源失效隐藏及账号隔离已实现。后端本地8/8、微信371/371、微信/H5和新页面产物通过；全量CI见最新PR。未录入/失效不算健康，动力电池与低压电瓶独立，无历史趋势/健康分或适用性核验。下一单元[R4a4读数历史](R4A4_OBSERVATION_HISTORY_NEXT_PLAN.md)。正式来源/身份/资金/真机仍欠。README按用户指示本阶段不更新，详见[执行](R4A3_HEALTH_OBSERVATIONS_EXECUTION.md)。下方保留历史。

> **2026-10-12 R4a2 已实施分段上传，待合并**：后端#128、微信#129，本人保养/保险/年检结构化资料、修正/清空、来源/账号隔离与未核验状态已实现。后端本地定向8/8、微信361/361、mp-weixin/H5和新页面产物通过；全量CI见最新PR。旧档案/报告/分享/登记里程/提醒兼容。完整证件、六指标/健康评分、正式接入和真机仍欠。下一步[R4a3健康来源与指标口径](R4A3_HEALTH_DATA_NEXT_PLAN.md)，详见[执行](R4A2_STRUCTURED_ARCHIVE_EXECUTION.md)。下方历史不用于当前欠项判断。

> **2026-10-12 R4a1 已编码分段上传，待合并**：后端 #125、微信 #126；本人保养日期/里程与保险/年检日期目标、来源替换/停用、三类列表和首页摘要已实现。用户确认仅目标到达提醒；来源失效不触发，未知不当正常。后端定向8/8、微信351/351、mp-weixin/H5及新页面产物通过；全量CI见最新PR。下一单元 R4a2 结构化字段与数据来源，六指标/健康评分未实现，真机/正式身份仍未验。详见[执行](R4A1_ARCHIVE_REMINDERS_EXECUTION.md)。下方为历史记录。

> **2026-10-12 R3c3 已实施并分阶段上传，待合并**：后端 [#122](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/122)、微信 [#123](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/123)。用户确认登录车主持链接只读查看；固定字段摘要、1/24/168小时、撤销/过期、源账号/车辆归属、幂等及访问审计已实现。微信 **341/341**、mp-weixin/H5构建及新增原生页面产物通过；后端真实MySQL/HTTP权限/并发验证见[执行记录](R3C3_ARCHIVE_SHARE_EXECUTION.md)。CI表数断言已修复，最终状态以PR检查为准。原生分享/真机/真实身份仍待验收；下一独立单元 **R4a1 档案结构化与可追溯提醒**，不凭空计算健康分数。下方保留历史。

> **2026-10-12 交付审计**：GitHub main 核对为 `bd10ae4`（#119），业务已推进至 R3c2；README 图集 #120 是独立文档 PR。最新 F01–F20 与三端缺口见[审计](DELIVERY_GAP_AUDIT_2026-10-12.md)，后续实施见[规划](THREE_ROLE_BUSINESS_PLAN_2026-10-12.md)。本次只核对并规划，没有新增业务或重新执行正式验收。

> **2026-10-11 R1c–R3c2 已合并至 main**：阶段 PR #85–#118 已按依赖顺序合并，六项 CI 均通过。R3c2 完成本人档案 PDF、日期范围、普通 HTTPS/云托管双传输、原生打开与临时文件生命周期；小程序 **331/331**、mp-weixin/H5 构建通过，后端 CI 验证 500/501 条边界和 PDF 3 MiB 上限。真实微信 PDF 阅读器/视觉验收仍列 R9。**下一步 R3c3 限时分享授权/撤销**，先明确范围、接收方式、有效期、撤销即时失效和访问审计，再分阶段实施。短信/正式身份/真实资金及 PC 后台不在当前交付范围；原始交付文档保持原样。执行见[收口验收记录](R3C2_ARCHIVE_REPORT_ACCEPTANCE_EXECUTION.md)及[验收证据](../testing/LOCAL_ARCHIVE_REPORT_ACCEPTANCE.md)。


> **2026-10-11 R3a1录车独立功能已实施，待合并**：用户确认OCR/VIN尚未开通，规格#107、后端#108、微信#109逐段上传。新增四入口、本人车牌只读匹配、VIN格式校验及手动确认回退；令牌绑定60秒一次内存带入，不写标识URL/本地缓存、不上传行驶证。后端25/25、微信312/312及微信/H5构建通过，真库与mock HTTP证据分开，原生新增页面/真机未验收。见[执行](R3A1_VEHICLE_ENTRY_EXECUTION.md)、[验收](../testing/LOCAL_VEHICLE_ENTRY_ACCEPTANCE.md)。**下一独立单元R3c1接车扫码与独立油表照片**；真实OCR/VIN仍R3a2欠项，照片语音识别/报告后续，正式身份资金R9，无PC。以下历史。

> **2026-10-11 R2c佣金应计已实施，待合并**：规格#103、后端#104、微信#105逐阶段上传。可信批准后三自然月免佣、下单冻结3%/分币、核销唯一应计、退款成功冲销与本店店长只读账单已实现；未知政策/旧单/券单保持待核对，真实/测试分列，不代表资金结算。本地定向39/39、微信304/304及微信/H5构建通过，全量CI见收口PR；详见[执行](R2C_COMMISSION_LEDGER_EXECUTION.md)、[验收](../testing/LOCAL_COMMISSION_LEDGER_ACCEPTANCE.md)。**下一步R3a录车与识别确认**，拆分见[R3规划](R3_VEHICLE_ARCHIVE_NEXT_PLAN.md)；OCR目前仍501，识别效果未交付。券免佣R5，正式身份/资金/原生真机R9，PC后台不实施。以下保留历史。

> **2026-10-11 R2b2 取消全额退款已实施，待合并**：规格#99、后端#100、微信#101逐阶段上传。按用户确认规则，本人申请、本店店长同意后全额退；拒绝/撤回/版本复核、可信原支付绑定、关闭与释放容量、唯一退款号/签名通知/查询优先及持久恢复已实现。订单关闭与资金成功分开，测试渠道不真实退款。微信294项和微信/H5构建通过；后端及最终CI证据见[执行](R2B2_ORDER_REFUNDS_EXECUTION.md)、[验收](../testing/LOCAL_ORDER_REFUNDS_ACCEPTANCE.md)。**下一步R2c：3%佣金、前三月免佣、台账/退款冲销及微信只读账单**。正式身份、微信资金与原生/真机仍R9；PC后台不实施。以下保留历史。


> **2026-10-11 R2b1 支付查询恢复已实施，待合并**：规格#94、后端#95、微信#96已逐阶段上传。车主空JSON核对、独立供应商签名查询、事务外IO与持久租约重试；迟到账保留异常，不恢复关闭订单或名额。后端定向 **49/49**、微信离线 **286/286**，微信/兼容H5构建通过；实际HTTP供应商与真实MySQL联动已验证，角色HTTP为mock业务服务。见[执行](R2B1_PAYMENT_RECOVERY_EXECUTION.md)、[验收](../testing/LOCAL_PAYMENT_QUERY_ACCEPTANCE.md)。**下一步 R2b2 取消退款资格/金额/审批及退款恢复，然后 R2c佣金**。用户已确认已接车/异议订单双方同意取消后全额退，暂不开放部分退款。正式微信/短信/真实资金保持R9，新增入口原生/真机未验收。完整R2b尚未完成；下文历史。


> **2026-10-11 R2a 完成事件可靠归档已实施，待合并**：核销同事务入队，无评价也生成本人施工档案；晚评价不改快照，经验草稿仍私有。规格#90、后端#91、微信联动#92已逐阶段上传。真实MySQL **62/62**、微信 **282/282**、实际HTTP **21/21**；重启后首次失败的任务按60秒规则自动恢复（workerAttempts=1），未宣称首次数据库失败原因已确定。见[执行](R2A_COMPLETION_ARCHIVE_EXECUTION.md)、[验收](../testing/LOCAL_COMPLETION_ARCHIVE_ACCEPTANCE.md)。**下一单元R2b支付渠道/取消退款，随后R2c佣金台账**；原生新增入口、真机、正式身份和资金仍待验收。下文为历史。


> **2026-10-11 R1c 核心实施与隔离联调完成，待合并**：四类标准目录维护/发布停用、独立运营权限、排序/来源/审计与车辆及订单历史保护已交付；规格 #86、后端 #87、微信页面 #88 逐阶段上传。真实 HTTP **42/42**、微信离线 **281/281**、微信/H5 构建通过；后端实际 MySQL 定向回归 40/40，补齐后最终 22/22（有重叠，不相加）。见[执行](R1C_CATALOG_EXECUTION.md)、[验收](../testing/LOCAL_CATALOG_ACCEPTANCE.md)。**下一单元 R2 交易业务**：完成事件可靠归档→支付渠道/取消退款恢复→佣金台账冲销。新增原生页面与真机未验收，正式短信/真实微信身份/资金保留 R9，下文为历史记录。

> **2026-10-11 最新三端清点**：R1a 入驻与 R1b 员工/门店资料已实现、已合并，分别有 92/92 与 136/136 真实容器记录；不再列为未实现。剩余功能和门槛见 [最新规划](THREE_ROLE_BUSINESS_PLAN_2026-10-11.md)。下一单元 R1c 标准项目/品牌/车型治理；正式短信、真实微信登录/账号激活/真机保持最后收口。


> **2026-10-11 R1b 收口联调已完成（真实容器 + 真实隔离库 136/136）**：在 `vehicle-auth/backend:r1b-staff` 镜像与 V021/63 表隔离库上跑通「店长建店员/建技师 → 停用即时踢下线 → 员工码签发/轮换/撤销并真实绑定 → 门店资料读写与审计」，脚本 `scripts/local_merchant_staff_e2e.cjs` **136/136 通过**、可重复执行、运行后合成数据零残留，见[验收](../testing/LOCAL_MERCHANT_STAFF_ACCEPTANCE.md)。**员工码是真实闭环**：码由店长经 HTTP 签发，再交给真实的 `POST /api/auth/technician/bind`，因此「轮换后旧码 403 失效」「撤销后不可再绑定」「旧绑定令牌 401」是对真实查询路径的断言，不是读一列。停用同时断言**原访问令牌与刷新令牌双双失效**（只改 `status` 会让 refresh 仍换新令牌，所以是双保险）。收口过程中定位并修复**两处小程序真实缺陷**：① **店员根本登录不上**——`wechat-auth.js` 只接受 `role==='merchant'`，店员（`staff`）的合法登录响应会被判成协议错误，已抽出 `STORE_ROLES` 并补一条"先在旧代码上确认会失败"的防漂移测试；② 门店资料读写白名单被合并，只读字段 `region_code` 混进"不可写"列表，导致**每一个合法读响应都被判协议错误**，已拆成只读/绝不该出现两份清单。另把门店端登录文案全仓统一为「门店」（原「商家」会造成"店员是不是走另一个入口"的误解），并把规格中"创建响应只含六字段"一句按实现改齐为列表行同九字段。小程序离线测试 **273/273**、mp-weixin 与 h5 构建通过。**正式短信、真实微信登录/员工绑定（R9）、开发者工具模拟器页面流程与真机仍未验收**，不得当作已交付。下文为历史进度。

> **2026-10-11 R1b 小程序页面已实施并上传（未联调）**：门店端[本店员工与员工码](../../apps/miniapp/src/pages/merchant/staff.vue)（列表＋新增＋启停＋技师员工码签发/撤销）与[门店资料](../../apps/miniapp/src/pages/merchant/profile.vue)（读＋店长写）已实现并注册路由，服务层 `merchant-staff.js`/`merchant-profile.js` 与两个状态机 `staff-flow.js`/`profile-flow.js` 与后端逐字段对齐，新增 18 项离线测试（小程序合计 **272/272**，mp-weixin 与 h5 构建通过）。店员复用门店登录入口（账号文案由"商家账号"改为"门店账号"、按钮改为"登录门店端"，全仓无残留），能否看到管理面入口取自服务端 `can_edit` 而非前端猜角色；技师工作台在账号停用/绑定撤销时改为给出明确下一动作"向店长索取新员工码后重新绑定"。编写过程中测试捕获两处真实缺陷并修复：创建员工响应曾少 `created_at`/`employee_code_issued`/`wechat_bound` 三个字段（6/9 字段契约漂移，客户端会把合法响应判成协议错误）、门店资料读写白名单把 `region_code` 同时放进"不可写"与"读白名单"导致所有合法读响应被判协议错误。运维说明见 [STORE_ACCOUNTS.md](../operations/STORE_ACCOUNTS.md)。**真实容器端到端验收、正式短信与真实微信员工绑定（R9）尚未完成**，不得当作已交付。下文为历史进度。

> **2026-10-11 R1b 后端已实施并上传（未联调）**：V021 迁移（`staff_account` 加 `display_name`/`created_by`，`information_schema` 守卫可重复执行，累计仍 63 表）、门店员工维护（列表/新增/启停）与门店资料（读/店长写）服务与控制器、员工码受控签发/撤销、停用单事务撤销会话与微信绑定，以及履约执行面 `role IN ('MERCHANT','STAFF')` 的 9 处授权放宽（`MerchantQuotes` 写仍限店长）均已落地；契约见 [MERCHANT_STAFF.md](../api/MERCHANT_STAFF.md)，OpenAPI 127 操作。MockMvc 6 项 + 真实 MySQL 8 项通过，相关回归 20 个测试类 **200/200**；`scripts/verify_mysql_schema.sh` 的 V021 重复迁移校验通过。测试过程中修掉一处真实边界缺陷：严格正文校验原先只在服务层，服务被替换时会漏检，已前移到 HTTP 边界（与 R1a 入驻控制器同款写法）。**小程序页面、真实容器端到端验收、正式短信与真实微信员工绑定（R9）尚未完成**，不得当作已交付。下文为历史进度。

> **2026-10-11 R1b 规格与实施计划已交付（未编码业务）**：门店员工维护（店员 `STAFF`／技师）、启停即时阻断会话、员工码受控签发与撤销、门店资料受控更新与审计的详细规格与四段实施计划定稿，见 [规格](../superpowers/specs/2026-10-11-r1b-staff-store-design.md)、[计划](../superpowers/plans/2026-10-11-r1b-staff-store.md)。用户已拍板：**店员业务同级、管理受限**——履约执行面（接车/派工/核销/报工/本店订单）放宽为 `role IN ('MERCHANT','STAFF')`，管理面（员工、员工码、门店资料、选品定价）限店长；**R1b 与 R1c 分开两套堆叠 PR**。规划同时冻结了员工码「轮换语义、无幂等键、明文不落库」的取舍。下文为历史进度。

> **2026-10-10 全部堆叠 PR 已合并回 main，R1a 收口端到端通过**：当日先把累积的 35 支堆叠 PR（#45–#79）按堆叠顺序全部合并回 `main`（`43903c9` → **`b389973`**），此后 **open PR 为 0、远端 79 支分支全部已并入 main**；本文档历史条目中「未合并」的描述自本条起作废，不再逐条改写。随后完成 **R1a 收口联调**：真实后端容器（`vehicle-auth/backend:r1a-onboarding`）＋真实隔离 MySQL（V020/63 表）上端到端 **92/92**，脚本可重复执行、运行后合成数据零残留，见[验收](../testing/LOCAL_MERCHANT_ONBOARDING_ACCEPTANCE.md)。**收口过程中定位并修复 #78 引入的真实契约漂移**：`submit`/`moderate`/`setQuota` 三个写接口把幂等信封又包了一层（`data.data`），与 OpenAPI 声明、小程序解析（单层 `data.data`）及既有 A7 控制器写法全部不符，真实环境会把合法响应判成协议错误——单测与 MockMvc 因 `$.code` 恒为 0 而全绿掩盖了它。已改为透传信封并加防漂移断言（`writeScopesReturnSingleEnvelope`）；后端入驻 HTTP 6/6、真实 MySQL 8/8 通过。**正式短信、门店账号激活（R9）、真实微信登录与真机仍未验收**。下文为历史进度。

> **2026-10-10 R1a 三端页面已接入（未真实联调）**：车主端[商家入驻申请](../../apps/miniapp/src/pages/owner/onboarding.vue)（表单＋资质私有上传＋状态与审核历史＋驳回后重提）、运营端[入驻审核](../../apps/miniapp/src/pages/operator/onboarding.vue)（待审列表＋详情＋批准／固定码驳回＋资质受控查看）与[区域品类配额](../../apps/miniapp/src/pages/operator/quotas.vue)（列表＋设置）已实现，入口分别挂在「我的」与经验审核页；服务层 `merchant-onboarding.js` 与后端逐字段对齐并新增 14 项离线测试（小程序合计 254 项全过，mp-weixin 与 h5 构建通过）。顺带修复 #78 引入的契约漂移：运营登录响应新增 `can_onboard` 后，小程序对 `user` 的严格等长校验会把合法登录误判为协议错误（旧夹具只有三个键所以测试全绿掩盖了它），已补齐字段、写入会话权限并加防漂移断言。**真实容器＋隔离库＋微信开发者工具的端到端验收、账号激活与正式短信仍未完成**；下一支为 R1a 收口联调。下文为历史进度。

> **2026-10-10 R1a 后端已实施并上传（未联调）**：V020 迁移（63 表）、车主提交/查询/重提、运营待审列表/详情/批准/驳回/配额与资质文件受控访问已实现，契约见[入驻接口](../api/MERCHANT_ONBOARDING.md)、运维见[运营账号](../operations/OPERATOR_ACCOUNTS.md)。MockMvc 5 项 + 真实 MySQL 8 项通过；并发批准不超额、锁序无死锁、失败整体回滚、跨身份与权限隔离均有用例。测试过程中捕获并修复两处真实缺陷：写事务内的配额统计读到 REPEATABLE READ 旧快照导致可超额、同值配额设置因数值类型不同被误判为变更而多写一条审计。下文为历史进度。

> **2026-10-10 R1a 规格与实施计划已交付（未编码业务）**：商家入驻申请/审核、区域品类配额与开店详细规格、四段实施计划定稿，见 [规格](../superpowers/specs/2026-10-10-r1a-merchant-onboarding-design.md)、[计划](../superpowers/plans/2026-10-10-r1a-merchant-onboarding.md)。规划 PR #76 六项 CI 全绿，未合并。下文为历史进度。

> **2026-10-10 最新推进顺序**：用户要求先补齐三端其他业务，正式短信、真实微信登录/手机号/员工绑定及真机接入后置。下一实施单元 **R1a 商家入驻**；R0 正式身份未通过不再阻塞 R1–R8，权限校验继续。三端逐项缺口和完成门槛见 [业务优先规划](THREE_ROLE_BUSINESS_PLAN_2026-10-10.md)。本次仅整理规划，没有实现 R1a；下文为历史进度。


> **2026-10-10 R0.2a 已实施**：用户确认短信服务尚未开通。只读微信身份配置检查器与 9 项保护性测试已交付；静态配置与正式身份验收分开，SMS_PROVIDER 不激活发送器。见 [执行与接入步骤](R0_IDENTITY_READINESS_EXECUTION.md)。正式短信、真实员工绑定及真机仍待完成。服务开通后推进 R0.2b 供应商适配；授权员工绑定可独立验证。小程序范围不变，PC 取消，最终 CI 见阶段 PR。下文保留历史进度。


> **2026-10-10 R0.1b3 诊断已实施**：私有授权/严格证书拒绝/显式信任下载 10 项、微信解码 3 项通过；**图片视觉与实际系统确认/取消未通过**，不计完整原生验收。小程序 231/231、微信/H5 构建通过。见 [执行](R0_NATIVE_PREVIEW_EXECUTION.md)、[诊断](../testing/WECHAT_NATIVE_PREVIEW_ACCEPTANCE.md)。正式身份与真机前置见 [清单](R0_IDENTITY_DEVICE_PREREQUISITES.md)，R0 未完整完成，PC 取消，最终 CI 见阶段 PR。

> **2026-10-10 R0.1b2c 核销、评价与档案原生链路已实施**：本阶段 **29/29**（原生 19、HTTP 9、后台 1），前序施工准备 **21/21**；小程序 **231/231**，微信/H5 构建及普通产物无测试桥通过。真实核销、带图本人评价、唯一后台归档和来源跳转完成；系统选择器/弹窗结果辅助，图片视觉预览/证书、正式身份与真机仍欠。见 [执行](R0_NATIVE_COMPLETION_EXECUTION.md)、[验收](../testing/WECHAT_NATIVE_COMPLETION_ACCEPTANCE.md)。下一单元私有图片原生预览与工具交互兼容性；R0 未整体完成，最终 CI 见阶段 PR。

> **2026-10-10 R0.1b2b 施工原生验收已实施**：真实文件上传/扫描、防护、完整报工、画布 PNG 签字到待核销及证据权限隔离 **21/21**（含来源准备），小程序 **231/231**。选择器/弹窗结果辅助，不计实际系统点击或真机通过。见 [执行](R0_NATIVE_WORK_FILES_EXECUTION.md)、[验收](../testing/WECHAT_NATIVE_WORK_FILES_ACCEPTANCE.md)。下一步核销→本人评价→档案；正式身份/真机/证书图片预览仍欠，PC 取消，R0 未整体完成。最终构建与 CI 见阶段 PR。

> **2026-10-09 R0.1b2a 原生页面业务已实施**：未确认阻断→车主确认→商家派工→本人接单及跨店/未派技师隔离，本机 **18/18**（含 3 项 HTTP 来源准备），小程序 **229/229**，普通微信构建无测试桥。系统弹窗采用官方结果 mock，实际弹窗点击未通过；原生上传/防护/报工/签字、核销/评价/档案后半段、正式短信/真机仍欠验收。商家“已接车即已确认”的误导提示已修正。见 [执行](R0_NATIVE_DISPATCH_EXECUTION.md)、[验收](../testing/WECHAT_NATIVE_DISPATCH_ACCEPTANCE.md)。R0 未完整完成，最终 CI 见阶段 PR。

> **2026-10-09 R0.1b1 原生审核闭环已实施、本机通过**：授权→批准→同款查看→撤回隐藏、驳回理由及登录保护 **11/11**，隔离 HTTP **42/42**；小程序 **228/228**，普通微信产物不含测试身份桥。采用隔离构建合成会话，不能算正式短信/真机验收。履约与文件原生闭环仍待实施，R0 未完成。见 [执行](R0_NATIVE_PUBLICATION_EXECUTION.md)、[验收](../testing/WECHAT_NATIVE_PUBLICATION_ACCEPTANCE.md)，最终 CI 以阶段 PR head 为准。

> **2026-10-09 R0.1a 已实施、本机通过**：微信模拟器实际连接；32 路由、未登录保护、真实 HTTP 与计算样式等原生基线 46/46；真实车主按钮登录/首页本人车辆/退出 6/6。修复 19 页公共 CSS 原生缺失，小程序 225/225、微信/H5 构建通过。完整履约/审核/文件原生流程、正式短信及真机仍待完成；R0 全阶段未完成。见 [执行](R0_NATIVE_BASELINE_EXECUTION.md)、[原生验收](../testing/WECHAT_NATIVE_BASELINE_ACCEPTANCE.md)。阶段 PR CI 以实际 head 为准。

> **2026-10-09 交付范围更新（规划，未编码）**：用户明确取消 PC 运营后台，仅交付微信小程序及必要后端；运营业务建议继续在小程序受限入口完成。新路线为 R0–R9，下一实施单元 R0.1 原生自动化连接/页面与真实接口验收。见 [微信剩余交付路线](WECHAT_DELIVERY_ROADMAP_2026-10-09.md)。历史代码、合并与验收状态不因规划更新而改变。

更新日期：2026-10-09。需求以[Spec](../SPEC.md)与[分阶段开发计划](../../汽车健康管家平台分阶段开发计划.md)为准。文档分别记录代码、自动验证、本机联调、真机与上线，完成判定见[阶段交付规则](STAGE_DELIVERY.md)。

## 当前阶段与 GitHub 状态

A7.2c2 微信小程序经验审核与同款摘要已实现并上传：规格#61、独立运营认证#62、审核后端#63、微信页面#64，六项CI全绿；页面阶段后端437/437、小程序216/216，收口新增权限失效清理后小程序217/217。独立密码+短信二次认证核心、离线账号管理、批准/固定码驳回、本人重新授权/撤回、同款五字段摘要与来源实时过滤已完成。微信构建及开发者工具导入成功，真实HTTP43/43和gstack辅助实际批准/撤回/同款展示通过；模拟器自动化与真机未验收。默认发布关闭，正式短信尚需供应商适配。收口codex/a7-publication-e2e最终CI见对应PR，未合并。[执行](A7_EXPERIENCE_PUBLICATION_EXECUTION.md)、[验收](../testing/LOCAL_EXPERIENCE_PUBLICATION_ACCEPTANCE.md)。

A7.2b 施工档案回写已实现：[规格 #53](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/53)、[后端 #54](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/54)、[页面 #55](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/55) 六项 CI 全绿；后端398/398、小程序196/196、真实HTTP29/29、本机数据库27/27，gstack档案/来源订单/首页标注通过。采用评价后可靠任务，默认关闭消费，联调收口分支codex/a7-archive-e2e最终CI见对应PR；未合并。[执行](A7_SERVICE_ARCHIVE_EXECUTION.md)、[验收](../testing/LOCAL_SERVICE_ARCHIVES_ACCEPTANCE.md)。自签证书直连图片仍待验收。

| 阶段 | 状态 | 证据与下一步 |
| --- | --- | --- |
| A1 履约规格 | 关键规则已确认并交付 | PR #32 已合并；预约码、仅商家接车、车主二次确认与不自动关闭已固化 |
| A2 履约状态机 | 已合并 | PR #33；服务端矩阵、行锁、幂等、同事务审计及前置阻断 |
| A3 接车检查 | 已合并 | [PR #34](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/34)，合并提交 `30ae704`；最终 CI 六项通过，后端 263 项、小程序 119 项；[接车验收记录](../testing/LOCAL_PICKUP_ACCEPTANCE.md) |
| A4 车主确认与异议 | 已合并 main | [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)；实现提交 `e0d4077` 六项 CI 通过，后端 271 项、小程序 120 项；[接口](../api/PICKUP_OWNER_DECISION.md)、[执行记录](A4_OWNER_DECISION_EXECUTION.md) |
| A5.1 派工与接单规格 | 已合并 main | [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36)，`c9187b9` 六项 CI 通过；[规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)、[契约](../api/TECHNICIAN_DISPATCH.md)、[计划](../superpowers/plans/2026-10-08-a5-dispatch.md)、[执行记录](A5_1_DISPATCH_SPEC_EXECUTION.md)；后端 271/小程序 120 项为已有实现回归，非派工验收 |
| A5.2 派工与技师本人接单后端 | 已合并 main | [PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)，`f76af64` 六项 CI 通过，后端 293 / 小程序 120 项；六个接口、V012、本人接单进入施工；[契约](../api/TECHNICIAN_DISPATCH.md)、[执行记录](A5_2_DISPATCH_BACKEND_EXECUTION.md) |
| A5.3 商家派工页与技师工作台 | 已合并 main | [PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38)，`be63751` 六项 CI 通过，小程序 140 项、微信与 H5 构建通过；商家派工页、技师独立会话、本人工单/详情/接单；[执行记录](A5_3_DISPATCH_UI_EXECUTION.md) |
| A5.4 派工安全与竞争验证 | 已合并 main | [PR #39](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/39)，`1f9bcb7` 六项 CI 通过；新增真实 MySQL 并发/无死锁/载荷/历史异常 11 项，相关回归 76 项；[执行记录](A5_4_DISPATCH_VERIFICATION_EXECUTION.md) |
| A5.5 本机联调与收口 | 已合并 main | [PR #40](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/40) 六项 CI 通过；合成身份接真实后端容器与隔离 MySQL，端到端 37 项通过（车主确认→候选→派工→本人查询→接单 + 拒绝矩阵）；[执行记录](A5_5_DISPATCH_E2E_EXECUTION.md)、[本机验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)、[A5 阶段总记录](A5_DISPATCH_EXECUTION.md) |
| A5.6 争议处理与恢复 | 已合并 main | [PR #41](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/41) 六项 CI 全绿；商家处理记录 + 车主复核恢复，新增两接口与 V013（52 表）；后端争议与回归 52/52、小程序 147 项、两端构建、本机端到端 **69/69**；[规格](../superpowers/specs/2026-10-08-dispute-resolution-design.md)、[契约](../api/DISPUTE_RESOLUTION.md)、[执行记录](A5_6_DISPUTE_RESOLUTION_EXECUTION.md)、[本机验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md) |
| A6.1 防护与报工后端 | 已合并 main | [PR #42](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/42)；`6ee0b86` 六项 CI 全绿，后端 348/小程序 147；相关 75/75 + 锁修复后施工 13/13（76 独立用例）；V014（54 表）、商家防护、本人完整报工/质检签字、私有证据访问；[规格](../superpowers/specs/2026-10-08-service-work-design.md)、[接口](../api/SERVICE_WORK.md)、[执行记录](A6_1_SERVICE_BACKEND_EXECUTION.md) |
| A6.2 防护与报工页面 | 已合并 main | [PR #43](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/43)，9254e86 六项 CI 全绿； 小程序 169/169、微信/H5 构建、gstack 实际防护→报工→PNG 签字→待核销；[执行记录](A6_2_SERVICE_UI_EXECUTION.md) |
| A6.3 真实 HTTP 联调 | 已合并 main | [PR #44](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/44)，504e99a；最终六项检查见 [PR #44 Checks](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/44/checks)； 真实 HTTP 65/65；[执行](A6_3_SERVICE_E2E_EXECUTION.md)、[验收](../testing/LOCAL_SERVICE_WORK_ACCEPTANCE.md)、[阶段总记录](A6_SERVICE_WORK_EXECUTION.md)；真机独立验收 |
| A7.1 核销验码与审计 | 后端与页面六项 CI 通过，真实 HTTP / H5 验收完成，逐阶段已上传 | [规格 #45](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/45)、[后端 #46](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/46)、[页面 #47](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/47)；前序完整 CI 369/181 项，收口 #48 新增七图门禁、相关 56 项与真实 HTTP 51/51，最终全量以 #48 Checks 为准；[阶段总记录](A7_REDEMPTION_EXECUTION.md)、[本机验收](../testing/LOCAL_ORDER_REDEMPTION_ACCEPTANCE.md)；未合并 |
| A7.2a 本人订单评价 | 后端/页面六项CI全绿，真实HTTP/H5完成，逐阶段上传 | [规格#49](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/49)、[后端#50](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/50)、[页面#51](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/51)；后端389/389、小程序193/193、真实HTTP44/44、57表；[执行](A7_OWNER_REVIEWS_EXECUTION.md)、[验收](../testing/LOCAL_ORDER_REVIEWS_ACCEPTANCE.md)。收口最终CI见其PR，未合并 |
| A7.2b 施工档案回写 | 已实现并逐阶段上传，收口最终CI见PR | 评价后唯一任务、来源快照、恢复、本人施工照片及测试标注；V017/58表，398/196项、HTTP29/29；[执行](A7_SERVICE_ARCHIVE_EXECUTION.md) |
| A7.2c1–c2 | 微信小程序私有授权、独立运营审核和同款摘要已实现并上传 | #57–#64及收口PR；默认开关关闭，正式短信/微信模拟器/真机另验收 |
| 后续 | 尚未实现 | 正式收退款、评价展示/商家评分、入驻/运营治理/增长，见[下一步规划](NEXT_STEPS.md) |

## 主分支合并验收

2026-10-09 已按顺序将 #35–#44 逐支改回 main 后合并；main `43903c9` 最终六项 CI 全绿（运行 37804547433），后端 348/348、小程序 169/169、V001–V014 重放 54 表。[合并记录](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/44)。A7.1 已进入[规划与实施](../superpowers/plans/2026-10-09-a7-redeem.md)。

## 已具备的业务基础

本人登录、车辆与档案录入/查询、首页摘要、私有图片、标准服务、商家报价、预约订单、测试支付、商家订单查询及 AI 对话已有代码与各阶段执行记录。测试支付标注“未真实扣款”，不能作为真实付款凭据。AI 上游已完成本机验证，真机单独验收。

接口入口见[API 文档](../api/README.md)；历史完成证据见[S0 执行记录](S0_EXECUTION_LOG.md)与[历史进度](history/CURRENT_STATUS_2026-10-07.md)。历史测试数量仅对应当时提交。

## 当前明确的限制

- 车主确认前不能派工；提出异议后订单置为 `DISPUTED`，A5.6 已补上商家处理记录与车主复核：只有车主本人接受复核后订单才回到争议前的状态（`PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`），恢复前派工与施工一律 `43007` 阻断。A4 之前的历史异议没有争议单，恢复一律拒绝（fail-closed），由人工处理。
- 派工、本人接单与争议处理的**后端、小程序界面、安全竞争验证与本机端到端联调**均已合并 main（A5.1–A5.6）；A5.6 用合成会话接真实后端容器与隔离 MySQL 完成异议→处理→复核→恢复→派工→接单闭环，合成会话是测试桥接，真实技师/车主微信登录、真机页面联调仍是独立验收项。A6.1 防护、完整报工与质检签字后端 76 个独立用例通过，已上传 PR #42，代码六项 CI 全绿，后端 348/小程序 147；A6.2 页面本地测试 169 项与两端构建通过，gstack 实际交互已验证到待核销；A6.3 可复现真实 HTTP 65/65 通过，已上传 PR #44，最新检查见 PR Checks。A7.1 已实现专用核销，规格/后端/页面 PR #45–#47 六项 CI 全绿（后端 369、小程序 181）；真实 HTTP 51/51 与 gstack 实际核销交互完成，test_mode=true 未真实扣款，正式凭据验收仍待完成。
- 真实相机、手机真机、本机测试证书下的直连图片预览继续待验收。
- 正式短信、微信收款/退款、OCR、正式公网环境和上线运维有独立依赖，见[依赖清单](S0_DEPENDENCIES.md)。

## 每阶段交付

用户已要求每完成一个阶段整理文档并上传 GitHub。后续按“验证→文档→提交→推送阶段分支→PR→CI”的顺序收口，在用户报告中提供 PR 链接与最终验证结果。详见[阶段交付规则](STAGE_DELIVERY.md)。
