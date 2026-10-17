# R8b1 门店月度考核来源政策

> R8b2a另增[独立运营月结/修订API](ASSESSMENT_SNAPSHOTS.md)。R8b2b已接通[本店结果读取](MONTHLY_ASSESSMENT_VIEWS.md)，scoring_available=true表示读取能力可用，政策配置动作本身始终不生成成绩。

2026-10-16，本地阶段完成；整个R8完成后才统一上传代码与文档。依据已确认规则：北京时间自然月，拉新/券/过程40%/40%/20%；月目标、必需证据和等级阈值须有运营来源。缺配置或有效分母为未知，不补历史成绩。实际取数与评分在R8b2实现，本步不产生分数。

## 接口与权限

全部no-store，拒绝额外/重复query、未知或重复正文属性及尾随JSON。独立运营`can_assessment`默认0，每次读取、写入和回执重放均重验当前数据库账号、会话与权限；积分、券、邀请和诊断权限不能替代。

- `GET /api/admin/assessment/merchants?page=1&page_size=20`：有效真实门店的id/name/address，不含联系人或凭据。
- `GET /api/admin/assessment/policies?page=1&page_size=20`：全部政策分页，页码1–1000000、每页固定20。
- `POST /api/admin/assessment/policies`：创建DRAFT，必须有UUID `Idempotency-Key`。
- `PUT /api/admin/assessment/policies/{id}`：仅草稿，完整正文另加`expected_revision`，必须有UUID键。
- `PUT /api/admin/assessment/policies/{id}/status`：仅`expected_revision`和`status=PUBLISHED|STOPPED`，必须有UUID键。DRAFT→PUBLISHED→STOPPED，不重开；每店最多一条PUBLISHED，过期原政策仍须先停止。发布刷新真实门店快照并冻结。
- `GET /api/merchant/assessment/policy`：当前真实店长，只读本店，不接受merchant_id或任何query。普通员工、其他角色拒绝。

写入成功本身是统一ApiResponse回执，不再嵌套第二层data。修改和状态均CAS；冲突必须重读，不以新键覆盖未知请求。

## 创建/更新正文

严格八项：`merchant_id`、`acquisition_target`、`coupon_target`、`process_requirements`、`grade_thresholds`、`source_note`、`valid_from`、`valid_to`。更新另有`expected_revision`。

- 两项目标为整数1–1000000。
- 必需证据为非空、无重复的支持项子集：CHECK_IN_CONFIRMED（接车确认）、PROTECTION_COMPLETE（防护证据）、REPORT_SUBMITTED（报工提交）、QUALITY_SIGNED（质检签字）。不接受客户端自定义事实名称。
- 等级数组2–10档，各项仅`code`、`label`、`min_score`。code为大写字母开头的1–16位大写字母/数字/下划线，label为1–32字有效文本，各自唯一；min_score为两位小数字符串，0.00–100.00，严格从高到低且末档0.00。无默认等级边界。
- 来源说明2–256字，无首尾空白、控制字符。月份时点须为北京时间月初00:00的UTC时间；开始在服务器当前时点之后，截止晚于开始，左闭右开，完整自然月。例如北京时间2027-01对应2026-12-31T16:00:00Z。创建/修订/发布等待锁后再次检查时间，不追溯当月或历史。

固定元数据不由请求输入：scope=MERCHANT、period=BEIJING_MONTH、timezone=Asia/Shanghai、weights={acquisition:40,coupon:40,process:20}、rule_version=R8B_POLICY_V1、metric_source_version=R8B_FACTS_V1、formula=TARGET_RATE_AND_PROCESS_COMPLETENESS_V1。

## 响应与恢复

政策返回八项正文及id、merchant（冻结id/name/address）、revision、status、上述固定元数据、created_at/published_at（未发布为null）。隐藏创建者、原键、内部哈希和永久首次回执。

本店当前响应固定`state`、`policy`、`scoring_available=true`、`timezone`。无PUBLISHED为UNKNOWN且policy=null；有政策时按窗口为NOT_STARTED、CONFIGURED或EXPIRED。CONFIGURED仅表示配置窗口有效，不是计算成功，不附旧assessment分数。

创建按创建者/规范化UUID永久唯一，保存递归规范化JSON哈希和首次结果；对象键顺序不影响相同请求，数组顺序保留。通用24h回执过期、草稿已修改/发布、月份已过去仍恢复原创建结果，同键改正文拒绝。恢复首次DRAFT后重新读取现状。业务、首次回执、通用回执和审计同事务，重放不重复审计。

400输入/键错误；401失效会话；403缺独立权限/非店长；404门店/政策不可用；409修订、月份或单店发布冲突；503数据库或审计暂不可用。停止不要求门店仍有效；当前门店读取仍须当前真实门店会话。

详见[运维](../operations/ASSESSMENT_POLICIES.md)、[设计](../progress/R8B1_ASSESSMENT_POLICY_DESIGN.md)。
