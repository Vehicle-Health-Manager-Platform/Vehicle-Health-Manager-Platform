# 商家到店核销本机验收

2026-10-20。范围：`pages/merchant/redeem.vue`（预约核销）与 `pages/merchant/coupon-redeem.vue`（服务券独立核销）的展示层改造，新增 `services/merchant-redemption-display.js`（15 个导出）与 17 项单测。**无后端、无迁移、无 API 变更**，`services/order-redemption.js` 与 `services/coupon-transactions.js` 的请求契约未改。

## 复现命令

在仓库根目录执行：

```bash
# 1. 小程序全量测试（997 项，含新增 17 项核销映射与页面契约）
npm test --workspace @autocare/miniapp

# 2. 微信小程序构建 + 两项原生门禁
npm run build:miniapp

# 3. H5 构建（若 dist/build/h5 有上次产物，先改名移走，否则 uni build 的批量清理会被本机拦截）
npm run build:h5 --workspace @autocare/miniapp

# 4. 一次跑完 1–3 并生成证据报告
python .cache/make_redemption_evidence.py   # 生成 docs/testing/reports/R9_A4D4A_REDEMPTION_2026_10_20.json
```

## 本轮实际结果

| 步骤 | 结果 |
| --- | --- |
| 1 | `# tests 997 / # pass 997 / # fail 0 / # skipped 0 / # cancelled 0` |
| 2 | exit 0，`DONE  Build complete.`；`PASS native shared styles available globally in app.wxss`；`PASS normal WeChat bundle contains no native test identity bridge` |
| 3 | exit 0，`DONE  Build complete.` |
| 4 | 报告 `passed: true`（`tests: 997`、`redeem_inputs: 1`、`coupon_inputs: 3`） |

## 编译产物结构核对

| 产物 | 核对项 |
| --- | --- |
| `merchant/redeem.wxml` | `ux-hero`、`ux-badge`、10 个锚点（`redeem-form`/`redeem-order`/`redeem-stage`/`redemption-receipt`/`redeem-form-card`/`redeem-code`/`redeem-submit`/`redeem-test-warning`/`redeem-error`/`redeem-login`）、静态文案"门店工作台"/"核销验码"/"输入六位核销码"、`<input>` 计数 **1** |
| `merchant/coupon-redeem.wxml` | `ux-hero`、`ux-badge`、14 个锚点（`coupon-redeem-form`/`coupon-form`/`coupon-scan`/`coupon-id`/`coupon-revision`/`coupon-code`/`coupon-preview`/`coupon-preview-card`/`coupon-redeem`/`coupon-saved`/`coupon-error`/`coupon-records`/`coupon-empty`/`coupon-retry`）、静态文案"门店工作台"/"服务券独立核销"/"本店服务券核销记录"、`<input>` 计数 **3** |
| 两页 wxml 共同 | 映射供给的六条文案（"验码并核销"、"测试核销已完成"、"测试核销，未真实扣款，不可作为收款凭据"、"确认交付并核销服务券"、"核对本店服务券与授权"、"重试原核销请求"）**均搜不到**（`mapping_text_expected_absent` 全为 `true`） |
| `services/merchant-redemption-display.js`（编译后） | 上述六条文案**全部存在**（`mapping_text_in_compiled_module` 全为 `true`），两页 `page.js` 均引用该模块 |

最后两行是本轮的关键证据：模板里只有 `{{submitView.label}}` 这类绑定，所以字面量必须"在 wxml 缺席、在映射模块在场"——只要有一边反了，就是没接线或没编译进去。

## 新增测试覆盖

`apps/miniapp/test/merchant-redemption-display.test.js`（17 项）：

