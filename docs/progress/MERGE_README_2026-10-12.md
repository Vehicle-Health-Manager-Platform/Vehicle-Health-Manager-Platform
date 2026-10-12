# 2026-10-12 合并与 README 更新记录

用户要求合并今天完成的内容，同时详细更新 GitHub 根 README；本次因此解除此前 README 等待指示的限制。业务产品保持微信小程序与共用后端，PC 运营后台不实施。

## 已完成合并

| PR | 内容 | 最终目标 |
| --- | --- | --- |
| #120 | 已确认的三张微信开发者工具模拟器截图与 README 图集 | main |
| #121 | 原始交付差异审计与三端剩余规划 | main |
| #122–#124 | R3c3 限时脱敏摘要分享、撤销、微信接收与验收记录 | main |
| #125–#127 | R4a1 本人目标到达提醒与微信入口 | main |
| #128–#130 | R4a2 本人结构化保养、保险与年检资料 | main |
| #131–#133 | R4a3 六类本人当前原始读数 | main |
| #134–#136 | R4a4 不可变读数历史、修正链与清空履历 | main |
| #137–#139 | R4b1 商家详情、原生区域筛选与预约衔接 | main |

合并前逐支核验最新 head 的六项 CI 为 SUCCESS。先合前序，再将后一支 base 改回 main，以 merge commit 保留祖先关系并使用 `--match-head-commit` 锁定审核提交。未将任何子 PR 合入前序业务分支。全部合并后查询 open PR 为零。

本轮业务合并后 main 提交：`0031f3a3eeaa6dae5285a316c6a382e93a589749`。业务收口 PR [#139](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/139) 的最终 [CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/38245718489) 六项通过，后端633/633、小程序388/388；对应 main 合并运行 [38257453034](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/38257453034) 及文档 PR/最终 main 运行分别在 GitHub Actions 跟踪，不将排队状态写成已通过。

## README 与进度同步

- 根 README 汇总三端权限、已合并阶段至 R4b1、剩余功能顺序、六项测试证据与数据库/API 基线。
- 保留 #120 的三张真实模拟器截图及来源说明，不使用旧 H5 原型替代小程序，也不把合成数据或模拟器称为真机验收。
- 保留开发环境、mp-weixin 导入路径、后端/Compose 启动、私有配置和数据库升级说明。
- 修复阶段表被空行拆断的问题，移除过时的分支/未合并概述。
- 当前进度、下一步、三端缺口和总规划顶部追加合并后状态；历史段落保留当时证据，以最新状态覆盖旧“未合并/README未更新”描述。
- 未修改个人原始交付文档，也未提交构建产物或凭据。

本次文档仅反映现有实现，不新增业务、部署或真实资金操作。新增文档链接、图集路径与差异格式在提交前核验。

## 后续

先 [R4b2a 位置与距离，再 R4b2b 公开评价](R4B2_LOCATION_REVIEWS_NEXT_PLAN.md)，随后 R4c AI 预约、R5 券、R6 邀请、R7 三端体验、R8 积分经营社区；正式短信、微信身份/手机号、资金、识别供应商与真机/发布验收保持 R9。可信健康来源与评分、完整证件和五类/提前提醒缺口继续保留。
