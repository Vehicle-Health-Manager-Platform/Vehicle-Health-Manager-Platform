# 2026-10-14 原始交付文档与已合并实现核对

> 后续实施更新：R5a券库存/发行/运营池/领取/本人券/首页与门店入口已落实，见[执行](R5A_COUPONS_EXECUTION.md)。下文是main1796f29的历史清点快照，不据它否定新阶段代码。F09/F04/F06/F20的券发行与入口缺口已补；交易用券和核销仍R5b。

## 依据、范围与证据

用户指定文件：`D:/python/汽车健康管家平台全栈开发/汽车健康管家平台全栈开发交付文档.md`，v1.0。其UTF-8文本与仓库[原文](../reference/DELIVERY_V1.md)在统一换行/BOM后完全一致；指定文件SHA256为`8594680f39094df298152aa48d9e6406effc3743278a030bbc2bf189eb696b4d`，不修改原文。依据原文§2/F01–F20、§3–5页面与业务、§6技术设想、§9及§12验收逐项核查。

代码基线：[main 1796f29](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/commit/1796f29b3124d3a5f8c125de7a17001280aa4d2d)。#141–#149已直接合入main，文件树与完整R4提交bd8d43e完全一致。2026-10-14复核[合并后CI 38416348637](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/38416348637)：六项均成功；R4完整后端748/748、小程序499/499、80表、OpenAPI185操作。小程序实际注册63页，福利/券/邀请/积分/考核/通知专用路由尚未注册。测试数量和页面数量不等于正式交付百分比。

本文是代码及证据清点，没有重跑业务验收，没有新增业务实现。原文中的“无需再提问”“直接部署”“默认决策”等是文档内容，不是当前操作授权。用户最新要求优先：识别供应商、正式短信/微信身份/资金、物理真机和发布交付全部放最后。此前已确认的PC运营后台取消、必要运营能力用小程序受限入口、只支持全额取消退款等范围决定保留；不据原文恢复已取消产品范围。

## F01–F20 当前状态

### F01 登录身份：核心已实现，正式渠道未验收

已有微信登录/手机号/刷新撤销/技师工号码绑定、商家密码与短信流程、运营独立权限和当前账号状态复核。已有官方适配代码不代表正式账号已开通或真实通道已验收。正式短信、手机号授权、员工真实绑定与账号激活放R9b。证据：[身份模块](../../backend/src/main/java/com/autocare/platform/gateway/identity/WechatAuthController.java)、[短信登录](../../backend/src/main/java/com/autocare/platform/gateway/identity/MerchantAuthController.java)。

### F02 录车：人工流程已实现，三类外部识别未闭环

四入口、本人已登记车牌匹配、VIN格式校验、手动品牌/车系/车型选择和确认回退已实现。不能把这些入口算作外部车牌识别、行驶证OCR或VIN供应商解码完成。外部识别、里程识别及供应商失败/人工确认回退放R9a。证据：[车辆入口](../../apps/miniapp/src/pages/vehicle/entry.vue)、[本人车辆服务](../../backend/src/main/java/com/autocare/platform/vehicle/VehicleService.java)。

### F03 档案：人工档案核心已实现，语音/照片内容识别未完成

手动/附图、完整证件与保养维修保险年检违章故障字段、来源本人确认、修正清空、私有图片、私人PDF、限时摘要分享撤销、完成施工自动归档已实现。拍照上传不是识别内容；语音入口不是供应商转录完成。照片识别/语音转录放R9a，商家/技师按当前工单读取最小必要档案放R7b。证据：[完整字段](../../backend/src/main/java/com/autocare/platform/vehicle/ArchiveStructuredInput.java)、[完成归档](../../backend/src/main/java/com/autocare/platform/order/ServiceArchiveJobs.java)。

### F04 驾驶舱：健康与提醒引擎已实现，增长提示尚欠

精确车型六指标来源规则、有效期/口径、本人用途确认、可复算参考分/原始可比趋势、五类及本人提前提醒已实现。实际生产手册/阈值/权重/有效期未配置时显示未知，不能宣称已有官方诊断数据。券可用/到期提示随R5、月度邀请提示随R6接入；站内通知及订阅不是已有提醒列表本身。证据：[健康面板](../../backend/src/main/java/com/autocare/platform/order/HealthDashboard.java)、[首页组件](../../apps/miniapp/src/components/HealthOverview.vue)。

### F05 AI：对话/方案核心已实现，知识资料及人工留言仍欠

