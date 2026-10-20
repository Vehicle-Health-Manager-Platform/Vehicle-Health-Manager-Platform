# R9 A4d-3a 商家订单阅读面设计

2026-10-20。前置 A4d-2h5（运营审核台）。范围：`pages/merchant/orders.vue`（本店订单列表）与 `pages/merchant/order-detail.vue`（本店订单详情），**后端、接口、迁移零改动**。

## 为什么先做这两页

车主侧六个页面与运营审核台都已统一，商家侧还没动。而商家订单这两页有三个不是"审美问题"的真实缺陷：

1. **列表把后端枚举直接渲染给门店看**：页面只映射了 `PENDING_PAYMENT`/`PAID`/`CLOSED` 三种，其余五个状态走 `row.status` 原样输出——门店会看到 `IN_SERVICE`、`PENDING_VERIFY`、`DISPUTED` 这种英文码，而 `stateLabel()` 早就有一份权威中文表。
2. **详情页把支付码直接渲染**：`支付状态 {{ payment_summary.status }}` → `SUCCEEDED`、`CREATED`。支付渠道是 `LOCAL_TEST` 时页面虽有测试支付提示，但支付状态本身仍是英文码。
3. **状态机被复制进模板**：详情页有 7 组 `['PAID','RECEIVED',...].includes(state.value.status)`，各自决定退款、接车、派工、防护、核销、接车单、工作区入口。改后端状态流转时，这 7 处没有任何机制提醒你跟。

## 做法

### 1. 色调映射抽成公共模块

新增 `apps/miniapp/src/services/ui-tone.js`（`TONE_CLASS` / `toneClass`），`public-review-display.js` 改为 re-export。两个域共用一份 class 名——写错的 class 不会报错，只会静默丢样式，所以只能有唯一来源。

### 2. 新增 `apps/miniapp/src/services/merchant-order-display.js`

| 导出 | 输入 | 输出 | 解决什么 |
| --- | --- | --- | --- |
| `merchantOrderStatusView(status)` | 状态码 | `{label,tone,copy}` | label 复用 `order-status.js` 的唯一文案表，这里只补色调与"下一步该干什么" |
| `merchantPaymentView(summary)` | 支付摘要或 null | `{label,tone,copy,testMode}` | 支付码翻译成人话；测试渠道与"需人工核对"分别说明 |
| `paymentExceptionCopy(row)` | 订单行 | 字符串 | 保留被端到端流程断言过的原文案 |
| `merchantOrderRow(row)` | 列表行 | `{title,statusLabel,statusTone,statusCopy,amount,appointment,testMode,exception,exceptionCopy,closeCopy}` | 门店扫读的四件事：项目、状态、金额、时间 |
| `MERCHANT_ORDER_FILTERS` | — | 4 个筛选项 | 状态筛选项与后端枚举绑定，页面不再写死中文 |
| `merchantOrderListCopy(list,filters)` | 列表 + 当前筛选 | `{empty,emptyTitle,emptyCopy,footer}` | 区分"这个筛选没有"和"本店还没有订单" |
| `merchantPriceFacts(row)` | 订单行 | `{amount,quoteVersion,quotePrice}` | 订单金额与"下单价来自哪个报价版本"分开说 |
| `merchantEntryList(order)` | `{status,close_reason}` | `[{key,label,route,params}]` | 7 组 `includes(...)` 收成一张表 |
| `merchantActionHint(status)` | 状态码 | 字符串 | 没有可执行动作时说清下一步，而不是只留"无操作" |

### 3. 入口矩阵

| 状态 | 入口（按显示顺序） |
| --- | --- |
| `PENDING_PAYMENT` | 无 |
| `PAID` | 接车检查、店长取消退款处理 |
| `RECEIVED` | 派工（"派工给本店技师"）、施工防护与记录、查看接车单、退款处理、必要资料与授权档案 |
| `IN_SERVICE` | 同上（派工按钮文案变"查看派工结果"） |
| `PENDING_VERIFY` | 派工、施工防护、核销验码、接车单、退款、工作区 |
| `COMPLETED` | 派工结果、施工防护、查看核销记录、接车单 |
| `DISPUTED` | 施工防护、接车单、退款处理（派工与核销被阻断） |
| `CLOSED` | 无；若 `close_reason=REFUND_CANCELLED` 则保留退款处理 |

入口 key 同时是 `data-testid` 后缀，所以 `merchant-order-dispatch`、`merchant-order-redeem` 两个已有锚点保持不变。

## 兼容约束（来自既有端到端流程）

`apps/miniapp/test/local-merchant-orders-browser-flow.js` 是一个真实浏览器流程，断言了四件事，改造必须全部保留：

1. 列表行容器 `data-testid="merchant-order-<id>"` 内**第一个 `uni-button`** 可点击 → 每行必须保留显式"查看订单详情"按钮，不能改成整行可点。
2. 页面必须出现 `预约日期：<日期>` 文本 → 日期筛选行文案不能改。
3. 列表文本必须包含"已支付" → `stateLabel('PAID')` 为"已支付待接车"，仍包含该子串。
4. 详情页必须包含"本订单存在付款异常，待核对" → 由 `paymentExceptionCopy` 逐字保留。

## 验收口径

1. `node --test test/*.test.js`：946 项全通过（新增 11 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`merchant/orders.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/"查看订单详情"；`merchant/order-detail.wxml` 含 `ux-hero`/`ux-badge` 且保留 `merchant-order-status`、`merchant-order-entries` 两个锚点。
5. 页面契约：两页必须引 `merchant-order-display.js`、必须用 `toneClass(`；`orders.vue` 不得再出现 `row.status === 'PENDING_PAYMENT' ?` 这类三元；`order-detail.vue` 不得再出现 `['PAID'` 这种数组字面量与 `payment_summary.status }}`。

## 不覆盖

工位与派工页（`merchant/slots.vue`、`merchant/dispatch.vue`，留作 A4d-3b）、接车检查与防护页、退款页、真机与模拟器渲染、视觉回归截图、暗色模式与无障碍朗读。
