# R9 车主端视觉刷新（frontend-design）设计

## 目标

把车主端核心旅程从小程序模板默认长相，改成一套有主张、可复用、不夸张的界面语言，同时保证三条硬底线：**不改任何业务逻辑与接口调用**、**不破坏既有测试断言**、**微信小程序构建与原生门禁继续通过**。

"保障前后端正常连接"在本阶段的具体含义是：只动模板结构与样式，不动 `api-runtime`、`request` 封装、`reactive` 会话单例、`uni.navigateTo` 跳转参数与 `data-testid` 选择器。任何一处被顺手改掉，都会表现为"页面在、接口不通"，所以下面把不可变量单独列成清单。

## 调性

从调性菜单单选：**工业实用 × 仪表盘语义**。理由是这个产品的核心不是"生活方式消费"，而是车辆状态、档案完整度、报价与时段、工单证据——读数、状态灯、发丝分隔比插画和大圆角更贴合"可信"。

明确不做的事（反 AI 味清单里最容易踩的几项）：

- 不用紫色/靛蓝渐变、不用霓虹发光、不用毛玻璃。
- 不用 emoji 充当图标。
- 不堆大圆角胶囊（只把徽章做成药丸，按钮走 12rpx 的中性圆角）。
- 不用"渐变文字标题"。
- 不在同一屏塞三组以上入场动画。

## 令牌

令牌挂在 `page` 上（`mp-weixin` 的自定义属性根节点是 `page`，不是 `:root`）。这样没引用新类的旧页面也能继承变量，避免"只有改过的页面配色统一"的半成品状态。

| 组 | 令牌 | 值 | 用途 |
| --- | --- | --- | --- |
| 底 | `--ux-bg` | `#f3f1eb` | 暖纸灰，60% 面积 |
| 面 | `--ux-surface` | `#fcfbf8` | 卡片，30% |
| 面（下沉） | `--ux-surface-sunk` | `#efede6` | 禁用态、次级底 |
| 字 | `--ux-ink` / `--ux-ink-soft` / `--ux-muted` / `--ux-faint` | `#1a1d18` / `#454a41` / `#63685e` / `#8a8f84` | 四级字阶，最浅一级也 ≥4.5:1 |
| 主色 | `--ux-primary` / `-ink` / `-wash` | `#0b6b43` / `#074d30` / `#e9f2ec` | 10% 强调：主按钮、选中、状态正常 |
| 强调 | `--ux-accent` / `-ink` / `-wash` | `#c25a00` / `#9a4700` / `#f7ecdf` | 待办、待处理、提示 |
| 风险 | `--ux-risk` / `-wash` | `#b3261e` / `#f8ebea` | 失败、超期、需核对 |
| 线 | `--ux-line` / `--ux-line-strong` | `rgba(26,29,24,.14)` / `.3` | 发丝边框、分隔 |
| 影 | `--ux-shadow-1` / `-2` | 两层低透明暖影 | 卡片、浮层 |
| 形 | `--ux-radius-card` / `-ctl` | `20rpx` / `12rpx` | 卡片 / 控件 |
| 动 | `--ux-ease` / `--ux-dur-fast` / `--ux-dur-base` | `cubic-bezier(.16,1,.3,1)` / `160ms` / `320ms` | 统一缓动与时长 |

主色选择墨绿而非常见的"科技蓝"，是因为蓝色在这类产品里已被"网约车/货运"占满，且和暖纸灰底容易显脏；墨绿在 `#fcfbf8` 上的对比度是 6.2:1，做正文按钮文字也够用。

## 组件层

新建 `apps/miniapp/src/styles/design-system.css`，只做视觉原语，不含任何业务语义：

- 壳：`.ux-page`、`.ux-hero`（`::before` 径向光斑做氛围层，不引图片资源）、`.ux-eyebrow`、`.ux-title`、`.ux-lead`。
- 容器：`.ux-card`、`.ux-card-title`、`.ux-card-note`、`.ux-divider`（负边距出血到卡片边缘）。
- 读数：`.ux-metric*`、`.ux-num`（`font-variant-numeric: tabular-nums`，保证数字滚动时不抖）。
- 状态：`.ux-badge` 及其 `-ok/-warn/-risk/-muted`。**每个徽章都带一个 `::before` 圆点 + 文字**，不允许只靠颜色传达状态（色弱可用性）。
- 按钮族：`.ux-btn` 默认是发丝描边的中性按钮，`.ux-btn-primary` 实色主绿，`.ux-btn-accent` 实色琥珀，`.ux-btn-quiet` 浅底，`.ux-btn-ghost` 无底。`[disabled]` 与 `:active` 有独立态，且 `[disabled]` 不用 `opacity` 降低（会影响文字对比度），而是换底色与字色。
- 空态：`.ux-empty*`（虚线框 + 居中短句）。
- 入场：`.ux-in` + `.ux-r1~r4`（0/80/160/240ms 错峰），只动 `opacity` 与 `translateY`。`prefers-reduced-motion: reduce` 下全部关闭并置为可见（不能只把 animation 去掉，那会让 `.ux-reveal` 的 `opacity:0` 永久不可见）。

