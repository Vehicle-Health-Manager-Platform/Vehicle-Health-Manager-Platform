# 私有结构化问答 API（R8c1b2）

2026-10-17。已确认独立授权运营非实时答复、默认私有，公开须后续b3单独授权审核。b2a后端与b2b实际页面、确认和本机未决原请求恢复已接通；不能把这些接口当成公开同款问答或R7人工诊断。

## 身份、受理及投影

V058新增can_community_manage和can_community_answer，默认0，各自独立；运营登录当前id/role加14权限位共16字段。原精选、诊断、审核等权限不替代。车主由当前OWNER会话识别，禁止客户端owner_id。所有成功与错误no-store、单层ApiResponse。无数据库503，不返回SQL或问题正文作为错误。

受理政策有来源说明、UTC整秒valid_from/to、mode AVAILABLE/LEAVE_MESSAGE/PAUSED；前两者均非实时。状态DRAFT→PUBLISHED→STOPPED，单一发布版本，草稿修改/状态CAS，发布后冻结，过期发布版本仍须先停止才能换版。车主context为`{state,accepting,policy}`，无配置UNKNOWN/false/null；其他状态NOT_STARTED/EXPIRED/SOURCE_UNAVAILABLE/PAUSED/AVAILABLE/LEAVE_MESSAGE。policy仅id/revision/mode/valid_from/to，来源说明只管理人员读取。

新提问/补充在同一事务重新核验当前受理版本、窗口、有有效处理人员、本人车辆及有效车型/车系/品牌。提问仅主动文字（1–2000 Unicode字符，可内部换行，首尾空白、其他控制/格式字符拒绝）、分类MAINTENANCE/REPAIR/FAULT和明确consent=true。不读取档案、诊断、图片、车牌/VIN/联系方式，不关联邀请资格或积分。

问答WAITING，分配后PROCESSING，回复后REPLIED；车主补充回PROCESSING，原处理人无效则清分配回WAITING。车主或当前获派人员关闭至CLOSED，不再继续写。已有问题的答复/关闭不要求当前受理仍开放。管理者可以分配，不能凭管理权限读取正文或关闭；即使兼具处理权限，也须当前获派。

## 16项实际接口

- `GET /api/owner/community/questions/context`：当前受理；无选择器。
- `GET /api/owner/community/questions/cases`：本人列表。
- `POST /api/owner/community/questions/cases`：`{vehicle_id,policy_id,policy_revision,category,message,consent}`。
- `GET /api/owner/community/questions/cases/{id}`：原提问者详情及消息分页。
- `POST /api/owner/community/questions/cases/{id}/message`：`{expected_revision,policy_id,policy_revision,message,consent}`。
- `POST /api/owner/community/questions/cases/{id}/close`：`{expected_revision}`。
- `GET /api/admin/community/questions/policies`：独立管理者政策列表。
- `POST /api/admin/community/questions/policies`：`{mode,source_note,valid_from,valid_to}`。
- `PUT /api/admin/community/questions/policies/{id}`：同创建正文加expected_revision，限草稿。
- `PUT /api/admin/community/questions/policies/{id}/status`：`{expected_revision,status}`，目标PUBLISHED/STOPPED。
- `GET /api/admin/community/questions/handlers`：独立管理者读取有效处理人员`{id,label}`。
- `GET /api/admin/community/questions/cases`：scope默认mine需处理权限，仅本人获派；scope=queue需管理权限，为最小分配队列。
- `GET /api/admin/community/questions/cases/{id}`：处理权限且当前获派的详情。
- `PUT /api/admin/community/questions/cases/{id}/assignment`：管理权限，`{expected_revision,operator_id}`，目标须当前ACTIVE且独立处理权限。
- `POST /api/admin/community/questions/cases/{id}/reply`：当前获派，`{expected_revision,message}`。
- `POST /api/admin/community/questions/cases/{id}/close`：当前获派，`{expected_revision}`。

