# R7c2 非实时人工诊断协议

用户确认的权限与业务契约见[设计](../progress/R7C2_DIAGNOSIS_DESIGN.md)。新增16项实际接口，OpenAPI共252操作。所有业务/控制器响应no-store；GET不产生留言、事件、审计或奖励。

## 权限与最小投影

- OWNER限本人会话；新提交/追加还要求当前本人有效车辆和当前R6资格。
- OPERATOR(can_diagnose_manage)管理受理配置、无正文队列、有效处理人及分配/关闭；管理权限不等于正文权限。
- OPERATOR(can_diagnose)仅本人当前获派会话可读正文/回复/关闭，转派或撤权后拒绝新读取及旧键重放。兼具两权限仍须获派才能读正文。
- 车主只主动共享本次文字及四项车型标签（brand/series/year/config_name），不默认共享车牌/VIN/证件/历史档案或照片。自由文字由车主用途确认，不宣称自动脱敏。

## 受理配置

- GET /api/admin/diagnosis/policies：page/page_size。
- POST /api/admin/diagnosis/policies：严格mode/source_note/valid_from/valid_to。
- PUT /api/admin/diagnosis/policies/{id}：以上加expected_revision，仅DRAFT。
- PUT /api/admin/diagnosis/policies/{id}/status：严格expected_revision/status，发布PUBLISHED或停止STOPPED。
- GET /api/admin/diagnosis/handlers：有效且有独立处理权限的人员，page/page_size，每项id/label。

mode为AVAILABLE（非实时可受理）、LEAVE_MESSAGE（离线留言）、PAUSED（暂停新受理）。配置唯一PUBLISHED，来源和有效期必填；无默认阈值/默认受理/默认授权。已发布条款不改，模式变化需停止旧版本后发布新版本。无配置UNKNOWN、未生效NOT_STARTED、到期EXPIRED、无有效处理人SOURCE_UNAVAILABLE均不接受新文字；PAUSED也不接受。历史处理与本人读取不因受理暂停而删除。

配置响应id/revision/status/mode/source_note/valid_from/valid_to/created_at/published_at；列表items/total/page/page_size。UTC日期时点。发布可受理或离线版本须存在有效处理人员，发布后人员全失效也即时关闭新增受理。

## 车主

- GET /api/owner/diagnosis/context：无query，返回service（state/accepting/policy）及eligibility（R6现有结构）。R6 service_available现在只在ELIGIBLE且实际配置允许、存在有效处理人时true，不表示人工实时在线。
- GET /api/owner/diagnosis/cases：本人最小列表，page/page_size。
- POST /api/owner/diagnosis/cases：严格vehicle_id/policy_id/policy_revision/message/consent（必须true）。当前车型由服务端查本人车辆，不信客户端车型/资格/处理人。
- GET /api/owner/diagnosis/cases/{id}：page/page_size为消息分页。
- POST /api/owner/diagnosis/cases/{id}/message：严格expected_revision/policy_id/policy_revision/message/consent，再次核对当前资格及受理。
- POST /api/owner/diagnosis/cases/{id}/close：严格expected_revision。

文字1至2000 Unicode字符，允许内部换行，拒绝首尾空白和其他控制/格式字符。创建绑定永久提交意图，通用24小时幂等回执过期后原键原正文仍不能重复创建；原键变正文拒绝。旧创建回执仅代表曾创建，须重读当前状态。资格跨月/政策停止不剥夺本人既有留言与答复的只读权利。

## 运营处理

- GET /api/admin/diagnosis/cases：scope=mine（默认，处理权限）或queue（管理权限），page/page_size。
- GET /api/admin/diagnosis/cases/{id}：仅当前获派处理人员，page/page_size。
- PUT /api/admin/diagnosis/cases/{id}/assignment：严格expected_revision/operator_id，管理权限；目标须当前有效且有处理权限。
- POST /api/admin/diagnosis/cases/{id}/reply：严格expected_revision/message，仅当前获派人、PROCESSING。
- POST /api/admin/diagnosis/cases/{id}/close：严格expected_revision，管理者或当前获派处理人员。

列表case_id/revision/status/created_at/updated_at；管理queue另有assigned_operator_id（可空），不返回正文、车型或车主身份。详情为case、vehicle、messages分页；消息仅message_id/sender（OWNER/OPERATOR）/body/created_at，不返回sender_id或登录账号。写回执仅case_id/revision/status。

WAITING→PROCESSING（分配）→REPLIED（答复）→CLOSED。未关闭可转派；车主追加文字时有有效获派者则PROCESSING，否则清空失效分配转WAITING。关闭不可重开、不可再追加/回复。

## 事务、来源与错误

写入必填UUID Idempotency-Key，除创建外有expected_revision CAS；正整数ID上限9007199254740991，page上限1000000、page_size固定20。除列明query外全部拒绝，包括重复query；写入无query。

受理使用控制行、当前发布配置、R6政策和邀请来源/有效新客锁，在等待后复核北京月、政策时窗、当前有效人数及本人车辆。关键判断使用当前锁定读取，不能沿用先前读快照。保存资格计数/月/政策版本、服务配置与车型快照；不把奖励已发等同于诊断资格。诊断短事务统一控制锁次序；无外部IO。

文字、状态、独立case_id事件与审计/回执原子；审计不保存正文。明确403/404撤权或409版本/来源冲突须重读；未知结果用原正文原键重试。400参数/同键异正文错误，401会话失效，403权限不足，404车辆/会话/当前分配不存在，409资格/配置/版本/状态变化，503数据库/事务不可用。

V046总102表、两权限默认0。诊断事件不是订单通知，R7c3第三步已接入[独立诊断站内消息](DIAGNOSIS_NOTIFICATIONS.md)，V048当前104表/256操作，诊断正文权限不扩展。R7c3第一步已接入车主新建/追加、当前获派运营答复和关闭的本机显式草稿及跨页面/重启原键恢复；恢复前从服务器复核当前权限，401重新认证，403/404清理失权业务。用途同意不随草稿恢复，无新增接口，见[执行](../progress/R7C3_RECOVERY_EXECUTION.md)。不冒称正式短信/微信通知。
