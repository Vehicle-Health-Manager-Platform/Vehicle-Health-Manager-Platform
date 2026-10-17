# R6b1 邀请奖励配置协议

本地实施步骤，整 R6 完成后统一上传。规则版本 `R6B_REWARD_V1`，V041 增量一表及 `operator_account.can_invite`（默认0），总92表。此步仅配置，不生成奖励意图、券、库存占用或诊断权限。

## 已确认规则与权限

用户确认 OWNER 每位有效新客双方各一次配置券，同模板可奖励不同邀请事件；公共领取仍一人一模板一次（后续发行来源改造前保持既有唯一约束）。STAFF 仅新客奖励，推广员工计拉新考核，不提供员工车主受益人字段。

独立运营 can_invite，每次请求含当前账号/会话与权限复核；审核/券池权限不能代替。离线 `operator-admin` 的 `invite-permission` 操作要求显式 `OPERATOR_CAN_INVITE=true|false` 并撤销旧会话，创建账号默认无此权限。登录响应增加布尔 `can_invite`，旧客户端结果与新客户端权限缺失保持默认拒绝。

## 实际六项接口

- GET `/api/admin/invite/reward-policies?page=1&page_size=20`：政策分页。
- GET `/api/admin/invite/reward-coupons?page=1&page_size=20`：当前可配置的精选已发布券，独立邀请权限即可读，不需要券池管理权限；未来生效或暂时无库存可配置，发奖时再复核库存。
- POST `/api/admin/invite/reward-policies`：创建草稿。
- PUT `/api/admin/invite/reward-policies/{id}`：按 expected_revision 修改草稿。
- PUT `/api/admin/invite/reward-policies/{id}/status`：按 expected_revision 发布/停止。
- GET `/api/invite/reward-policy?scope=OWNER|STAFF`：登录车主查看一般政策来源，不读员工/其他车主记录，去除运营 source_note。

管理写操作必需 UUID Idempotency-Key。严格正文、重复/尾随 JSON 拒绝，列表固定20与页码1..1000000，未知/重复查询拒绝，所有响应 no-store。400字段/格式，401会话，403权限，404来源不存在，409版本/发布/唯一政策冲突，503事务暂不可用原正文/键重试。

## 正文及生命周期

草稿字段：scope/source_note/valid_from/valid_to/inviter_coupon_id/inviter_publication_revision/invitee_coupon_id/invitee_publication_revision；修改另加 expected_revision。OWNER 两对 ID/版本为正整数，STAFF 的 inviter 两项必须 null。来源说明2..256字符、无控制字符，时间为规范秒级 UTC。状态正文仅 expected_revision/status，目标 PUBLISHED 或 STOPPED。

引用券必须有 R5 真实出版版本、当前门店/标准项目/在售报价有效、精选池选中，政策有效期完整落在每份券有效期内。创建、修改、发布均复核；发布后冻结券 terms，停止不重新发布。允许同一券用作双方奖励，配置不占库存，也不意味着存在两份库存。

每种 scope 只有一个 PUBLISHED（包括未来/已过期但未停止的政策），数据库生成 active_scope 唯一索引保证；新政策需停止旧政策。不同类型各可发布一份。多券来源统一先按门店、项目锁定，再逐券锁定，与公共领取共享资源锁顺序；并发唯一冲突返回409。通用写回执保持项目既有24小时期限，政策发布唯一约束不随回执到期失效。

读取状态 UNKNOWN（无发布政策）、NOT_STARTED、EXPIRED、CONFIGURED、SOURCE_UNAVAILABLE；CONFIGURED 只说明配置满足当前来源条件，不能当发券到账。后续 R6b2 才接资格冻结、库存与唯一任务；缺配置不能凭界面示例创建生产政策。

## 页面与升级

运营登录页工作区提供 `/pages/operator/invite-policies`，按权限开放，规则与候选券均分页；草稿选择、北京时间输入转换UTC、确认发布/停止、失败原正文与 UUID 重试、隐藏/账号切换清理私有显示和迟到结果。适配部署先应用V041，Compose空卷挂载已同步；已有卷备份后显式迁移，不删除卷升级。下个步骤见[R6b计划](../progress/R6B_NEXT_PLAN_2026-10-15.md)。
