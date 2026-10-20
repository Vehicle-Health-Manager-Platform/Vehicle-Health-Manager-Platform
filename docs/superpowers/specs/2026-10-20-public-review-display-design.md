# R9 A4d-2h4 评价与公开展示面设计

## 目标

把"公开星级 / 公开文字 / 评价要点"三个展示面，以及车主侧三个公开授权入口，做成**状态可读、空态明确、文案可被单测**的一层。**不新增接口、不改服务层契约、不改后端**。

上一阶段（A4d-2h3）已经证明了接口层的授权与撤回语义；这一阶段解决的是另一个问题：**同一个状态在页面上被怎么讲**。

## 为什么先把文案搬出模板

改造前四个页面的状态文案全部内联在模板里，例如：

```html
<text>审核状态：{{ TEXT_STATUS[state.value.status] }}</text>
<text>当前公开：{{ state.value.visible ? '正在展示获批文字' : '未公开' }}</text>
<text>来源全文 · 发布于 {{ source(h.public_id)?.published_date }}</text>
```

三个具体后果：

1. **不可测**。模板表达式没有单测覆盖，改文案不会有任何红灯。
2. **会漏出 undefined**。第三行在来源被撤回后直接渲染 `发布于 undefined`——这不是假想，是 A4d-2h3 里真实存在的写法（服务层校验保证"当次响应"里有 sources，但页面渲染的是同一响应里的 `highlights`，来源可能已被移出）。
3. **状态区分不出来**。"已批准但来源失效"和"未公开"用同一个 `visible` 分支，用户看不出自己是被撤了还是被卡了。

所以这一层新增 `src/services/public-review-display.js`：纯函数、只吃已校验的响应对象、任何字段缺失都退化成可读文案而不是抛错。页面只负责摆放。

## 状态矩阵

### 公开星级列表

| 数据 | 展示 |
| --- | --- |
| `summary.count === 0` | 平均分显示 `—`，徽标"暂无有效公开评价"，空态说明"授权或来源失效会立即移出统计" |
| `count > 0` | 平均分大字 + `/5` + "N 条有效公开评价"，逐条 分数/UTC 日期/条形 |
| `average_rating` 缺失 | 仍显示 `—`，不显示 `undefined` |

### 公开文字状态

| `can_submit` / `status` / `visible` | 徽标 | 撤销入口 |
| --- | --- | --- |
| `can_submit=false, DISABLED` | 功能未开启（muted） | 有（按 `status` 判断） |
| `can_submit=false` 其他 | 来源未通过核对（risk） | 有 |
| `NONE` | 尚未投稿（muted） | 无 |
| `PENDING` | 待审核（warn） | 有 |
| `APPROVED, visible` | 已公开（ok） | 有 |
| `APPROVED, !visible` | **已批准但停止展示**（warn） | 有 |
| `REJECTED` | 已驳回（risk）+ 理由 | 有（清空被驳回文字） |
| `WITHDRAWN` | 已撤销（muted） | 无 |

"已批准但停止展示"是这一层最重要的新增区分：文字审核通过了，但来源核对不再通过（例如付款异常、争议开启、退款），用户必须知道这不是自己的操作失败。

撤销入口的显示条件**与状态文案解耦**：只要 `status` 不是 `NONE/WITHDRAWN` 就给按钮。改造前就是这个条件，保留它——被驳回的文字也应该能清空。

### AI 用途授权

| 状态 | 徽标 | 主动作 |
| --- | --- | --- |
| `can_consent=false` | 无可授权文字（muted） | 无 |
| `effective` | 已授权且有效（ok） | 撤销 |
| `consented && !effective` | **授权已失效**（warn） | 按当前文字重新授权 |
| `!consented` | 未授权（muted） | 授权 |

`consented && !effective` 对应"文字改版后旧授权不再生效"（后端把授权时的 `text_version` 冻结在授权行里）。这是四条独立授权里最容易被误解的一条：用户以为"我授权过了"，系统却按新版本判定失效。

### 评价要点

| `status` | 标题 | 色调 |
| --- | --- | --- |
| `AI_READY` | AI 选取的完整原句 | ok |
| `FALLBACK` | 原文摘录（未采用 AI 输出） | warn |
| `SOURCE_CHANGED` | 来源已变化，本次结果作废 | risk |
| `DISABLED` | 评价要点尚未开启 | muted |
| `EMPTY` | 暂无可用于要点的文字 | muted |

要点引用的来源用 `quoteSource()` 兜底：来源缺失时显示"来源已不可见"并提示"仅保留要点原句，请勿当作当前公开内容"，而不是渲染 `undefined`。

## 三个实现约束（踩过才知道）

1. **`.reservation-page button` 会盖掉 `.ux-btn`**。前者是 (0,1,1)，后者是 (0,1,0)，而 `reservations.css` 在 `App.vue` 里后导入。所以页面内主按钮一律用 `reservation-primary`（(0,2,0)）；全仓 80 个页面也都是这么写的，`ux-btn` 只适合脱离 `reservation-page` 的容器。
2. **徽标用 `<view>` 不用 `<text>`**。`ux-badge` 的圆点是 `::before` 伪元素，`<text>` 在 mp-weixin 里对它支持不稳；徽标本身是药丸块，用块级容器语义也更对。
3. **共享类必须落 `app.wxss`**。页面里重复 `@import` 会被微信构建去重，后续页面静默丢样式。所以 `design-system.css` 只在 `App.vue` 引一次，页面直接用类名；构建门禁再断言这些类确实在 `app.wxss` 里。

## 验收口径

- `node --test test/*.test.js`：927 项全通过（新增 11 项：状态映射 10 项 + 页面契约 1 项）。
- 页面契约项扫描 6 个页面的源码，断言它们**必须**走 `public-review-display.js`、两个列表页**必须**有空态分支与 `data-testid` 锚点、要点页**必须**用 `quoteSource(` 且不再出现 `source(h.public_id)?.` 这种直取写法。
- `npm run build:mp-weixin`：`DONE Build complete.` + 共享选择器门禁 PASS + 无测试身份桥 PASS。
- `npm run build:h5`：`DONE Build complete.`。
- 证据落 `docs/testing/reports/R9_A4D2H4_PUBLIC_REVIEW_DISPLAY_2026_10_20.json`：含测试计数、两个构建退出码、`app.wxss` 共享选择器逐项布尔、6 个页面编译产物结构、9 个源文件哈希。

## 不覆盖

- 真机渲染与视觉走查：本机开发者工具未打开该项目（与 PR164 同一阻塞），模拟器冒烟没有计入通过项。
- 截图对比、暗色模式、无障碍朗读、字体回退。
- 商家端与运营端的评价相关页面（`operator/review.vue`、`operator/review-texts.vue`）仍是旧视觉，本阶段不动。
- 后端与接口层零改动：本阶段不改变任何授权/撤回语义，只改变这些语义被讲出来的方式。
