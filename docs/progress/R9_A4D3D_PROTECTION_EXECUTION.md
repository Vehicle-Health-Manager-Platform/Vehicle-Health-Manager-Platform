# R9 A4d-3d：商家施工防护执行记录

2026-10-20。前置 PR191（A4d-3c 商家接车检查）；分支 `codex/r9-a4d3d-protection`。目标是把施工防护的阶段判断、勾选、提交前置与报工记录收进可单测的纯函数——**后端、接口、`service-work.js` 的提交契约零改动**。

## 本轮实现

- 新增 `apps/miniapp/src/services/merchant-protection-display.js`：11 个导出（`PROTECTION_REQUIRED`、`REPORT_GROUPS` + 9 个映射）。
- 重做 `apps/miniapp/src/pages/merchant/protection.vue`：页面头部、阶段卡、防护项与照片卡、提交卡、已提交记录卡、报工记录卡、读取失败卡；四层 `v-if` 链收敛为 `protectionStageView` 的五阶段。
- 新增 `apps/miniapp/test/merchant-protection-display.test.js`：10 项（映射 9 项 + 页面契约 1 项）。

## 修掉的具体缺陷

| 缺陷 | 改造前 | 现在 |
| --- | --- | --- |
| 四层 `v-if` 链不可断言 | `protection` → `RECEIVED/IN_SERVICE` → `DISPUTED` → 其他，顺序即规则 | `protectionStageView` 五阶段枚举，单测锁顺序（含"已有防护优先于状态"） |
| 未知状态被当成可提交 | 最后一条 `v-else` 落到"当前订单状态不能新增"，但没有测试保证 | 明确落 `not-allowed`，单测断言 `WAT` 与 `{}` 都不可提交 |
| 勾选行靠下标 | `PROTECTION_LABELS[index]` + `index<2?'（必选）'` | `protectionItemRows` 给出文案与必选标记 |
| 提交按钮不说原因 | 灰着 + 一句通用"未上传防护照片，技师无法报工" | `还需勾选：方向盘套；还需 1 张防护照片。` |
| 报工记录条件散落 | `template/else` + 内联中文表 + 模板拼配件行 | `protectionReportView` 一次给出状态、三组照片、方案/分析/配件/工时 |
| 读取失败只有重试 | `unauthorized/forbidden` 时也只给"重试加载" | `protectionReadFailureView` 分出"重新登录"与"重试" |

## 两处刻意的口径

1. **照片只认防护那一张**：`protectionSelectionState` 只把 `kind === 'PROTECTION'` 且 `fileId > 0` 的图算作"已上传"，`PROCESS`/`FAULT`/`FINISH` 不顶替。单测固定这条，否则"传了施工照就能提交防护"这种误解会再回来。
2. **未知报工状态当待签字**：`protectionReportView` 只认 `SIGNED`，其余（含未知值）都显示"待质检签字"。宁可让用户多看一眼，也不默认宣称已签字。

## 结果

A4d-3d 本地 PASS：小程序单测 **980 / 980 通过**（0 失败 / 0 跳过，新增 10 项）；`npm run build:miniapp` exit 0 且"共享选择器""无测试身份桥"两项门禁 PASS；`npm run build:h5 --workspace @autocare/miniapp` exit 0。10 个共享选择器在 `app.wxss` 中逐项存在；`merchant/protection.wxml` 含 `ux-hero`/`ux-badge`、7 个静态锚点（`protection-form`/`protection-stage`/`protection-items`/`protection-submit`/`protection-existing`/`protection-report`/`protection-read-error`）与静态文案"施工防护"、"门店工作台"。报告：`docs/testing/reports/R9_A4D3D_PROTECTION_2026_10_20.json`（`passed: true`）。

## 未覆盖

核销与退款页、真机与模拟器渲染（本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞）、视觉回归截图、暗色模式、无障碍朗读。`work-image-flow.js` 的上传与 `protectionBody`/`reportBody` 的字段校验沿用既有用例。全仓 108 个页面中现已 15 个接入设计系统。

## 后续

商家侧"接车检查 → 施工防护 → 订单 → 时段 → 派工"五个高频主流程页已全部统一。下一批按既定顺序：核销/退款等独立缺口 → A5 交付准备 → B 外部接入 → C 真机（外部接口继续后置）。PR 依赖 PR191，保持草稿，合并前需把 base 逐支改回 `main`。
