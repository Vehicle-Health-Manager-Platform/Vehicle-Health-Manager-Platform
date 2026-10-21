# R9 A4d-4b：商家券发行与员工管理执行记录

2026-10-21。前置 PR193（A4d-4a 商家到店核销，已合并进 `main`）；分支 `codex/r9-a4d4b-coupons-staff`（从 `main` 直开）。目标是把券发行的状态机与表单缺口、员工管理的权限与行内动作收进可单测的纯函数——**后端、接口、`coupons.js`/`merchant-staff.js`/`staff-flow.js` 的契约零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/merchant-coupon-issue-display.js`：9 个导出（`COUPON_TYPES`、`COUPON_TYPE_LABELS` + 8 个函数映射）。
- 新增 `apps/miniapp/src/services/merchant-staff-display.js`：10 个导出（`STAFF_FILTERS` + 9 个函数映射）。
- 重做 `apps/miniapp/src/pages/merchant/coupons.vue`：页头、发行说明与入口卡、条款编辑卡、已发行券列表卡、写入结果卡；模板里的状态分支、类型下标、空态与翻页判断全部搬进映射。
- 重做 `apps/miniapp/src/pages/merchant/staff.vue`：页头、导航与权限卡、新增员工卡、员工列表卡；权限文案、行内动作、一次性员工码与分页全部搬进映射。
- 新增 `apps/miniapp/test/merchant-coupon-issue-display.test.js`（9 项）与 `apps/miniapp/test/merchant-staff-display.test.js`（9 项）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 券状态机在模板里 | `v-if="row.status==='DRAFT'"` / `'PUBLISHED'` 两段，`STOPPED` 无提示 | `couponIssueRowView` 给出动作与说明；**未知状态零动作**并提示刷新 |
| 类型靠下标反推 | `:range="['金额优惠券','…']"` + `Number(v)===1?'SERVICE':…` | `COUPON_TYPES` 单一来源，`typeRows` 带 `key`/`index`/`selected` |
| 表单一次只报一条错 | `save()` 只校验库存，其余靠 `validCouponTerms` 抛 | `couponTermsFormView` 一次列全：名称/项目/金额/门槛/库存/有效期先后 |
| 空态与翻页散落 | `v-else-if`、`v-if="state.page>1"`、`state.page*20<state.total` | `couponIssueEmptyView` / `couponIssuePagingView`（忙碌、未加载都不算空） |
| 项目下拉不说为什么选不上 | 只有一句"只能选择本店有效且在售的项目" | `couponProjectOptionRows` 逐行给 `selectable`，可由 UI 标灰 |
| 权限与文案耦合 | `canManage` 布尔 + 三处各写一遍条件 | `staffPermissionView` 一次给出只读提示、空态提示与入口显隐 |
| 行内动作三段内联 | 三元表达式决定"停用/启用"、"签发/重新签发"，`:loading` 手工比对 | `staffRowActions` 输出动作数组，loading 只落在"这一行 + 这个动作" |
| 停用提示藏在插值里 | `{{ role==='TECHNICIAN' ? '，微信绑定同时撤销' : '' }}` | `staffRowView.disabledCopy`，按角色说准 |
| 新增表单缺口不可见 | 只有 placeholder 暗示"店员必填手机号" | `staffCreateFormView` 给 `missing`/`hint`/`canSubmit`，已创建后整体锁定 |
| 一次性员工码判断在模板 | `actions.issued.staffId === item.staff_id` | `staffIssuedView` 只在本行返回可见 |

## 三处刻意的口径

1. **未知券状态零动作**：`couponIssueRowView` 对未知 `status` 只给"当前状态无法在本页操作，请刷新后核对"。猜成"可发布"会把草稿状态的错误推断带到发布接口上。
2. **`hidden` 与 `canPrev/canNext` 分开**：写入结果未确认（`retryWrite`）时不能翻页，但翻页区仍然显示——否则用户会以为"只有一页"。
3. **没读到 `can_edit` 就是只读**：`staffPermissionView(undefined).canManage === false`，与 `loadPermission` 的 catch 分支一致。宁可让店长看不到入口，也不让店员误触管理动作。

## 结果

A4d-4b 本地 PASS：小程序单测 **1015 / 1015 通过**（0 失败 / 0 跳过，新增 18 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/coupons.wxml` 含 18 个静态锚点、`input` 4、`picker` 6；`merchant/staff.wxml` 含 17 个静态锚点、`input` 3、`picker` 1。两页的行级锚点是绑定形式，编译后压成 `{{row.k}}`/`{{row.n}}`（wxml 里搜不到，只能源码断言）；映射供给的 15 条文案全部"wxml 缺席 + 编译后模块在场"。报告：`docs/testing/reports/R9_A4D4B_COUPONS_STAFF_2026_10_21.json`（`passed: true`）。

**已上传并合并**：[PR194](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/194)（base=`main`，从 `main` 直开、不再叠栈）。八项 CI 全部 success——`web` / `miniapp` / `schema-and-ocr` / `schema-mysql` / `database-recovery` 约 1–3 分钟，`performance-baseline` 3 分钟，`backend` 约 47 分钟（38 步 Testcontainers 全过），`compose-smoke` 2 分钟。合并方式 `merge`，合并后 `main` = `2968ddce`，`open PR = 0`；随后 `main` 上的两次推送（合并提交与文档回填提交 `f7b91fb`）各自触发的 8 项 CI 也全部 success。

## 未覆盖

退款页；真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。券状态机（`coupons.test.js`）与员工服务（`merchant-staff.test.js`）的既有用例未改，本轮只新增展示层用例。

## 后续

商家侧"接车检查 → 施工防护 → 到店核销 → 券发行 → 员工与员工码 → 订单 → 时段 → 派工"八个主流程页已统一，108 个页面中 19 个接入设计系统。下一批按既定顺序：退款页与其余域缺口 → A5 交付准备 → B 外部接入 → C 真机（外部接口继续后置）。
