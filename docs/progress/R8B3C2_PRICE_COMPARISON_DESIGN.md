# R8b3c2 只读价格比较设计

2026-10-16。沿用户已确认的具体报价版本、内容/单位/车型适用性、来源/窗口与含边界区间实施，无新增业务规则需确认。按既有brainstorming和planning-with-files记录设计及实施，整R8完成前不上传。

## 读取与候选

复用c1真实来源核验，不复制一套价格来源推测。每次单REPEATABLE_READ事务复核当前独立can_price_monitor与会话，捕获observed_at，读取当前来源和发布依据。候选为当前有效门店/标准项目的上架报价，加仍有PUBLISHED依据的报价；后者即使下架或删除也保留UNKNOWN，便于停止失效依据。不从全站用户或订单取数。

同报价先取PUBLISHED；没有则取最新草稿或停止记录用于说明，绝不拿草稿/停止范围给出已知比较。依据发布成功审计须是该依据最后一次状态事件且未软删除，并与冻结内容/修订一致；报价原保存凭证同样须为最后事件且未软删除，后续已删除事件仍阻断回退旧证据。旧创建回执及快照不改写。当前依据响应comparison_available=true表示读取能力，永久旧创建结果仍可能false，客户端兼容这两个明确布尔值。

## 结果

当前来源不存在SOURCE_UNAVAILABLE、旧凭证破损SOURCE_UNKNOWN；其后按无依据POLICY_MISSING、草稿POLICY_NOT_PUBLISHED、停止POLICY_STOPPED、依据凭证异常POLICY_SOURCE_UNKNOWN、未生效NOT_STARTED、截止EXPIRED、来源或版本变化SOURCE_CHANGED给出UNKNOWN。原因只用于解释，不产生默认正常结果。

仅完整有效发布依据、原发布审计、当前来源哈希/快照匹配且处于[valid_from,valid_to)时，使用BigDecimal将当前价格与两位区间比较。等于两端均WITHIN_RANGE；低/高为BELOW_RANGE/ABOVE_RANGE且review_required=true，仅“需核对”。不推算偏离百分比，不跨车型/单位泛化，不自动改价或处罚。

## 接口和页面

GET /api/admin/price-monitor/comparisons 固定20分页，GET /api/admin/price-monitor/comparisons/{quote_id} 单条；no-store，严格选择器、角色/独立权限，缺候选404，无库503。返回最小当前来源、冻结依据、状态/原因/核对提示及观察时点，不输出员工或审计正文。

实际运营列表/详情两页和共享结果组件，来源/区间/单位/适用说明/窗口与旧版依据明确显示。读取前清旧数据；切号、失权、隐藏或换详情丢弃迟到响应。客户端严格核对实体/字段/时间和边界关系，使用有上界的安全整数分币校验，不在客户端生成正式判定。

## 验收和后续

真实MySQL核验两端/分币/大金额、未来与截止、无源/改版/停止/下架、原发布审计失效、无已知回退、权限撤销、分页与只读无副作用；保留c1永久原请求/审计/并发测试。前端协议/范围/迟到和真Vue绑定、微信/H5、OpenAPI与文档。无新迁移，仍116表/56SQL挂载。

完成c2后整理R8b3c和R8b销项，下一R8c，最后整R8统一门禁。正式供应商识别、短信/微信身份/资金、真机和发布交付仍R9。
