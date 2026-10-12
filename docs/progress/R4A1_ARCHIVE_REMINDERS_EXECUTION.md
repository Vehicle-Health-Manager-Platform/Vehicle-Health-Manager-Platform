# R4a1 个人档案目标与到达提醒执行记录

日期：2026-10-12。

## 分阶段交付

1. 用户确认目标到达提醒；中文[规格](../superpowers/specs/2026-10-12-r4a1-archive-reminders-design.md)和[计划](../superpowers/plans/2026-10-12-r4a1-archive-reminders-plan.md)独立提交上传，随后端 PR 交付。
2. 后端 [#125](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/125)：来源权限、三类目标、状态、版本/幂等/审计、V028 和中文契约。详见[后端执行](R4A1_ARCHIVE_REMINDERS_BACKEND_EXECUTION.md)。
3. 微信 [#126](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/126)：档案入口、来源设置、首页摘要、提醒列表、确认及生命周期。详见[微信执行](R4A1_ARCHIVE_REMINDERS_MINIAPP_EXECUTION.md)。
4. 本收口阶段同步进度、README、缺口与下一步，并上传 PR。

## 验证

本地定向后端 **8/8**，微信 **351/351**，mp-weixin/H5 构建及新增原生产物通过；init/baseline 41 表、完整迁移 68 表，OpenAPI 149 操作。证据层次、命令和未验项见[验收](../testing/LOCAL_ARCHIVE_REMINDERS_ACCEPTANCE.md)。GitHub 全量回归与六项 CI 按每支最新 head 记录于 PR 正文；本地定向测试不等同全量。

## 尚欠与下一步

R4a1 只完成本人档案关联的结构化目标，不代表完整证件/养护字段、首页五类提醒、六指标趋势、健康分数、违章/官方查询、提前提醒或订阅发送已完成。

下一独立单元 **R4a2 数据来源与结构化字段**：先盘点证件/保养现有字段和交付要求，明确动力电池与低压电瓶口径，再冻结录入/确认/缺失状态契约；后端与微信逐段实施，不从自由备注生成测量数据或分数。具体门槛见[下一步方案](R4A2_STRUCTURED_ARCHIVE_NEXT_PLAN.md)。

门店、技师的 R4b/R7/R8 等欠项继续跟踪；短信、真实微信身份、资金、OCR/VIN 及真机仍最后接入，PC 不实施。

## 合并依赖

#121 → #122 → #123 → #124 → #125 → #126 → 本收口。每支前序合入后把下一支 base 改回 main，核对变更范围并重跑检查后再合。本轮上传，不自动合并/部署。#120 README 图集独立。
