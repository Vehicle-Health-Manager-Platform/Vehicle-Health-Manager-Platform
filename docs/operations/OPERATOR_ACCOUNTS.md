# 运营认证与审核账号

> V046新增独立诊断处理/管理权限can_diagnose和can_diagnose_manage，均默认0，旧权限不会继承。离线动作`diagnose-permission`/`diagnose-manage-permission`分别要求显式环境`OPERATOR_CAN_DIAGNOSE=true|false`/`OPERATOR_CAN_DIAGNOSE_MANAGE=true|false`及`--operator.id=...`；沿operator-admin且无HTTP的既有流程运行。权限变更/审计/撤销所有会话同事务，重新登录后生效；管理权不自动授予正文读取，仍须有处理权和当前分配。见[诊断运维](ASYNC_DIAGNOSIS.md)。

> V038新增独立券池权限can_coupon，默认0，不随目录/审核权限授予。显式环境`OPERATOR_CAN_COUPON=true|false`，使用既有无HTTP的operator-admin流程，参数`--operator.action=coupon-permission --operator.id=...`；变更会审计并撤销现有会话，重新登录后才能管理精选池。协议见[券接口](../api/COUPON_INVENTORY.md)。

日期2026-10-09，V019先迁移；2026-10-10 起 V020 增加 `operator_account.can_onboard`（入驻审核权限，与经验审核 `can_review` 互不交叉，默认0）。本阶段运营入口在微信小程序，账号密码+短信二次校验、独立短会话；不是车主/商家身份。默认无账号与审核授权，无HTTP注册/提权。

## 离线管理

在可信服务器受控环境提供数据库与JWT配置，使用 `operator-admin` profile且 `spring.main.web-application-type=none` 启动后端JAR。命令只输出账号ID，凭据不得放在命令参数、仓库、聊天或日志。

- 新建：受控环境 `OPERATOR_ACCOUNT`、`OPERATOR_PASSWORD`（12字符以上且UTF-8≤72字节）、`OPERATOR_PHONE`、`OPERATOR_CAN_REVIEW=true/false`；可选 `OPERATOR_CAN_ONBOARD=true/false`（不写则默认 false）；启动参数 `--spring.profiles.active=operator-admin --spring.main.web-application-type=none --operator.action=create`。
- 禁用：相同启动profile与无HTTP参数，`--operator.action=disable --operator.id=...`。
- 审核权限变更：受控环境显式 `OPERATOR_CAN_REVIEW=true/false`，参数 `--operator.action=review-permission --operator.id=...`。
- 入驻权限变更：受控环境**必须**显式 `OPERATOR_CAN_ONBOARD=true/false`，参数 `--operator.action=onboard-permission --operator.id=...`。经验审核与入驻审核是两组互不交叉的权限，`can_review` 不会带来入驻审核能力，反之亦然。

禁止对生产自动创建账号；由有授权的运维人员运行。管理变更与审计原子提交，禁用/权限变更撤销全部该运营会话。不保存明文密码，不输出手机号、验证码或token。

## API与短信

POST `/api/auth/operator/code`：严格 `{account,password}`。POST `/api/auth/operator/login`：另加6位字符串sms_code。POST `/api/auth/operator/logout`：运营Bearer，严格 `{}`。未知/重复/尾随字段与query均拒绝，全部no-store。账号ASCII 3–64字符，大小写统一，密码不截断。

验证码bcrypt保存、5分钟有效、一次消费、用途绑定运营ID。发码60秒1次/每天5次，登录IP30次/15分钟、账号10次/15分钟。登录事务复核账号、消费验证码、创建15分钟JWT/数据库会话及最小审计；不发refresh，过期需再次两步认证。`can_review` 与 `can_onboard` 只供界面决定入口提示，服务端每次请求都实时复核数据库权限列。退出即时撤销。当前登录响应含 `user.can_review/can_onboard/can_catalog/can_coupon/can_invite/can_diagnose/can_diagnose_manage/can_points/can_assessment/can_analytics/can_price_monitor/can_community_curate/can_community_answer/can_community_manage/can_knowledge` 十五个布尔位，另有id/role；客户端严格校验并兼容已知旧版本形状，不接受任意权限字段。新增权限时须同步消费端；仅有诊断、积分、考核、价格、精选或经营权限的账号登录后进入相应工作区。