- 核销码：空值还差 6 位；`'12 34-56'` 归一化成 `123456`（粘贴带分隔符不能变 8 位）；超长截断；数字入参归一化。
- 阶段：`no-order` / `redeemed`（凭据优先，`DISPUTED`+凭据仍是 `redeemed`）/ `loading`（含未传 `loaded`）/ `history-no-record` / `blocked` / `not-now`（未知 `WAT`、`{}`）/ `ready`，并逐阶段断言标题、说明、tone 齐全。
- 订单事实：`'88.50'` 原样、`'999999.99'` 不丢精度、`88` → `88.00`、`'88.5'` → `¥—`、缺失 → `¥—`；`test_mode` 只认严格 `true`，字符串 `'true'` 不算。
- 凭据：测试核销 → "测试核销已完成"/warn/"未真实扣款，不可作为收款凭据"；真实 → "已核销"/ok；`order_status/redeemed_at/test_mode/changed` 形状（提交回执）同样可用；`null`/`undefined`/`''`/`0` 全部 `exists: false`。
- 提交按钮：码不齐不给点、冷却 5.2 秒显示"请等待 6 秒"、`busy` 显示"正在提交核销…"、负冷却与 `NaN` 不会锁死按钮。
- 读取失败：`unauthorized`/`forbidden` 给重新登录（订单与凭据任一侧越权都算）、其余给重试。
- 服务券表单：三格全缺 → `还需填写：本人券编号、授权版本、32 位授权码`；授权版本 `0` 不算已填；编号拒绝前导零/小数/超长；授权码拒绝大写、31/33 位、非 hex；进度 `5 / 32`。
- 预览卡：可核销 → warn；`can_redeem=false` 且无 `redemption` → risk + 核对提示；**有 `redemption` → ok + "该券已于 <时间> 核销，凭据见下方"**，不再误报"不可核销"。
- 凭据卡：三行，含"佣金 ¥0.00"与"此凭据不表示微信或现金收款。"。
- 记录卡：空态标题与说明；页脚"本店核销记录 共 9 条 · 已加载 2 条"；`total` 缺失不产生 `NaN`。
- 冷却：`0` 不激活、4200ms → 4 秒、1001ms → 1 秒（不足一秒也提示）、过期不激活。
- 失败口径：`kind` 三态（`unauthorized`/`forbidden` 可重登、`uncertain` 保留原请求），并断言写错字段名（`failureKind`）时**不会**误开重新登录。
- 两个按钮：三格齐才能核对；`preview.can_redeem` 严格 `true` 才能核销；`busy`/冷却/`uncertain` 三种情况两个按钮都锁；表单进度不受锁影响。
- 页面契约（两页各一项）：必须引用映射函数、必须有 `ux-hero`/`ux-card`/`toneClass(`；锚点逐个在；核销页必须 `submitView.label`/`facts.testModeCopy`/`receiptView.title` 来自映射、必须合并 `receipt.value?.redemption || state.saved`、`<input>` 只出现一次且在 `stageView.stage === 'ready'` 卡片内、不得残留 `/^[0-9]{6}$/` 与三层 `v-else-if`；服务券页必须用 `parseServiceCouponQr` + `apiRuntime.scanCode` + `couponTransactionsApi.serviceRecords`，三个按钮文案来自映射且模板里不得出现"确认交付并核销服务券"字面量。
- 常量同源：`REDEEM_CODE_LENGTH`/`COUPON_CODE_LENGTH` 与 `order-redemption.js`/`coupon-transactions.js` 里的正则一致，两页不得自己再写一遍格式。

## 未执行

- 模拟器冒烟（`node apps/miniapp/test/simulator-smoke.cjs`）**未执行**：本机微信开发者工具未打开 `dist/build/mp-weixin`，与 PR164 同一阻塞。
- 真实核销链路（`scripts/local_*_e2e.cjs`）本轮未跑：需要本地后端与测试门店账号；文案契约已由页面契约用例锁定（"验码并核销"、"测试核销已完成"、"测试核销，未真实扣款，不可作为收款凭据"三条 e2e 依赖的字符串）。
- 真机扫码（`apiRuntime.scanCode`）与 H5 预览流程未跑：需要真机相机授权与 H5 预览服务。

因此本轮只声明"源码与编译产物层面通过"，不声明真机或浏览器端渲染通过。
