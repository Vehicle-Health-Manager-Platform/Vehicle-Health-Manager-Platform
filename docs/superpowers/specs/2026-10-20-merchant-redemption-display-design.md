# R9 A4d-4a 商家到店核销设计

2026-10-20。前置 A4d-3d（商家施工防护）。范围：`apps/miniapp/src/pages/merchant/redeem.vue`（预约核销）、`apps/miniapp/src/pages/merchant/coupon-redeem.vue`（服务券独立核销），**后端、接口、迁移、`order-redemption.js` 与 `coupon-transactions.js` 的请求契约零改动**。

## 这两页的问题

### 预约核销 `redeem.vue`

1. **三层 `v-if/else-if` 判断混在模板里**：`receipt.value?.redemption` → `status==='PENDING_VERIFY' && receipt.loaded && !receipt.message && !has_payment_exception && !state.saved` → `COMPLETED` → 其他。顺序就是规则（**已核销凭据优先于订单状态**），但改一处就可能把已核销订单重新放出验码框。
2. **"读不到核销记录"没有独立状态**：`receipt.loaded === false` 时整块区域空白——用户既看不到验码框，也不知道为什么没有。
3. **码校验内联在模板属性里**：`:disabled="state.busy || remaining>0 || !/^[0-9]{6}$/.test(state.code)"`，正则与 `redemptionBody` 里那份各写一遍。
4. **金额直接插值**：`应付 ¥{{ detail.value.amount_due }}`，后端给什么显示什么，异常值会原样露给门店。
5. **测试核销的提示是条件字符串**：`v-if="payment_summary?.test_mode"` 里硬写"测试核销，未真实扣款，不可作为收款凭据"，与凭据卡的"未真实扣款，不可作为收款凭据"两处各写一半。

### 服务券核销 `coupon-redeem.vue`

6. **三格校验在模板里用 `:disabled` 拼**：`!(form.coupon && form.revision && /^[a-f0-9]{32}$/.test(form.code))`，与 `validServiceBody` 的 `safeId`/正则各写一遍，且**不说缺哪一格**。
7. **"不可核销"与"已经核销过"混为一谈**：预览返回 `redemption` 时 `can_redeem=false`，页面只显示"当前不可核销"，门店会以为券有问题，其实只是这张券已经在本店核销过。
8. **失败口径只有 `state.message`**：`unauthorized`/`forbidden` 时也给"重试"，登录失效的门店会在原地反复点。
9. **记录卡空态与页脚写在模板**：`items.length` 与 `total` 各拼一次字符串。

## 做法

新增 `apps/miniapp/src/services/merchant-redemption-display.js`，15 个导出（2 常量 + 13 映射）：

| 导出 | 解决什么 |
| --- | --- |
| `REDEEM_CODE_LENGTH` | `6`，与 `order-redemption.js` 的 `/^[0-9]{6}$/` 同源 |
| `COUPON_CODE_LENGTH` | `32`，与 `coupon-transactions.js` 的 `validServiceBody` 同源 |
| `redeemCodeView(code)` | 只留数字、超长截断、还差几位、能否提交 |
| `redeemStageView(detail, receipt)` | 七阶段：`no-order` / `redeemed` / `loading` / `history-no-record` / `blocked` / `not-now` / `ready` |
| `redeemOrderFacts(detail)` | 项目、订单号、金额、状态、测试核销提示、付款异常提示 |
| `redeemSubmitView(code, state, remaining)` | 按钮文案（"请等待 N 秒"/"验码并核销"）、能否点、一行原因 |
| `redeemReceiptView(redemption)` | 测试核销与真实核销的标题/语气/时间 |
| `redeemQueryFailureView(detail, receipt)` | 重新登录还是重试，二选一 |
| `couponFormView(form)` | 三格各自是否有效、缺什么一次列全、授权码进度 |
| `couponPreviewView(preview)` | 可核销 / 已核销 / 不可核销三态与原因 |
| `couponSavedView(saved)` | 凭据三行（含"佣金 0.00"与"不是收款"） |
| `couponRecordsCopy(records)` | 空态文案与"共 N 条 · 已加载 M 条"页脚 |
| `couponCooldown(tick, waitUntil)` | 限流剩余秒数（向上取整） |
| `couponFailureView(state)` | 失败文案、能否重登、结果是否不确定 |
| `couponSubmitView(state, tick, form)` | 两个按钮的可用性与三个按钮文案 |

### 阶段顺序是业务规则

| 顺序 | 阶段 | 判断 | 为什么在这个位置 |
| --- | --- | --- | --- |
| 1 | `no-order` | `!detail` | 订单都没读到 |
| 2 | `redeemed` | 有凭据行 | **凭据优先于一切**：已核销的单不该因为事后出现异常又放出验码框 |
| 3 | `loading` | `PENDING_VERIFY` 且核销记录未读到 | 没确认"没有凭据"之前不显示验码框，否则会对已核销订单重复提交 |
| 4 | `history-no-record` | `COMPLETED` 且无凭据 | 历史完成单没有可信凭据，提示人工核对收款 |
| 5 | `blocked` | `has_payment_exception === true` | 付款异常阻断核销 |
| 6 | `not-now` | 其余状态（含未知值） | 兜底，**不把未知状态当可核销** |
| 7 | `ready` | 其余（即 `PENDING_VERIFY`） | 只有落到这里才出验码框 |

