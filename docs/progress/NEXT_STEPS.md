# 下一步规划

更新日期：2026-10-08。当前业务链路已到接车检查与车主决定；已付款订单不会自动关闭，车主未确认或订单有异议时不能派工。用户要求每完成一个阶段整理文档并上传 GitHub，执行规则见[阶段交付规则](STAGE_DELIVERY.md)。

## 当前优先：A5.3 商家派工与技师工作台

A5.1 已上传至 [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36)，规格提交六项 CI 通过；依赖尚未合并的 A4 PR #35。详细证据与最新检查入口见[A5.1 执行记录](A5_1_DISPATCH_SPEC_EXECUTION.md)。

A3 已合并至 [PR #34](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/34)。A4 已上传至 [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)，实现提交 `e0d4077` 六项 CI 通过（后端 271 项、小程序 120 项）。A5.1 已形成[规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)、[六个接口契约](../api/TECHNICIAN_DISPATCH.md)和[中文计划](../superpowers/plans/2026-10-08-a5-dispatch.md)：一单一位技师、本人接单开始施工、防护在 A6 报工校验。A5.2 已编码六个后端接口与 V012，商家通用 START_SERVICE 已停用；下一步 A5.3 接入商家派工界面与技师独立会话/本人工单。A5.2 证据见[执行记录](A5_2_DISPATCH_BACKEND_EXECUTION.md)。交付及 CI 见[当前进度](CURRENT_STATUS.md)与[A5.1 执行记录](A5_1_DISPATCH_SPEC_EXECUTION.md)。

## 业务开发顺序

| 阶段 | 范围与前置 | 阶段完成条件 |
| --- | --- | --- |
| A4 | 车主确认接车单与提出异议，依赖 A3 | 本人确认生效；异议置争议并阻断施工；权限、幂等、回滚和并发验证通过；文档与 PR 齐全 |
| A5 | 商家派工、技师本人工作台与接单，依赖 A4 | 未确认/争议订单不可派工；仅本店有效技师可被派；技师只能接本人单；派工与接单可联调并有审计 |
| 争议处理 | 明确商家处理记录、车主复核与恢复条件 | 原因、处理和最终决定完整留痕；争议未解决不能恢复派工/施工；需要单独规格 |
| A6 | 防护、施工/故障件/完工照片、方案、配件、工时、质检与报工，依赖派工归属 | 防护缺失不能报工；必要证据齐全才送核销；只能操作本人派工 |
| A7.1 | 完工与到店核销 | 服务端验码、防猜测、状态迁移与双审计成立；正式付款凭据的验收单列 |
| A7.2 | 评价、档案回写、经验卡片 | 本人评价、回写与卡片均不重复，失败可恢复；拆成独立任务和 PR |

A5 详细顺序、验收和需要统一的业务规则见[A5 下一阶段计划](../superpowers/plans/2026-10-08-a5-dispatch.md)。预约码已经由 A3 生成和展示；最终核销在 A7.1 实现，A4 只交付确认与异议。

## 每阶段文档与仓库交付

每个子阶段同步对应规格/计划、接口、OpenAPI、迁移、验收记录与进度。提交到 `codex/` 阶段分支并推送 GitHub；前序已合并时 PR 指向 `main`，否则指向直接依赖分支，依赖合并后调整基线并复核 CI。未完成验证保持草稿；不能把“已上传”写成“已验收”。见[交付规则](STAGE_DELIVERY.md)。

## 独立验收与外部依赖

真实相机与真机、测试证书下的直连图片预览、正式短信、正式微信支付/退款和公网环境仍各自待验收。继续按已有决定先推进本机业务开发，外部凭据和手机操作分别记录依赖，不伪造通过结果。

- [真实相机清单](../testing/REAL_CAMERA_ACCEPTANCE.md)、[接车验收](../testing/LOCAL_PICKUP_ACCEPTANCE.md)。
- [真机登录](../operations/LAN_DEVICE_LOGIN_RUNBOOK.md)、[网络环境](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)。
- [外部依赖](S0_DEPENDENCIES.md)、[三端及运营端缺口](ROLE_GAP_ANALYSIS.md)。

## 后续产品范围

履约闭环后按缺口清点推进增长能力、车主体验、运营与考核、上线运维。AI 流式输出、方案与商家联动、转人工按独立规格推进。OCR/VIN/车牌录车、评分提醒与附近商家等功能仍需各自数据源或业务规则。

旧规划、曾用网络路径与历史执行说明移至[2026-10-07 历史规划](history/NEXT_STEPS_2026-10-07.md)。历史文件中的“下一步”不代表当前执行顺序。
