# R9 A4d-4a：商家到店核销执行记录

2026-10-20。前置 PR192（A4d-3d 商家施工防护，已随全栈合并进 `main`）；分支 `codex/r9-a4d4a-redemption`（从 `main` 直开，**不再叠栈**）。目标是把预约核销与服务券核销的阶段判断、码校验、按钮可用性与失败口径收进可单测的纯函数——**后端、接口、`order-redemption.js`/`coupon-transactions.js` 的请求契约零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/merchant-redemption-display.js`：15 个导出（`REDEEM_CODE_LENGTH`、`COUPON_CODE_LENGTH` + 13 个映射）。
- 重做 `apps/miniapp/src/pages/merchant/redeem.vue`：页头、订单卡、阶段卡、凭据卡、验码卡、失败卡、登录卡；三层 `v-if` 链收敛为 `redeemStageView` 的七阶段。
- 重做 `apps/miniapp/src/pages/merchant/coupon-redeem.vue`：授权表单卡、预览卡、凭据卡、错误卡、记录卡；三格校验与两个按钮的可用性收敛为 `couponFormView`/`couponSubmitView`。
- 新增 `apps/miniapp/test/merchant-redemption-display.test.js`：17 项（映射 14 项 + 页面契约 2 项 + 常量同源 1 项）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 三层 `v-if` 链不可断言 | 凭据 → 可验码条件（五个 `&&`）→ `COMPLETED` → 其他 | `redeemStageView` 七阶段枚举，单测逐条锁顺序（含"凭据优先于异常") |
| 读不到核销记录时整块空白 | `receipt.loaded === false` 时既不显示框也不解释 | `loading` 阶段："读到核销记录之前不显示验码框" |
| 已核销订单可能重复提交 | 可验码条件里内联 `!state.saved` | 凭据行合并 `receipt.value?.redemption \|\| state.saved`，提交成功即进"已核销" |
| 未知状态被当成可核销 | 最后一条 `v-else-if` 落到"不能核销"，无测试保证 | 明确落 `not-now`，单测断言 `WAT` 与 `{}` 都不可核销 |
| 码校验各写一遍 | 模板内联 `/^[0-9]{6}$/` | `redeemCodeView` + `REDEEM_CODE_LENGTH`，单测对比源码里的同源正则 |
| 金额原样插值 | `应付 ¥{{ amount_due }}` | 只认 `^\d+\.\d{2}$`，非法值 `¥—`（不做 `Number()` 化） |
| 服务券三格校验内联且不说缺什么 | `:disabled="!(coupon && revision && /^[a-f0-9]{32}$/.test(code))"` | `还需填写：本人券编号、授权版本、32 位授权码` |
| 已核销误显示为"不可核销" | 只显示红色"当前不可核销" | 徽标改"已核销"（ok），附"该券已于 <时间> 核销，凭据见下方" |
| 失败口径只有一句 message | 越权也只给"重试" | `couponFailureView` 分出"重新登录门店账号"；`uncertain` 只给"重试原核销请求" |
| 记录卡空态与页脚在模板拼 | `items.length` / `total` 各拼一次 | `couponRecordsCopy` 一次给出空态与"共 N 条 · 已加载 M 条" |

## 三处刻意的口径

1. **凭据优先于状态**：只要拿到凭据行（读取结果或提交回执），阶段立刻是 `redeemed` 且页面只读。反过来"状态已经 COMPLETED 但读不到凭据"是 `history-no-record`（warn，提示人工核对收款）——两者都不是可提交态。
2. **没读到凭据之前宁可多等一秒**：`PENDING_VERIFY` + `receipt.loaded !== true` 一定落 `loading`。少一个验码框不会有人抱怨，多一次重复核销就是事故。
3. **金额绝不 `Number()` 化**：`'88.50'` 原样显示、`'88.5'` 显示 `¥—`。门店看到的数字必须与后端回执逐字一致；浮点尾巴和"脏数据被抹平"都不能接受。

## 结果

A4d-4a 本地 PASS：小程序单测 **997 / 997 通过**（0 失败 / 0 跳过，新增 17 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/redeem.wxml` 含 10 个静态锚点、`input` 计数 1；`merchant/coupon-redeem.wxml` 含 14 个静态锚点、`input` 计数 3。六条映射文案在 wxml 中**均搜不到**（模板只剩绑定），同时全部出现在编译后的 `services/merchant-redemption-display.js`——接线成立。报告：`docs/testing/reports/R9_A4D4A_REDEMPTION_2026_10_20.json`（`passed: true`）。

**已上传并合并**：[PR193](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/193)（base=`main`，从 `main` 直开、不再叠栈）。八项 CI 全部成功——`web` / `miniapp` / `schema-and-ocr` / `schema-mysql` 约 1 分钟内完成，`performance-baseline` 2 分钟，`database-recovery` 3 分钟，`backend` 47 分钟（38 步 Testcontainers 全过），`compose-smoke` 2 分钟。合并方式 `merge`，合并后 `main` = `a90a6c51`，`open PR = 0`；随后 `main` 上的两次推送（合并提交与文档回填提交 `71ac0fc`）各自触发的 8 项 CI 也全部 success。

## 未覆盖

退款页、券列表页、员工管理页；真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。`order-redemption.js` 的核销幂等与 429 冷却、`coupon-transactions.js` 的券状态机沿用既有用例。

## 后续

商家侧"接车检查 → 施工防护 → 到店核销 → 订单 → 时段 → 派工"六个高频主流程页已全部统一，108 个页面中 17 个接入设计系统。下一批按既定顺序：`merchant/coupons`、`merchant/staff`（10678 行，最大未接页）等独立缺口 → A5 交付准备 → B 外部接入 → C 真机（外部接口继续后置）。
