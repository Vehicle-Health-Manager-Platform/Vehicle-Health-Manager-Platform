# R8a1 积分任务来源配置

状态：本地分步实施，整个R8完成后才统一上传；本步骤只配置规则，不发积分/券，不开放兑换，不导入旧余额。用户确认签到按北京自然日一次，首次完整人工档案/首次成功AI按账号一次；分值和窗口按来源配置，未知不发、不补历史、TEST不计正式积分。

## 权限及接口

独立`can_points`，默认0；不继承邀请、券或诊断权限。所有接口no-store，拒绝未知/重复query和额外字段，当前账号/权限在回执重放前也复核。

- `GET /api/admin/point/task-policies?page=1&page_size=20`：运营分页，最大页1000000；page_size只能20。
- `POST /api/admin/point/task-policies`：草稿新建。`task_type`为CHECK_IN / ARCHIVE_FIRST_COMPLETE / AI_FIRST_SUCCESS，`points`整数1–1000000，`source_note`2–256字、无首尾空白/控制字符；`valid_from`和`valid_to`为UTC，截止严格晚于开始且创建时未到期。
- `PUT /api/admin/point/task-policies/{id}`：上述字段及`expected_revision`；只可修改DRAFT。
- `PUT /api/admin/point/task-policies/{id}/status`：`expected_revision`及PUBLISHED或STOPPED。发布冻结，不重开；每任务仅一个PUBLISHED，过期旧政策也先停止再发布另一条。
- `GET /api/point/tasks`：当前车主，固定三任务，UNKNOWN / NOT_STARTED / EXPIRED / CONFIGURED；`timezone=Asia/Shanghai`；R8a2接入后`earning_available`按当前获分资格判断，进度与到账见[积分账本](POINT_LEDGER.md)。配置不等于任务执行或到账。

三写均需UUID Idempotency-Key。新建按创建者/原键唯一保存正文哈希及首次结果；通用24h回执过期也返回原创建data，原键改正文拒绝，不重复审计。调用方收到旧创建快照后必须重新读当前列表，不用首次DRAFT当现状。修改/状态用CAS；冲突重读，不换新键覆盖未知请求。

成功政策字段：id、task_type、points、source_note、valid_from/to、revision、status、rule_version=R8A_TASK_V1、frequency（签到DAILY，其余LIFETIME）、timezone、created_at、published_at。隐藏创建者、内部提交键/哈希及持久回执。金额/余额不出现在本接口。

400输入/键无效，401会话失效，403无独立权限，404政策不可用，409版本/窗口/发布唯一冲突，503数据库/审计暂不可用。业务、持久创建意图、回执和审计同事务；重放不再新增审计。