列表/详情/handlers接受page=1…1000000和固定page_size=20，其余未知、重复或跨范围选择器400。详情分页用于消息，按消息ID升序；问答列表ID降序。id正安全整数；写入均要求UUID Idempotency-Key、严格JSON字段和类型，无重复/尾随JSON。

## 返回与恢复

列表项`{case_id,revision,status,created_at,updated_at}`，queue仅额外assigned_operator_id，可空；不含用户/车辆ID、分类、车型或正文。分页统一`{items,total,page,page_size}`。详情为`{case,category,vehicle,messages}`，vehicle仅model_id/brand/series/year/config_name，消息仅message_id/sender(OWNER或OPERATOR)/body/created_at，无sender_id或内部证据。写回执`{case_id,revision,status}`；政策写回执为原政策视图。

每次读取、写入和回放重新检查当前账号/会话；旧处理者重分配后404，撤处理权限403，过期会话401。车辆转移后原提问者仍可读历史，新车主不能读取；新补充须当前仍有该车。当前获派人员按原提交车型快照处理，不取得车主后续档案。

政策和问答创建永久保存原键/规范正文哈希/原创建data，通用回执过期后恢复原创建data，不以新状态伪造原回执、不新建第二条。同键换正文400；恢复仍核验当前提交者身份，政策恢复仍需当前管理权限。问答创建恢复不重新要求当前政策/车辆仍有效。非创建操作通用回执24小时，回放仍检查当前范围；过期重发旧修订409，不能自行换键推定原操作失败。

全体问答业务写先控制行串行，再当前身份/相关账号及业务行，避免不同管理人员发布/分配并发穿透。新输入在当前锁和等待后重验窗口；分配/答复/关闭CAS。消息、事件、状态、原意图、通用回执、最小审计同事务，任一失败全部回滚。审计无问题正文；独立事件表目前无通知投递API，不混入R7通知或公开精选。

见[设计](../progress/R8C1B2A_PRIVATE_QA_DESIGN.md)、[操作](../operations/PRIVATE_COMMUNITY_QUESTIONS.md)、[下一页面计划](../progress/R8C1B2B_PRIVATE_QA_UI_NEXT_PLAN.md)。

## b2b实际页面与本机边界

车主`/pages/community/questions`和`/pages/community/question?role=owner&id=...`；运营`/pages/operator/question-service`、`/pages/operator/questions?scope=mine|queue`，当前获派详情`/pages/community/question?role=admin&id=...`。登录回跳校验精确参数及独立权限，只有新权限的账号也有实际落点。所有数据和当前身份/范围绑定，隐藏或失权清除迟到结果。

确认展示原文字、车型/分类、当前修订和具体操作；分配展示目标有效人员，政策展示模式/来源/北京时间窗口，关闭说明不可追加。确认前复制body，先持久化并回读原请求后发送；存储失败阻写，不保存未提交草稿或token。键按角色、账号、创建/政策/分配队列/具体问答隔离，与R7恢复键不通用。创建政策/提问可长期恢复，非创建采用23小时保守窗口；过期须先读授权范围、明确核对后仅清本机。清理比较原请求或读取标记，其他页面覆盖时拒绝删除。未知响应仅同body/key重试；历史或来源503不伪造空数据，当前正文/队列读取成功后才允许恢复。

整b2销项见[完成记录](../progress/R8C1B2_PRIVATE_QA_COMPLETION.md)，下一[b3独立公开授权与审核](../progress/R8C1B3_PUBLIC_QA_NEXT_PLAN.md)。没有公开问答或通知投递完成声明。

## V059消息来源补强

公开问答b3a为新私有消息补充内部指纹和原审计hash，车主输入按已有服务器身份/app核验冻结证据；私有API允许字段不变，不把身份或指纹投到详情，不授予积分或默认公开。V059后124表，旧消息不补造正式来源，仍可按原b2权限读取私有历史。见[独立公开问答](PUBLIC_COMMUNITY_QUESTIONS.md)。
