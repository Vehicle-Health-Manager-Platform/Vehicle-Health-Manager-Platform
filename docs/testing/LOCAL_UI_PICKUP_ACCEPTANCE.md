# 商家接车检查本机验收

2026-10-20。范围：`pages/merchant/pickup.vue` 的展示层改造，新增 `services/pickup-display.js`（13 个导出）与 13 项单测。**无后端、无迁移、无 API 变更**，`services/pickup.js` 的提交契约未改。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（970 项，含新增 13 项接车映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_pickup_evidence.py   # 生成 docs/testing/reports/R9_A4D3C_PICKUP_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 970 / # pass 970 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true` |

编译产物结构核对：`merchant/pickup.wxml` 含 `ux-hero`、`ux-badge`、`pickup-form`、`pickup-login`、`pickup-order`、`pickup-mileage`、`pickup-fuel`、`pickup-arrival`、`pickup-submit`、`pickup-read-error`，以及"接车检查"、"油量与损伤"、"到店与授权"、"前往门店登录"。

**注意**：`:data-testid="`pickup-slot-${row.slot}`"` 与 `:class="`pickup-photo-${row.slot}`"` 在 mp-weixin 产物里会被压成 `{{row.t}}` / `{{[...]}}`，wxml 里搜不到原文。这两个锚点属于源码层契约，由单测扫描 `pickup.vue` 保证；报告里把它们记成 `*_expected_absent` 两项断言，不要拿编译产物当缺失依据。

## 新增测试覆盖

`apps/miniapp/test/pickup-display.test.js`（13 项）：

- 照片三态：未上传（muted/拍照选图）、待上传（warn/可原图重试）、已上传（ok/替换图片/可预览）；未知槽位回落槽名；`undefined` photo 当作未上传。
- 照片进度：满八张 `已上传 8 / 8 张` 且无缺口；六张时列出 `['内饰','油表']` 与"还差 2 张：内饰、油表"；`undefined` 时按 `PICKUP_SLOTS` 顺序列出全部八张。
- 里程：空值不给差值、`1000/1000` 差 0 且不要求原因、`900/1000` 差 −100 且 `tone: 'risk'` 与"回退原因"提示；`10000000`/`-1`/`12.5`/`abc` 一律非法；`9999999` 合法。
- 基线：`ARCHIVE` → 最近档案、其他 → 车辆里程、缺失时值 0 且 `known: false`。
- 油量：`HALF` → 下标 2 / `½`；`FULL` → 下标 4 / `满`；未选 → "请选择油量"、下标 0、muted；未知值同未选。
- 损伤：未选 / 无损伤 / 有损伤三态文案与色调；已标注 2 / 20 且剩余 18；满 20 后 `canMark: false`。
- `canMarkDamage`：油表恒 false；未选"有损伤" false；`url` 为空 false；`fileId` 为 0 false；满 20 false；`form` 缺失 false。
- 损伤行标题："车左 · 标注 2"、未知槽位"未知位置"。
- 订单事实：项目名、`预约 2026-10-21 10:00（北京时间）`、基线文案、须知含"八张"与"油表照片须独立"；缺字段时给"项目未提供"/"预约时间未提供"。
- 读取失败：`conflict` → "接车单已存在"/可跳查看；其他 → "订单未能加载"/可重试。
- 提交前置：缺 1 张时不可提交且提示"还差 1 张：油表"；齐备可提交；busy 与 saved 都不可提交；saved 时提示"接车单已提交，不能重复提交。"。
- 写入状态：`unauthorized` 可重新登录、"重试原接车提交请求（不使用当前编辑内容）"；`rejected` 不提示登录；`saved` 判定为真。
- 页面契约：必须引用映射的 6 个函数 + `canMarkDamage` + `damageRowLabel` + `toneClass(`；必须有 `ux-hero`/`ux-card`；必须保留 `pickup-form`、`pickup-slot-${row.slot}`、`pickup-photo-${row.slot}` 三个锚点；必须仍用 `pickupBody(order, form)` 前置校验并保留 `save.save()`/`save.retry`/`flow.load`；不得残留 `form.damages.length >= 20`、`form.photos[slot].fileId ? '替换图片'`、旧标注说明原文。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真实浏览器流程与真机拍照上传本轮未跑（需要本地后端、H5 预览服务与真机授权）。照片上传、损伤坐标归一化与 `pickupBody` 字段校验由 `apps/miniapp/test/pickup.test.js` 既有 6 项用例覆盖，不依赖本页 DOM。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
