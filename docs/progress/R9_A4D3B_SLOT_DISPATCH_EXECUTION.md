# R9 A4d-3b：商家时段与派工执行记录

2026-10-20。前置 PR189（A4d-3a 商家订单阅读面）；分支 `codex/r9-a4d3b-slot-dispatch`。目标是把时段状态与派工阶段收进可单测的纯函数，并修掉候选加载时机与守卫的耦合——**后端与接口零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/merchant-scheduling-display.js`：10 个导出（时段行/列表/项目选项/发布表单事实、派工阶段/候选行/候选列表/已有派工/提交文案/是否该拉候选）。
- 重做 `apps/miniapp/src/pages/merchant/slots.vue`：在售项目从"模板里 filter 出的按钮串"改为 `slotProjectOptions` 驱动的胶囊选择；新增"可发布/待补全"徽标与时间窗、名额、还差什么的提示；时段行给出开放/已关闭/已约满三种状态与"剩余 x / y"；空态与页脚来自映射。
- 重做 `apps/miniapp/src/pages/merchant/dispatch.vue`：4 层 `v-if` 链收敛为 `dispatchStageView` 的六阶段（`no-order`/`existing`/`disputed`/`not-ready`/`loading`/`ready`）；已有派工按`派工状态`表展示；候选行与提交文案来自映射；阶段锚点 `dispatch-stage-*` 便于核对。
- 新增 `apps/miniapp/test/merchant-scheduling-display.test.js`：11 项（映射 9 项 + 页面契约 1 项 + 重试入口 1 项）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 约满没有独立状态 | `开放预约 · 剩余 0 / 5`，车主端已约不上 | 单独 `已约满` 徽标 + "如需继续接单请另发时段" |
| 发布表单不说差什么 | 按钮可点，提交时才抛"请选择在售项目，容量填写1–100" | 表单摘要常驻显示项目/时间窗/名额，并给具体提示（`ready` 只提示，不阻止提交） |
| 派工阶段靠模板链 | 4 层 `v-if/v-else-if`，顺序即规则但不可断言 | `dispatchStageView` 六阶段，单测锁顺序（含"已有派工优先于争议"） |
| 候选加载时机耦合 | `watch(order.value)`：`ready` 依赖后到的派工结果，阶段切换不触发 watcher | 改 `watch(stageView.stage)`，配合 `shouldLoadCandidates` 判定 |
| 派工状态与订单状态混用风险 | 模板直接 `assignmentStatusLabel(...)` 与 `stateLabel(...)` 各自取用 | `dispatchExistingFacts` 明确只用派工自己的标签表，订单状态走 `merchantOrderStatusView` |

## 结果

A4d-3b 本地 PASS：小程序单测 **957 / 957 通过**（0 失败 / 0 跳过，新增 11 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/slots.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/"发布预约时段"/"关闭预约时段"；`merchant/dispatch.wxml` 含 `ux-hero`/`ux-badge`/`dispatch-submit`/`dispatch-existing-status`/"确认派工"/"加载更多候选技师"。报告：`docs/testing/reports/R9_A4D3B_SLOT_DISPATCH_2026_10_20.json`（`passed: true`）。

## 一条工具事实（写进文档避免下次踩）

mp-weixin 编译产物会把绑定形式的 `data-testid`（`:data-testid="'candidate-' + id"`）压成 `{{O}}` / `{{row.h}}`，**在 wxml 里搜不到原文**。所以证据报告只能核对静态锚点与静态文案，行级锚点由单测扫描源码保证。第一次生成报告时 `dispatch-stage-` 判为 false 就是这个原因，不是页面缺锚点。

## 未覆盖

接车检查/防护/核销/退款页、真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。全仓 108 个页面中现已 13 个接入设计系统。

## 后续

商家侧"订单 → 时段 → 派工"三个主流程页已统一，下一批可转向 `merchant/pickup`（接车检查，9573 字符）与 `merchant/protection`（施工防护，7453 字符），或按既定顺序进入 A5 交付准备。PR 依赖 PR189，保持草稿，合并前需把 base 改回 `main`。
