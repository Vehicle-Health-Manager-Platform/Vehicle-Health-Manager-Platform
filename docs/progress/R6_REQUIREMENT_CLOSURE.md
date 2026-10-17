# R6 邀请阶段需求销项

范围依据[交付原文](../reference/DELIVERY_V1.md)的 F10、首页邀请提示、C20 进度与海报、员工推广归因，以及[剩余阶段规划](REMAINING_DELIVERY_PLAN_2026-10-14.md)。按用户确认的业务规则实现；本文件以实际代码、数据库与测试证据销项。

| 需求 | 已实现行为 | 主要证据 |
| --- | --- | --- |
| 首次注册归因 | 随机稳定推广码；首次注册固定车主或员工及原门店；已注册轻提示、无补填/改来源；未知/测试不升级 | [R6a](R6A_INVITATIONS_EXECUTION.md)、InvitationRegistration、Invitations |
| 有效新客 | 同 app 服务端身份及首次手机号历史，资格与审计原子；停用/转店复核、外部换码后重验会话 | [归因协议](../api/INVITATIONS.md)、JdbcInvitationsTest |
| 月度四档循环 | 北京自然月增量1/2/3/4，即累计1/3/6/10后重复；资格发生时间和归属月份用同一时点 | [R6a验收](../testing/LOCAL_R6A_INVITATIONS_ACCEPTANCE.md)、月界回归 |
| 真实奖励政策 | 独立 can_invite 权限；OWNER双方、STAFF仅新客；已发布券来源/版本，草稿/冻结/停止、CAS/幂等/审计；无配置未知 | [R6b1](R6B1_REWARD_POLICY_EXECUTION.md)、[政策协议](../api/INVITE_REWARD_POLICIES.md) |
| 双向可靠发券 | 资格事件冻结原政策及受益人，批次/明细唯一；双方同事务发券/扣库存/凭证/审计；员工账号不当车主 | [R6b2](R6B2_REWARD_EXECUTION.md)、InvitationRewards |
| 重试和来源变化 | 持久等待与后台扫描；库存/来源/账号恢复后仍用原快照；未知政策不追溯套新规则，过期不续期 | [运维](../operations/INVITATION_REWARD_RUNBOOK.md)、真实库恢复/并发/锁等待回归 |
| 公共领券与交易兼容 | PUBLIC每人每模板一次，INVITE每事件/受益人一次；邀请券可现金预约/全退返原券，SERVICE独立核销 | CouponInventory、JdbcCouponTransactionsTest |
| 记录和首页提示 | 本人/原门店分页记录，另一方券编号隐藏；首页只在可信政策或实际奖励存在时显示真实状态 | [奖励协议](../api/INVITATION_REWARDS.md)、InvitationOverview/Rewards |
| 本地邀请海报 | 用户点击画布生成随机码/输入说明，当前页图片预览及明确相册保存；换号/离页清理、原生保存完成后删临时图 | InvitationPoster、[海报说明](../operations/INVITATION_POSTER_AND_UNLOCK.md) |
| 人工诊断资格来源 | 运营配置月内人数、窗口及来源；每次按北京当前月和新客当前状态复算，未知不解锁，停用/跨月重新判断 | [R6b3](R6B3_IMPLEMENTATION_PLAN.md)、[资格协议](../api/INVITE_DIAGNOSIS_ELIGIBILITY.md) |
| 页面恢复与隔离 | 账号/权限/隐藏隔离迟到响应；未知写结果沿原正文与键重试；确认保存后退出旧草稿编辑，避免旧CAS版本连续提交 | invite-policies/invite-diagnosis/invitation-poster 服务及测试 |

整 R6 新增 V040–V043 共7张表，当前95表；实际增长接口22项（归因9、奖励配置6、奖励记录2、资格5），另补既有条件云登录契约。小程序新增5页、总75页；OpenAPI219项含其他阶段草案，不能把总数视为所有接口已实现。原141条追踪、44表基线、根README与首启动路由保留。

本批在既有 R6a 分支/PR152 上传全部后续业务代码、迁移、测试、配置、生成协议和使用/运维/进度/验收文档。R6b1/b2/b3实施期间没有中间推送。源码准备与本地环境限制见[统一验收](../testing/LOCAL_R6_COMPLETION_ACCEPTANCE.md)，最终提交及六项远程 CI 结果在 PR152 正文记录。

实际人工诊断留言/分配由 R7c 实现并在接收事务重验资格；员工考核由 R8 使用冻结事件。正式供应商识别、短信/微信身份、资金、官方小程序码/分享、真机与发布保留最后 R9。正式身份与奖励后台默认关闭，合成测试不计正式新客。下一步按[R7a计划](R7A_OWNER_EVIDENCE_NEXT_PLAN.md)做本人订单施工证据受控读取与进度页面。
