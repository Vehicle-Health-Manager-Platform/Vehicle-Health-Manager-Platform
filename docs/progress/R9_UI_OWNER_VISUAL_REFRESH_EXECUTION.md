# R9 车主端视觉刷新执行记录

2026-10-18。前置PR163；分支 `codex/r9-ui-owner-visual-refresh`。**本轮结论：车主端 9 个页面/组件的视觉已刷新，业务逻辑与接口调用零改动；916 项前端测试、微信构建与两项原生门禁、H5 构建通过。** 模拟器冒烟只跑到 2 项（Devtools RPC 超时），不作为通过证据。

## 起点

原界面是 uni-app 模板默认长相：纯白卡片无边框、`#007aff` 系统蓝、按钮圆角胶囊混排、九宫格功能入口无分组、金额与里程用正文字号、状态只靠文字颜色。问题不是"不好看"，而是**读不出轻重**：车主打开首页看不到"我的车况读数"，打开订单列表分不清哪些要处理。

约束来自三处既有门禁，先摸清再动手：

- `test/check-native-styles.cjs` 断言 `app.wxss` 必须含 `reservation-page`、`reservation-title`、`reservation-panel`、`reservation-primary` 四个类。共享样式必须落在 `App.vue` → `app.wxss`，页面内重复 `@import` 会被微信构建去重，造成"某页样式随机消失"。
- `test/simulator-smoke.cjs` 读 `.reservation-page` 的计算样式，要求 `padding-left`/`padding-top` 非零。改页面壳不能把 padding 归零。
- 部分测试用 `readFileSync` + 正则断言 `.vue` 源码（例如 `mine/index.vue` 必须出现 `pages/community/questions`，`pages.json` 第一页必须是 `pages/index/index`）。

## 实现

分三层，避免"每个页面各写一套"：

1. **令牌层** `src/uni.scss`：把 `$uni-*` 内置变量换成同一套语义值（墨绿 `#0b6b43`、琥珀 `#c25a00`、风险 `#b3261e`、暖纸灰底 `#fcfbf8`/`#f3f1eb`、`8px` 基础圆角）。项目内确认零引用这些变量，改值安全；顺手修掉 `$uni-font-size-lg: 16` 缺单位的问题。
2. **设计系统** `src/styles/design-system.css`（新建，264 行）：令牌挂在 `page` 上（`mp-weixin` 的自定义属性根），提供 `ux-page/ux-hero/ux-card/ux-metric/ux-badge/ux-btn/ux-row/ux-empty/ux-in` 一族原语。旧页面不引新类也能继承变量，不会出现"改过的页面和没改的页面两种配色"。
3. **共享层升级** `src/styles/reservations.css`：保留全部 10 个选择器与布局契约，只换视觉——`.reservation-page` 改暖底 + `40rpx 32rpx 96rpx` 内边距，`.reservation-panel` 加发丝边框与轻阴影，按钮默认改描边中性态并补 `[disabled]`/`:active`，`.reservation-primary` 改实色主绿。

然后逐页精修，共 9 个文件：

| 文件 | 关键改动 |
| --- | --- |
| `pages/index/index.vue` | 首屏加仪表盘状态灯母题（`.dial/.light.ok/.light.warn` + "业务运行状态以服务端为准"），角色卡左主色条 + 圆形箭头，`ux-r1~r3` 错峰 |
| `components/OwnerTabShell.vue` | hero 加 `::before` 径向光斑；未登录/待接入面板加 `.panel-badge`；`.action` 改实色主绿 |
| `pages/home/index.vue` | 档案摘要改 `.meter` 读数（64rpx 等宽数字 + 单位），档案计数改绿色药丸，测试记录提示改 `.badge.warn` |
| `components/VehicleList.vue` | 车辆行改卡片，选中态左侧主色条 + `.tick.on` 勾选圆圈，逐行 `80 + 60n` ms 错峰 |
| `pages/mine/index.vue` | 九连按钮改三组分组列表（订单与资产 / 互动与私域 / 合作与账号）+ 账号组，`/pages/community/questions` 入口保留 |
| `pages/service/index.vue` | 分类改药丸芯片（选中实色主绿），项目卡左主色条，输入框换暖底发丝边框，价格等宽 |
| `pages/order/list.vue` | 新增 `FILTERS` 常量与 `tone()` 纯展示映射，筛选改 `.chip`，状态 `.ok/.warn/.muted` 色点+文字，订单卡 `data-testid` 保留 |
| `pages/order/book.vue` | 报价卡改收银台读数（64rpx 等宽），车辆/时段选项虚线→实线主绿+✓，提交按钮 92rpx |
| `pages/order/detail.vue` | 应付金额 64rpx 等宽，订单号 `.ux-num`，标题 `ux-in` |

## 验证

| 项 | 结果 |
| --- | --- |
| 前端全量测试 | **916 / 916 通过**，0 失败、0 跳过、0 取消（4625ms） |
| 微信小程序构建 | `npm run build:miniapp` exit 0，`DONE Build complete.` |
| `app.wxss` 共享样式门禁 | **PASS native shared styles available globally in app.wxss** |
| 无测试身份桥门禁 | **PASS normal WeChat bundle contains no native test identity bridge** |
| H5 构建 | `npm run build:h5 --workspace @autocare/miniapp` exit 0，`DONE Build complete.` |
| 模拟器冒烟 | **INCOMPLETE**：`complete build and source route inventory match`、`native WebSocket handshake and RPC connection` 两项 PASS，随后 `Devtools RPC timeout: App.callWxMethod` 中断 |

