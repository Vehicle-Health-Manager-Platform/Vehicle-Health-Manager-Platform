# R9 A4d-2h5 运营审核台设计

2026-10-20。前置 A4d-2h4（评价与公开展示面）。范围：`pages/operator/review.vue`（运营工作台入口与待审经验摘要）与 `pages/operator/review-texts.vue`（公开评价文字审核）两个页面，**后端、接口、迁移零改动**。

## 为什么先做这两个页面

评价域的六个车主侧页面在 A4d-2h4 已经统一，但审核这一侧仍是旧写法：

1. **入口墙**：`operator/review.vue` 把 21 个入口平铺成 21 个 `<button>`，权限判断散在模板里（`v-if="operatorSession.canXxx"`），组与组之间没有层次。"商家入驻审核"甚至没有权限判断，任何运营都能看到。
2. **状态读不出来**：待审文字卡片只有一行 `来源资格已失效，当前不能批准；可驳回`，运营要先点一次批准才知道不能批；驳回理由是裸 code 拼出来的文案。
3. **视觉断裂**：两个页面都没有设计系统原语，与车主侧同一批数据看起来像两个产品。

这三条都属于"展示层"，不需要动接口就能修，且都能被纯函数单测覆盖。

## 做法：把判断搬进纯函数

新增到 `apps/miniapp/src/services/public-review-display.js`（与车主侧映射同一文件，因为读的是同一批授权数据）：

| 函数 | 输入 | 输出 | 解决什么 |
| --- | --- | --- | --- |
| `operatorEntryGroups(session)` | 运营会话 | `[{key,title,note,entries:[{key,label,note,url}]}]` | 入口按权限过滤、按固定分组下发；空组不下发 |
| `operatorCapabilitySummary(session)` | 运营会话 | `{granted:[标签],tone,copy}` | 说清"为什么只看到这些入口" |
| `moderationCardView(item)` | 待审文字行 | `{approve,label,tone,versionCopy,copy}` | 行内就能看出能否批准、为什么不能 |
| `moderationConsoleCopy(list)` | 待审列表响应 | `{empty,pending,loaded,blocked,emptyTitle,emptyCopy,footer}` | 区分"真的没有"与"还没加载"，并统计来源失效条数 |
| `reasonPicker(reasons)` / `reasonLabel(code,reasons)` | 理由常量 | `{codes,labels,placeholder}` / 文案 | code 只用于请求，页面只渲染文案 |
| `operatorCardFacts(card)` | 待审经验摘要 | `{title,revisionCopy,month,work,parts,model,canApprove,tone}` | 把"能不能批准"（车型）与摘要事实分开说 |

## 四个分组与权限口径

| 分组 | 说明 | 入口（权限键） |
| --- | --- | --- |
| 内容与授权审核 | 每条都要人工检查隐私 | 许可资料(`canKnowledge`)、脱敏图片(`canReview`)、问答摘要(`canReview`)、评价文字(`canReview`)、经验精选(`canCommunityCurate`) |
| 我的处理工作区 | 只含分配给我的任务 | 诊断处理(`canDiagnose` 或 `canDiagnoseManage`)、私有问答(`canCommunityAnswer`) |
| 经营配置 | 会立即影响车主端可见内容 | 目录(`canCatalog`)、诊断配置(`canDiagnoseManage`)、问答受理(`canCommunityManage`)、券池(`canCoupon`)、积分规则/兑换(`canPoints`)、邀请奖励/诊断邀请(`canInvite`)、商家入驻(`canOnboard`) |
| 只读与结算 | 只读，不改状态 | 价格比较/价格来源(`canPriceMonitor`)、经营看板(`canAnalytics`)、月度结算/考核(`canAssessment`) |

`can` 支持数组表示"任一满足"（`diagnosis-cases` 用 `['canDiagnose','canDiagnoseManage']`）。清单是唯一来源：页面不写 URL、不写 `v-if`；权限摘要的标签也由同一批 `can_*` 推导，单测断言"摘要里出现的每项能力至少有一个入口"，防止两边漂移。

## 修掉的具体缺陷

| 缺陷 | 表现 | 处理 |
| --- | --- | --- |
| 入驻审核未收口 | 任何运营都能看到"商家入驻审核"按钮 | 归到经营配置组，由 `can_onboard` 过滤 |
| 不可批准要试才知道 | 待审文字行只有一行说明，批准按钮 `disabled` 但没说原因 | `moderationCardView` 行内徽标"来源已失效" + 说明只能驳回 |
| 队列数字缺失 | 页面只有"当前没有待审文字" | `moderationConsoleCopy` 给 `待审 N 条 · 本页 M 条 · 其中 K 条来源已失效只能驳回` |
| 驳回理由两套写法 | 文字页 `TEXT_REASONS[...] \|\| '请选择驳回理由'`，摘要页另一套 | 统一 `reasonPicker`/`reasonLabel` |
| 车型缺失靠人眼看 | `{{ card.model_id ? ... : '缺少有效车型，不能批准' }}` | `operatorCardFacts().canApprove` 同时驱动徽标、文案与按钮禁用 |

## 验收口径

1. `node --test test/*.test.js`：935 项全通过（新增 8 项）。
2. `npm run build:mp-weixin`：exit 0，两项原生门禁 PASS。
3. `npm run build:h5`：exit 0。
4. 编译产物：`operator/review.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/`operator-entry`，`operator/review-texts.wxml` 含 `ux-hero`/`ux-badge`/`ux-empty`/`moderation-row`。
5. 页面契约：两页都必须引 `public-review-display.js`；`review.vue` 不得再出现 `/pages/operator/knowledge` 或 `/pages/community/public-images` 这类散落路径。

## 不覆盖

真机与模拟器渲染、视觉走查与截图对比、暗色模式与无障碍朗读、运营端其余 23 个页面（仍是旧视觉）、审核并发（已由 service 层 4 条竞态测试覆盖，见执行记录）。
