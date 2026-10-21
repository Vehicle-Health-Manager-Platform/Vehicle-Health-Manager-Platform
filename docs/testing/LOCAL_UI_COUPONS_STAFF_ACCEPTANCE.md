# 商家券发行与员工管理本机验收

2026-10-21。范围：`pages/merchant/coupons.vue`（本店券库存与发行）与 `pages/merchant/staff.vue`（本店员工与员工码）的展示层改造，新增 `services/merchant-coupon-issue-display.js`（9 个导出）、`services/merchant-staff-display.js`（10 个导出）与 18 项单测。**无后端、无迁移、无 API 变更**，`services/coupons.js`、`services/merchant-staff.js`、`services/staff-flow.js` 的契约未改。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（1015 项，含新增 18 项券发行与员工管理映射/页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_coupons_staff_evidence.py   # 生成 docs/testing/reports/R9_A4D4B_COUPONS_STAFF_2026_10_21.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 1015 / # pass 1015 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true`（`tests: 1015`、`coupons_inputs: 4`、`staff_inputs: 3`） |

## 编译产物结构核对

| 产物 | 核对项 |
| --- | --- |
| `merchant/coupons.wxml` | `ux-hero`、`ux-badge`、18 个静态锚点（`coupon-issue-form`/`coupon-issue-login`/`coupon-issue-create`/`coupon-terms-card`/`coupon-name`/`coupon-type`/`coupon-project`/`coupon-amount`/`coupon-min-spend`/`coupon-quantity`/`coupon-valid-from`/`coupon-valid-to`/`coupon-save`/`coupon-close`/`coupon-board`/`coupon-issue-empty`/`coupon-issue-message`/`coupon-issue-retry`）、`<input>` 4、`<picker>` 6、静态文案"门店工作台"/"本店券库存与发行"/"本店已发行券" |
| `merchant/staff.wxml` | `ux-hero`、`ux-badge`、17 个静态锚点（`staff-page`/`staff-login`/`staff-readonly-notice`/`staff-create`/`staff-form`/`staff-form-hint`/`staff-form-message`/`staff-role`/`staff-display-name`/`staff-phone`/`staff-password`/`staff-save`/`staff-close`/`staff-list-message`/`staff-action-message`/`staff-empty`/`staff-issued-code`）、`<input>` 3、`<picker>` 1、静态文案"门店端"/"本店员工与员工码" |
| 两页共同 | 行级锚点是绑定形式（`coupon-row-${id}` / `staff-row-${id}`），编译后压成 `{{row.k}}`/`{{row.n}}` → wxml 里**搜不到**（`row_anchor_expected_absent: true`），只能源码断言 |

## 反向断言（本轮的关键证据）

映射供给的 15 条文案必须同时满足"wxml 缺席"与"编译后模块在场"：

| 页面 | 文案 | wxml | 编译后模块 |
| --- | --- | --- | --- |
| 券发行 | 金额优惠券、指定项目服务兑换券、发布并冻结条款、停止新领取、本店还没有新发行券、还需填写：、券类型未提供 | 均搜不到 | 全部在场 |
| 员工 | 停用、签发员工码、重新签发员工码、撤销员工码、一次性员工码（只显示这一次）、只有店长可以新增…、请联系店长添加。、加载更多员工 | 均搜不到 | 全部在场 |

只要有一边反了，就是"模板写死了"或"模块没编进去"，两种都不算接线成立。

## 新增测试覆盖

`apps/miniapp/test/merchant-coupon-issue-display.test.js`（9 项）：

- 类型：`COUPON_TYPES` 顺序固定（`DISCOUNT` 在前）、标签、未知类型"券类型未提供"、`typeRows` 的 `index`/`selected`。
- 行视图：草稿（两项动作 + `发布后条款冻结` + `草稿版本 1`）、已发布（一项 `stop` + `发布版本 2` + `不影响已领取`）、已停止（零动作 + `仍按原条款使用`）、未知状态零动作 + `无法在本页操作`；字段缺失退化为 `剩余 0 / 发行 0`，不出现 `NaN`。
- 空态：忙碌、未加载、有行都不算空；只有"已加载且零行"才出空态。
- 分页：一页时 `hidden`；45 条时 `canNext`；第 2/3 页的 `canPrev`/`canNext`；`retryWrite` 只锁按钮不改 `hidden`。
- 写入结果：ok / risk、`forbidden` 可重登、`conflict` 不可重登、`retryWrite` 的措辞含"不要改动表单另建一张券"。
- 表单缺口：空表单一次列全 6 项；合法 DISCOUNT ready；SERVICE 自动 `amountLocked` 且不要求金额；金额 `5.0`/`0.00` 无效、门槛小于优惠无效；库存 `0`/`1.5`/`-1` 无效而 10 位整数有效；有效期倒序单独提示；名称 129 字只报名称超长。
- 项目选项：`available && status === 1` 才 `selectable`，顺序与 `index` 一致，缺名字回落"项目未命名"。
- 编辑态初值：UTC→本地四格拆分；`localTime` 返回空串时四格一起空；`terms` 为 `null` 或未传 `localTime` 都不产生 `undefined` 字样。
- 页面契约：8 个映射必须被引用、`ux-hero`/`ux-card`/`toneClass(` 在、18 个锚点在、`createCouponFlow` + `newKey: imageRequestKey` + `couponUtcTime`/`couponLocalTime` + `<CouponTerms>` + `quotesApi.ownList` 都在；不得残留 `v-if="row.status==='DRAFT'"`、`state.rows.length`、内联类型数组、`v-if="state.page>1"`。

`apps/miniapp/test/merchant-staff-display.test.js`（9 项）：

- 筛选：三段顺序与选中态；未知/缺失角色归"全部"（不会三个都不高亮）。
- 行视图：三段事实文案、`ok`/`muted`/`warn` 语气、`isTechnician`；停用提示对技师带"，微信绑定同时撤销"、对店员不带；字段缺失退化。
- 行内动作：店员只有启停；技师未签发 → `签发员工码`；技师已签发 → `重新签发员工码` + `撤销员工码`；`loading` 只落在"这一行 + 这个动作"；任一行忙碌时该行按钮全 disabled。
- 一次性员工码：只对匹配的 `staff_id` 可见，空码/其它行/缺失都不可见。
- 新增表单：默认 STAFF 的三项缺口；合法后可提交；技师不必填手机但填了必须合法；密码过短/纯数字/纯字母都无效；姓名 1 字无效、32 字有效；已创建后 `locked` 且按钮变"返回本店员工"。
- 列表失败：`unauthorized` 可重登、`network` 不可；**写错字段名（`kind`）不得放出重新登录**。
- 空态与权限：`loaded && !busy && 零行` 才可见，文案按店长身份分岔；`staffPermissionView(undefined).canManage === false`。
- 分页与操作结果：45 条显示"加载更多"、空列表不显示、`busy` 时 disabled；成功 ok / 失败 risk + 可重登。
- 页面契约：10 个映射必须被引用、17 个锚点在、行级锚点仍是绑定形式、`merchantProfileApi.read` + `profile.can_edit === true` + 三支 staff 流程都在；不得残留 `['unauthorized','forbidden'].includes(list.failureKind)`、两处三元文案、内联空态三元、`apiRuntime.request(`。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真实券发行与员工码签发链路（`scripts/local_*_e2e.cjs`）本轮未跑：需要本地后端与店长账号；接口形状与状态机由 `coupons.test.js`、`merchant-staff.test.js` 既有用例覆盖。
- 真机与 H5 预览流程未跑。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
