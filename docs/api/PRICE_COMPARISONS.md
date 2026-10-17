# R8b3c2 当前报价的只读价格比较

2026-10-16。沿已确认的具体门店报价版本、来源、服务内容/单位/车型适用说明和含边界区间；独立 `can_price_monitor`，全部 `no-store`。配置协议见[价格依据](PRICE_MONITOR_SOURCES.md)。无新增迁移，仍 V056/116 表。

## 两个读取接口

- GET `/api/admin/price-monitor/comparisons?page=1&page_size=20`：仅单值 page/page_size，固定 20，拒绝门店、用户、报价等额外选择器。
- GET `/api/admin/price-monitor/comparisons/{id}`：id 是报价编号，正安全整数；拒绝全部 query。

每次在一个只读 REPEATABLE_READ 事务核对当前运营会话/独立权限，捕获一个 UTC 整秒 `observed_at`。返回 `ApiResponse.data`：列表为 `{observed_at,comparisons:{items,total,page,page_size}}`，详情为 `{observed_at,comparison}`。401 会话失效、403 缺独立权限、400 非法选择器、404 不属于当前候选、503 无库或来源暂不可读；错误同样不缓存。

候选是当前有效门店/项目的上架报价，加仍有 PUBLISHED 依据的报价。后者即使下架/失效也保留未知结果，方便运营核对后停止旧依据；停止后，失效报价不再是候选。一个报价优先选 PUBLISHED；没有发布版则只返回最近草稿或停止记录供解释，绝不使用其区间判定正常。

## 精确投影与判定

每项仅含 `quote_id/state/reason/review_required/current/policy`。`current` 为 c1 的当前来源投影或 null，`policy` 为可校验的原冻结依据投影或 null；不输出员工账号、原审计正文、提交键或客户标识。

按以下顺序产生 UNKNOWN，`review_required=false`：

1. SOURCE_UNAVAILABLE：当前门店、标准项目或上架报价不可用。
2. SOURCE_UNKNOWN：最新报价/原保存凭证不完整或已失效。
3. POLICY_MISSING：没有依据；POLICY_SOURCE_UNKNOWN：依据结构、版本、时间或原发布凭证无法核验。
4. POLICY_NOT_PUBLISHED：仅有草稿；POLICY_STOPPED：依据已停止。
5. NOT_STARTED：尚未生效；EXPIRED：到达或超过截止。
6. SOURCE_CHANGED：当前来源哈希或快照与发布依据不同，包括报价改版、服务内容/项目修订或门店地区变化。

有效比较须处于 `[valid_from,valid_to)`，当前报价/冻结快照/哈希一致且原发布审计完整。`reason=VERIFIED` 时用 BigDecimal 比较当前价格：低于下限 BELOW_RANGE，高于上限 ABOVE_RANGE；等于两端以及区间内均 WITHIN_RANGE。只有低/高两项 `review_required=true`，文案为“需核对”。不以项目参考价兜底、不自动改价或处罚，不跨车型、单位或服务范围泛化。

报价凭证须是最后一次真实 MERCHANT_PROJECT_SAVE 审计，并且未软删除；删除最新版本不能回退旧依据。发布凭证也须是该依据最后一次 PRICE_POLICY_STATUS 审计且未软删除，并与冻结内容和修订相同；停止后恢复旧数据行不能重新取得有效比较。后续软删除的状态事件仍阻断向更旧证据回退。

## 兼容与读取边界

当前依据响应的 `comparison_available=true` 表示比较接口可用；c1 永久创建回执保持原始 false。客户端接受这两个明确布尔值，均不得当作有效比较结果，必须重新 GET。该能力标志不参与业务冻结证据核对。

读取不写报价、政策、幂等记录、审计、考核或资金流水。页面刷新会先清理旧结果，切号/失权/隐藏/换详情丢弃迟到响应。每次刷新只代表本次观察时点，不声称持续监控或自动处置。见[操作](../operations/PRICE_COMPARISONS.md)。
