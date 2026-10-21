# R9 A4d-4b 商家券发行与员工管理设计

2026-10-21。前置 A4d-4a（商家到店核销）。范围：`apps/miniapp/src/pages/merchant/coupons.vue`（本店券库存与发行）、`apps/miniapp/src/pages/merchant/staff.vue`（本店员工与员工码），**后端、接口、迁移、`coupons.js`/`merchant-staff.js`/`staff-flow.js` 的契约零改动**。

## 这两页的问题

### 券发行 `coupons.vue`

1. **状态机写在模板里**：`v-if="row.status==='DRAFT'"` 出"修改草稿 + 发布并冻结条款"，`v-if="row.status==='PUBLISHED'"` 出"停止新领取"，`STOPPED` 什么也不出。规则对，但不可断言，且**多一个未知状态就会静默变成"没有动作"而不说明原因**。
2. **类型选择器靠下标猜**：`:range="['金额优惠券','指定项目服务兑换券']"`，`type()` 再用 `Number(e.detail.value)===1?'SERVICE':'DISCOUNT'` 反推。文案与枚举各写一遍，改顺序就错。
3. **表单缺口只校验库存**：`save()` 里只有 `/^[1-9][0-9]{0,9}$/`，其余（名称、项目、金额、最低消费、有效期先后）全部靠 `validCouponTerms` 抛错后才告诉用户，且一次只说一条。
4. **空态/分页/写入结果散在模板**：`v-if="state.busy"`、`v-else-if="state.loaded&&!state.rows.length"`、`v-if="state.page>1"`、`v-if="state.page*20<state.total"`、`v-if="state.retryWrite"` 各写一遍。
5. **项目选择不说明为何选不上**：`choose()` 里"只能选择本店有效且在售的项目"是唯一提示，下拉里看不出哪些项不可选。

### 员工管理 `staff.vue`

6. **权限判断与文案耦合**：`canManage` 只是布尔，只读提示、空态里的"可先新增/请联系店长"、入口按钮的显隐各自写一遍条件。
7. **行内动作三段内联**：`item.status === 'ACTIVE' ? '停用' : '启用'`、`item.employee_code_issued ? '重新签发员工码' : '签发员工码'`、`v-if="item.employee_code_issued"` 出撤销；每个按钮的 `:loading` 还要比对 `busyId` 与 `action`。
8. **停用提示靠模板拼**：`已停用：原登录会话立即失效{{ item.role === 'TECHNICIAN' ? '，微信绑定同时撤销' : '' }}`，角色差异藏在插值里。
9. **新增表单缺口不可见**：只靠 `staffBody` 抛错，一次一条；店员必填手机号这条规则只出现在 placeholder 里。
10. **一次性员工码的"只显示这一次"** 与列表行绑定判断（`actions.issued.staffId === item.staff_id`）写在模板。

## 做法

两个模块，一个页一个域（不强行合并成一个大映射）：

### `services/merchant-coupon-issue-display.js`（9 个导出）

| 导出 | 解决什么 |
| --- | --- |
| `COUPON_TYPES` | `DISCOUNT`/`SERVICE` + 中文标签 + 一句话说明，**顺序即 picker 顺序** |
| `COUPON_TYPE_LABELS` / `couponTypeLabel()` | 未知类型显示"券类型未提供"，不默认成金额券 |
| `couponTypeRows(activeType)` | picker 行（含 `index` 与 `selected`），页面不再用下标反推枚举 |
| `couponIssueRowView(row)` | 状态标签、语气、`剩余 x / 发行 y`、版本、可执行动作、一句说明 |
| `couponIssueEmptyView(state)` | 忙碌/未加载/有行都不算空；只有"已加载且零行"才出空态 |
| `couponIssuePagingView(state)` | 页码文案、上下页可用性、只有一页时整块隐藏 |
| `couponIssueWriteView(state)` | 成功（ok）/失败（risk）、能否重登、"原请求重试"措辞 |
| `couponTermsFormView(form, dates, projects)` | 一次列全缺口；金额/门槛/库存/有效期口径与 `validCouponTerms` 同源；服务券自动锁金额 |
| `couponProjectOptionRows(projects)` | 下拉行与"可选"标记（`available === true && status === 1`），与 `choose()` 同源 |
| `couponDateFields(terms, localTime)` | 把 UTC 条款时间拆回四个本地输入框 |

