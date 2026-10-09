# 当前进度

更新日期：2026-10-09。需求以[Spec](../SPEC.md)与[分阶段开发计划](../../汽车健康管家平台分阶段开发计划.md)为准。文档分别记录代码、自动验证、本机联调、真机与上线，完成判定见[阶段交付规则](STAGE_DELIVERY.md)。

## 当前阶段与 GitHub 状态

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
| A7.2c及后续 | 尚未实现 | 经验卡片、授权脱敏与审核、评价公开规则、退款/取消争议订单分步推进，见[下一步规划](NEXT_STEPS.md) |

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