### 全局注入的必要性

微信小程序构建会把页面 `<style>` 里重复的 `@import` 去重，导致"某个页面单独引了共享样式、另一个没引"的随机失效。所以 `design-system.css` 与 `reservations.css` 一律在 `App.vue` 里引入一次，落到 `app.wxss`。`test/check-native-styles.cjs` 会对 `app.wxss` 断言 `reservation-page/title/panel/primary` 四个类必须存在，这条门禁正是防这个坑。

`uni.scss` 同步把 `$uni-*` 内置变量换成同一套语义值。项目内确认零引用这些变量，改值安全；顺手修掉 `$uni-font-size-lg: 16` 缺单位的 bug（缺单位在部分编译路径下会静默失效）。

## 页面改动范围

本阶段只覆盖车主端主旅程，共 9 个文件：

| 文件 | 改动要点 | 记忆点 |
| --- | --- | --- |
| `pages/index/index.vue` | 三角色入口 | 仪表盘状态灯母题（`.dial/.light.ok/.light.warn`）+ 角色卡左主色条 + 圆形箭头 |
| `components/OwnerTabShell.vue` | 4 个 tab 页共用壳 | hero 光斑、`.panel-badge` 标明"未登录/待接入"、主操作实色 |
| `pages/home/index.vue` | 档案摘要 | `.meter` 读数（64rpx 等宽数字 + 单位），`.count` 改绿色药丸 |
| `components/VehicleList.vue` | 车辆列表 | 选中态左侧主色条 + `.tick.on` 勾选圆圈（不只靠颜色），逐行 80+60n ms 错峰 |
| `pages/mine/index.vue` | 我的 | 九连按钮改三组分组列表（订单与资产 / 互动与私域 / 合作与账号） |
| `pages/service/index.vue` | 服务项目 | 分类改药丸芯片、项目卡左主色条、输入框换暖底发丝边框 |
| `pages/order/list.vue` | 订单列表 | `.chip` 筛选、`.status.ok/.warn/.muted` 色点+文字、价格等宽大字 |
| `pages/order/book.vue` | 下单 | 报价卡改收银台读数、时段/车辆选项虚线→实线主绿+✓、提交按钮 92rpx |
| `pages/order/detail.vue` | 订单详情 | 应付金额 64rpx 等宽、订单号 `.ux-num` |

`styles/reservations.css` 只升级视觉，**保留全部 10 个选择器与布局契约**（`.reservation-page` 的 `padding` 必须非零，模拟器冒烟会读计算样式）。

## 不可变量清单

改动过程中逐条守住，任何一条破坏都会表现为"UI 好了但功能坏了"：

1. `data-testid` 一个都不改名：`home-archive-summary`、`vehicle-list`、`order-${id}`、`order-detail`、`appointment-code`、`owner-redeem-code`、`owner-archive-entry`、`owner-review-entry`、`owner-payment-reconcile`、`owner-work-evidence-entry`、`booking-vehicle-${id}`、`booking-slot-${id}`。
2. `mine/index.vue` 必须保留指向 `pages/community/questions` 的入口（有测试用 `readFileSync` + 正则断言源码）。
3. `pages.json` 第一个页面必须仍是 `pages/index/index`。
4. 生命周期不能丢：`order/list.vue` 的 `watch(token, …)`、`onShow/onHide/onUnload` 是接口触发与清理的唯一入口，重写模板时极易连坐删除。
5. 所有 `uni.navigateTo` 的 `url` 与 query 参数原样保留。
6. 不新增第三方依赖、不引外部图片/字体（包体与离线可用性）。

## 无障碍与降级

- 正文对比度全部按 ≥4.5:1 选值，最浅的 `--ux-faint` 仅用于辅助小字。
- 触摸目标：按钮最小高度 84rpx（≈42px），提交类主按钮 92rpx，列表行含内边距后 ≥44px。
- 状态不只靠颜色：徽章带圆点+文字，车辆选中带 ✓。
- `prefers-reduced-motion` 下关闭入场动画并保证内容可见。
- 等宽数字避免读数跳动造成的误读。

## 未覆盖

只覆盖车主端 9 个文件，商家端、技师端、运营端与其余大量页面仍是旧视觉，不做"整体 UI 已完成"的声明。不涉及真机、不涉及设计稿对照验收、不做暗色模式（当前产品未定义暗色语义，强行加会与商家端冲突）。本阶段无后端改动，接口连通性由既有 916 项前端测试与构建门禁保证，不代表真实联调验收。
