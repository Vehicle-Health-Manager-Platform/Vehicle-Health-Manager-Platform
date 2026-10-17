# R6b2 邀请奖励运行记录

R6 源码与文档收口见[全部需求销项](../progress/R6_REQUIREMENT_CLOSURE.md)，统一上传与最终全套 CI 结果以 PR152 正文为准。正式身份/资金/真机发布仍 R9。

## 迁移

R6b2 的 V042 阶段为94表；当前整 R6 新卷按 V001–V043 初始化，共95表。已有卷先备份、核对历史可信 coupon_issue，再按顺序执行所有缺失迁移至 V043（含 V041/V042/V043）；Compose挂载不会迁移已有数据卷。V042可以重放：原PUBLIC凭证不变；按origin区分唯一键，新建批次/明细；已有QUALIFIED无快照仅记UNKNOWN_POLICY，没有发券或库存扣减。

## 开关与恢复

INVITE_REWARD_ENABLED 默认false。配置政策本身不发券。正式身份开关也默认关闭，应完成R9身份验收后再启用真实新客及后台。不要把测试身份当成正式身份。

后台限100件/轮，每分钟扫描，等待或事务失败300秒后重试。WAIT_ACCOUNT 核查原受益人/员工是否停用或转店；WAIT_SOURCE 核查原发行门店、项目、报价、券发布版本及期限；恢复同一来源后下一轮继续原任务。EXPIRED/INVALID_EVIDENCE/UNKNOWN_POLICY不自动替换政策或重发。

WAIT_STOCK保留任务和原券信息。当前已发布模板额度不可由客户端修改，本阶段没有新增补库存、替换券或补偿券权限；库存不足时先核查库存流水与真实可用额度。迁移及测试中的合成库存修复不代表新增运营接口，严禁通过修改快照或删唯一凭证重复发券。原额度耗尽只能等待业务另行确定补偿政策或原券过期。

故障恢复只重跑原批次：资格记录、政策版本、受益人和券条款不变；成功状态不能回退。审计写失败也回滚持券、coupon_issue、库存与明细，数据库恢复后后台可继续。账号锁忙不会阻塞资格写入，由后台再次执行。

## 核查

核对 invite_reward_batch.state/attempts/next_attempt_at，invite_reward_item 每batch/role至多一条；COMPLETE双方（员工一方）都有来源凭证和库存流水，user_coupon_id只出现一次。PUBLIC同用户模板一次，INVITE同明细一次。记录中没有手机号/openid。

在小程序“邀请记录与月度进度”或“我的店员推广”查看奖励状态。新客登录后可查看自己持券编号；员工看不到新客编号。页面刷新只读，不触发发券。源码验收见[本地测试](../testing/LOCAL_R6B2_REWARD_ACCEPTANCE.md)。
