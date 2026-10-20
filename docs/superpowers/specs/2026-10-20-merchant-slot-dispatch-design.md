# R9 A4d-3b 商家时段与派工设计

2026-10-20。前置 A4d-3a（商家订单阅读面）。范围：`pages/merchant/slots.vue`（本店预约时段）与 `pages/merchant/dispatch.vue`（派工），**后端、接口、迁移零改动**。

## 这两页的问题不是样式

1. **时段状态没有三分**：页面只有 `{{ row.open ? '开放预约' : '已关闭' }} · 剩余 2 / 5`。约满（`open=true` 且 `capacity_left=0`）没有独立状态，门店看到的仍是"开放预约"，车主端却已经约不上。
2. **发布表单不说还差什么**：项目没选、名额填错，按钮可点，只在提交时抛一句"请选择在售项目，容量填写1–100"。
3. **派工页有 4 层 `v-if` 链**：`assignment.assignment` → `DISPUTED` → `status !== 'RECEIVED'` → `!assignment.loaded` → 候选区，每层判断都写在模板里，顺序本身是业务规则（已有派工优先于争议），但没有可断言的表达。
4. **候选加载时机藏在 watcher 里**：`watch(() => order.value, ...)` 里判断 `status === 'RECEIVED' && !assignment.assignment && !candidates.loaded`。顺序隐含"订单先到、派工结果后到"，一旦顺序变化就会漏拉或多拉。

## 做法

新增 `apps/miniapp/src/services/merchant-scheduling-display.js`：

| 导出 | 解决什么 |
| --- | --- |
| `slotRowView(row)` | 开放 / 已关闭 / 已约满三种状态 + `剩余 x / y` + 一句说明 |
| `slotListCopy(list)` | 空态与"共 N 条 · 已加载 M 条"页脚 |
| `slotProjectOptions(items, selectedId)` | 只列在售可用项目，选中态算好，模板不再 `filter` |
| `slotFormFacts(form)` | 选了什么项目、时间窗、名额、还差什么（`ready` 只用于提示，不阻止提交） |
| `dispatchStageView(order, assignment)` | 六个阶段：`no-order` / `existing` / `disputed` / `not-ready` / `loading` / `ready` |
| `candidateRowView(row, selectedId)` | 候选行与"已选择该技师/选择该技师" |
| `dispatchCandidateCopy(list)` | 候选空态与页脚 |
| `dispatchExistingFacts(assignment)` | 技师、派工状态（用派工自己的标签表）、派工/接单时间、未接单提示 |
| `dispatchSubmitCopy(selectedId, label)` | 提交按钮文案与"请先选择一位技师"提示 |
| `shouldLoadCandidates(stage, list)` | 何时该拉候选：`ready` 且未加载、非空、非忙碌 |

### 阶段顺序是业务规则

| 顺序 | 阶段 | 为什么在这个位置 |
| --- | --- | --- |
| 1 | `no-order` | 订单都没读到，谈不上派工 |
| 2 | `existing` | **已有派工优先于争议**：服务端已定的事实不能被"争议中"面板盖掉 |
| 3 | `disputed` | 争议阻断派工与施工 |
| 4 | `not-ready` | 状态不是"已接车"就不满足派工条件 |
| 5 | `loading` | 派工结果没读到前不给候选，避免对已有派工的订单重复提交 |
| 6 | `ready` | 才显示候选与提交 |

单测逐条锁这六个阶段，含"`DISPUTED` + 已有派工 → `existing`"这条容易被改错的顺序。

### 一处必须改的迁移

候选加载的 watcher 从 `watch(() => order.value)` 改为 `watch(() => stageView.value.stage)`。原因：`ready` 需要 `assignment.loaded`，而派工结果是**另一次请求**，通常晚于订单到达；监听的仍是 `order.value` 时，阶段变 `ready` 的那一刻不会触发 watcher，候选永远不加载。单测里 `shouldLoadCandidates('loading', …) === false` 与 `'ready' === true` 一起固定了这个前提。

## 编译产物层面的限制

`data-testid` 写成绑定（`:data-testid="'candidate-' + id`）时，mp-weixin 编译产物会把它压成 `{{O}}` / `{{row.h}}`，**在 wxml 里搜不到原文**。所以证据报告只核对静态锚点（`dispatch-submit`、`dispatch-existing-status`）和静态文案（"确认派工"、"加载更多候选技师"），行级锚点由单测扫描源码来保证。

## 验收口径

1. `node --test test/*.test.js`：957 项全通过（新增 11 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`merchant/slots.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/"发布预约时段"/"关闭预约时段"；`merchant/dispatch.wxml` 含 `ux-hero`/`ux-badge`/`dispatch-submit`/`dispatch-existing-status`/"确认派工"/"加载更多候选技师"。

## 不覆盖

接车检查、防护、核销、退款页；真机与模拟器渲染；视觉回归截图；暗色模式与无障碍朗读。时段容量一致性（`reserved_count` 语义）已由后端与前端服务层的既有测试覆盖，本轮没有新增并发用例。