凭据行有两个来源：读取结果的 `receipt.value.redemption`，与刚提交成功的回执 `state.saved`。两者字段同为 `{redeemed_at, test_mode}`，页面合并成 `receiptRow` 后同时喂给阶段卡与凭据卡——**修掉了"提交成功但重新读取还没回来"这段空档里验码框会重新出现的问题**（`createRedemptionFlow` 自身有 `state.saved` 闸门，但此前 UI 靠的是 `!state.saved` 内联条件，改造时最容易漏掉）。

### 金额只认契约格式

`money()` 只接受 `^(0|[1-9]\d{0,7})\.\d{2}$` 的字符串（与 `reservations.js`/`coupon-transactions.js` 的金额校验器同款），非法值显示 `¥—`。不做 `Number()` 再 `toFixed(2)`：那样会把浮点尾巴显示给门店，而且把"后端给了脏数据"这件事悄悄抹平。数字入参只在测试与兼容场景下格式化。

### 服务券：已核销不是"不可核销"

`merchantPreview` 在券已核销时会带 `redemption` 且 `can_redeem=false`。此时：

- 徽标显示 **已核销**（`tone: 'ok'`），不是红色"不可核销"；
- 附一句"该券已于 <时间> 核销，凭据见下方"；
- 核销按钮不出现，但凭据卡（`coupon-saved`）同时显示。

只有"没有 redemption 且 `can_redeem=false`"才是真的不可核销，此时才给"请核对券的发行门店、标准项目与授权有效期是否与本店一致"。

### 两个按钮什么时候能点

`couponSubmitView` 把"锁定"统一成 `busy || 冷却中 || uncertain` 三合一：

- 核对：三格齐 + 未锁定；
- 核销：`preview.can_redeem === true` + 未锁定（只认严格 `true`，字符串 `'true'` 不算）；
- `uncertain`（网络/服务不可用/协议异常，结果未定）时两个按钮都锁住，只留"重试原核销请求"——这是既有 `createMerchantCouponFlow` 的语义，页面不再自己判断。

## 保留的契约

- 预约核销仍先过 `redemptionBody(order, code)`，再走 `createRedemptionFlow` 的二确认、幂等键（`imageRequestKey`）与 `onChanged: refresh`；限流仍由 `state.waitUntil` + `Retry-After` 驱动。
- 服务券仍走 `createMerchantCouponFlow`（`confirm` 里"佣金0.00，不产生现金支付"的二次确认原样保留），扫码仍用 `parseServiceCouponQr` + `apiRuntime.scanCode`，记录列表仍走 `couponTransactionsApi.serviceRecords`。
- **端到端脚本依赖的文案一字未改**：`scripts/native-completion-e2e.cjs` / `native-work-files-e2e.cjs` 按按钮文本 tap、按提示断言，所以"验码并核销"、"测试核销已完成"、"测试核销，未真实扣款，不可作为收款凭据"、"请等待 N 秒"必须继续由映射层产出同样的字面量。
- 既有锚点全部保留并新增：`redeem-form`/`redeem-order`/`redeem-stage`/`redemption-receipt`/`redeem-form-card`/`redeem-code`/`redeem-submit`/`redeem-test-warning`/`redeem-error`/`redeem-login`；`coupon-redeem-form`/`coupon-form`/`coupon-scan`/`coupon-id`/`coupon-revision`/`coupon-code`/`coupon-preview`/`coupon-preview-card`/`coupon-redeem`/`coupon-saved`/`coupon-error`/`coupon-records`/`coupon-empty`/`coupon-retry`。
- **只读态不出输入框**：核销页整页只有验码卡里一个 `input`，且该卡片由 `stageView.stage === 'ready'` 控制；单测与证据报告各断言一次（`input_count === 1`）。

## 验收口径

1. `node --test test/*.test.js`：997 项全通过（新增 17 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：两页 wxml 各含 `ux-hero`/`ux-badge` 与全部静态锚点；`input` 计数 1 / 3。
5. **反向断言**：由映射供给的六条文案（"验码并核销"、"测试核销已完成"、"测试核销，未真实扣款，不可作为收款凭据"、"确认交付并核销服务券"、"核对本店服务券与授权"、"重试原核销请求"）在 wxml 里**必须搜不到**（模板只剩 `{{...}}` 绑定），同时必须出现在编译后的 `services/merchant-redemption-display.js` 里。前者证明模板没写死，后者证明模块真的被编进去了。

## 不覆盖

退款页与券列表页（`merchant/coupons`、`merchant/staff` 等仍在缺口清单）；真机与模拟器渲染；视觉回归截图；暗色模式与无障碍朗读。`order-redemption.js`/`coupon-transactions.js` 的请求校验与幂等沿用既有用例，本轮只新增展示层用例。
