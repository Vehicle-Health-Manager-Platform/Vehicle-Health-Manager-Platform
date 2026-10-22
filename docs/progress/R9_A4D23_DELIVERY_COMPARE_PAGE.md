# R9 A4d23 车主端取车对比页阶段执行记录

## 结果与范围

2026-10-22 完成实施。分支 `codex/r9-a4d23-delivery-compare-page`，基于 A4d22 的 `codex/r9-a4d22-delivery-compare`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。

后端与四类证据在 A4d22 已交付，本阶段补齐 R7a 的另一半：车主端页面，让"取车对比"从可复现接口变成可操作界面。

## 实施与行为

- 新增协议层 `services/delivery-compare.js`：认证 `POST /api/check/delivery/compare`（正文只有 `order_id`，不带幂等键——后端是只读投影），逐字段白名单校验投影，把 400/401/403/404/409/503 映射成稳定 `kind`。
- 新增展示层 `services/delivery-compare-display.js`：结论徽标与解释、接车基线事实（里程与差值、油量、损伤、照片张数与槽位、车主确认态）、完工证据事实（防护、报工状态与四类照片计数）、缺口清单（每个编码都翻成人话）、失败视图。
- 新增页面 `pages/order/delivery-compare.vue`：复用 `createReservationReadFlow` 与车主会话单例；`onShow` 加载、`onHide/onUnload` 重置；映射吃 `state.value` 而不是 state 本身。
- 入口：订单详情新增按钮，仅 `PENDING_VERIFY`/`COMPLETED` 显示（`data-testid="delivery-compare-entry"`）；页内提供"查看接车单""查看施工进度与前后照片""刷新取车对比"。
- 失败态分三路：409 接车单缺失 → "先查看接车单"（不给重试）；401/403 → 重新登录；其余 → 重试加载。

## 校验比"能渲染"更严

`validDeliveryCompare` 除了逐字段白名单，还断言**结论与缺口一致**（`READY` 必须 `missing` 为空）、`deliverable` 只能出现在 `READY` 上、`photo_count` 等于槽位数、槽位不重复且只含七个接车槽位、报工 `submitted`/`status`/`signed_at` 自洽。再加上"投影 `order_id` 必须回指请求订单"，共 29 种被拒形态写进单测。

## 实际验证

- 小程序单测：**1023 / 1023 通过**（新增 `test/delivery-compare.test.js` 8 项：schema 白名单与矛盾、认证只读 POST 与订单绑定、状态码映射、四组展示映射、失败分流）。
- 微信构建 `npm run build:miniapp`：`DONE Build complete.`，并附加原生样式检查 `PASS native shared styles available globally in app.wxss` 与"微信包不含原生测试身份桥"`PASS`。
- H5 构建 `npm run build:h5 --workspace @autocare/miniapp`：`DONE Build complete.`
- 文案位置成对验证：编译后的 `pages/order/delivery-compare.wxml` 里搜不到"接车里程""还差 ""接车时记录无损伤"等映射输出，编译后的 `services/delivery-compare-display.js` 里存在；`app.json` 已登记 `pages/order/delivery-compare`。
- 首页静态提示特意改写成"这里只判断证据是否齐全"，避免与映射标签"证据齐备"撞词，让上述配对检查仍然可信。

## 下一阶段与边界

R7a 的"后端 + 页面"已齐；下一阶段可在以下方向里选：

1. 商家侧取车对比视图（同一投影，商家在核销前核对）；
2. `GET /api/admin/reconciliation` 的财务口径立项；
3. 真机/开发者工具页面走查（本阶段只声明单测与构建，不声明渲染通过）。

**未执行**：微信开发者工具模拟器与真机走查、视觉回归、真实账号登录；页面无图片预览（照片在接车单与施工证据页查看），不做车况差异识别与仪表盘 OCR。全 API 验收仍为 false，本阶段不涉及接口与覆盖数字变化。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。
