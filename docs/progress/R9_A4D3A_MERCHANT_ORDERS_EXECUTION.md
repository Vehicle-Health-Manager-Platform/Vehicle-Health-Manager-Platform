# R9 A4d-3a：商家订单阅读面执行记录

2026-10-20。前置 PR188（A4d-2h5 运营审核台）；分支 `codex/r9-a4d3a-merchant-orders`。目标是把商家订单列表与详情的状态语义、入口矩阵收进可单测的纯函数——**后端与接口零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/ui-tone.js`（`TONE_CLASS`/`toneClass`），`public-review-display.js` 改为 re-export：两个域共用一份 `ux-badge-*` 映射，避免各写一份 class 名（写错不报错、只静默丢样式）。
- 新增 `apps/miniapp/src/services/merchant-order-display.js`：9 个导出（`merchantOrderStatusView`、`merchantPaymentView`、`paymentExceptionCopy`、`merchantOrderRow`、`MERCHANT_ORDER_FILTERS`、`merchantOrderListCopy`、`merchantPriceFacts`、`merchantEntryList`、`merchantActionHint`）。
- 重做 `apps/miniapp/src/pages/merchant/orders.vue`：状态筛选从"4 个按钮 + 三个字符串字面量判断"改为 `MERCHANT_ORDER_FILTERS` 驱动的胶囊筛选；行卡片给出项目、状态徽标、金额、预约时间，测试支付与付款异常各自有徽标；空态区分"筛选没有"与"本店没有"；错误卡片区分"需重新登录"与"可重试"。
- 重做 `apps/miniapp/src/pages/merchant/order-detail.vue`：事实行（金额/报价来源/订单号/预约/支付期限/关闭原因/门店/地址）统一排版；支付徽标由 `merchantPaymentView` 驱动；7 组 `includes(...)` 换成 `merchantEntryList` 的「相关处理」卡片；无可执行动作时用 `merchantActionHint` 说下一步。
- 新增 `apps/miniapp/test/merchant-order-display.test.js`：11 项（映射 10 项 + 页面契约扫描 1 项）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 列表状态只映射三种 | `RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`/`COMPLETED`/`DISPUTED` 直接渲染英文枚举 | 8 种状态全部走 `stateLabel` + 色调 + 解释 |
| 支付码直出 | `支付状态 {{ payment_summary.status }}` → `SUCCEEDED`/`CREATED` | `merchantPaymentView` 给出"支付成功/已创建待支付"并区分测试渠道与需人工核对 |
| 状态机复制进模板 | 详情页 7 组 `['PAID','RECEIVED',…].includes(status)` | 一张 `ENTRY_DEFS` 表，单测按状态逐条断言入口集合 |
| 报价与订单金额混在一起 | `报价版本 {{ price_snapshot?.version \|\| '未提供' }} · 下单价 ¥{{ price_snapshot?.price \|\| amount_due }}` | `merchantPriceFacts` 分成"应付/报价版本/下单价"三行 |
| 空态与页脚缺失 | 一行"当前筛选条件下暂无本店订单。" | 区分筛选空态与空店，页脚给"共 N 条 · 已加载 M 条" |

## 结果

A4d-3a 本地 PASS：小程序单测 **946 / 946 通过**（0 失败 / 0 跳过，新增 11 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/orders.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty` 且保留行内"查看订单详情"按钮；`merchant/order-detail.wxml` 含 `ux-hero`/`ux-badge` 并保留 `merchant-order-status`、`merchant-order-entries` 锚点。报告：`docs/testing/reports/R9_A4D3A_MERCHANT_ORDERS_2026_10_20.json`（`passed: true`）。

## 兼容约束（既有端到端流程）

`apps/miniapp/test/local-merchant-orders-browser-flow.js` 断言了四件事，本轮全部保留：行内必须有真实 `<button>`（它点击 `[data-testid="merchant-order-<id>"] uni-button`）、页面必须有 `预约日期：<日期>`、列表文本必须含"已支付"、详情必须含"本订单存在付款异常，待核对"。这四条已写进页面契约单测的注释里，避免下一轮改造顺手删掉。

## 实际执行与证据

- 远程 CI（PR189，head `5cf954b`，run `39438049355`，`conclusion=success`）：八项 job 全部 `completed / success` —— `web` 22 秒、`schema-and-ocr` 38 秒、`schema-mysql` 41 秒、`database-recovery` 57 秒、`miniapp` 62 秒、`performance-baseline` 120 秒、`backend` 48 分 11 秒（14:18:43Z → 15:06:54Z）、`compose-smoke` 2 分 28 秒（15:06:57Z → 15:09:25Z）。
- `backend` 的 38 个步骤全部通过，含第 7 步 `mvn -B -Pcore-coverage clean verify` 与各条隔离真跑验收链路。本阶段只改 `merchant/orders.vue`、`merchant/order-detail.vue` 与新增的展示映射，这条通过说明前端改动没有连带破坏后端验收。
- `compose-smoke` 有 `needs: [web, backend, schema-mysql]`，所以它是 `backend` 结束后才创建的第 8 个 check-run；`backend` 未完成时只看到 7 项属于正常顺序，不是缺项。
- `mergeable_state=clean`（按栈内 base `codex/r9-a4d2h5-operator-review-console` 计算）。
- 本地侧证据见 `docs/testing/reports/R9_A4D3A_MERCHANT_ORDERS_2026_10_20.json` 与 `docs/testing/LOCAL_UI_MERCHANT_ORDERS_ACCEPTANCE.md`。
- PR 保持草稿，未合并、未部署；合并前需先把 base 改回 `main`（堆叠 PR 逐支改）。

## 未覆盖

工位与派工页（`merchant/slots.vue`、`merchant/dispatch.vue`，留 A4d-3b）、接车检查/防护/退款页面本身、真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。全仓 108 个页面中现已 11 个接入设计系统，剩余按域分批。

## 后续

A4d-3b 商家工位与派工 → 其他独立缺口 → A5 交付 → B 外部接入 → C 真机。PR 依赖 PR188，保持草稿，合并前需把 base 改回 `main`。
