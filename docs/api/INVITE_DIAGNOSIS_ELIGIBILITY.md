# R6b3 当月邀请解锁资格

用户确认规则：运营配置当月有效新客人数阈值、政策窗口和来源说明；达到阈值后只在政策有效期和当月内可用。停用、到期、跨月与账号失效重新核对。未配置 UNKNOWN。留言服务接续 R7，当前资格不表示人工在线或已经接单。

## 接口

- GET `/api/admin/invite/diagnosis-policies?page=1&page_size=20`
- POST `/api/admin/invite/diagnosis-policies`
- PUT `/api/admin/invite/diagnosis-policies/{id}`
- PUT `/api/admin/invite/diagnosis-policies/{id}/status`
- GET `/api/invite/diagnosis-eligibility`

运营端必须当前 OPERATOR 和独立 can_invite；其他运营权限不能替代。OWNER只能读取本人当前资格，拒绝全部query。列表固定20条，page1–1000000，拒绝重复/额外参数。写入UUID Idempotency-Key，当前权限重验、正文绑定、CAS、原子审计；所有响应 no-store。400/401/403/404/409/503均不返回数据库内部细节。

创建严格4字段：required_count整数1–1000000，source_note无控制字符且trim后2–256码点，valid_from/valid_to规范UTC且起点早于终点。修改增加expected_revision，仅DRAFT可修改。状态正文expected_revision/status，DRAFT→PUBLISHED→STOPPED，不重开；同一时刻至多一条PUBLISHED，包括未来政策。发布后的变更使用新政策。

政策10字段：id、revision、status、required_count、source_note、valid_from、valid_to、created_at、published_at、rule_version=R6B_DIAGNOSIS_V1。

本人响应8字段：month、timezone=Asia/Shanghai、qualified_count、state、policy、usable_until、service_available=false、rule_version。状态UNKNOWN/NOT_STARTED/EXPIRED/LOCKED/ELIGIBLE。UNKNOWN时policy为null；仅ELIGIBLE有usable_until，取政策valid_to和北京时间下月一日00:00较早者。

只计算当前账号作为user邀请者，当月服务端VERIFIED_WECHAT、ATTRIBUTED、QUALIFIED，且新客账号当前有效的记录。员工推广不作为同编号车主邀请；TEST/UNKNOWN/历史未确认记录不计入。本规则资格会随新客账号失效撤回，R6a历史月度计数仍保留原始资格事实，两者含义不同。

GET只读、不发券、不保存永久授权。当前service_available固定false，没有留言提交或授权消费API；R7提交事务须重验本人、当时政策和当前有效资格，不能信任客户端eligible或旧页面快照。

V043新增1表，共95表。没有默认阈值或历史授权种子；can_invite仍默认关闭。
