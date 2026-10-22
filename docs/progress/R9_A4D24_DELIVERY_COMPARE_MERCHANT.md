# R9 A4d24 商家侧取车对比（核销前核对）阶段执行记录

## 结果与范围

2026-10-22 完成实施。分支 `codex/r9-a4d24-delivery-merchant-view`，基于 A4d23 的 `codex/r9-a4d23-delivery-compare-page`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。

A4d22 给了后端、A4d23 给了车主端页面；本阶段补商家侧：**核销前**告诉店员证据齐不齐。核销是订单终点，事后才发现缺照片只能走争议或人工补救。

## 实施与行为

- 不新开接口、不新开页面：后端授权本来就同时允许本单车主与本店 MERCHANT/STAFF，投影内容一致。页面 `pages/order/delivery-compare.vue` 支持 `?role=merchant` 切到门店会话（与 `pickup-detail.vue` 同一套路），服务层只把"请先登录车主账号"改成"请先登录账号"。
- 文案分叉收在展示层：`deliveryPageView(role)` 给标题与说明（商家是"取车对比（核销前核对）"，说明里多一句"再决定是否核销"），`deliveryResultView(data, role)` 给结论解释（商家"证据齐备，核销前无需再补照片或签字"）。未知角色一律按车主口径。
- 两个入口都在订单进入待核销之后：商家订单详情的入口矩阵新增 `delivery-compare`（`params` 必带 `{role:'merchant'}`，只在 `PENDING_VERIFY`/`COMPLETED` 出现，标签随状态变化），核销页顶部加一个按钮（`data-testid="redeem-delivery-compare"`）——店员真正的操作现场在那里。入口顺序排在 `redeem` 之前，先核对再核销。
- **不在前端阻止核销**：能不能核销由后端守卫决定，前端复制一套规则只会两边漂移；页面只报缺口。

## 实际验证

- 小程序单测：**1024 / 1024 通过**。新增/更新 2 项：角色文案分叉（车主与商家两套措辞、未知角色回落车主口径）、商家入口矩阵（PENDING_VERIFY/COMPLETED 出现、必带 role、IN_SERVICE 不出现、标签随状态）。
- 微信构建 `npm run build:miniapp`：`DONE Build complete.`，`PASS native shared styles available globally in app.wxss`、`PASS normal WeChat bundle contains no native test identity bridge`。
- H5 构建 `npm run build:h5 --workspace @autocare/miniapp`：`DONE Build complete.`
- 文案配对逐条核对：`核销前核对`、`核销前无需再补`、`再决定是否核销`、`暂不能核销`、`取车对比（核销前核对）`、`查看取车对比` 在编译后的 wxml 里均为 0 次，在编译后的 `services/delivery-compare-display.js` / `services/merchant-order-display.js` 里存在；`pages/merchant/redeem.wxml` 含 `redeem-delivery-compare` 按钮。

## 下一阶段与边界

R7a 的取车对比已形成"后端 + 车主页 + 商家核销前核对"闭环。下一阶段可选：

1. `GET /api/admin/reconciliation` 的财务口径立项（需先定渠道账单来源）；
2. 真机/开发者工具页面走查（目前只有单测与构建，不声明渲染通过）；
3. 剩余 5 项 `not-implemented` 中可离线实现的部分（当前都已判断为核心依赖外部条件）。

**未执行**：开发者工具与真机走查、视觉回归、真实账号登录；未接入"核销前强制确认已看对比"（当前无此业务要求）。接口与覆盖数字未变（316/352，缺口 36）。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。


阶段已上传[草稿PR215](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/215)，实现提交 `0641389`，base=`codex/r9-a4d23-delivery-compare-page`，依赖PR214；远程CI已触发，结果以PR实际检查为准，未合并。
