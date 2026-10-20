# 商家施工防护本机验收

2026-10-20。范围：`pages/merchant/protection.vue` 的展示层改造，新增 `services/merchant-protection-display.js`（11 个导出）与 10 项单测。**无后端、无迁移、无 API 变更**，`services/service-work.js` 的提交契约与 `WorkPhotos` 组件未改。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（980 项，含新增 10 项防护映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_protection_evidence.py   # 生成 docs/testing/reports/R9_A4D3D_PROTECTION_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 980 / # pass 980 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

编译产物结构核对：`merchant/protection.wxml` 含 `ux-hero`、`ux-badge`、`protection-form`、`protection-stage`、`protection-items`、`protection-submit`、`protection-existing`、`protection-report`、`protection-read-error`，以及"施工防护"、"门店工作台"。本轮锚点全为静态字面量，无需按绑定表达式另作说明。

## 新增测试覆盖

`apps/miniapp/test/merchant-protection-display.test.js`（10 项）：

- 阶段：`null` → `no-order`；`DISPUTED` + 已有防护 → `existing`（已有记录优先于状态）；`RECEIVED`/`IN_SERVICE` → `ready`；`DISPUTED` → `disputed`（risk）；`PAID` → `not-allowed`（muted）；未知状态 `WAT` 与 `{}` 都落 `not-allowed`。
- 必选项：`PROTECTION_REQUIRED` 恰为座椅套与方向盘套；四行文案、必选标记与选中态；`undefined` 全部未选中。
- 缺口提示：全空 → `还需勾选：座椅套、方向盘套；还需 1 张防护照片。`；缺一项 → `还需勾选：方向盘套；还需 1 张防护照片。`；只缺照片 → `还需 1 张防护照片。`；齐备 → `ready: true` 且 `blockedCopy` 为空；`PROCESS` 类图片不能顶替防护照片。
- 提交卡：未就绪给原因、`busy` 与 `saved` 都不可提交、已保存提示"防护记录已提交，不能重复提交。"。
- 已提交记录：未知项回落"未知防护项"、提交时间带北京时间、`photo_file_id` 为 0 时不给预览、只读说明含"不可修改"。
- 报工记录：`SUBMITTED` → 待质检签字/warn/无签字行；`SIGNED` → 已质检签字/ok/签字时间/签名照片 id；配件行 `机油 · 5W-30 · 示例 × 4`；`no_parts`/`no_fault_parts` 各自独立；未知状态当待签字；照片 id 非正数被过滤；不存在的报工给空结构。
- 读取失败：`unauthorized`/`forbidden` → 只给重新登录；其余 → 只给重试。
- 照片状态：上传中消息与权限类失败分开（`needsPermission`）。
- 写入状态：`rejected` 不提示重登、"重试原防护提交请求（不使用当前编辑内容）"、`saved` 判定。
- 页面契约：必须引用映射、必须有 `ux-hero`/`ux-card`/`toneClass(`；7 个锚点必须在；必须仍用 `protectionBody(order, selected.value…)` + `confirmStillHolds` + `save.save()` + `photoFlow.choose`；不得残留 `v-else-if="['RECEIVED','IN_SERVICE'].includes(read.value.order_status)"`、`PROTECTION_LABELS[index]`、`index<2?'（必选）'`、`process_photos:'施工照'`。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真机拍照上传与真实浏览器流程本轮未跑：需要真机相机授权、本地后端与 H5 预览服务。照片上传与 `protectionBody`/`reportBody` 校验由 `work-image-flow`/`service-work` 既有用例覆盖，不依赖本页 DOM。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
