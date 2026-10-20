# R9 A4d-3d 商家施工防护设计

2026-10-20。前置 A4d-3c（商家接车检查）。范围：`apps/miniapp/src/pages/merchant/protection.vue`（施工防护），**后端、接口、迁移、`services/service-work.js` 的 `protectionBody` 契约零改动**。

## 这页的问题：状态判断与文案散在模板里

1. **四层 `v-if` 链**：`read.value.protection` → `['RECEIVED','IN_SERVICE'].includes(order_status)` → `order_status==='DISPUTED'` → 其他。顺序本身就是规则（**已有防护记录优先于订单状态**），但不可断言，改一处很容易把"已提交"的订单重新显示成可编辑表单。
2. **勾选行靠下标**：`PROTECTION_LABELS[index]` 与 `index<2?'（必选）'` 内联，必选项其实是 `PROTECTION_ITEMS` 的前两项，与 `service-work.js` 里 `items()` 的硬校验各写一遍。
3. **提交按钮不给原因**：`:disabled="disabled"`，缺勾选或缺照片时按钮灰着，只有一句通用的"未上传防护照片，技师无法报工"。
4. **报工记录条件散落**：`status==='SUBMITTED'` 的 `template/else`、内联的三组照片中文表 `{process_photos:'施工照',…}`、配件 `v-for` 拼字符串，都在模板里。
5. **读取失败**：`['unauthorized','forbidden'].includes(read.failureKind)` 判断写在模板上，且始终只给"重试加载"。

## 做法

新增 `apps/miniapp/src/services/merchant-protection-display.js`，11 个导出（2 常量 + 9 映射）：

| 导出 | 解决什么 |
| --- | --- |
| `PROTECTION_REQUIRED` | `['SEAT_COVER','STEERING_COVER']`，与 `service-work.js` 的 `items()` 同源 |
| `REPORT_GROUPS` | 报工三组照片的 key 与中文标签（施工照/故障件照/完工照），与 `PHOTO_LABELS` 不是同一套，不能混用 |
| `protectionStageView(detail)` | 五阶段：`no-order` / `existing` / `ready` / `disputed` / `not-allowed` |
| `protectionItemRows(selected)` | 勾选行的 key、文案、是否必选、是否选中 |
| `protectionSelectionState(selected, files)` | 缺哪几项、防护照片是否已上传、能否提交、一句可照做的提示 |
| `protectionSubmitView(selected, files, flags)` | 按钮文案、能否点、已保存态、通用须知 |
| `protectionExistingFacts(protection)` | 已提交项名、提交时间、照片 id、只读说明 |
| `protectionReportView(report)` | 待签字/已签字两态、三组照片、方案/分析/配件/工时 |
| `protectionReadFailureView(read)` | 重新登录还是重试，二选一 |
| `protectionWriteView(write)` | 失败文案、能否重登、"原请求重试"措辞 |
| `protectionPhotoStatus(photos)` | 上传中/失败消息、是否需要开启相机权限 |

### 阶段顺序是业务规则

| 顺序 | 阶段 | 判断 | 为什么在这个位置 |
| --- | --- | --- | --- |
| 1 | `no-order` | `!detail` | 记录都没读到 |
| 2 | `existing` | `detail.protection` | **已有防护优先于状态**：提交过的订单不该再出现可编辑表单 |
| 3 | `ready` | `RECEIVED` / `IN_SERVICE` | 只有这两个状态可提交 |
| 4 | `disputed` | `DISPUTED` | 争议阻断新增防护 |
| 5 | `not-allowed` | 其余（含未知值） | 兜底，且**不把未知状态当可提交** |

单测逐条锁这五个阶段，含 "`DISPUTED` + 已有防护 → `existing`" 与 "未知状态 → `not-allowed`"。

### 提交前置合成一句话

`protectionSelectionState` 把两类缺口拼成一句能照着做的提示：

- 缺必选：`还需勾选：座椅套、方向盘套`
- 缺照片：`还需 1 张防护照片`
- 合成：`还需勾选：方向盘套；还需 1 张防护照片。`

照片只认 `kind === 'PROTECTION'` 且 `fileId > 0` 的那一张——`PROCESS`/`FAULT`/`FINISH` 类图片不能顶替（单测固定）。`ready` 只用于提示，真正的校验仍在 `protectionBody`：`items()` 要求 2–4 项且必须含座椅套与方向盘套，`fileId` 必须是正整数。

### 报工记录：两态 + 三组

- `status === 'SIGNED'` → 已质检签字（`ok`），附签字时间与签名照片；其余（含未知）→ 待质检签字（`warn`，不默认宣称已签字）。
- 三组照片按 `REPORT_GROUPS` 输出，id 非正数被过滤（`[1,0,-2]` → `[1]`），避免把 0 当文件。
- 配件行格式：`名称 · 型号 · 品牌 × 数量`；`no_parts`/`no_fault_parts` 各自一句声明，不互相顶替。

## 保留的契约

- 提交仍先过 `protectionBody(order, selected.value, fileId)`，再经 `confirmStillHolds(opened, …)` 比对"打开弹窗时的目标"与"提交时的目标"，最后走 `createReservationWriteFlow` 的幂等键与"原请求重试"。
- `WorkPhotos` 的 `choose`/`remove`/`retry`/`preview` 四个事件照旧接给 `createWorkImageFlow`，组件本身未改。
- 二次确认文案（防护照片须同时拍到座椅套与方向盘套）与 `write.retry` 的"不使用当前编辑内容"措辞保留。
- 新增静态锚点：`protection-form`、`protection-stage`、`protection-items`、`protection-submit`、`protection-existing`、`protection-report`、`protection-read-error`、`protection-login`。这些是**新增**而非替换——本页原本没有任何 `data-testid`。

## 验收口径

1. `node --test test/*.test.js`：980 项全通过（新增 10 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`merchant/protection.wxml` 含 `ux-hero`/`ux-badge` 与 7 个静态锚点、"施工防护"、"门店工作台"。

本轮锚点全是静态字面量，**不需要**像 A4d-3b/3c 那样额外记录"绑定锚点在编译产物里搜不到"。

## 不覆盖

核销与退款页；真机与模拟器渲染；视觉回归截图；暗色模式与无障碍朗读。照片上传（`work-image-flow.js`）与 `protectionBody`/`reportBody` 的字段校验沿用既有用例，本轮只新增展示层用例。
