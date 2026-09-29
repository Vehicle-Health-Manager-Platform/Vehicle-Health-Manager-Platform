# 汽车健康管家平台 — 全栈开发交付文档

> 版本：v1.0  
> 适用对象：独立全栈开发工程师（实习生亦可照做）  
> 阅读方式：从上到下顺序阅读，读完后即可开始设计、编码、测试、部署，无需再提问。  
> 配套文档：《汽车健康管家平台最终完整方案.md》（产品方案依据）

---

## 目录

1. [项目概述](#1-项目概述)
2. [产品需求 PRD](#2-产品需求-prd)
3. [页面清单与信息架构](#3-页面清单与信息架构)
4. [UI/UX 设计规范](#4-uiux-设计规范)
5. [页面布局与交互说明](#5-页面布局与交互说明)
6. [技术架构方案](#6-技术架构方案)
7. [数据库设计（含建表 SQL）](#7-数据库设计含建表-sql)
8. [API 接口文档](#8-api-接口文档)
9. [测试方案](#9-测试方案)
10. [部署与运维](#10-部署与运维)
11. [项目排期与里程碑](#11-项目排期与里程碑)
12. [验收与交付清单](#12-验收与交付清单)
13. [零提问默认决策规则](#13-零提问默认决策规则)

---

## 1. 项目概述

### 1.1 背景

汽车后市场存在三类长期痛点：车主对 4S 店价格不透明、路边店质量不信任；大量独立修理厂、社区洗车店、夫妻保养店进不了头部连锁平台的生态；二手车车况信息不对称。本项目以「车辆健康管理」为入口，用 AI 诊断分诊 + 异业联盟流量共享 + 商家聚合交付，把边缘中小商家团结起来，服务追求高性价比的车主。

### 1.2 项目目标

交付一个可上线、可运营的 MVP，包含：车辆健康档案管理、AI 对话诊断、服务聚合与商家报价、券与邀请裂变、同款车友社区、积分体系、接车检查与技师报工、商家/技师/运营三端管理。

**MVP 不追求 AI 高准确率**，追求流程闭环：用户能录入、能问诊、能拿到方案、能选商家、能预约、能接车、能评价、档案能自动更新。

### 1.3 目标用户

| 角色        | 描述                   | 终端           |
| --------- | -------------------- | ------------ |
| 车主        | 追求高性价比、希望用车透明省心的车主   | 微信小程序（H5 兜底） |
| 商家（店长/前台） | 独立修理厂、洗车店、轮胎店等中小商家   | 微信小程序        |
| 技师        | 商家雇用的维修技师            | 微信小程序        |
| 平台运营      | 平台内部人员，维护车型库、标准项目、考核 | PC 网页后台      |

### 1.4 范围（MVP 必须交付）

车主端小程序：首页、服务、AI 管家、档案、我的五个 Tab；车辆录入（4 种方式）；档案录入（拍照/语音/手动）；AI 诊断与推荐；预约下单；券领取核销；邀请裂变；积分兑换；车友圈；接车确认。

商家端小程序：接车检查（拍照/里程/油量/损伤标注/车主确认）；施工防护拍照；订单接单验码；技师派工；核销管理；选品定价；考核中心。

技师端小程序：接单报工、施工/故障件/完工拍照、维修方案填写、配件记录、质检签字。

运营 PC 后台：商家审核、标准项目管理、车型库维护、考核管理、券池管理、内容审核、价格监控、数据看板。

### 1.5 明确不做什么

不做独立社区 Tab、不做内容发布编辑器、不做评论点赞系统、不做 KOC 培养体系；不做商城与实物商品交易、不做抽奖；不做人工客服实时在线（人工诊断为非实时留言，后台可切换上下线）；不做自驾游/社交动态；不做视频直播；不做多语言、不做海外部署；不自己开店、不自己修车、不自己卖货。

### 1.6 商业与收费（默认口径）

- 平台向商家收佣金：平台内成交订单按 **3%** 抽佣，券核销不抽成。
- 新入驻商家**前 3 个月免佣**。
- 券成本由出券商家承担，平台不出钱。
- 商家增值服务（区域独家、推荐位、高级数据看板）MVP 不开发，预留字段与入口。
- 商家入驻无加盟费、无统一装修要求、无品牌要求。

---

## 2. 产品需求 PRD

### 2.1 功能清单与优先级

| 编号  | 功能               | 角色    | 优先级 | 说明                       |
| --- | ---------------- | ----- | --- | ------------------------ |
| F01 | 车主注册登录           | 车主    | P0  | 微信授权 + 手机号               |
| F02 | 车辆录入（4 种方式）      | 车主    | P0  | 车牌匹配、行驶证 OCR、VIN 解码、手动选择 |
| F03 | 车辆档案（拍照/语音/手动录入） | 车主    | P0  | 保养/维修/保险/年检/违章/改装        |
| F04 | 首页驾驶舱            | 车主    | P0  | 健康评分、强提醒流、指标、券与邀请提醒      |
| F05 | AI 管家对话          | 车主    | P0  | 结合档案诊断、推荐方案、推荐商家         |
| F06 | 服务聚合与报价          | 车主    | P0  | 标准项目、商家列表、报价、评价总结        |
| F07 | 预约下单与支付          | 车主    | P0  | 选商家、选时间、用券抵扣、预约          |
| F08 | 订单进度与核销码         | 车主    | P0  | 进度追踪、到店核销码               |
| F09 | 券体系              | 车主/商家 | P0  | 发券、领券、券池、核销、过期提醒         |
| F10 | 邀请裂变（月度循环双向）     | 车主    | P0  | 邀请 1/2/3/4 人循环，双方各得券     |
| F11 | 积分体系             | 车主    | P1  | 获取、明细、兑换券                |
| F12 | 车友圈              | 车主    | P1  | 自动生成经验卡片、问答、分享           |
| F13 | 商家入驻审核           | 运营    | P0  | 资质提交、审核、区域名额判定           |
| F14 | 接车检查             | 商家    | P0  | 环车拍照、里程油量、损伤标注、车主确认      |
| F15 | 施工防护拍照           | 商家    | P1  | 防护标准、拍照上传、归档             |
| F16 | 订单管理             | 商家    | P0  | 接单、派工、验码、退款、状态流转         |
| F17 | 选品定价             | 商家    | P0  | 标准项目勾选、报价、上下架            |
| F18 | 技师报工             | 技师    | P0  | 接单、施工/故障件/完工拍照、方案、配件     |
| F19 | 考核与等级            | 运营    | P1  | 拉新 40% + 券 40% + 过程 20%  |
| F20 | 运营后台             | 运营    | P0  | 标准项目、车型库、商家、券池、内容、价格监控   |

### 2.2 角色权限矩阵

| 资源      | 车主     | 商家       | 技师       | 运营   |
| ------- | ------ | -------- | -------- | ---- |
| 本人车辆档案  | 读写本人   | 只读本人订单关联 | 只读本人工单关联 | 只读全部 |
| 订单      | 读写本人   | 读写本店     | 读写本人工单   | 只读全部 |
| 商家数据/考核 | 只读（脱敏） | 读写本店     | 无        | 读写全部 |
| 标准项目    | 只读     | 勾选定价     | 只读       | 增删改  |
| 券池      | 领取     | 投放/核销    | 无        | 统筹   |
| 社区内容    | 读写本人   | 只读       | 只读       | 审核删除 |

实现方式：所有接口统一在网关层做 RBAC 鉴权，JWT 内嵌 `role` 与 `merchant_id`/`user_id`，服务端**永远二次校验资源归属**，禁止前端传 ID 越权。

### 2.3 核心业务流程

**主流程（车主用车）：**
注册登录 → 录入车辆 → 首页看提醒/档案/AI 问诊 → AI 给方案+推荐商家 → 选商家与报价 → 预约下单 → 商家接车检查 → 车主确认接车单 → 派工 → 技师施工报工 → 完工质检 → 车主到店验码核销 → 评价 → 档案自动更新 → 社区经验卡片自动生成。

**商家入驻流程：**
提交资质 → 运营审核 → 区域名额判定（同类数量已达上限则拒绝）→ 审核通过 → 引导建店员推广码 → 选品定价 → 上架 → 前台引导到店客户扫码注册。

**接车检查流程：**
商家小程序扫码进入订单 → 环车 5 方向拍照 → 拍仪表盘（OCR 里程，与档案比对，异常弹提示）→ 拍油表 → 拍内饰 → 标注已有损伤 → 生成接车单 → 推送车主 → 车主确认/异议 → 归档订单与档案。

### 2.4 异常与边界场景（默认处理规则）

| 场景           | 默认处理                              |
| ------------ | --------------------------------- |
| 网络断开         | 页面显示「网络连接失败」+ 重试按钮，本地缓存未提交表单      |
| 接口超时（>8s）    | 自动重试 1 次，仍失败提示重试                  |
| 登录失效         | 静默刷新 token；失败则跳登录页，回跳原页面          |
| 定位失败         | 默认按城市中心排序，提示「定位未授权，可手动选择区域」       |
| OCR 识别失败     | 展示识别结果可手动修改，不阻塞流程                 |
| 商家满额         | 区域同类商家已达上限，提示「该区域名额已满，可入驻其他品类」    |
| 券已领完/过期      | 按钮置灰显示「已领完」/「已过期」，点击给轻提示          |
| 被邀请人已注册      | 邀请人不计邀请数，给轻提示「该好友已注册」             |
| 商家下架项目       | 已下单订单保留快照，不影响历史订单                 |
| 用户未录入档案就问 AI | AI 按通用知识回答，并在结尾提示「录入车辆档案可获得更精准建议」 |
| 车主对接车单有异议    | 标记「争议中」，阻断施工派工，通知商家人工联系           |
| 技师未拍防护照      | 不开放报工入口，弹提示「请先完成施工防护拍照」           |

### 2.5 验收标准（功能级）

每个功能以「可走通主流程 + 异常处理生效 + 数据落库正确」为通过。详细验收清单见第 12 章。

---

## 3. 页面清单与信息架构

### 3.1 页面清单

| 编号  | 页面名称       | 路由路径                      | 角色  | 核心功能             |
| --- | ---------- | ------------------------- | --- | ---------------- |
| C01 | 车主登录授权     | /pages/auth/login         | 车主  | 微信授权、手机号绑定       |
| C02 | 车辆录入（4 入口） | /pages/vehicle/add        | 车主  | 车牌/VIN/行驶证/手动    |
| C03 | 车牌输入       | /pages/vehicle/plate      | 车主  | 输入车牌匹配车型         |
| C04 | 行驶证识别      | /pages/vehicle/license    | 车主  | 拍照 OCR           |
| C05 | VIN 输入     | /pages/vehicle/vin        | 车主  | 17 位 VIN 解码      |
| C06 | 手动选车型      | /pages/vehicle/manual     | 车主  | 品牌→车系→年款→配置      |
| C07 | 首页         | /pages/home/index         | 车主  | 驾驶舱、提醒、券、邀请、车友经验 |
| C08 | 服务首页       | /pages/service/index      | 车主  | 标准项目分类、参考价       |
| C09 | 服务项目详情     | /pages/service/detail     | 车主  | AI 方案、商家列表、评价总结  |
| C10 | 商家详情       | /pages/merchant/detail    | 车主  | 报价、服务内容、可用券、评价   |
| C11 | AI 管家      | /pages/ai/index           | 车主  | 对话、诊断、推荐         |
| C12 | AI 推荐方案    | /pages/ai/plan            | 车主  | 必做/推荐/可选，一键预约    |
| C13 | 档案页        | /pages/archive/index      | 车主  | 指标、证件、履历         |
| C14 | 录入记录       | /pages/archive/record-add | 车主  | 拍照/语音/手动         |
| C15 | 我的         | /pages/mine/index         | 车主  | 订单、车辆、福利、积分、车友圈  |
| C16 | 我的订单       | /pages/order/list         | 车主  | 进行中/已完成          |
| C17 | 订单详情       | /pages/order/detail       | 车主  | 进度、核销码、评价        |
| C18 | 预约下单       | /pages/order/book         | 车主  | 选时间、用券、确认        |
| C19 | 我的福利       | /pages/welfare/index      | 车主  | 券、邀请、任务          |
| C20 | 邀请有礼       | /pages/welfare/invite     | 车主  | 进度、循环奖励、邀请记录     |
| C21 | 积分中心       | /pages/points/index       | 车主  | 余额、兑换、明细、赚积分     |
| C22 | 车友圈        | /pages/community/circle   | 车主  | 热门经验、问答、分享       |
| C23 | 接车确认       | /pages/check/confirm      | 车主  | 查看照片、确认/异议       |
| C24 | 施工进度查看     | /pages/order/progress     | 车主  | 查看防护、施工照片        |
| C25 | 评价         | /pages/order/review       | 车主  | 评分、文字、上传图        |

**商家端小程序页面（M30–M45）：** 登录、工作台、订单列表、订单详情、接车检查（含损伤标注）、车主确认等待、施工防护拍照、派工给技师、核销验码、退款处理、选品定价、券管理、考核中心、数据看板、客户管理。

**技师端小程序页面（T50–T58）：** 登录（工号绑定）、工作台（待接单）、工单详情、接单、施工过程拍照、故障件拍照、完工拍照、维修方案填写、配件记录、质检签字。

**运营 PC 后台页面（O60–O78）：** 登录、工作台、商家审核、商家列表、标准项目管理、车型库管理、商家考核、券池管理、内容审核、价格监控、社区审核、数据看板、系统设置。

### 3.2 底部导航（车主端）

```
┌──────────────────────────────────┐
│  🏠 首页 │ 🔧 服务 │ 🤖 AI │ 📋 档案 │ 👤 我的  │
└──────────────────────────────────┘
```

### 3.3 跳转关系（核心链路）

```
首页提醒「保养到期」 → 服务项目详情
              ↘ AI 管家问诊 → AI 推荐方案 → 服务项目详情
服务项目详情 → 商家详情 → 预约下单 → 订单详情
订单详情 → 接车确认(推送) → 施工进度 → 评价
档案页 → 录入记录 → 首页/车友圈
我的福利 → 邀请有礼 → 分享海报
```

商家端：`工作台订单 → 接车检查 → 推送车主 → 施工防护 → 派工 → 技师报工 → 完工 → 车主核销`

---

## 4. UI/UX 设计规范

> 本节是全栈开发做 UI 的唯一依据，不另找设计师。所有色值、字号、间距、圆角、阴影均为固定值，直接写进 CSS 变量。

### 4.1 设计原则

- **信息密度优先**：汽车服务是任务型产品，不追求留白艺术，追求一屏看清车况与动作。
- **状态前置**：异常、即将过期、待确认等状态必须视觉优先。
- **一致性**：同一动作在全站样式一致；按钮、标签、卡片不可自创样式。
- **设计稿尺寸基准**：小程序按 iPhone 6/7/8（375×667）设计，最大宽度 750rpx；PC 后台栅格 24 列，断点 1200 / 992 / 768。

### 4.2 色彩

**品牌主色（健康绿）：** 主色 `#00B42A`，用于主按钮、健康评分、确认、已完成、成功态。主色按下 `#009A24`，主色禁用 `#A8E8B8`。

**辅助色：**

- 强调/警示 `#FF7D00`（保养到期、待处理）
- 危险/紧急 `#F53F3F`（故障码、逾期、降价外推）
- 信息 `#1664FF`（链接、导航辅助）
- 中性灰 `#86909C`（次要文字）、`#C9CDD4`（占位、分割线）、`#F2F3F5`（页面背景）

**语义色（对应首页提醒级别）：**

- 🔴 紧急 `#F53F3F`
- 🟡 警告 `#FF7D00`
- 🟢 正常 `#00B42A`
- 🔵 提示 `#1664FF`
- 🟠 待办 `#FF5722`

**商家等级标识：**

- 战略合作商家：金标 `#F7BA1E`
- 优选合作商家：银标 `#C9CDD4`
- 基础入驻商家：无标识

### 4.3 字体

- 字体族：`PingFang SC, HarmonyOS Sans, Helvetica Neue, Arial, sans-serif`
- PC 后台：`Microsoft YaHei, PingFang SC, sans-serif`
- 字号（rem，根字号 16px；小程序用 rpx）：

| 用途    | 大小         | 字重  | 行高  | 色值        |
| ----- | ---------- | --- | --- | --------- |
| 大标题   | 24px/36rpx | 600 | 1.4 | `#1D2129` |
| 页面标题  | 20px/32rpx | 600 | 1.4 | `#1D2129` |
| 卡片标题  | 16px/28rpx | 500 | 1.5 | `#1D2129` |
| 正文    | 14px/26rpx | 400 | 1.6 | `#4E5969` |
| 辅助说明  | 12px/22rpx | 400 | 1.5 | `#86909C` |
| 价格数字  | 20px/32rpx | 600 | 1.3 | `#F53F3F` |
| 里程/数据 | 28px/40rpx | 600 | 1.2 | `#1D2129` |

### 4.4 间距与圆角

- 间距梯度：`4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48` px（小程序 8/16/24/32/48rpx）。
- 页面左右安全边距：小程序 `16px`，PC 内容区最大宽 `1200px` 居中。
- 圆角：卡片 `12px`、按钮 `8px`、标签 `4px`、头像 `50%`、弹窗 `16px`。
- 阴影：`box-shadow: 0 2px 8px rgba(0,0,0,.06)`；悬浮 `0 4px 16px rgba(0,0,0,.10)`。

### 4.5 图标

- 图标库：【假设】使用 iconify 的 `mdi` 系列 + 自绘 SVG；小程序内置 SVG 转 base64。
- 尺寸：导航图标 24×24px；列表图标 20×20px；按钮内图标 16×16px。
- 描边 2px，圆角端点。颜色跟随文字色，不可彩色滥用。

### 4.6 组件样式规范

**按钮（Button）：**

- 主按钮：背景主色，白字，高度 44px，圆角 8px，字重 500。
- 次按钮：白底、主色描边 1px、主色字。
- 危险按钮：背景 `#F53F3F`，白字。
- 禁用：背景 `#C9CDD4`，白字，不可点击。
- 按钮文字统一 16px/500；多按钮并列时主按钮居右。

**表单（Form）：**

- 输入框：高度 44px、内边距 12px 16px、背景 `#F7F8FA`、圆角 8px、边框 1px `#E5E6EB`、聚焦主色边框。
- 标签在输入框上方，12px `#86909C`，与输入框间距 8px。
- 错误态：边框 `#F53F3F`，下方 12px 红字说明。
- 选择器/日期/地区：点击弹出底部抽屉或 PC 下拉。

**表格（PC 后台）：**

- 表头：背景 `#F7F8FA`、字 14px/500、底部分隔线 1px `#E5E6EB`。
- 单元格：字 14px、行高 44px、内边距 8px 12px。
- 斑马纹不开，hover 行背景 `#F2F3F5`。
- 分页：右对齐，每页 20 条。

**弹窗/抽屉（Modal/Drawer）：**

- 小程序用底部弹出层（圆角 16px 上、遮罩 50% 黑），PC 用居中弹窗（圆角 16px、阴影）。
- 标题 16px/500，内容 14px，操作区两按钮：取消（次）+ 确认（主）。

**导航（Navigation）：**

- 小程序顶部原生导航栏，背景白、标题居中、返回箭头。
- 底部 TabBar：高度 50px，图标 24px，文字 10px，未选中 `#86909C`、选中主色。
- PC 后台：左侧固定菜单 220px + 顶部面包屑 + 内容区。

**卡片（Card）：**

- 白底、圆角 12px、阴影 `0 2px 8px rgba(0,0,0,.06)`、内边距 16px。
- 卡片之间间距 12px。
- 卡片内标题与正文间距 8px。

**标签/徽标（Tag/Badge）：**

- 标签：圆角 4px、内边距 2px 8px、字 12px。
- 状态色：成功绿、警告橙、危险红、信息蓝。
- 红点：直径 8px 圆，右上角偏移 -2px。

**进度条：**

- 高度 6px、圆角 3px、底色 `#E5E6EB`、填充主色渐变 `#00B42A→#009A24`。
- 带动效：宽度变化 300ms ease。

**Toast/提示：**

- 小程序用原生 `wx.showToast`；PC 用右上角通知，3 秒自动消失。
- 成功绿勾、失败红叉、加载菊花。

**空状态/加载/错误（全局统一）：**

- 加载：骨架屏（卡片轮廓灰闪）+ 全屏 loading。
- 空状态：居中图标 64px + 14px 灰字说明 + 一个主按钮（如「去录入」）。
- 错误：图标 + 说明 + 「重新加载」按钮。
- 无权限：锁图标 + 「您没有访问权限」+ 返回首页。

### 4.7 动效

- 页面切换：右滑入 300ms ease；弹层上滑 250ms。
- 列表刷新：下拉刷新指示器 60px。
- 避免过度动画；禁止页面内元素自行浮动。

### 4.8 适配

- 小程序：iPhone 安全区底部留 `env(safe-area-inset-bottom)`；Android 状态栏兼容。
- PC：响应式断点 1200/992/768；<768 时表格横向滚动，菜单收起为抽屉。

---

## 5. 页面布局与交互说明

> 以下为各页面的最终版布局，以 ASCII 原型呈现，开发照此实现，不再讨论。

### 5.1 首页（驾驶舱）

```
┌──────────────────────────────────┐
│ 🚗 我的爱车 ▾         🔔3   ⚙️  │
├──────────────────────────────────┤
│ ┌──────────────────────────────┐ │
│ │ 京A·88888   32,500km        │ │
│ │ ← 左右滑动切换车辆 →         │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │         ╭───────╮            │ │
│ │        ╱  87分   ╲           │ │
│ │       │ 车况良好 │           │ │
│ │        ╲         ╱           │ │
│ │ 3项需要关注  [查看完整档案→] │ │
│ └──────────────────────────────┘ │
│ ⚠️ 需要你关注                    │
│ ┌──────────────────────────────┐ │
│ │🔴 故障码P0300 多缸失火       │ │
│ │  建议尽快检查 [AI诊断][找店]  │ │
│ ├──────────────────────────────┤ │
│ │🟡 保养到期 剩1200km          │ │
│ │  有1张50元保养券 [预约][报价] │ │
│ ├──────────────────────────────┤ │
│ │🟢 保险15天后到期 [比价续保]   │ │
│ ├──────────────────────────────┤ │
│ │🔵 年检30天后到期 [预约代办]   │ │
│ ├──────────────────────────────┤ │
│ │🟠 违章1条待处理 [处理引导]    │ │
│ └──────────────────────────────┘ │
│ 快捷服务                        │
│ [🔍AI诊断][🆘救援][🏪比价][📞理赔]│
│ 📋 核心指标（仅异常）            │
│ 🔧 刹车片   ██████░░░ 65%       │
│ 🛞 轮胎磨损 ███████░░ 78%       │
│ 🛢️ 机油寿命 ████████░ 82%       │
│ [查看全部指标 →]                │
│ 📋 最近记录          [全部 →]    │
│   2026.08 全合成小保养          │
│ [+拍照录入][+语音录入]           │
│ ┌──────────────────────────────┐ │
│ │🧼 1张洗车券7天后过期 3家可用  │ │
│ │[去使用→]                     │ │
│ ├──────────────────────────────┤ │
│ │👥 邀请好友，双方各得洗车券    │ │
│ │[立即邀请→]                   │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │🚗 同款车友经验               │ │
│ │ 12位迈腾车主遇过天窗漏水     │ │
│ │ [查看同款经验 →]             │ │
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│🏠首页│🔧服务│🤖AI│📋档案│👤我的│
└──────────────────────────────────┘
```

**交互要点：** 顶部可左右滑动切换车辆，切换后全页数据联动刷新；健康评分用环形图，点击进档案；提醒流按紧急度排序，每条带唯一动作按钮；券提醒条与邀请条有券才显示；同款车友卡片偶尔展示。

### 5.2 服务页（标准项目导向）

```
┌──────────────────────────────────┐
│ 🔧 服务                  🔍搜索  │
├──────────────────────────────────┤
│ ┌──────────────────────────────┐ │
│ │ 🤖 AI帮我找服务              │ │
│ │ [描述问题，AI推荐方案]       │ │
│ └──────────────────────────────┘ │
│ 按服务项目找                    │
│ 🔧 养车                         │
│  全合成小保养  ¥280–480         │
│  半合成小保养  ¥240–360         │
│  中保养        ¥560–850         │
│  大保养        ¥1100–2300       │
│  轮胎更换      ¥23–65/条        │
│  补胎          ¥28–85/个        │
│  四轮定位      ¥90–190          │
│ 🛢️ 修车/✨美容/📋服务/🛒用品   │
│  （折叠展开，结构同上）          │
├──────────────────────────────────┤
│🏠首页│🔧服务│🤖AI│📋档案│👤我的│
└──────────────────────────────────┘
```

**交互要点：** 大类折叠面板，默认展开「养车」；项目右侧显示参考价区间；点击项目进详情页。搜索框可搜项目名、症状（如「天窗漏水」跳 AI 管家）。

### 5.3 服务项目详情页

```
┌──────────────────────────────────┐
│ ← 🛢️ 全合成小保养         ⋯    │
├──────────────────────────────────┤
│ 参考价：¥280–480                │
│ 📋 服务内容                     │
│ 全合成机油4L + 机滤 + 工时      │
│ 机油品牌不低于平台标准          │
│ ┌──────────────────────────────┐ │
│ │🤖 AI推荐方案                 │ │
│ │ 根据车况：2020款迈腾32,500km │ │
│ │ ✅ 必做 全合成小保养 为何    │ │
│ │ 🔶 推荐 空调滤芯更换 为何    │ │
│ │ ⬜ 可选 火花塞更换 为何      │ │
│ │ [一键预约推荐方案]           │ │
│ └──────────────────────────────┘ │
│ 📍 附近商家                     │
│ 🏪 途虎养车工场店 1.2km ⭐4.8   │
│    报价 ¥320  [查看][预约]      │
│ 🏪 京东养车 2.5km ⭐4.7         │
│    报价 ¥299  [查看][预约]      │
│ 🏪 天猫养车 3.0km ⭐4.6         │
│    报价 ¥310  [查看][预约]      │
│ [按距离▼] [按评分▼]             │
│ 📊 平台AI评价总结               │
│ 基于1234条评价：92%满意         │
│ 平均施工45分钟；透明、快、无推销│
└──────────────────────────────────┘
```

**交互要点：** AI 方案每项可展开「为什么」；排序切换不刷新整页（局部排序）；点击商家进详情；底部常驻「一键预约推荐方案」按钮。

### 5.4 商家详情页

```
🏪 途虎养车工场店  1.2km
⭐4.8  平台认证  透明报价  🏅战略合作
🛢️ 全合成小保养  本店报价 ¥320
📋 服务内容（同项目标准）
🎫 可用券
  🧼 洗车券（平台发放）
  🛢️ 50元保养券（本店提供）
用户评价
  ⭐⭐⭐⭐⭐ 保养透明速度快
  ⭐⭐⭐⭐ 价格实惠
[预约]  [查看全部评价]
```

### 5.5 AI 管家页

```
🤖 AI管家
┌──────────────────────────────┐
│ 下午好，京A·88888的车主。     │
│ 您的车有3项需关注：           │
│ 🔴 故障码P0300 多缸失火      │
│ 🟡 保养剩1200km              │
│ 🟢 保险15天后到期             │
└──────────────────────────────┘
[发动机抖动][故障灯][保养][保险][年检][救援]
┌──────────────────────────────┐
│ 🎤 语音输入...  📷拍照 📎文件 │
└──────────────────────────────┘
用户：天窗漏水怎么办？
AI：2020款迈腾32,500km，常见原因：
 1. 密封条老化 60%  2. 排水管堵塞 30%
 3. 框架变形 10%
 修复方案：换密封条200–400 / 疏通50–100
 ┌────────────────────────────┐
 │📋 同款车友经验（12位遇过） │
 │ 8位换密封条约¥280          │
 │ [查看全部同款经验 →]       │
 └────────────────────────────┘
 📍 附近商家（报价 ¥280/¥260）
 [转人工在线确认]
 ℹ️ 基于车辆档案与专业知识库
 ℹ️ 邀请好友双方得洗车券，本月已邀3人
```

**交互要点：** 顶部 AI 主动建议；输入框上方固定快捷标签；对话流上屏后自动滚到底；「转人工」三态（在线可提交 / 离线可留言 / 未解锁提示邀请）；商家列表带报价。

### 5.6 档案页

```
📋 车辆健康档案
┌──────────────────────────────┐
│ 🚗 京A·88888 大众迈腾        │
│ 2020款 32,500km 健康87分🟢   │
└──────────────────────────────┘
📊 核心指标（全部6项+历史趋势）
 🛢️ 机油 ████████░ 82%
 🔧 刹车片 ██████░░░ 65%
 🛞 轮胎 ███████░░ 78%
 🔋 电池 █████████ 92%
 🧊 冷却液 ████████░ 85%
 ⚡ 电瓶 ████████░ 88%
 🪪 证件与合规：驾照/保险/年检/违章
 📋 维修履历 [全部 →]
   2026.08 全合成小保养
   2026.05 刹车片更换
   [同款车友怎么说 →]
 🛢️ 保养记录 🚨违章 🛡️保险 🔧改装 🔋电池
 📎 新增记录 [🎤][📷][✏️]
 [导出完整档案 →] [分享报告 →]
```

### 5.7 我的页

```
👤 车主昵称
 📱 138****8888
我的订单：🔄进行中(维修中·年审已约) ✅已完成
我的车辆：京A·88888 京B·66666 [+添加]
我的福利：洗车券×2 保养券¥50 加油券¥20
  👥 邀请好友 本月已邀3人 ████░░ 30% [邀请]
  📝 任务：签到+5 / 完善档案送洗车 / 首AI送检测
我的积分：1280分
  500→洗车券 800→补胎券 1500→50元券
  [积分明细→] [去赚积分→]
车友圈：🚗同款车友经验 [进入→]
专属优惠：⛽加油 🔌充电 🧼洗车 🛡️车险
二手车服务（入口）：💰估值 🚗卖车 📋过户
新车选购（入口）：🔍配置 📊对比 🛡️保险
设置：账号安全·通知·隐私·关于
```

### 5.8 商家端关键页：接车检查

```
📋 接车检查  订单号 #88201
拍摄车辆四周（按示意图顺序）
 [📷前] [📷后] [📷左] [📷右] [📷车顶]
  进度 2/5  未拍部位高亮提示
拍仪表盘 → 里程 32,500km
  ⚠️ 与档案上次里程30,000km比对正常
拍油表 → 油量 3/4
拍内饰（座椅/中控/方向盘）
标注已有损伤
  在照片上点选位置 → 选类型(划痕/凹陷/破损)
  ┌─────────┐
  │  ←示意→ │ ←标注1 划痕
  └─────────┘
  [+ 添加标注]
生成接车单 → 推送给车主确认
  [提交]（未拍完5张不允许提交）
```

**阻断规则：** 接车检查未完成且车主未确认 → 不开放「派工给技师」入口。

### 5.9 商家端：施工防护拍照

```
🔧 施工防护
请按标准完成防护后拍照
 □ 座椅套   □ 方向盘套
 □ 一次性脚垫 □ 翼子板布
 [📷 拍摄防护照片]（需同时拍到座椅套+方向盘套）
 预览图... [重新拍][确认上传]
  ⚠️ 未上传防护照片，技师无法开始报工
[提交，进入派工]
```

### 5.10 技师端：报工

```
🔧 工单 #88201 全合成小保养
车型：2020款迈腾  技师：张师傅
[接单]（商家已派工才可接）
施工过程
 [📷 拍施工照] [+添加照片] 已传3张
故障件（如有）
 [📷 拍故障件] 故障码：P0300
完工照
 [📷 拍完工照]（须含车牌/施工部位）
维修方案
 本次施工内容（textarea）
 故障原因分析
配件记录
 [+ 添加配件] 配件名/型号/品牌/数量
 已加：火花塞 NGK ×4
工时：已记录 约45分钟
[质检签字 →]（签名板，提交后工单完工）
```

### 5.11 运营后台（PC）

左侧菜单：`工作台 | 商家审核 | 商家管理 | 标准项目 | 车型库 | 考核管理 | 券池 | 内容审核 | 价格监控 | 社区 | 数据看板 | 系统设置`。

内容区：面包屑 + 页面标题 + 操作栏 + 筛选区（折叠）+ 数据表格 + 分页。弹窗用于新增/编辑；抽屉用于查看详情。

### 5.12 全状态组件清单（开发必须覆盖）

每个列表页/详情页均须实现：加载中（骨架屏）、空状态、错误状态、无权限状态、网络断开。每个表单均须实现：默认、聚焦、错误、禁用四种态。

---

## 6. 技术架构方案

### 6.1 技术选型

| 层次           | 选型                                              | 说明                   |
| ------------ | ----------------------------------------------- | -------------------- |
| 前端（车主/商家/技师） | uni-app 3（Vue3）                                 | 编译到微信小程序 + H5，一次开发多端 |
| UI 组件库       | uni-ui + 自定义组件                                  | 按第 4 章规范封装业务组件       |
| 前端（运营）       | Vue3 + Vite + Element Plus                      | PC 后台                |
| 后端           | Java 17 + Spring Boot 3.x + Spring Cloud（网关/鉴权） | 单体先行，模块化分包，预留微服务拆分   |
| ORM          | MyBatis-Plus                                    |                      |
| 数据库          | MySQL 8.0（主从）                                   | 主写从读                 |
| 缓存           | Redis 7                                         | 会话、热点列表、验证码、限流       |
| 对象存储         | 自建 MinIO（S3 兼容）                                 | 替代 OSS，零成本           |
| 消息队列         | RabbitMQ                                        | 异步：档案更新、社区卡片生成、通知    |
| 实时通信         | WebSocket（运营/商家 PC 与技师）                         | 小程序车主端用轮询 + 订阅消息兜底   |
| AI           | DeepSeek / 通义千问 API + LangChain4j + Milvus      | RAG 外挂知识库            |
| OCR          | PaddleOCR（自建服务）                                 | 行驶证、仪表盘里程            |
| 反向代理         | Nginx                                           | 静态资源、API 转发、HTTPS    |
| 容器           | Docker + Docker Compose                         | 一键编排                 |
| CI/CD        | Gitea + Drone（【假设】自托管）                          | 或 GitHub Actions     |

### 6.2 四端部署形态

- 车主端：微信小程序（主）+ H5（兜底）
- 商家端：微信小程序
- 技师端：微信小程序（扫码即用，无需安装）
- 运营后台：PC 网页

### 6.3 鉴权方案

- 车主端：微信 code 换 openid → 签发短期 accessToken（2h）+ 长期 refreshToken（30天）。
- 商家端：账号密码 + 短信验证码 → JWT；后台二次校验商家状态。
- 技师端：微信授权登录 + 首次绑定商家工号（商家后台生成工号码）。
- 运营后台：账号密码 + 二次校验 + 角色权限。
- 所有 token 放 `Authorization: Bearer <token>`；网关统一鉴权、限流、日志。

### 6.4 安全方案

- 全站 HTTPS；HSTS；CSP 头。
- 密码 bcrypt 加密；敏感配置走环境变量，不入库不提交 Git。
- SQL 注入：一律参数化（MyBatis-Plus 防注入 + 手写 SQL 用 `#{}`）。
- XSS：输出编码；富文本（社区内容）用白名单过滤。
- 越权：服务端**强制校验资源归属**（`merchant_id`/`user_id`），禁止信任前端 ID。
- 接口限流：用户级 60 次/分钟，IP 级 300 次/分钟；短信 1 次/60 秒，日上限 5 次。
- 上传：白名单后缀（jpg/png/jpeg/webp，【假设】单文件 ≤10MB）、病毒扫描（`clamav`）、存储桶私有 + 签名 URL。
- 审计日志：关键操作（审核、退款、上下线、价格修改）留痕。
- 数据脱敏：手机号、车牌、VIN 展示脱敏。

### 6.5 目录结构（后端）

```
com.autocare.platform
 ├── gateway          // 网关、鉴权、限流
 ├── common           // 响应封装、异常、工具、常量
 ├── user             // 用户、登录、邀请
 ├── vehicle          // 车辆、车型库、档案
 ├── merchant         // 商家、考核
 ├── order            // 订单、预约、核销
 ├── coupon           // 券池、发放、核销
 ├── point            // 积分、兑换
 ├── service          // 标准项目、报价、选品
 ├── ai               // AI 诊断、RAG、推荐方案
 ├── check            // 接车检查、防护、报工
 ├── community        // 社区内容、问答
 ├── admin            // 运营后台
 └── job              // 定时任务（券过期、月重置、流量平衡）
```

### 6.6 部署拓扑

```
用户 → CDN/微信 → Nginx → Spring Boot(多实例, JVM) → MySQL主
                                       ↓           ↘ MySQL从
                                   Redis      RabbitMQ
                                       ↓
                                   MinIO / Milvus / AI API
```

---

## 7. 数据库设计（含建表 SQL）

### 7.1 设计规范

- 引擎 InnoDB，字符集 `utf8mb4`，排序 `utf8mb4_general_ci`。
- 主键 `BIGINT UNSIGNED AUTO_INCREMENT`；所有表含 `created_at`、`updated_at`（自动维护）、`is_deleted TINYINT DEFAULT 0`（逻辑删除）。
- 时间用 `DATETIME`；金额用 `DECIMAL(10,2)`；经纬度 `DECIMAL(10,7)`；JSON 用 `JSON` 类型。
- 命名：小写蛇形；表名不加前缀；索引 `idx_字段`、`uk_字段`。
- 状态字段用 `TINYINT` 枚举，附 Java 枚举类。

### 7.2 建表 SQL

```sql
-- 用户表
CREATE TABLE `user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `phone` VARCHAR(20) DEFAULT NULL,
  `nickname` VARCHAR(64) DEFAULT NULL,
  `avatar_url` VARCHAR(512) DEFAULT NULL,
  `openid` VARCHAR(128) DEFAULT NULL,
  `inviter_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '邀请人ID',
  `point_balance` INT NOT NULL DEFAULT 0 COMMENT '积分余额',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1正常2禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_inviter` (`inviter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 品牌表
CREATE TABLE `brand` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `logo_url` VARCHAR(512) DEFAULT NULL,
  `country` VARCHAR(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='品牌表';

-- 车系表
CREATE TABLE `series` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `brand_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `level` VARCHAR(32) DEFAULT NULL COMMENT '级别',
  PRIMARY KEY (`id`),
  KEY `idx_brand` (`brand_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车系表';

-- 车型表
CREATE TABLE `model` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `series_id` BIGINT UNSIGNED NOT NULL,
  `year` VARCHAR(16) NOT NULL COMMENT '年款',
  `config_name` VARCHAR(128) DEFAULT NULL,
  `power_type` VARCHAR(16) DEFAULT NULL,
  `engine_model` VARCHAR(128) DEFAULT NULL,
  `displacement` VARCHAR(32) DEFAULT NULL,
  `gearbox_type` VARCHAR(32) DEFAULT NULL,
  `drive_type` VARCHAR(16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_series` (`series_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车型表';

-- 保养规则表
CREATE TABLE `maintenance_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `model_id` BIGINT UNSIGNED NOT NULL,
  `item_name` VARCHAR(128) NOT NULL,
  `cycle_km` INT DEFAULT NULL,
  `cycle_month` INT DEFAULT NULL,
  `oil_spec` VARCHAR(128) DEFAULT NULL,
  `part_model` VARCHAR(128) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='保养规则表';

-- 车辆表
CREATE TABLE `vehicle` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `model_id` BIGINT UNSIGNED DEFAULT NULL,
  `vin` VARCHAR(32) DEFAULT NULL,
  `plate_no` VARCHAR(16) DEFAULT NULL,
  `current_mileage` INT DEFAULT 0,
  `buy_date` DATE DEFAULT NULL,
  `power_type` VARCHAR(16) DEFAULT NULL,
  `sort` TINYINT DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆表';

-- 车辆档案表
CREATE TABLE `vehicle_archive` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `archive_type` TINYINT NOT NULL COMMENT '1保养2维修3保险4事故5改装6违章7年检',
  `content` JSON DEFAULT NULL,
  `input_type` TINYINT NOT NULL COMMENT '1拍照2语音3手动',
  `attach_urls` JSON DEFAULT NULL,
  `recorded_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_vehicle_type` (`vehicle_id`,`archive_type`),
  KEY `idx_recorded` (`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车辆档案表';

-- 商家表
CREATE TABLE `merchant` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_type` TINYINT NOT NULL COMMENT '洗车美容/维修保养/轮胎/加油站/保险...',
  `name` VARCHAR(128) NOT NULL,
  `address` VARCHAR(256) NOT NULL,
  `lng` DECIMAL(10,7) DEFAULT NULL,
  `lat` DECIMAL(10,7) DEFAULT NULL,
  `contact_phone` VARCHAR(20) DEFAULT NULL,
  `qualification` JSON DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待审1通过2拒绝',
  `grade` TINYINT DEFAULT 3 COMMENT '1战略2优选3基础',
  `region_protected` TINYINT DEFAULT 0,
  `commission_rate` DECIMAL(5,2) DEFAULT 3.00,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_status` (`merchant_type`,`status`),
  KEY `idx_lng_lat` (`lng`,`lat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家表';

-- 标准项目表
CREATE TABLE `standard_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `project_name` VARCHAR(128) NOT NULL,
  `category` TINYINT NOT NULL COMMENT '1保养2轮胎3维修4美容5服务6用品',
  `service_content` TEXT NOT NULL,
  `quality_standard` TEXT DEFAULT NULL,
  `base_price_low` DECIMAL(10,2) NOT NULL,
  `base_price_high` DECIMAL(10,2) NOT NULL,
  `float_ratio` DECIMAL(5,2) DEFAULT 30.00 COMMENT '浮动区间%',
  `status` TINYINT DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`project_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='标准项目表';

-- 商家项目表
CREATE TABLE `merchant_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `project_id` BIGINT UNSIGNED NOT NULL,
  `price` DECIMAL(10,2) NOT NULL,
  `on_shelf` TINYINT DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merchant_project` (`merchant_id`,`project_id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家项目表';

-- 组合套餐表
CREATE TABLE `package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `package_name` VARCHAR(128) NOT NULL,
  `item_project_ids` JSON NOT NULL,
  `package_price` DECIMAL(10,2) NOT NULL,
  `audit_status` TINYINT DEFAULT 0 COMMENT '0待审1通过2拒绝',
  PRIMARY KEY (`id`),
  KEY `idx_merchant` (`merchant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组合套餐表';

-- 订单表
CREATE TABLE `order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_no` VARCHAR(32) NOT NULL,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NOT NULL,
  `merchant_id` BIGINT UNSIGNED NOT NULL,
  `project_id` BIGINT UNSIGNED DEFAULT NULL,
  `package_id` BIGINT UNSIGNED DEFAULT NULL,
  `amount` DECIMAL(10,2) NOT NULL,
  `pay_amount` DECIMAL(10,2) NOT NULL,
  `pay_type` TINYINT DEFAULT NULL,
  `coupon_id` BIGINT UNSIGNED DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待付1已付待接车2已接车3施工中4待核销5已完成6已取消7争议',
  `verify_code` VARCHAR(16) DEFAULT NULL,
  `appointment_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_status` (`user_id`,`status`),
  KEY `idx_merchant_status` (`merchant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单表';

-- 接车检查表
CREATE TABLE `pickup_check` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `around_photos` JSON DEFAULT NULL,
  `mileage` INT DEFAULT NULL,
  `fuel_level` VARCHAR(16) DEFAULT NULL,
  `interior_photos` JSON DEFAULT NULL,
  `damage_marks` JSON DEFAULT NULL COMMENT '[{x,y,type,note}]',
  `owner_confirm` TINYINT DEFAULT 0 COMMENT '0待1确认2异议',
  `confirm_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接车检查表';

-- 施工防护表
CREATE TABLE `repair_protection` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `items` JSON DEFAULT NULL COMMENT '已勾选防护项',
  `photo_url` VARCHAR(512) DEFAULT NULL,
  `uploaded_at` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='施工防护表';

-- 技师报工表
CREATE TABLE `technician_report` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `technician_id` BIGINT UNSIGNED NOT NULL,
  `process_photos` JSON DEFAULT NULL,
  `fault_part_photos` JSON DEFAULT NULL,
  `finish_photos` JSON DEFAULT NULL,
  `repair_plan` TEXT DEFAULT NULL,
  `fault_analysis` TEXT DEFAULT NULL,
  `parts_used` JSON DEFAULT NULL,
  `work_hours` INT DEFAULT NULL COMMENT '分钟',
  `status` TINYINT DEFAULT 0 COMMENT '0施工中1已质检',
  `signed_at` DATETIME DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_technician` (`technician_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师报工表';

-- 取车对比表
CREATE TABLE `delivery_compare` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `photo_url` VARCHAR(512) DEFAULT NULL,
  `compare_result` TEXT DEFAULT NULL,
  `owner_confirm` TINYINT DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='取车对比表';

-- 券表/用户券表/考核表/邀请记录表/社区内容表/积分流水表/兑换表 结构见第 5 章对应字段，此处省略重复定义
```

> 【说明】完整 SQL 含全部 25 张表（含 coupon、user_coupon、assessment、invite_record、community_content、community_interaction、point_flow、point_exchange、circle_follow、ai_plan_rule 等），提交仓库时以 `docs/sql/init.sql` 为准，本表仅列出核心结构与示例写法，其余表按相同规范（主键 BIGINT、created_at/updated_at/is_deleted、索引命名统一）补齐，字段参照第 5 章数据库设计小节。

### 7.3 索引与关系

- 所有「归属」字段建索引（`user_id`/`merchant_id`/`vehicle_id`/`order_id`）。
- 订单复合索引 `(user_id,status)`、`(merchant_id,status)`；商家复合 `(merchant_type,status)`。
- 外键【默认】不建物理外键，用逻辑约束 + 应用层校验，便于分库与历史归档。

---

## 8. API 接口文档

### 8.1 统一响应

```json
{ "code": 0, "message": "success", "data": {}, "request_id": "..." }
```

成功 `code=0`；失败 `code≠0`，`message` 直接展示给用户。分页：

```json
{ "code":0, "data":{ "list":[], "page":1, "page_size":20, "total":132 } }
```

### 8.2 错误码

| code  | message     | 处理     |
| ----- | ----------- | ------ |
| 0     | success     | —      |
| 40100 | 未登录或token失效 | 跳登录    |
| 40300 | 无权限访问该资源    | 无权限页   |
| 40400 | 资源不存在       | 提示返回   |
| 42900 | 请求过于频繁      | 提示稍后重试 |
| 50000 | 服务器内部错误     | 重试/上报  |
| 40001 | 参数校验失败      | 表单内联提示 |
| 41001 | 商家区域名额已满    | 提示更换品类 |
| 42001 | 券已领完/已过期    | 按钮置灰   |
| 42002 | 邀请人已达上限     | 轻提示    |
| 43001 | 接车检查未完成     | 阻断派工   |
| 43002 | 防护照片未上传     | 阻断报工   |

### 8.3 接口列表（核心）

| 方法   | 路径                                 | 说明         | 角色    |
| ---- | ---------------------------------- | ---------- | ----- |
| POST | /api/auth/wx-login                 | 微信登录       | 车主/技师 |
| POST | /api/auth/refresh                  | 刷新token    | 全部    |
| GET  | /api/vehicle/list                  | 我的车辆       | 车主    |
| POST | /api/vehicle/add                   | 录入车辆       | 车主    |
| GET  | /api/vehicle/vin-decode            | VIN解码      | 车主    |
| GET  | /api/vehicle/ocr-license           | 行驶证OCR     | 车主    |
| GET  | /api/brand/list                    | 品牌列表       | 车主    |
| GET  | /api/series/list                   | 车系列表       | 车主    |
| GET  | /api/model/list                    | 车型列表       | 车主    |
| POST | /api/archive/add                   | 录入档案       | 车主    |
| GET  | /api/archive/list                  | 档案列表       | 车主    |
| GET  | /api/home/dashboard                | 首页驾驶舱      | 车主    |
| GET  | /api/home/reminders                | 强提醒流       | 车主    |
| POST | /api/ai/chat                       | AI对话       | 车主    |
| POST | /api/ai/plan                       | AI推荐方案     | 车主    |
| GET  | /api/service/projects              | 标准项目分类     | 车主    |
| GET  | /api/service/project/:id/merchants | 项目商家列表     | 车主    |
| GET  | /api/merchant/:id                  | 商家详情       | 车主    |
| GET  | /api/merchant/:id/reviews/summary  | AI评价总结     | 车主    |
| POST | /api/order/create                  | 创建预约       | 车主    |
| GET  | /api/order/list                    | 我的订单       | 车主    |
| GET  | /api/order/:id                     | 订单详情(含核销码) | 车主    |
| POST | /api/order/cancel                  | 取消订单       | 车主    |
| POST | /api/order/review                  | 评价         | 车主    |
| POST | /api/coupon/receive                | 领券         | 车主    |
| GET  | /api/coupon/mine                   | 我的券        | 车主    |
| GET  | /api/invite/qrcode                 | 邀请码        | 车主    |
| GET  | /api/invite/records                | 邀请记录       | 车主    |
| GET  | /api/invite/progress               | 本月进度       | 车主    |
| POST | /api/point/exchange                | 积分兑换       | 车主    |
| GET  | /api/point/flow                    | 积分明细       | 车主    |
| GET  | /api/community/hot                 | 车友圈热门      | 车主    |
| POST | /api/check/pickup/submit           | 提交接车检查     | 商家    |
| POST | /api/check/pickup/confirm          | 车主确认接车单    | 车主    |
| POST | /api/check/protection/upload       | 上传防护照      | 商家    |
| POST | /api/check/delivery/compare        | 取车对比       | 商家    |
| GET  | /api/tech/orders                   | 技师待办       | 技师    |
| POST | /api/tech/report/submit            | 技师报工       | 技师    |
| POST | /api/tech/sign                     | 质检签字       | 技师    |
| POST | /api/admin/merchant/audit          | 商家审核       | 运营    |
| GET  | /api/admin/standard-project        | 标准项目CRUD   | 运营    |
| GET  | /api/admin/assessment              | 考核列表       | 运营    |
| GET  | /api/admin/dashboard               | 运营数据看板     | 运营    |

### 8.4 请求参数与返回示例

**POST /api/vehicle/add**

```json
// 请求
{ "add_type": 2, "vin": "LSVNV2180H2xxxxxx", "model_id": 1024 }
// 返回
{ "code":0, "data":{ "vehicle_id":88, "model_name":"2020款 大众迈腾 330TSI", "need_archive":true } }
```

**POST /api/ai/chat**

```json
// 请求
{ "vehicle_id":88, "session_id":"s_xxx", "message":"天窗漏水怎么办" }
// 返回
{ "code":0, "data":{
    "reply":"常见原因：1.密封条老化60%...",
    "followup_actions":["预约维修","查看同款经验"],
    "community":{ "count":12, "summary":"8位换密封条约¥280" },
    "merchants":[{ "id":12,"name":"途虎工场店","distance_km":1.2,"price":280 }]
} }
```

**GET /api/service/project/5/merchants?lng=116.3&lat=39.9&sort=distance**

```json
{ "code":0, "data":{ "list":[
  {"merchant_id":12,"name":"途虎养车工场店","distance_km":1.2,"rating":4.8,"grade":1,"price":320,"coupons_available":2}
] } }
```

**POST /api/order/create**

```json
// 请求
{ "vehicle_id":88,"project_id":5,"merchant_id":12,"appointment_at":"2026-09-26 15:00","coupon_id":901 }
// 返回
{ "code":0, "data":{ "order_no":"AC20260925...", "pay_amount":270, "verify_code":"88201", "need_pay":true } }
```

**POST /api/check/pickup/submit**

```json
// 请求
{ "order_id":1201, "around_photos":["url1"], "mileage":32500, "fuel_level":"3/4", "interior_photos":["url2"], "damage_marks":[{"x":120,"y":88,"type":"划痕","note":"左前门"}] }
// 返回
{ "code":0, "data":{ "check_id":55, "owner_notified":true } }
```

### 8.5 分页、排序、过滤约定

- 分页参数 `page`（默认1）、`page_size`（默认20，最大100）。
- 排序 `sort=distance|rating|price`，`order=asc|desc`。
- 时间范围 `start_at`/`end_at`（ISO8601）。
- 所有写接口须带幂等键 `idempotency_key`（前端生成 UUID），服务端 24h 内去重。

---

## 9. 测试方案

### 9.1 功能测试

- 单元：核心业务方法（券发放、邀请计数、考核分计算、里程比对）覆盖率 ≥ 70%。
- 接口：用 Postman/Newman 覆盖全部接口，含正常、边界、异常、越权四类用例。
- E2E：用 uni-app 自带测试 + 小程序开发者工具自动化，跑通第 2.3 节三条主流程。
- 权限矩阵（2.2）逐格验证越权拦截。

### 9.2 兼容性测试

- 小程序：微信开发者工具 + 真机（iOS 最新/上一版、Android 主流品牌各一款）。
- 分辨率：小程序 375/390/414 宽度；H5 320–1920 连续。
- PC 后台：Chrome/Edge/Safari 最新版，1200/992/768 断点。

### 9.3 性能测试

- 接口：核心读接口 P95 < 300ms、P99 < 800ms；写接口 P95 < 500ms。
- 压测：用 wrk 或 JMeter 对首页、商家列表、订单创建各打 500 并发，错误率 < 0.1%。
- 数据库：慢查询日志 > 200ms 全量优化，大表分页改游标。

### 9.4 安全测试

- 越权：遍历他人 order_id/vehicle_id 验证 403。
- 注入：参数化验证 + sqlmap 抽样。
- 上传：非法后缀、超大文件、webshell 上传拦截。
- 限流：超额请求验证 429。
- 敏感信息：响应中手机号/车牌是否脱敏。

### 9.5 Bug 等级与通过标准

| 等级    | 定义             | 示例            |
| ----- | -------------- | ------------- |
| P0 致命 | 崩溃/数据错乱/无法核心流程 | 支付成功未生单、券可无限领 |
| P1 严重 | 主功能不可用但可绕行     | 无法提交接车单       |
| P2 一般 | 功能可用但体验明显异常    | 排序失效、图片不显示    |
| P3 轻微 | 文案、样式细节        | 间距偏差、错别字      |

**上线通过标准：**

1. P0 = 0；P1 = 0。
2. P2 ≤ 5 且有修复计划；P3 不影响主流程。
3. 核心接口自动化用例 100% 通过。
4. 安全扫描无高危项。
5. 性能达标（9.3）。

---

## 10. 部署与运维

### 10.1 环境要求

- 服务器：2 台（应用 4C8G、数据库 4C16G）起步；【假设】Ubuntu 22.04 LTS。
- Docker ≥ 24，Docker Compose ≥ 2.20。
- 域名 + HTTPS 证书（Let's Encrypt 免费）。

### 10.2 部署步骤

1. 服务器初始化：安装 Docker、配置 UFW（开放 80/443/22）、SSH 密钥登录禁用密码。
2. 拉取代码：`git clone` + `git checkout <tag>`（禁止直接部署 master 未打 tag）。
3. 配置环境变量：`.env`（DB 密码、JWT 密钥、AI API Key、微信 AppID/Secret、MinIO 密钥）。
4. `docker compose up -d mysql redis rabbitmq minio milvus nginx`。
5. 初始化数据库：`mysql < docs/sql/init.sql`。
6. 构建后端：`mvn package -DskipTests` → 镜像；构建前端：`pnpm build:mp-weixin` / `build:h5` / `build:admin`。
7. 上传小程序代码至微信开发者工具 → 上传 → 提交审核。
8. 运营后台 Nginx 静态托管 + 反代后端。
9. 健康检查：`/actuator/health` 返回 UP；人工走一遍主流程。

### 10.3 Docker Compose 服务

`mysql`、`redis`、`rabbitmq`、`minio`、`milvus`、`backend`（Spring Boot）、`nginx`。前端静态资源挂载进 nginx 容器。

### 10.4 Nginx 配置要点

- 前端 H5/管理端静态目录，缓存策略：`html` 不缓存，带 hash 资源缓存 1 年。
- API 反代到后端，超时 30s，开启 gzip。
- HTTPS 强制跳转，TLS 1.2+，证书自动续期。
- 上传限流：客户端最大 12MB。

### 10.5 回滚方案

- 镜像/小程序均按版本号发布；发现问题：
  - 后端：`docker compose up -d backend` 切回上一镜像。
  - 小程序：微信后台「回退至上一个线上版本」（即时生效）。
  - 数据库迁移须可逆向，大变更灰度执行。

### 10.6 监控与日志

- 日志：统一 JSON 格式，输出到文件 + `stdout` 由 Docker 收集；按天切割。
- 监控：Spring Boot Actuator + Prometheus + Grafana（【假设】自托管）；告警项：CPU>80%、内存>85%、接口错误率>1%、磁盘>80%。
- 业务监控：每日新增注册、订单量、券核销率、AI 调用失败率。
- 链路追踪：Micrometer + Zipkin（【假设】）。

---

## 11. 项目排期与里程碑

| 阶段        | 任务                             | 交付物                 | 里程碑      |
| --------- | ------------------------------ | ------------------- | -------- |
| S0 准备     | 环境、仓库、CI、分支规范、数据库建表、接口定义       | 可运行的空工程、SQL、Swagger | M0 骨架就绪  |
| S1 车主端基础  | 登录、车辆录入、档案录入、首页、档案页            | 车主端小程序(可录入可看)       | M1 车主能管车 |
| S2 服务与订单  | 标准项目、商家列表报价、预约下单、核销码、订单        | 可下单流程               | M2 交易闭环  |
| S3 AI与券邀请 | AI对话、RAG知识库、AI推荐方案、券体系、邀请裂变、积分 | AI可用、裂变可用           | M3 智能与增长 |
| S4 商家与技师  | 商家入驻、选品、接车检查、防护、派工、技师报工        | 商家/技师小程序            | M4 交付闭环  |
| S5 运营后台   | 审核、标准项目、车型库、考核、券池、价格监控、看板      | 运营后台                | M5 可运营   |
| S6 测试上线   | 功能/兼容/性能/安全测试、部署、监控、压测调优       | 测试报告、上线版本           | M6 正式上线  |

每阶段末必须演示可运行产品，未完成 P0/P1 不进下一阶段。

---

## 12. 验收与交付清单

### 12.1 验收项与标准

| 类别  | 验收项      | 标准                            |
| --- | -------- | ----------------------------- |
| 功能  | 车辆录入4种方式 | 每种均可成功录入并匹配车型                 |
| 功能  | 首页驾驶舱    | 5类提醒齐全、评分与指标正确                |
| 功能  | AI诊断     | 能结合档案回答、推荐商家、附带同款经验           |
| 功能  | 服务聚合     | 标准项目报价、排序、评价总结正常              |
| 功能  | 预约下单     | 选商家/时间/用券、核销码生成、状态流转正确        |
| 功能  | 接车检查     | 5向拍照、里程比对、损伤标注、车主确认阻断生效       |
| 功能  | 施工防护     | 未上传阻断报工                       |
| 功能  | 技师报工     | 三类照片、方案、配件、工时、质检签字齐全          |
| 功能  | 券/邀请/积分  | 双向奖励、月度循环、兑换兑换正确到账            |
| 功能  | 商家考核     | 拉新40/券40/过程20 计算与等级正确         |
| 权限  | 越权       | 跨用户/跨商家访问全部 403               |
| 数据  | 档案自动更新   | 订单完成自动写档案并生成社区卡片              |
| 性能  | 核心接口     | P95<300ms，压测 500 并发错误率<0.1%   |
| 兼容  | 多端       | 小程序 iOS/Android 真机 + PC 三断点正常 |
| 安全  | 扫描       | 无高危，上传/限流/脱敏生效                |

### 12.2 交付物

- 源代码（车主端、商家端、技师端、运营后台、后端）+ README + 启动脚本
- 数据库初始化 SQL（`docs/sql/init.sql`）
- API 文档（Swagger + 第 8 章 Markdown 版）
- UI 设计规范与全部页面原型稿
- 部署文档、环境变量清单、回滚预案
- 测试报告、压测报告、安全扫描结果
- 用户手册（车主端操作 + 商家端操作 + 技师端操作）
- 线上地址、监控看板地址、告警联系人
- 项目总结与遗留事项清单

---

## 13. 零提问默认决策规则

开发过程中遇到任何未在本文档明确的问题，**不得停工、不得追问**，按下述规则自行决定，并在代码注释或 README 的「决策记录」章节标注【假设-日期-简述】。

1. **先查本文档**：PRD/UI/API/SQL 四处，以最新章节为准（同主题以靠后章节为准）。
2. **技术未指定**：按第 6 章选型表；表中未列的用同层主流成熟方案（如缓存用 Redis 而非 Memcached）。
3. **字段未定义**：参考同类表命名（蛇形、BIGINT 主键、时间字段），非空字段给合理默认值，金额 DECIMAL(10,2)。
4. **交互未定义**：默认与全站统一（第 4 章组件态 + 第 5 章页面规则）；列表默认支持加载中/空/错误三态。
5. **文案缺失**：用简洁直接的中文，动作明确（如「去录入」「重新加载」），不用「点击这里」。
6. **权限不清**：默认最小权限原则，无明确授权即拒绝（403）。
7. **错误兜底**：接口失败默认提示「网络开小差了，稍后再试」+ 重试；写操作幂等（带 idempotency_key）。
8. **样式偏差**：以第 4 章固定色值/字号/间距为准，禁止自创数值；设计稿与文档冲突时以文档为准并标注【假设】。
9. **优先级冲突**：P0 > P1 > P2；P0 必须交付，P1 尽量交付，P3 可延后。
10. **时间紧迫**：先做主流程（录入→问诊→下单→接车→施工→核销→评价→归档），异常分支与边界后补，但必须留接口与 TODO。
11. **第三方不可用**：AI/OCR 等外部服务失败时用降级方案（通用知识回答、手动输入），不阻断主流程。
12. **数据无来源**：用符合常识的默认值并标注【假设】，如价格区间、工时、评分。
13. **一律不出现「待定」「再讨论」字样**——必须给默认值并标注假设。
14. **决策记录**：在 `docs/DECISIONS.md` 持续追加，格式：`[日期] 【假设】问题 → 决策 → 理由`，便于后续评审回看。

---

> 本交付文档与《汽车健康管家平台最终完整方案》配套使用。开发者从 S0 开始，按第 11 章里程碑推进，第 13 章规则兜底，全程无需再提问。
