# 商家订单阅读面本机验收

2026-10-20。范围：`pages/merchant/orders.vue`、`pages/merchant/order-detail.vue` 两个页面的展示层改造，新增 9 个纯映射、1 个公共色调模块与 11 项单测。**无后端、无迁移、无 API 变更**。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（946 项，含新增 11 项商家订单映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_merchant_order_evidence.py   # 生成 docs/testing/reports/R9_A4D3A_MERCHANT_ORDERS_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 946 / # pass 946 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

编译产物结构核对：`merchant/orders.wxml` 含 `ux-hero`、`ux-badge`、`ux-empty`、"查看订单详情"；`merchant/order-detail.wxml` 含 `ux-hero`、`ux-badge`，并保留 `merchant-order-status`、`merchant-order-entries` 两个锚点。报告里 `order_status_anchor` 在列表页为 false、`row_button` 在详情页为 false，是两份不同页面的正常结果。

## 新增测试覆盖

`apps/miniapp/test/merchant-order-display.test.js`（11 项）：

- 8 个订单状态逐一有标签、色调与解释；`PENDING_PAYMENT`/`PENDING_VERIFY` 为 warn、`DISPUTED` 为 risk、`COMPLETED`/`CLOSED` 为 muted；`PAID` 标签仍包含"已支付"（端到端流程依赖）。
- 支付摘要：未发起支付、成功、失败、已关闭、未知状态；`LOCAL_TEST` 渠道给测试支付文案；`requires_review` 覆盖为 risk 并提示人工核对。
- 付款异常文案逐字保留，非异常为空串（`undefined` 不抛错）。
- 列表行：项目名兜底"历史订单"、金额格式、预约时间含北京时间、测试支付/异常标志、关闭原因。
- 列表空态：筛选命中与空店两种文案不同；页脚"共 N 条 · 已加载 M 条"；`undefined` 列表视为空。
- 价格事实：应付/报价版本/下单价三行，缺 `price_snapshot` 时回落到订单金额，缺版本给"报价版本未提供"。
- 入口矩阵：逐状态断言入口 key 集合（含 `CLOSED + REFUND_CANCELLED` 仍给退款入口）；派工与核销按钮文案随状态变化但 key 不变；退款/接车单/工作区入口必须带 `role=merchant`。
- 无动作提示：`PAID` 指向接车检查，其余说明"没有可执行的履约操作"。
- 筛选胶囊与 `ORDER_STATES` 绑定；色调映射在评价域与商家域是同一份（`TONE_CLASS` 相等）。
- 页面契约：两页必须引映射、必须有 `ux-hero`/`ux-card`/`toneClass(`；列表页必须保留空态、行锚点、行内"查看订单详情"按钮与 `预约日期：`；详情页必须保留三个既有锚点，且不得再出现 `['PAID'` 数组字面量与 `payment_summary.status }}`。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真实浏览器流程 `apps/miniapp/test/local-merchant-orders-browser-flow.js` 需要本地后端与 H5 预览服务，本轮未跑；本轮改动按它的四条断言逐条做了保留（行内按钮、日期文案、已支付子串、异常原文），并在页面契约单测里锁定。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