需配置正式 `OperatorSmsSender` 供应商适配器。当前未配置会返回503，不返回验证码，不使用固定代码或绕过；供应商失败回滚已发验证码状态。隔离测试可用合成供应商/验证码夹具，不能代替正式短信验收。车主/商家/技师接口不能使用运营身份。


R8a1新增独立`can_points`（默认0），离线动作`points-permission`和显式`OPERATOR_CAN_POINTS=true/false`，会话撤销及审计同事务。不得借邀请/券/诊断权限。见[积分配置操作](POINT_TASK_POLICIES.md)。

R8b1/V053新增独立`can_assessment`（默认0），离线动作`assessment-permission`和显式`OPERATOR_CAN_ASSESSMENT=true/false`，会话撤销及审计同事务。创建账号不自动授予，其他权限不能替代，无HTTP提权。见[考核政策操作](ASSESSMENT_POLICIES.md)。

R8b3a/V055新增独立`can_analytics`（默认0），离线动作`analytics-permission`和显式`OPERATOR_CAN_ANALYTICS=true/false`，会话撤销及审计同事务。创建账号不自动授予，其他权限不能替代，无HTTP提权。只读来源核验经营汇总，不代表已结资金，见[经营看板操作](BUSINESS_DASHBOARD.md)。

R8b3c1/V056新增独立`can_price_monitor`（默认0），离线动作`price-monitor-permission`与显式`OPERATOR_CAN_PRICE_MONITOR=true/false`，会话撤销及审计同事务。登录id/role及十一布尔位共13字段；其他权限不能替代，无HTTP提权，创建账号不自动授予。见[价格来源配置](PRICE_MONITOR_SOURCES.md)。

R8c1b1/V057新增独立`can_community_curate`（默认0），离线动作`community-curate-permission`与显式`OPERATOR_CAN_COMMUNITY_CURATE=true/false`，会话撤销及审计同事务；c1b1阶段登录id/role及十二布尔位共14字段。精选不授予审核、诊断或未来问答权限，无HTTP提权，来源说明仅运营可读，见[运营精选操作](CURATED_EXPERIENCES.md)。上文13字段为c1价格权限阶段历史快照。

R8c1b2a/V058新增独立`can_community_answer`和`can_community_manage`（均默认0），当前登录id/role及十四布尔位共16字段。离线动作`community-answer-permission`/`community-manage-permission`分别要求显式`OPERATOR_CAN_COMMUNITY_ANSWER=true|false`/`OPERATOR_CAN_COMMUNITY_MANAGE=true|false`；保持无HTTPoperator-admin流程，权限/审计/旧会话撤销同事务。管理队列不授予正文，处理须当前获派，旧精选/诊断权限不能替代。见[私有问答操作](PRIVATE_COMMUNITY_QUESTIONS.md)。b2a后端和b2b问答页面/精确登录落点已实现，只有管理或处理权限的账号分别进入问答受理或获派工作区；上文13/14字段是对应阶段历史快照。

R8c1b3a使用现有独立`can_review`仅审核车主另外确认提交的公开问答预览，不授予b2私有详情；`can_community_manage/answer/curate`不能替代。没有新增运营位，当前登录仍16字段。独立`COMMUNITY_PUBLICATION_ENABLED=false`默认关闭，见[公开问答操作](PUBLIC_COMMUNITY_QUESTIONS.md)。

## R8c3许可资料独立权限

V061增加can_knowledge，默认0，当前登录id/role及15布尔位共17字段。无HTTP离线动作knowledge-permission要求显式OPERATOR_CAN_KNOWLEDGE=true|false，变更、审计与原会话撤销原子；不继承目录、精选、问答或审核权限。仅此权限的账号进入/pages/operator/knowledge。客户端兼容明确旧16字段并将缺失权限清为false。上文16字段是V058/V059时点记录。操作见[公开图片与许可资料](PUBLIC_IMAGES_AND_KNOWLEDGE.md)。
