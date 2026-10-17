# R8a2 已核验积分与可信任务

> R8a3b起实际流水含[兑换支出](POINT_EXCHANGES.md)，收入原结构保持，支出为独立精确来源结构。余额扣除成功兑换；原R8a2实施说明保留如下。

本地实现，整个R8完成前不上传。任务配置见[规则协议](POINT_TASK_POLICIES.md)。本阶段无兑换或资金通道。

## 本人接口（均no-store）

- `GET /api/point/overview`：balance仅为VERIFIED_TASKS_V1已到账任务积分；legacy_state为NONE或REVIEW_REQUIRED，旧非零余额/旧流水不清零、不导入。earning_available由当前同app会话来源与获分开关决定；day/Asia/Shanghai及固定三任务，包含当前来源政策、状态、已到账分值和登记/到账时间。
- `GET /api/point/flow?page=1&page_size=20`：本人新可信到账流水；id、delta、balance_after、created_at、task_type、period_key、policy_id/revision，不返回原档案、AI文字、私有链接或身份内部证据。page上限1000000，page_size只能20；旧point_flow不混入。
- `POST /api/point/check-in`：UUID Idempotency-Key；严格正文day（北京日期）、policy_id、policy_revision。锁等待后固定一个资格时点并重验当前身份、日期与已发布规则窗口；不接收eligible、积分值、账号或其他来源字段。回执event_id/day/status/points，PENDING仅为登记；须重新读overview确定到账。
- `GET /api/point/tasks`沿用配置投影，但earning_available由R8a2实时资格判断，不再固定false；不含余额/到账进度，进度使用overview。

任务状态UNKNOWN/NOT_STARTED/EXPIRED来自当前规则；IDENTITY_UNVERIFIED表示当前无法领取（含获分尚未开放），SOURCE_UNAVAILABLE表示AI缺当前可信来源。READY允许完成；PENDING/CREDITED优先显示已冻结事件事实，不能因政策停止而丢掉历史到账事实。PENDING/CREDITED的policy是登记时冻结的快照，其他状态才是当前政策。原政策停止或换版不改这一快照；快照的PUBLISHED表示登记当时状态，不保证现时仍开放。CREDITED的points_awarded是原事件实际分值，不按新规则改算。

401重新认证，403错误角色，400未知/重复query、坏日期/正文/键，409日期/规则/当前资格变化，503数据库/审计不可用。原键永久保存正文哈希及首次签到结果；通用回执过期或跨日仍不新签到，改正文拒绝。同日不同键也只归于一个事件。跨日旧请求未执行则409，不自动换日期或键。

## 服务器来源与一致性

用户确认三任务：北京自然日签到、每账号一次完整人工档案、每账号一次成功AI。奖励首次在有效规则和可信资格下完成的行为，不扫描历史补发。完成但当时无有效政策/身份不会冻结事后奖励。

人工档案仅input_type 1/3、类型1/2/10，经现有ArchiveStructuredInput本人确认及必填校验，从不完整变为完整的成功保存；与源事务同写事件。自动施工归档/仅图片/未确认/重复保存已完整记录不计。源指纹仅保存编号、版本和哈希，不复制正文。

AI仅官方端点实际成功返回、非空答复、无所选档案降级，且AI来源开关开启后，在外部IO结束重新验证当前会话；失败/替身不计。仅留随机来源号和结果哈希，不保存问题/答复。正式模型效果与身份仍待R9实际验收。

签到user+北京日期，其余user+任务类型+LIFETIME唯一；换政策不重发。源事务先PENDING冻结原政策/分值、身份会话/来源最小证据及业务时间。可靠任务逐事件事务锁当前有效user，唯一point_verified_entry、账户余额/版本、到账审计及事件CREDITED原子；并发/失败/重启不重复，失败退避30秒，停用账号暂停而不伪造成功。已捕获完成行为按原规则兑现，后续政策停止或档案修改不自动扣回/追加；捕获前失权或确认无效则不记录。

## 数据与身份

V050新增4表，累计109表；auth_session.point_identity_source默认UNKNOWN，旧会话不升级。新车主登录仅POINTS_VERIFIED_IDENTITY_ENABLED与官方微信端点共同满足才由服务器标记VERIFIED_WECHAT；TEST不升级，刷新沿同一会话保留来源。POINTS_EARNING_ENABLED、POINTS_VERIFIED_IDENTITY_ENABLED、POINTS_AI_VERIFIED_ENABLED均默认false，不以测试替代正式验收。
