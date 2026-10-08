# 当前进度

更新日期：2026-10-08。需求以[Spec](../SPEC.md)与[分阶段开发计划](../../汽车健康管家平台分阶段开发计划.md)为准。文档分别记录代码、自动验证、本机联调、真机与上线，完成判定见[阶段交付规则](STAGE_DELIVERY.md)。

## 当前阶段与 GitHub 状态

| 阶段 | 状态 | 证据与下一步 |
| --- | --- | --- |
| A1 履约规格 | 关键规则已确认并交付 | PR #32 已合并；预约码、仅商家接车、车主二次确认与不自动关闭已固化 |
| A2 履约状态机 | 已合并 | PR #33；服务端矩阵、行锁、幂等、同事务审计及前置阻断 |
| A3 接车检查 | 已合并 | [PR #34](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/34)，合并提交 `30ae704`；最终 CI 六项通过，后端 263 项、小程序 119 项；[接车验收记录](../testing/LOCAL_PICKUP_ACCEPTANCE.md) |
| A4 车主确认与异议 | 阶段验证通过，已上传，PR 待合并 | [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)；实现提交 `e0d4077` 六项 CI 通过，后端 271 项、小程序 120 项；[接口](../api/PICKUP_OWNER_DECISION.md)、[执行记录](A4_OWNER_DECISION_EXECUTION.md) |
| A5.1 派工与接单规格 | 文档交付并上传，规格提交六项 CI 通过，PR 待合并 | [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36)，`c9187b9` 六项 CI 通过；[规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)、[契约](../api/TECHNICIAN_DISPATCH.md)、[计划](../superpowers/plans/2026-10-08-a5-dispatch.md)、[执行记录](A5_1_DISPATCH_SPEC_EXECUTION.md)；后端 271/小程序 120 项为已有实现回归，非派工验收 |
| A5.2 派工与技师本人接单后端 | 阶段验证通过，已上传，PR 待合并 | [PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)，`f76af64` 六项 CI 通过，后端 293 / 小程序 120 项；六个接口、V012、本人接单进入施工；[契约](../api/TECHNICIAN_DISPATCH.md)、[执行记录](A5_2_DISPATCH_BACKEND_EXECUTION.md) |
| A5.3 商家派工页与技师工作台 | 阶段验证通过，已上传，PR 待合并 | [PR #38](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/38)，`be63751` 六项 CI 通过，小程序 140 项、微信与 H5 构建通过；商家派工页、技师独立会话、本人工单/详情/接单；[执行记录](A5_3_DISPATCH_UI_EXECUTION.md) |
| A5.4 派工安全与竞争验证 | 本机验证通过，已上传，PR 待合并 | 真实 MySQL 并发/无死锁/载荷/历史异常 11 项通过，相关回归 76 项通过；[执行记录](A5_4_DISPATCH_VERIFICATION_EXECUTION.md) |
| A5.5 本机联调与收口 | 待执行 | 合成身份接真实后端/MySQL 完成派工→本人查询→接单，并收口 A5 文档 |
| A6/A7 施工至履约闭环 | 尚未实现 | 防护与报工、核销、评价、档案回写、经验卡片按[下一步规划](NEXT_STEPS.md)逐项交付 |

## 已具备的业务基础

本人登录、车辆与档案录入/查询、首页摘要、私有图片、标准服务、商家报价、预约订单、测试支付、商家订单查询及 AI 对话已有代码与各阶段执行记录。测试支付标注“未真实扣款”，不能作为真实付款凭据。AI 上游已完成本机验证，真机单独验收。

接口入口见[API 文档](../api/README.md)；历史完成证据见[S0 执行记录](S0_EXECUTION_LOG.md)与[历史进度](history/CURRENT_STATUS_2026-10-07.md)。历史测试数量仅对应当时提交。

## 当前明确的限制

- 车主确认前不能派工；提出异议后订单置为 `DISPUTED`，后续施工动作由状态矩阵拒绝。A4 只提供第一次决定，争议处理与恢复尚需独立设计。
- 派工与本人接单的后端与小程序界面均已上传待合并；真实技师微信登录、真机页面联调与完整竞争/安全证据属 A5.4/A5.5，尚未验收。完整报工与核销仍未实现，缺前置时服务端明确拒绝。
- 真实相机、手机真机、本机测试证书下的直连图片预览继续待验收。
- 正式短信、微信收款/退款、OCR、正式公网环境和上线运维有独立依赖，见[依赖清单](S0_DEPENDENCIES.md)。

## 每阶段交付

用户已要求每完成一个阶段整理文档并上传 GitHub。后续按“验证→文档→提交→推送阶段分支→PR→CI”的顺序收口，在用户报告中提供 PR 链接与最终验证结果。详见[阶段交付规则](STAGE_DELIVERY.md)。
