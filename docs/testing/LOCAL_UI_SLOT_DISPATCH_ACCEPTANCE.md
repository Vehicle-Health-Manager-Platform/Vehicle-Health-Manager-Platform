# 商家时段与派工本机验收

2026-10-20。范围：`pages/merchant/slots.vue`、`pages/merchant/dispatch.vue` 两个页面的展示层改造，新增 10 个纯映射与 11 项单测。**无后端、无迁移、无 API 变更**。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（957 项，含新增 11 项时段/派工映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_slot_dispatch_evidence.py   # 生成 docs/testing/reports/R9_A4D3B_SLOT_DISPATCH_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 957 / # pass 957 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

编译产物结构核对：`merchant/slots.wxml` 含 `ux-hero`、`ux-badge`、`ux-empty`、"发布预约时段"、"关闭预约时段"；`merchant/dispatch.wxml` 含 `ux-hero`、`ux-badge`、`dispatch-submit`、`dispatch-existing-status`、"确认派工"、"加载更多候选技师"。

**注意**：绑定形式的 `data-testid`（如 `:data-testid="'candidate-' + id`）在 mp-weixin 产物里会被压成 `{{O}}`/`{{row.h}}`，wxml 里搜不到原文；行级锚点由单测扫描源码保证，不要拿编译产物当缺失依据。

## 新增测试覆盖

`apps/miniapp/test/merchant-scheduling-display.test.js`（11 项）：

- 时段行：开放（ok）、约满（warn + "已约满"说明）、已关闭（muted + 不再接受新预约）；字段缺失不渲染 undefined；`剩余 0 / 0` 也不抛错。
- 时段列表空态与页脚互斥（`total=0` 不出"共 0 条"）。
- 可发布项目：`available=false` 与 `status!==1` 被过滤；选中态由 `selectedId` 决定；项目名缺失给兜底。
- 发布表单：未选项目/名额非法/合法三种提示；时间窗带"（北京时间）"；名额 1 与 100 合法、101 不合法。
- 派工阶段：六阶段逐一断言，含 `DISPUTED` + 已有派工 → `existing`；`RECEIVED` + 派工结果未读到 → `loading`；读到且无派工 → `ready`；`undefined` assignment 视为未读到。
- 候选行与空态：选中态文案、`#7` 兜底、空态文案逐字（联系管理员维护技师账号）、页脚计数。
- 已有派工：`ACCEPTED` → 已接单/ok/有接单时间/无未接单提示；`ASSIGNED` → 待接单/warn/有未接单提示；未知状态 → "派工状态未提供"/muted；`null` → 空事实。
- 提交文案：未选择时不给按钮文案并提示先选；选择后为"派工给「张师傅」"；标签缺失回落 `#7`。
- 候选加载时机：只有 `ready` 且未加载、非空、非忙碌才该拉；`undefined` 列表视为该拉。
- 页面契约：两页必须引映射、必须有 `ux-hero`/`ux-card`/`toneClass(`；时段页保留 `slot-project-*`、`merchant-slot-*`、空态锚点且不再有 `projects.items.filter`；派工页保留 `dispatch-order-status`/`dispatch-existing-status`/`dispatch-submit`/`dispatch-message`/`candidate-*`/`candidate-selected`，且不得再出现 `v-else-if="order.value?.status === 'DISPUTED'"` 这类阶段链。
- 重试入口：两页都必须保留"按原请求重试"的 `publish.retry`/`close.retry`/`assign.retry`。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真实浏览器流程 `local-reservations-browser-flow.js` / `local-payments-browser-flow.js` 本轮未跑（需要本地后端与 H5 预览服务）；它们通过 API 断言时段发布与关闭，不依赖这两个页面的 DOM，因此不受本轮改动影响。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