前端 916 项里包含 `api-runtime.test.js`、`cloudrun-transport.test.js`、各业务流测试，它们断言的是请求方法、路径、信封与页面装配关系，本轮零改动即可全绿，说明"改的只是壳"这件事有测试兜底，不是靠人工目测。

## 两个环境坑（不是代码问题）

1. **H5 构建曾一次性失败，根因是本工具环境的删除钩子。** 报错是 `Build failed with errors`，但日志里没有任何编译错误。带完整输出复跑后看到：
   ```
   [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":262,"threshold":50,
    "targets":["...dist\\build\\h5\\assets"]}
   Build failed with errors.
   ```
   `uni build` 在产出后需要清空上一次构建遗留的 262 个 `assets` 文件，这一步被拦截，整条构建被判失败。把旧产物目录改名移走后构建 `exit 0`。**所以这不是源码错误，也不是偶发**——只要 dist 里有上次的产物就会复现，在用户自己的终端不会有这个问题。
2. **模拟器冒烟需要开发者工具处于就绪状态。** 两次运行都停在 `App.callWxMethod` 超时，前置的两项（构建产物与源码路由清单一致、WebSocket 握手与 RPC 连接）都通过。这属于本机开发者工具没有打开该项目，不是样式改动导致；因此不计入通过项，也不据此声明 UI 验收完成。

## 第二批：全仓色板统一（54 个页面文件）

第一批做完后发现一个比"没改完"更糟的问题：**只改了 9 个页面，其余 99 个还是模板色板**，两套配色同屏，像"只有几个页面被单独美化过"。根因是页面级 `<style scoped>` 里全是硬编码十六进制色值，`design-system.css` 的令牌覆盖不到。

于是按值映射统一，不逐页重写。替换 54 个页面文件 + 6 个组件/服务文件，共 400+ 处：

| 语义 | 旧值（次数） | 新值 |
| --- | --- | --- |
| 品牌绿 | `#00b42a`(29)、`#009a24`(1) | `#0b6b43` |
| 深绿（绿字） | `#008f24`(58)、`#167348`(1) | `#074d30` |
| 浅绿底 | `#eef8f0`(28) 及 5 个近义变体 | `#e9f2ec` |
| 主/次/三级文字 | `#1d2129`(31)、`#4e5969`(32)、`#86909c`(17)、`#697585`(14) | `#1a1d18`、`#454a41`、`#63685e` |
| 线/下沉底 | `#e5e6eb`(25)、`#f2f3f5`(27) 等 | `#e0ded6`、`#efede6` |
| 卡片白 | `#fff`(44)、`white`(23)、`#fafafa`、`#f7f8fa` | `#fcfbf8` |
| 风险/琥珀 | `#b42318`(30)、`#805300` 等 | `#b3261e`、`#9a4700`/`#f7ecdf` |
| 图表蓝 | `#2478d4` | `#0b6b43` |

**三处故意不改**：签名板画布保持纯白 `#ffffff`（签名图要存档与打印，背景非纯白会带进后续流程，只换笔色）；二维码保持纯黑 `#000`（改色降低识别率，这是功能不是装饰）；`PickupQr`/`ServiceCouponQr`/`SignaturePad` 三个 `<canvas>` 的 **CSS 背景**保持 `#fff`——canvas 未绘制的透明区会露出 CSS 背景，跟着改暖白会让二维码对比度下降。最后这条是批量替换跑完后复查 diff 才发现并退回的。

安全依据：`apps/miniapp/test/` 下颜色字面量出现 **0 次**，没有测试断言色值；916 项断言的是请求契约与页面装配。改完复跑：**916/916 仍全绿**、微信构建 exit 0 + 两项原生门禁 PASS、H5 构建 exit 0。

脚本留在 `.cache/unify_palette.py`（未入库），映射表与例外原因写进规格文档，便于后续新增页面沿用同一套值。

## 第三批：主操作标记（4 个关键业务页）

共享层把 `.reservation-page button` 统一成中性描边后，**旧页面的主操作不再突出**——这是升级共享层的副作用。80 个页面复用这个壳，用 `:first-of-type` 之类的位置规则去猜主操作并不可靠（有的页面第一个按钮是筛选芯片），所以不改共享规则，只手工标记 4 个高频页面：

- `merchant/orders.vue` → 扫码接车
- `merchant/pickup.vue` → 提交完整接车单
- `merchant/coupon-redeem.vue` → 扫描车主服务券授权码
- `technician/work.vue` → 确认提交完整报工

只加 `class="reservation-primary"`（该类早已在 `reservations.css` 定义），`:disabled`/`:loading`/`@tap`/`data-testid` 原样。改后 916/916 仍全绿。

## 没有做的事

- 逐页精修只覆盖车主端 9 个文件。色板已全仓统一（54 文件），但商家端、技师端、运营端页面的布局与信息层级仍是旧结构，**不声明"整体 UI 优化完成"**。
- 不做真机验收、不做设计稿逐像素对照、不做暗色模式（产品未定义暗色语义，硬加会和商家端现有配色冲突）。
- 未新增依赖、未引外部图片或字体，包体与离线可用性不变。
- 后端零改动，因此"前后端连接正常"的证据仍是既有 916 项前端测试与构建门禁，**不代表真实联调验收**。
