# R9 A4d-2h5：运营审核台执行记录

2026-10-20。前置 PR187（A4d-2h4 评价与公开展示面）；分支 `codex/r9-a4d2h5-operator-review-console`。目标是把运营审核台两个页面的入口与状态语义收进可单测的纯函数——**后端与接口零改动**。

## 本轮实现

- `apps/miniapp/src/services/public-review-display.js` 新增 7 个导出：`OPERATOR_GROUPS`、`operatorEntryGroups`、`operatorCapabilitySummary`、`moderationCardView`、`moderationConsoleCopy`、`reasonPicker`/`reasonLabel`、`operatorCardFacts`。
- 重做 `apps/miniapp/src/pages/operator/review.vue`：21 个平铺按钮改为按权限分组的 4 个分组卡片，每行一个 `ux-row` 入口（标题 + 说明 + 箭头）；权限摘要卡片列出当前账号已获授权的能力标签；待审经验摘要单独成卡，行内徽标区分"可批准/缺有效车型"。
- 重做 `apps/miniapp/src/pages/operator/review-texts.vue`：队列卡片给出 `待审 N · 本页 M · 其中 K 条来源已失效`；每行待审文字用徽标 + 版本 + 只能驳回的原因；空态用 `ux-empty` 说明为什么不会有内容；驳回理由走 `reasonPicker`。
- 新增 `apps/miniapp/test/operator-review-console.test.js`：8 项（映射 7 项 + 页面契约扫描 1 项）。
- 更新 `apps/miniapp/test/community-questions.test.js` 的一处断言：运营入口的权限收口从"页面里出现 `canCommunityManage`"改为"页面走 `operatorEntryGroups`、权限键在映射模块里"，意图不变（入口仍受权限控制），避免入口清单搬走后断言失去意义。

## 修掉的具体缺陷

| 缺陷 | 表现 | 处理 |
| --- | --- | --- |
| 入驻审核入口未收口 | 任何运营账号都能看到"商家入驻审核" | 归入经营配置组，由 `canOnboard` 过滤 |
| 不可批准要试才知道 | 待审文字只有一行静态说明，批准按钮灰着但没说原因 | `moderationCardView` 行内徽标"来源已失效"+ "只能驳回" |
| 队列数量缺失 | 只有"当前没有待审文字" | `moderationConsoleCopy` 给三类计数与"来源已失效"条数 |
| 驳回理由两套实现 | 文字页与摘要页各写一套 `REASONS[code] \|\| '请选择驳回理由'` | 统一 `reasonPicker`/`reasonLabel` |
| 车型缺失靠人眼看 | `{{ card.model_id ? … : '缺少有效车型，不能批准' }}` | `operatorCardFacts().canApprove` 同时驱动徽标、文案、按钮禁用 |

## 结果

A4d-2h5 本地 PASS：小程序单测 **935 / 935 通过**（0 失败 / 0 跳过，新增 8 项，1826ms）；`npm run build:miniapp` exit 0 且"共享选择器"与"无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`operator/review.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/`operator-entry`，`operator/review-texts.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/`moderation-row`。报告：`docs/testing/reports/R9_A4D2H5_OPERATOR_REVIEW_CONSOLE_2026_10_20.json`（`passed: true`）。

## 一处认知修正

前两个阶段我反复写"并发下的公开资格竞态与审核并发未覆盖"。核查后这个缺口不存在，service 层已有 4 条真正的竞态测试：

- `JdbcOrderReviewsTest.race()`：两会话并发提交，内容相同落 1 条评价、内容不同恰好 1 个 `44002`；
- `JdbcPublicReviewTextsTest.concurrentWithdrawAndApproveNeverLeaveWithdrawnTextVisible()`：批准与撤回并发，恰好一个 409，且撤回后文字不可见；
- `JdbcReviewSummariesTest.concurrentOldVersionGrantsOnlyMutateOnce()`：并发旧版本授权只改一次（200 + 409）；
- `JdbcReviewSummariesTest.thirdConcurrentRequestDegradesInsteadOfStartingAnotherUpstreamCall()`：第三个并发摘要请求降级为 `FALLBACK`，不重复打上游。

本轮不再单开并发阶段，改为在文档里把这条结论写清楚，避免下一阶段重复"补"已存在的覆盖。

## 未覆盖

真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、截图对比、暗色模式、无障碍朗读、运营端其余 23 个页面（仍是旧视觉，等 A5 前分批统一）、审核链路的 HTTP 级并发（同上，service 层已覆盖）。

## 后续

按既定顺序：其他独立缺口 → A5 交付 → B 外部接入 → C 真机。全仓 108 个页面中仅 9 个接入设计系统，剩余部分应成批处理而不是单页开阶段。PR 依赖 PR187，保持草稿，合并前需把 base 改回 `main`。
