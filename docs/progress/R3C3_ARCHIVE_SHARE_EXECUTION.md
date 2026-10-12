# R3c3 档案摘要限时分享与撤销执行记录

日期：2026-10-12。

## 交付

- 用户确认设计已记录为[规格](../superpowers/specs/2026-10-12-r3c3-archive-share-design.md)和[计划](../superpowers/plans/2026-10-12-r3c3-archive-share-plan.md)。
- 后端 PR [#122](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/122)：五个 OWNER 接口、不可变白名单快照、到期/撤销/源账号与车辆归属检查、哈希能力凭据、幂等和访问审计。中文契约见[档案分享](../api/ARCHIVE_SHARES.md)。
- 微信 PR [#123](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/123)：分享管理与只读接收、原生转发、登录返回、重试与页面生命周期清理，见[微信执行](R3C3_ARCHIVE_SHARE_MINIAPP_EXECUTION.md)。
- 迁移 V027 可重复执行；初始化/基线生成40表，完整版本链67表，OpenAPI146操作。已有环境只追加 V027，不重放基线来升级业务结构。没有公开文件或历史全文回填。

## 验证与修复

本地后端 **14/14** 定向验证通过（真实 MySQL、HTTP角色、并发幂等、撤销读取竞争、有效分享上限与历史列表）。列表优先未过期未撤销记录，保证车主能找到全部有效分享。微信341/341、mp-weixin/H5与新增原生页面产物通过。详细范围及原生待验收见[验收](../testing/LOCAL_ARCHIVE_SHARE_ACCEPTANCE.md)。

首次 GitHub CI：backend/miniapp/web/schema-and-ocr 通过，schema-mysql 发现旧66表断言使 compose 跳过；已修正为67并重新推送。最终状态见各 PR 检查，不以首次旧结果作为最新结论。

## 合并顺序

依赖规划文档 #121 → 后端 #122 → 微信 #123 → 本收口文档。每个前序合入后，把下一个 PR base 改回 main 并重新检查 CI，再合入下一支。#120 README 图集独立。本轮只上传 PR，不自动合并/部署。

## 下一步

R4a1 先做档案结构化字段、本人证件到期/养护提醒及可追溯来源；六指标/健康分数待明确来源与电池/电瓶口径后再实施。正式短信、真实微信身份、资金、OCR/VIN与真机验收保持最终接入。PC后台不实施。