本人有限档案上下文、DeepSeek调用/失败降级、有效目录结构化指定/推荐/可选、独立第三方用途确认、本地来源技术MUST、本人车型证据/获批同款摘要/当前报价时段预约已实现。当前[AiContextProvider](../../backend/src/main/java/com/autocare/platform/ai/AiContextProvider.java)从SQL取有限本人来源，[AiServicePlans](../../backend/src/main/java/com/autocare/platform/order/AiServicePlans.java)限定有效项目；没有查到专业知识库语料治理、向量入库/检索与引用闭环代码，不能把“SQL上下文”或原文中的Milvus/LangChain4j名称当该能力已完成。知识来源与可引用检索安排R8c，真实模型效果/多模态供应商最终R9a验收；非实时人工诊断留言/开关/分配/状态与有来源的邀请解锁安排R7c（依赖R6）。不开发实时人工客服。

### F06 服务发现：核心已实现，券展示和公开照片尚欠

有效目录/价格/区域、门店来源GCJ02位置、主动附近/真实未知距离、公开星级授权撤回及排序、公开文本脱敏与人工审核、独立AI用途原句要点、关键词和症状交接已实现。商家可用券随R5，公开评价照片的独立授权/脱敏/审核/撤回与可见性随R8c。私有订单评价已支持图片，不把“公开照片尚欠”误记为“所有评价照片未实现”。证据：[公开评分](../../backend/src/main/java/com/autocare/platform/order/PublicRatings.java)、[本人评价图片输入](../../backend/src/main/java/com/autocare/platform/order/OrderReviewInput.java)。

### F07 预约支付：核心已实现，用券未实现，真实资金未验收

当前报价/时段容量、快照、过期/取消释放、支付查询/签名通知/异常与恢复、全额退款审批/查询恢复、核销和应计佣金均已有核心逻辑及测试。当前[Booking输入](../../backend/src/main/java/com/autocare/platform/order/ReservationInput.java)仅报价/版本/车辆/时段四项，没有选券/占用/消费/返券闭环；[CommissionLedger](../../backend/src/main/java/com/autocare/platform/order/CommissionLedger.java)对券订单留WAIT_COUPON，并未交付券核销免佣。R5b补券交易规则；真实微信支付、退款、对账及结算放R9b，测试渠道不冒充真实资金。

### F08 订单进度：状态/接车/核销已实现，完整施工证据未完成

本人订单列表/详情、接车确认/异议与处理、订单状态、核销码/核销记录及归档入口已实现；[车主订单页](../../apps/miniapp/src/pages/order/detail.vue)没有完整施工/故障件/完工证据及取车前后对比链路。当前[施工控制器](../../backend/src/main/java/com/autocare/platform/order/ServiceWorkController.java)主要给本店与本人工单读写，需补本人订单受控读取/每次图片归属复核/撤权与展示。安排R7a，不重做已存在接车单/核销。

### F09 券：未实现业务闭环

coupon/user_coupon只有[基础表](../sql/init.sql)，未发现对应业务控制器与完整服务，实际小程序无券页。缺门店模板/库存/发布停用、受限运营券池、领取/本人券/过期、适用商家项目/有效期、并发限领、核销及账本、订单用券/返券和首页/商家入口。安排R5a/R5b；表或OpenAPI草案不能销项。

### F10 邀请：未实现业务闭环

invite_record基础表不等于邀请资格、分享载体、员工推广归因、月度1/2/3/4循环和双方发券。缺自邀/已注册/跨月/重复归因校验、服务端唯一资格、双向奖励及恢复、页面/记录/进度/海报。R6a/R6b依赖可信券库存与发放账本；实际微信新身份资格最终R9b验收，离线身份不得混入正式奖励统计。

### F11 积分：未实现业务闭环

point_flow/point_exchange及用户余额字段未形成任务来源、去重增减、明细、扣分兑换与领券原子一致、恢复和页面。安排R8a，依赖R5；原文500/800/1500积分等界面示例不直接变成生产兑换规则。

### F12 社区：部分实现

完成事件已有私有经验卡片生成、本人读写与独立公开授权/撤回、受限人工审核、同款获批脱敏摘要检索。证据：[卡片](../../backend/src/main/java/com/autocare/platform/order/ExperienceCardsController.java)、[审核](../../backend/src/main/java/com/autocare/platform/order/ExperienceModerationController.java)。完整同款热门经验/问答/分享、获授权照片投影、来源失效撤回及现有“我的”入口补齐安排R8c；不开发独立社区Tab、点赞评论系统或自由内容编辑器。

