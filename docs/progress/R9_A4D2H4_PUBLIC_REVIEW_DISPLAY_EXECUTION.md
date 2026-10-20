# R9 A4d-2h4：评价与公开展示面执行记录

2026-10-20。前置 PR186；分支 `codex/r9-a4d2h4-public-review-display`。目标是把公开星级、公开文字、评价要点三个展示面与三个公开授权页，做成与设计系统一致、状态可读、文案可被单测的一层——**后端与接口零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/public-review-display.js`：10 个纯展示映射（`ratingHeadline` / `ratingRow` / `publicationView` / `textStatusView` / `draftMeter` / `textListCopy` / `consentStatusView` / `summaryView` / `summarySampleCopy` / `quoteSource`）与色调到 `ux-badge-*` 的映射。全部容错：字段缺失退化成可读文案，不抛错、不渲染 `undefined`。
- 重做 6 个页面：`service/ratings.vue`、`service/review-texts.vue`、`service/review-summary.vue`、`order/public-review-text.vue`、`order/review-publication.vue`、`order/review-summary-consent.vue`。统一 `ux-hero / ux-card / ux-metric / ux-badge / ux-row / ux-empty / ux-card-note / ux-divider`，保留 `reservation-*` 布局契约与 `reservation-primary` 主按钮。
- 新增 `apps/miniapp/test/public-review-display.test.js`：11 项（10 项状态映射 + 1 项页面契约扫描）。
- 扩展 `apps/miniapp/test/check-native-styles.cjs`：新增 8 个设计系统选择器必须在 `app.wxss`。

## 修掉的具体缺陷

| 缺陷 | 表现 | 处理 |
| --- | --- | --- |
| 要点来源缺失 | 撤回来源后渲染 `来源全文 · 发布于 undefined` | `quoteSource()` 返回 `{published_date:'来源已不可见', missing:true}`，页面改显示警示文案 |
| 平均分为空 | 空样本时读数为 `null`/`NaN` | `ratingHeadline()` 返回 `—` + "暂无有效公开评价" |
| 状态区分不足 | "已批准但来源失效"与"未公开"共用一套文案 | 拆出 8 种文字状态、4 种授权状态、5 种要点状态 |
| 字数计数器 | 模板里 `[...state.draft].length` 重复实现 | 收敛为 `draftMeter()`，按码点计数并单独标记超限 |
| 列表空态 | 只有一行"暂无有效公开文字" | `ux-empty` 空态 + 说明为什么不会有内容；有内容时才显示"共 N 条"页脚 |

## 三个实现约束

1. `.reservation-page button`（(0,1,1)）会盖掉 `.ux-btn`（(0,1,0)），因为 `reservations.css` 在 `App.vue` 里后导入；页面内主按钮必须用 `reservation-primary`（(0,2,0)）。
2. `ux-badge` 的圆点是 `::before`，徽标改用 `<view>` 承载，`<text>` 在 mp-weixin 下伪元素支持不稳。
3. 共享类只在 `App.vue` 引一次（`@import './styles/design-system.css'`），页面重复引会被微信构建去重；构建门禁逐项断言它们在 `app.wxss` 里。

## 结果

A4d-2h4 本地 PASS：小程序单测 **927 / 927 通过**（0 失败 / 0 跳过，新增 11 项）；`npm run build:mp-weixin` 构建完成且共享选择器门禁、无测试身份桥门禁均 PASS；`npm run build:h5` 构建完成。10 个共享选择器在 `app.wxss` 中逐项存在，6 个页面编译产物均含 `ux-hero`/`ux-badge`，两个列表页含 `ux-empty`。报告：`docs/testing/reports/R9_A4D2H4_PUBLIC_REVIEW_DISPLAY_2026_10_20.json`。

## 实际执行与证据

- 远程 CI（PR187，head `0812112`，run `39411103681`，`conclusion=success`）：八项 job 全部 `completed / success` —— `web` 36 秒、`miniapp` 54 秒、`schema-and-ocr` 32 秒、`schema-mysql` 45 秒、`database-recovery` 72 秒、`performance-baseline` 118 秒、`backend` 50 分 47 秒（10:53:32Z → 11:44:19Z）、`compose-smoke` 2 分 20 秒（11:44:22Z → 11:46:42Z）。
- `compose-smoke` 有 `needs: [web, backend, schema-mysql]`，所以它是 `backend` 结束后才创建的第 8 个 check-run；`backend` 未完成时只看到 7 项且 `mergeable_state=unstable` 属于正常顺序，不是缺项。
- `backend` 的 38 个步骤全部通过，其中第 7 步 `mvn -B -Pcore-coverage clean verify`、第 31 步 `Verify owner review and four independent public grants`（隔离真跑 `--ci-built-jar --reviews-cases`）、第 32/33 步证据归档均为 `success`。本阶段只改小程序源码与文档，这条通过说明前端改动没有连带破坏任何一条后端验收链路。
- 本地侧证据见 `docs/testing/reports/R9_A4D2H4_PUBLIC_REVIEW_DISPLAY_2026_10_20.json`（927/927、两个构建 exit 0、10 个共享选择器、6 个页面编译产物、9 个源文件哈希）与 `docs/testing/LOCAL_UI_PUBLIC_REVIEW_DISPLAY_ACCEPTANCE.md`（复现命令）。
- 最终 `mergeable_state=clean`，PR 保持草稿，未合并、未部署；合并前需先把 base 从 `codex/r9-a4d2h3-owner-reviews` 改回 `main`。

## 未覆盖

真机渲染与视觉走查（本机开发者工具未打开该项目，模拟器冒烟未计入通过项）、截图对比、暗色模式、无障碍朗读、商家端与运营端评价页面、并发与视觉回归。

## 后续

先核对远程 CI，再按既定顺序补其他独立缺口，然后 A5 交付、B 外部接入、C 真机。正式身份、外部资金/识别/地图与第三方模型继续后置。PR 依赖 PR186，保持草稿，合并前需把 base 改回 `main`。
