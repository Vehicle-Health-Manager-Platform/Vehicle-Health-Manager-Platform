# 运营审核台本机验收

2026-10-20。范围：`pages/operator/review.vue`、`pages/operator/review-texts.vue` 两个页面的展示层改造，新增 7 个纯映射与 8 项单测。**无后端、无迁移、无 API 变更**。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（935 项，含新增 8 项运营审核台映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_operator_evidence.py   # 生成 docs/testing/reports/R9_A4D2H5_OPERATOR_REVIEW_CONSOLE_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 935 / # pass 935 / # fail 0 / # skipped 0 / # cancelled 0`，1826ms |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

`app.wxss` 共享选择器逐项核对（10 项全 true）：`reservation-page`、`reservation-primary`、`ux-hero`、`ux-card`、`ux-metric-value`、`ux-badge`、`ux-row`、`ux-empty`、`ux-card-note`、`ux-divider`。

编译产物结构核对：`operator/review.wxml` 含 `ux-hero`、`ux-badge`、`ux-empty`、`operator-entry`；`operator/review-texts.wxml` 含 `ux-hero`、`ux-badge`、`ux-empty`、`moderation-row`。报告里两个页面的锚点互斥（`operator-entry` 只在前者、`moderation-row` 只在后者），是两份不同页面的正常结果，不是缺失。

## 新增测试覆盖

`apps/miniapp/test/operator-review-console.test.js`（8 项）：

- 待审文字行：可批准/来源已失效两种文案与色调、版本号缺省为"版本未知"、`undefined` 不误判为可批准。
- 待审队列：未加载（`null`）与真的为空必须分开；混合列表的 footer 带"其中 N 条来源已失效只能驳回"，无失效行时不出该短语。
- 驳回理由：`codes` 与 `Object.keys(REASONS)` 一致、`labels` 与可读文案一致、未知 code 返回空串（不渲染 code）。
- 入口分组：空会话 `[]`；`canReview` 只有 3 个内容审核入口；`canOnboard` 只有入驻审核；`canDiagnose` 与 `canDiagnoseManage` 的差别（后者多一个诊断配置）；全会话 4 组 21 入口且 URL 唯一、路径以 `/pages/` 开头。
- 权限摘要与入口不漂移：对每个 `can_*` 单独授权，都必须至少命中一个入口且摘要文案为"1 项权限"。
- 车型是批准前提：`model_id: null` 与 `model_id: 0` 都不能批准；`no_parts` 与 `part_kinds` 的文案互斥正确；字段缺失给兜底文案。
- 页面契约：两页必须引统一映射、必须有 `ux-hero`/`ux-card`/`toneClass(`；`review.vue` 必须用 `operatorEntryGroups(`、必须不再出现 `/pages/operator/knowledge` 与 `/pages/community/public-images` 这类散落路径；`review-texts.vue` 必须有 `ux-empty`、`reasonPicker(`、`moderationCardView(`。

## 未执行

模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 记录的同一阻塞。因此本轮不声明真机或模拟器渲染通过，也不提供截图对比。