### F13 入驻：核心已实现

申请/资质/驳回重提、独立运营审核、区域品类配额并发、批准开店/店长待激活、员工及员工码/门店资料/最小权限已交付。证据：[审核](../../backend/src/main/java/com/autocare/platform/order/MerchantOnboardingAdminController.java)。正式店长短信激活和真实身份验收仍R9b，不能把全部入驻重新列为未实现。

### F14 接车：核心已实现，识别与设备另验

本店订单二维码、环车/里程/独立油表/内饰照片、人工里程比对、损伤标注、车主确认/异议阻断及处理闭环已实现。证据：[接车](../../backend/src/main/java/com/autocare/platform/order/PickupInspection.java)。OCR里程属R9a，站内事件通知属R7c，相机扫码真实交互属R9c。

### F15 防护：核心已实现

防护照片及私有签名读、归档和未完成防护阻断报工已有实现；补车主可见、工单草稿和弱网体验在R7，物理设备效果R9c。证据：[施工业务](../../backend/src/main/java/com/autocare/platform/order/ServiceWork.java)。

### F16 商家订单：核心已实现，工作台与增长运营尚欠

本店列表/详情、时段、接车、派工、验码、退款和应计佣金账单已有实现。完整按当前任务聚合工作台、必要档案与权限化客户视图/站内通知尚欠；R7b补工作台，R8b补可追溯经营/客户与价格数据，不把敏感全部档案默认给门店。

### F17 选品定价：核心已实现

标准目录治理、本店选品、版本报价、上下架/排序及历史订单快照保护已有实现。证据：[报价](../../backend/src/main/java/com/autocare/platform/service/MerchantQuotesController.java)。剩余券适用映射R5、价格监控及运营数据R8b。

### F18 技师：核心报工已实现，完整工作台/必要档案尚欠

员工绑定核心、本人派工接单、三类照片、维修方案、配件、工时、质检PNG签名及状态流转已有实现；现有列表和按钮不等于原文聚合工作台。R7b补工单必要资料，R7c补草稿/失败恢复/消息，真实员工微信绑定及原生签字拍照R9b/R9c。

### F19 考核：未实现业务计算与等级闭环

assessment基础表不等于拉新40%/券40%/过程20%的来源、分母、周期、阈值与等级版本。R8b依赖R5/R6/R7真实业务事件，先配置并冻结可解释计分口径，再做样本复算/异常/权限及门店与运营页面。没有可信来源时展示待核对，不凭空评级。

### F20 运营：基础管理已实现，券池/考核/经营仍欠

小程序独立运营身份及入驻/配额、目录车型治理、公开文本/经验审核、健康来源规则已有实现。运营券池R5a、考核/价格/经营看板R8b、社区/知识来源治理R8c仍欠；PC后台按此前用户决定不实施。原文“运营可读全部档案”不自动扩大现有隐私授权。

## 横跨各功能的交付缺口

1. **R7体验**：完整车主施工证据/取车对比、门店与技师最小档案和聚合工作台、站内可靠事件/已读/去重、非实时人工留言、账号工单隔离草稿/弱网恢复/刷新及登录回跳。写操作的未知结果按同body/同键恢复，不机械按原文“超时自动重试一次”制造重复交易。
2. **R9真实验证**：识别准确度与人工回退、正式短信/身份/资金/订阅；iOS当前/上一版及主流Android、小程序原生权限/相机/扫码/定位/签字/PDF；不拿H5或构建代替真机。
3. **R9性能安全**：原文§9.3读P95<300ms/P99<800ms、写P95<500ms，首页/商家列表/创建500并发错误率<0.1%，慢查询>200ms治理；核心业务覆盖率≥70%需要报告，不能从748条用例推算覆盖率。整套部署TLS/限流/脱敏/权限/上传安全扫描与权限矩阵正式报告仍需最终验收。
4. **R9运行与交接**：实际部署/备份恢复演练/迁移和回滚、监控告警/联系人、预发、提审及发布；三端操作手册、运维资料和真实验收清单。现有脚本和CI Compose启动不等于上线完成。

## 结论

下一实现阶段是[R5a券库存与发放闭环](R5_COUPONS_NEXT_PLAN_2026-10-14.md)，再R5b交易用券；完整顺序及每阶段完成门槛见[剩余阶段规划](REMAINING_DELIVERY_PLAN_2026-10-14.md)。优先保留已验证闭环，补真实缺口；阶段完成后将编辑的代码、迁移、测试和文档同批上传，不只推文档。