### `services/merchant-staff-display.js`（10 个导出）

| 导出 | 解决什么 |
| --- | --- |
| `STAFF_FILTERS` / `staffFilterRows()` | 全部/店员/技师三段，选中态；未知角色归"全部"（否则三个都不高亮） |
| `staffRowView(item)` | 三段事实、状态语气、是否技师、停用提示（技师才带"微信绑定同时撤销"） |
| `staffRowActions(item, {busyId, action})` | 启停 + 技师的两态员工码；`loading` 只落在"这一行 + 这个动作" |
| `staffIssuedView(issued, staffId)` | 一次性员工码卡，只在本行刚签发时出现 |
| `staffCreateFormView(form)` | 姓名/手机/密码缺口、店员手机号必填、placeholder 随角色变、已创建后整体锁定 |
| `staffListFailureView(list)` | 越权给重新登录、其余给"重试加载" |
| `staffEmptyView(list, canManage)` | 空态文案按身份分岔 |
| `staffPermissionView(canManage)` | 只读提示与空态提示一次给出，入口显隐同源 |
| `staffPagingView(list)` | "加载更多"只在真有更多且列表非空时出现 |
| `staffActionView(actions)` | 操作结果的成功/失败语气与是否能重登 |

### 几处刻意的口径

- **未知券状态不给动作**：`couponIssueRowView` 对未知 `status` 返回空动作 + "当前状态无法在本页操作，请刷新后核对"。宁可让门店刷新，也不猜"应该能发布"。
- **`hidden` 与 `canNext` 分开**：`couponIssuePagingView` 的 `hidden` 只由"有没有别的页"决定，写入挂起（`retryWrite`）只影响 `canPrev`/`canNext`。锁住不等于把翻页区藏掉。
- **权限按只读处理**：`staffPermissionView(undefined).canManage === false`——没读到 `can_edit` 就不放开任何管理入口，`merchantProfileApi.read` 失败时同理（原逻辑已是如此，这里补上单测）。
- **员工码仍是轮换语义**：签发不带幂等键（旧码要立即失效），页面不得自己发请求；`staffRowActions` 只负责显隐与 loading。

## 保留的契约

- 券：`createCouponFlow` 的 `write/retry` 与幂等键（`imageRequestKey`）、`couponUtcTime`/`couponLocalTime` 的 UTC↔北京时间互转、`<CouponTerms>` 条款组件、`quotesApi.ownList` 的在售项目来源全部保留；`save()` 仍先过 `validCouponTerms`（经 flow）。
- 员工：`createStaffListFlow/CreateFlow/ActionFlow` 三支流程、`merchantProfileApi.read` 的 `can_edit`、`staffBody` 的字段校验全部保留。
- **既有锚点一个不丢**：`staff-readonly-notice`、`staff-form`、`staff-form-message`、`staff-list-message`、`staff-action-message`、`staff-empty`、`staff-issued-code`、`staff-row-${id}`（绑定形式）；券发行页原本没有任何锚点，本轮新增 18 个。

## 验收口径

1. `node --test test/*.test.js`：1015 项全通过（新增 18 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`coupons.wxml` 18 个静态锚点、`input` 4、`picker` 6；`staff.wxml` 17 个静态锚点、`input` 3、`picker` 1。
5. **反向断言**：两页的行级锚点是绑定形式，编译后压成 `{{row.k}}`/`{{row.n}}`，wxml 里**必须搜不到** `coupon-row-`/`staff-row-`；映射供给的 15 条文案同样必须"wxml 缺席 + 编译后模块在场"。

## 不覆盖

退款页、其余未接页面；真机与模拟器渲染；视觉回归截图；暗色模式与无障碍朗读。`coupons.js`/`merchant-staff.js`/`staff-flow.js` 的请求与状态机沿用既有用例（`merchant-staff.test.js`、`coupons.test.js` 等），本轮只新增展示层用例。
