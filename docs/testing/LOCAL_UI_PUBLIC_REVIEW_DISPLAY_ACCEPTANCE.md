# 评价与公开展示面本机验收

2026-10-20。范围：6 个评价相关页面的展示层改造（公开星级、公开文字、评价要点 + 三个公开授权入口），新增 1 个展示映射模块与 11 项单测。**无后端、无迁移、无 API 变更**。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（927 项，含新增 11 项展示映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先把它改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_display_evidence.py   # 生成 docs/testing/reports/R9_A4D2H4_PUBLIC_REVIEW_DISPLAY_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 927 / # pass 927 / # fail 0 / # skipped 0 / # cancelled 0`，1761ms |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

`app.wxss` 共享选择器逐项核对（10 项全 true）：`reservation-page`、`reservation-primary`、`ux-hero`、`ux-card`、`ux-metric-value`、`ux-badge`、`ux-row`、`ux-empty`、`ux-card-note`、`ux-divider`。

编译产物结构核对：6 个页面 wxml 均含 `ux-hero` 与 `ux-badge`；`service/ratings.wxml`、`service/review-texts.wxml` 含 `ux-empty`；`service/review-summary.wxml`、`order/public-review-text.wxml`、`order/review-publication.wxml`、`order/review-summary-consent.wxml` 不含 `ux-empty`——这四页用状态卡片解释原因而不是空态占位，是有意为之。

## 新增测试覆盖

`apps/miniapp/test/public-review-display.test.js`（11 项）：

- 平均分空样本给 `—`/`暂无有效公开评价`，绝不出现 `null`/`undefined`/`NaN`。
- 星级行分数 clamp 到 0–5，条形宽度与日期缺省文案。
- 星级授权：测试订单、来源不合格、已授权、未授权四种文案与撤销入口。
- 公开文字 8 种状态：功能未开启、来源未过、尚未投稿、待审核、已公开、**已批准但停止展示**、已驳回（含理由兜底）、已撤销。
- 字数计量按码点计数并单独标记超限。
- 列表空态与页脚互斥（`total=0` 时不出"共 0 条"）。
- AI 用途授权：有效、**授权已失效**、未授权、无可授权文字。
- 要点 5 种状态 + 样本说明不得声称覆盖全部评价。
- 来源缺失兜底 `来源已不可见`。
- 色调类名映射到文档化的 4 个 `ux-badge-*`。
- 页面契约扫描：6 个页面必须走统一映射、两个列表页必须有空态与 `data-testid`、要点页必须用 `quoteSource(` 且不得再出现 `source(h.public_id)?.`。

## 未执行

模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 记录的同一阻塞。因此本轮不声明真机或模拟器渲染通过，也不提供截图对比。
