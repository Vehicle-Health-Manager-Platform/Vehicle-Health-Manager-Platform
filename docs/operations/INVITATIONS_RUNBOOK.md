# R6a 邀请升级与排查

先完成 V001–V039，再应用 `docs/sql/migrations/V040__invitation_attribution.sql`。新增 invite_code/invite_registration/invite_phone_evidence，共 91 表；CREATE IF NOT EXISTS 可重复执行，无旧邀请回填、无生产奖励种子。不得将旧 invite_record/user.inviter_id 直接标成 QUALIFIED。

本阶段发现 Compose 新卷初始化只挂载到 V026，补齐依赖 V027–V040，冒烟改为核验完整91表及三个邀请表。MySQL initdb 挂载只作用于空数据卷；已有卷必须备份后显式应用缺失迁移，不能通过删除生产卷升级。

`INVITE_VERIFIED_IDENTITY_ENABLED` 默认 false。保持关闭至 R9 验证真实 app、官方换码、手机号、已有历史身份迁移边界。云托管新注册始终 UNKNOWN，本阶段不扩大可信网关声明。关闭开关阻止新正式身份证据，但不删除已有历史资格；记录 UNKNOWN/TEST 不可自动升格。

## 排查顺序

1. 从本人 registration 结果和不透明 record_id 定位；后台只在最小授权下关联内部 user_id。不要在用户响应、截图或日志贴手机号、openid、手机号摘要或登录令牌。
2. 确认 register 是否真正首次插入，归因 ATTRIBUTED 是否有 invite_code_id/source_type/source_id/merchant_id 原始快照；已有账号无注册账本是未知历史，不能补写归因。
3. PENDING：核对同 app 注册和服务端绑定证据。UNKNOWN/TEST：不计正式人数。REJECTED/PHONE_SEEN：永久手机号历史，不得删除以制造新客。SOURCE_UNAVAILABLE：核对当时冻结的推广者及门店。
4. QUALIFIED：qualified_at 转北京时间确认 reward_month，再按 1/3/6/10 节点复算；当前月份由服务端确定，客户端不能指定月份或计数。月度 progress 与历史 pending/unknown/test/rejected 口径不同。
5. 事务/审计失败：注册和资格均应回滚，重复官方换码需要新的微信临时 code，客户端仍沿用原邀请码；生成推广码重试用原 UUID。稳定码不会因 24h 回执到期重复生成。
6. 店员转店后旧码失效，新会话不能访问旧店记录；原码不能伪装成新店来源。当前实现拒绝跨店重新使用同一员工码，需按员工账号实际生命周期处理，不能改冻结历史。

## 回滚与下一阶段

先停流量并关闭资格开关，回滚应用前核对已生成的邀请落地请求兼容。保留三表及审计，勿删除正式资格或手机号唯一历史。应用回滚不意味着撤销已确认新客。当前没有邀请奖励发券任务，R6b 将依赖 QUALIFIED 的唯一注册事实；不可直接补写旧 invite_record 或重复发券。

阶段代码、迁移、测试与文档同批上传；最终远程 CI 以阶段 PR 精确 head 为准。此运维说明不代表已上线/正式身份已验收。
