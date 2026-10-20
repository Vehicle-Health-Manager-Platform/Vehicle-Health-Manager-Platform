# R9 A4d-3c：商家接车检查执行记录

2026-10-20。前置 PR190（A4d-3b 商家时段与派工）；分支 `codex/r9-a4d3c-pickup`。目标是把接车检查的八张照片、里程比对、损伤标注与提交前置收进可单测的纯函数——**后端、接口、`services/pickup.js` 的提交契约零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/pickup-display.js`：13 个导出（`DAMAGE_LIMIT` + 12 个映射）。
- 重做 `apps/miniapp/src/pages/merchant/pickup.vue`：页面头部、订单事实卡、八张照片卡、里程卡、油量与损伤卡、到店与授权卡、提交卡；模板不再出现 `form.photos[slot].fileId ? … : …`、`Number(form.mileage) - baseline`、`damages.length >= 20` 这类判断。
- 新增 `apps/miniapp/test/pickup-display.test.js`：13 项（映射 12 项 + 页面契约 1 项，页面契约里同时锁定既有锚点、`pickupBody` 前置校验与重试入口）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 八张照片没有状态 | 只有 `complete` 布尔；徽标与按钮文案内联 | 未上传 / 待上传 / 已上传三态，徽标色调与按钮文案由 `photoSlotView` 给出 |
| 缺片靠用户数 | 提交按钮灰着，不说还差什么 | `已上传 6 / 8 张` + `还差 2 张：内饰、油表`（按 `PICKUP_SLOTS` 顺序） |
| 里程差值算在模板里 | `{{Number(form.mileage) - baseline}}`，没有回退判断 | `pickupMileageView` 只在 `^\d{1,7}$` 成立时给差值，并区分"低于基线需写原因 / 提交时重新比对" |
| "需填原因"是静态标签 | 不管这次是否回退都显示 | 由 `needsReason` 决定，徽标同时变 `risk` |
| 点照片没反应不解释 | 四个条件挤在 `mark()` 里 | `canMarkDamage` 一处判定，说明文案落在 `pickupDamageView.copy` |
| 上传失败后的"待上传"看不见 | 只有一句 `message` | `待上传 / warn` 徽标 + "原图重试上传"入口显式化 |
| 提交失败措辞含糊 | "重试"两字 | `pickupWriteView.retryLabel`："重试原接车提交请求（不使用当前编辑内容）" |

## 一处必须保留的异步前提

`mark()` 现在先问 `canMarkDamage`，**在 `uni.createSelectorQuery().boundingClientRect` 回调里再问一次**。查询是异步的：期间用户可能替换了该照片（`fileId` 变化）或删光了标注。原实现用 `fileId !== form.photos[slot].fileId` 挡住前一种，本轮把这个前提换成同一个函数，顺带覆盖"回调期间标注被删空/额度已满"。

## 结果

A4d-3c 本地 PASS：小程序单测 **970 / 970 通过**（0 失败 / 0 跳过，新增 13 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/pickup.wxml` 含 `ux-hero`/`ux-badge`、8 个静态锚点（`pickup-form`/`pickup-login`/`pickup-order`/`pickup-mileage`/`pickup-fuel`/`pickup-arrival`/`pickup-submit`/`pickup-read-error`）与 4 条静态文案（"接车检查"/"油量与损伤"/"到店与授权"/"前往门店登录"）。报告：`docs/testing/reports/R9_A4D3C_PICKUP_2026_10_20.json`（`passed: true`）。

## 一条工具事实（延续 A4d-3b 的结论）

mp-weixin 会把绑定形式的 `data-testid` / `class` 压成 `{{row.t}}` 这类短名，wxml 里搜不到原文；`pickup-slot-` 与 `pickup-photo-` 本轮同样是 0 命中。这次**直接把"应当搜不到"写成断言**（`dynamic_slot_anchor_expected_absent`、`dynamic_photo_class_expected_absent`），而不是留一句注释等下次再踩。

## 未覆盖

`merchant/protection`（施工防护）、核销与退款页、真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。照片上传与 `pickupBody` 字段校验沿用 `pickup.test.js` 的既有用例。全仓 108 个页面中现已 14 个接入设计系统。

## 后续

商家侧高频页只剩 `merchant/protection`（施工防护，7453 字符）；做完"接车 → 防护 → 订单 → 时段 → 派工"就成片统一。之后按既定顺序进入 A5 交付准备，外部接口继续后置。PR 依赖 PR190，保持草稿，合并前需把 base 逐支改回 `main`。
