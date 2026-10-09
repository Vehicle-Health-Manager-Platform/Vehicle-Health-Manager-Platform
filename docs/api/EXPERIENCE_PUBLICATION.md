# A7.2c2 微信小程序经验审核与同款展示接口

先迁移V019、后端与新版微信小程序，再显式开启 EXPERIENCE_PUBLICATION_ENABLED（默认false）。独立运营身份/短信见[账号管理](../operations/OPERATOR_ACCOUNTS.md)。关闭展示不阻止本人撤回。

| 方法 | 路径 | 身份与输入 |
| --- | --- | --- |
| GET | /api/admin/experience-cards | OPERATOR + can_review；page默认1（≤1000000）、page_size默认20（≤50） |
| POST | /api/admin/experience-cards/{id}/moderate | OPERATOR + can_review；UUID，严格revision/decision/reason_code，无query |
| GET | /api/community/experiences | OWNER；本人vehicle_id必选，cursor可选（安全正整数） |

所有参数拒绝重复/未知，JSON拒绝重复键/尾随/类型转换，全部no-store。

待审data `{items,total,page,page_size}`；每项仅card_id/revision/title/summary/model_id（无车型null）。不返回车主/订单/档案/签名/照片。只PENDING_REVIEW且已授权非测试。操作输入示例 `{"revision":1,"decision":"APPROVE","reason_code":null}`；REJECT只允许INSUFFICIENT_DETAIL/NOT_SUITABLE固定理由，不接受自由文字。

审核写data `{card_id,status,revision,decision,reason_code}`；批准PUBLISHED、驳回REJECTED，revision+1，卡片/原送审revision唯一只追加记录。批准复核原授权、冻结来源/摘要一致、核销付款与无争议及车型有效；无法发布时可驳回。重放/旧版本必须复核权限与当前状态，不能覆盖撤回或再次送审，45003版本/授权变化，45004缺车型，45001来源不可信。

本人 `/api/experience-cards` DTO旧三个状态不变，增加PUBLISHED/REJECTED；REJECTED另含review_reason（固定码或历史缺记录null）。车主可撤回已发布/驳回卡片，撤回清除有效授权且保留原档案/评价；驳回重新送审必须重新勾选声明，新revision独立审核。已发布需要先撤回才可重新授权。

同款data `{items,next_cursor}`，最多扫描20个候选，可能空页带下一游标；next_cursor=null结束。每项严格experience_id（随机UUID）、title、summary、model_id、published_at，绝不返回私有card/vehicle/order/archive/user/review/运营ID、文件、施工原文。真实非测试、授权有效、审核revision匹配、原车主仍拥有车辆、有效车型与本人车型相同、当前来源仍可信；撤回/转移/删除/付款异常/争议/冻结来源变化实时隐藏。没有全局total；客户端每次重入重查，游标不持久化。

summary仍为version/work_minutes/part_kinds/no_parts/recorded_month五项事实，无维修效果推断、图片或身份。测试卡片永久排除；不计商家评分、不进入AI。H5仅辅助联调，主要页面与传输位于微信小程序。

错误：40001参数，40100会话，40300角色/审核权限，40400不可用，45001/45003/45004业务冲突，42900认证限流，50300开关/依赖/事务（写原键原载荷重试）。
