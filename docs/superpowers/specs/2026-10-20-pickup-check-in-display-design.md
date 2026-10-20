# R9 A4d-3c 商家接车检查设计

2026-10-20。前置 A4d-3b（商家时段与派工）。范围：`apps/miniapp/src/pages/merchant/pickup.vue`（接车检查），**后端、接口、迁移、`services/pickup.js` 的提交契约零改动**。

## 这页的问题不在样式，在"没有反馈"

1. **八张照片只有一个布尔**：模板用 `complete` 控制提交按钮，`form.photos[slot].fileId ? '替换图片' : '拍照 / 选图并上传'` 内联在模板里。缺哪几张、哪张选了但没传上去，只能靠用户自己数八次。
2. **里程差值是模板算的**：`{{Number(form.mileage) - baseline}}` 只在 `form.mileage !== ''` 时显示，没有"低于基线"的独立提示，"需填原因"是一条永远显示的静态标签——不管这次到底是不是回退。真正的 0–9999999 校验要到点提交时才由 `pickupBody` 抛出。
3. **标注点没反应时不说为什么**：`mark()` 里挤着四个条件（油表不标注、必须选"有损伤"、最多 20 处、照片必须已上传），全都在函数体里，用户点照片没反应得不到任何解释。
4. **提交按钮不给原因**：`:disabled="disabled || !complete"`，缺片时按钮灰着，但页面不说还差哪几张。

## 做法

新增 `apps/miniapp/src/services/pickup-display.js`，13 个导出（1 常量 + 12 函数）：

| 导出 | 解决什么 |
| --- | --- |
| `DAMAGE_LIMIT` | 20，与 `pickupBody` 的 `damages>20` 同源，用于提前提示（硬校验仍在 `pickup.js`） |
| `photoSlotView(slot, photo)` | 未上传 / 待上传 / 已上传 三态 + 徽标色调 + 主按钮文案 + 是否可预览/可重试上传 |
| `pickupPhotoProgress(form)` | `已上传 n / 8 张` + 缺哪几张（按 `PICKUP_SLOTS` 顺序）+ "还差 2 张：内饰、油表" |
| `pickupBaselineView(baseline)` | 基线数值、来源（`ARCHIVE` → 最近档案，其余 → 车辆里程）、是否已知 |
| `pickupMileageView(mileage, baseline)` | 合法性、差值、是否低于基线需写原因、一句提示 |
| `pickupFuelView(fuel)` | picker 需要的下标 + 未选时的"请选择油量" |
| `pickupDamageView(form)` | 选择状态、已标注数、剩余额度、能否继续标注、按状态换一句说明 |
| `canMarkDamage(slot, form, photo)` | 四条前提合成一个可断言判断（油表/未选有损伤/超额/图未上传） |
| `damageRowLabel(damage, index)` | "车左 · 标注 2"，槽位未知回落"未知位置" |
| `pickupOrderFacts(context)` | 项目名、预约时间、基线、拍摄须知，字段缺失一律给兜底 |
| `pickupReadFailureView(read)` | 冲突（已提交）与普通失败的标题、能否重试、能否跳查看 |
| `pickupSubmitView(form, flags)` | 提交前置、按钮文案、缺片提示、已保存态 |
| `pickupWriteView(write)` | 失败文案、是否该重新登录、"重试原请求不用当前编辑内容"的措辞 |

### 照片三态是这页的主线

| 状态 | 判据 | 徽标 | 主按钮 | 附加入口 |
| --- | --- | --- | --- | --- |
| 未上传 | `fileId<=0` 且无 `candidate` | 未上传 / `muted` | 拍照 / 选图并上传 | — |
| 待上传 | 有 `candidate`，上传未成功 | 待上传 / `warn` | 拍照 / 选图并上传 | 原图重试上传 |
| 已上传 | `fileId>0` | 已上传 / `ok` | 替换图片 | 预览 / 刷新图片 |

"待上传"必须能被看见：`choose()` 会自动接着 `upload()`，但上传失败时 `candidate` 仍在、`fileId` 仍为 0，用户此前只能从一句 `message` 猜状态。

### `canMarkDamage` 的四条前提

| 前提 | 不满足时用户看到的 |
| --- | --- |
| 槽位不是 `FUEL` | `pickupDamageView` 的说明里写明"油表照片不参与损伤标注" |
| `damageStatus === 'PRESENT'` | "必须明确选择有无损伤" |
| 已标注 `< 20` | "已标注 20 / 20 处"，不再增长 |
| 照片 `fileId>0` 且 `url` 非空 | 图片没显示出来时点不到，也就无从误解 |

模板不再自己写 `slot === 'FUEL' || form.damageStatus !== 'PRESENT' || form.damages.length >= 20`；`mark()` 只问 `canMarkDamage`，并且**在 `boundingClientRect` 回调里再问一次**——查询是异步的，期间用户可能换了照片或删光标注（沿用原实现里 `fileId` 比对的同一思路）。

### 里程：把"要不要写原因"变成可判断的

`pickupMileageView` 只在 `^\d{1,7}$` 成立时给 `delta`，因此：

- 未填 → `deltaCopy` 为空，不再出现 `0 - 基线` 那种假差值；
- 合法且 `< 基线` → `needsReason: true`、`tone: 'risk'`、"里程低于基线，请在下方填写回退原因"；
- 合法且 `>= 基线` → "提交时服务端会再次比对基线里程"。

服务端提交时仍会重新比对基线并校验原因，映射只负责**提前说清楚**，不替代 `pickupBody` 与后端。

## 保留的契约

页面继续用 `pickupBody(order, form)` 做提交前校验（异常写进 `write.message`，不发请求），提交仍走 `createReservationWriteFlow` 的幂等键与"原请求重试"。三个既有锚点全部保留：

- `data-testid="pickup-form"`（根节点）
- `:data-testid="`pickup-slot-${row.slot}`"`（八个槽位卡片）
- `:class="`pickup-photo-${row.slot}`"`（`mark()` 的 `createSelectorQuery` 目标，锚点名不能改）

## 编译产物层面的限制

`:data-testid="`pickup-slot-${row.slot}`"` 与 `:class="`pickup-photo-${row.slot}`"` 在 mp-weixin 产物里会被压成 `{{row.t}}` / `{{[...]}}` 这类短名，**wxml 里搜不到原文**；来自映射的文案（"提交完整接车单"、"还差 N 张"）同样是绑定表达式。证据报告因此核对静态锚点（`pickup-form`/`pickup-order`/`pickup-mileage`/`pickup-fuel`/`pickup-arrival`/`pickup-submit`/`pickup-read-error`/`pickup-login`）与静态文案（"接车检查"/"油量与损伤"/"到店与授权"/"前往门店登录"），并把"动态锚点本就搜不到"显式记成两项断言（`dynamic_slot_anchor_expected_absent`、`dynamic_photo_class_expected_absent`），避免下次误判成缺锚点。

## 验收口径

1. `node --test test/*.test.js`：970 项全通过（新增 13 项）。
2. `npm run build:mp-weixin`：exit 0，"共享选择器""无测试身份桥"两项门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`merchant/pickup.wxml` 含 `ux-hero`/`ux-badge` 与上面 8 个静态锚点、4 条静态文案。

## 不覆盖

`merchant/protection`（施工防护）、核销与退款页；真机与模拟器渲染；视觉回归截图；暗色模式与无障碍朗读。照片上传、损伤坐标归一化、`pickupBody` 的字段校验已由 `pickup.test.js` 既有用例覆盖，本轮只新增展示层用例。
