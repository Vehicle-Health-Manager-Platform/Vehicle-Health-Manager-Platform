# 车主端取车对比页设计

## 背景

A4d22 交付了 `POST /api/check/delivery/compare` 的后端与四类证据，但页面没接——R7a 的验收条件写的是"取车对比有实际后端与页面"。本阶段补页面，把后端投影变成车主能看懂的三块事实：接车基线、完工证据、缺口。

## 页面位置与入口

`pages/order/delivery-compare.vue`，从「订单详情」进入，只在 `PENDING_VERIFY` 与 `COMPLETED` 两个状态显示入口按钮（`data-testid="delivery-compare-entry"`）。理由是：施工没结束就没有完工证据可对比，提前给入口只会带来"什么都缺"的误导。

## 三层结构（沿用既有约定）

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 协议 | `services/delivery-compare.js` | 认证 POST、严格校验投影、把状态码映射成稳定 `kind`；**不含任何文案** |
| 展示 | `services/delivery-compare-display.js` | 吃载荷（`state.value`）产出 view model：结论徽标、基线事实、完工事实、缺口清单、失败视图 |
| 页面 | `pages/order/delivery-compare.vue` | 只做 `{{view.xxx}}` 渲染 + 读取流程的生命周期守卫 |

展示层**吃载荷不吃读取流程 state**：`createReservationReadFlow` 把投影放在 `state.value`，直接传 state 会静默拿到 `undefined`（A4d-4a 踩过）。所以页面用 `computed(() => 映射(state.value))`。

## 协议校验比"能解析"更严

`validDeliveryCompare` 逐字段白名单，并额外断言**结论与缺口一致**：

- `result==='READY'` 必须 `missing` 为空，反之亦然——否则"说齐备却列着缺口"会被当成有效数据渲染出去；
- `deliverable===true` 必须 `result==='READY'`；
- `photo_count` 必须等于 `photo_slots` 长度，槽位只能是七个接车槽位且不重复；
- `report.submitted` 与 `report.status` 必须自洽，`SIGNED` 必须有 `signed_at`；
- 投影的 `order_id` 必须回指请求的订单——拿到别的订单的对比结果等于展示错误的车况。

任何一条不满足都判 `protocol` 错误，页面显示"响应异常，请刷新核对"，而不是把可疑数据展示给车主。

## 文案从映射层出

模板里只有静态说明和 `{{}}`。所有会随数据变化的句子（"接车里程 120 km"、"比接车基线多 12 km"、"还差 4 项：…"、"证据齐备，到店取车时由商家核销完成交付。"）都在 `delivery-compare-display.js`。验证方式是成对的：**编译后的 `.wxml` 里搜不到这些文案，编译后的 `services/delivery-compare-display.js` 里存在**。

首页那句静态提示特意改写成"这里只判断证据是否齐全"，避免与映射输出的"证据齐备"标签撞词，让上面的配对检查仍然可信。

## 失败态

- `conflict`（409，接车单缺失）：标题"缺少接车检查基线"，给"先查看接车单"按钮，不给重试——重试也不会变；
- `unauthorized`/`forbidden`：给"重新登录"；
- 其余（网络/协议）：给"重试加载"。

## 边界

- 没有图片预览：对比页只核证据齐备，照片在同页的"查看施工进度与前后照片"和接车单里看，避免再造一条签名链接入口。
- 不做车况差异识别、不做仪表盘 OCR；页面文案明确写"不做车况差异识别"，不暗示有视觉比对。
- 未在微信开发者工具/真机执行，不声明渲染通过；只声明单测与两条构建通过。
