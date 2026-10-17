# 部署与运维文档

> 2026-10-17当前：[R8公开图片与许可资料操作](PUBLIC_IMAGES_AND_KNOWLEDGE.md)：逐图独立授权、人工审核、来源失效拒绝新读取、许可范围及独立can_knowledge；新开关默认关闭。

> [R8c1b3a公开问答后端操作](PUBLIC_COMMUNITY_QUESTIONS.md)：V059/124表、独立默认关闭公开开关；旧/测试消息不补正式来源，现有can_review仅审核公开预览，不读取私有详情。

> [R8c1b2私有问答页面与恢复](PRIVATE_COMMUNITY_QUESTIONS.md)：车主/当前获派详情、最小队列/政策四页；独立权限落点、永久创建及非创建23小时原请求恢复，公开仍b3。

> [R8c1b2a私有问答后端操作](PRIVATE_COMMUNITY_QUESTIONS.md)：V058/122表、两权限默认0与16字段登录、独立非实时受理及私有范围；本步无问答页面入口。

> [R8c1b1运营精选操作](CURATED_EXPERIENCES.md)：V057/117表、默认关闭can_community_curate、14字段登录、配置/停止/原键恢复和手动引用导入。

> [R8c1a同款经验详情与引用](EXPERIENCE_DETAIL.md)：原开关和审核授权、当前重验/撤回失效、应用内复制入口及登录返回，无新迁移。

> [R8b3c2价格比较操作](PRICE_COMPARISONS.md)：观察时点/来源/窗口核对，失效或改版未知、偏离仅需核对；无新迁移或自动处置。

> [R8b3c1价格来源操作](PRICE_MONITOR_SOURCES.md)：V056、默认关闭独立can_price_monitor、13字段登录及原请求恢复。

> [R8b3b本店客户关系操作](CUSTOMER_RELATIONS.md)：无新增迁移或长期档案授权，店长专属只读与原逐条授权入口。

> [R8b3a经营看板操作](BUSINESS_DASHBOARD.md)：V055默认关闭can_analytics，离线授权后读取；不是结算余额。

> [R8b2b月结确认、历史和账号恢复](MONTHLY_ASSESSMENT_VIEWS.md)；无新增迁移，115表。整个R8完成前源码与文档保留本地。

> [R8b2a月结、退款原月修订、永久原请求与V054](ASSESSMENT_SNAPSHOTS.md)：115表，无成绩回填，整R8完成前不上传。

> [R8b1考核独立授权、V053及政策操作](ASSESSMENT_POLICIES.md)；112表，无政策/等级种子，整R8完成前不上传。

> [R8a3c车主兑换确认、断线与账号恢复](OWNER_POINT_EXCHANGES.md)，沿用V052/111表，无新增迁移；整个R8完成前代码和文档保留本地。

> [R8a3b独立兑换开关、V052及永久回执](POINT_EXCHANGES.md)；本地98不同后端验收通过，整个R8完成前不上传源码或文档。

> [R8a3a兑换目录、V051及独立原请求恢复](POINT_EXCHANGE_CATALOG.md)；整个R8完成前所有源码和文档保留本地。

> [R8a2任务、余额与原日期恢复](POINT_LEDGER.md)；整个R8完成前保留本地，不做中间上传。

> [R8a1独立权限与任务配置操作](POINT_TASK_POLICIES.md)；整个R8完成前不上传中间内容。

> 整R7：[恢复/登录/配置安全窗口](../progress/R7_RECOVERY_MATRIX.md)，[统一验收与本地环境限制](../testing/LOCAL_R7_COMPLETION_ACCEPTANCE.md)。已有卷V044–V048升级先备份再顺序显式迁移，初始化挂载不替代升级。

> R7c3 第三步：[诊断消息使用和恢复](DIAGNOSIS_NOTIFICATIONS.md)。

> 当前R7本地实施：[R7a施工证据使用与权限](OWNER_SERVICE_EVIDENCE.md)；整个R7完成后才统一上传全部代码和文档。

> 整 R6 当前交付范围见[需求销项](../progress/R6_REQUIREMENT_CLOSURE.md)，统一上传与最终六项 CI 以 PR152 正文为准；下方保留各阶段说明。

> R6b1 V041/独立 can_invite 默认关闭和离线授权说明见[配置协议](../api/INVITE_REWARD_POLICIES.md)。本地实施，整R6完成后上传；已有卷需备份后显式迁移。

> R6a V040 邀请资格证据、默认关闭正式计数及手机号历史排查见[邀请运维](INVITATIONS_RUNBOOK.md)。

> 券V038/V039升级、独立权限、永久创建编号与支付/退款/券恢复见[R5运维](COUPON_TRANSACTIONS_RUNBOOK.md)。正式发布仍最后R9。

按交付文档 §10，记录环境变量、Compose/Nginx、监控告警、发布与回滚步骤。仓库已有 Compose 与 Nginx 配置并通过 CI 冒烟；这只验证临时容器能够启动，真实 HTTPS 环境、已有数据库 V002/V003 迁移、对象存储、告警以及发布/回滚演练尚未验收。当前外部条件见[依赖清单](../progress/S0_DEPENDENCIES.md)，M0 验收顺序见[下一步规划](../progress/NEXT_STEPS.md)。

本机 Docker 登录环境和新建 V001–V005 测试卷已实际联调；已有外部库仍须单独核对迁移。开发者工具可用本机 HTTP，正式自建后端须配置备案 HTTPS 通讯域名，详见[小程序开发与上线网络说明](MINIAPP_NETWORK_ENVIRONMENTS.md)。

真实登录环境可按[联调预检与验收清单](AUTH_INTEGRATION_RUNBOOK.md)检查入口和逐角色流程；清单中的未执行项仍须在测试部署中完成。

网络通道与真机登录：

- [小程序开发与上线网络说明](MINIAPP_NETWORK_ENVIRONMENTS.md)：模拟器、手机调试与常规真机的网络边界。
- [测试号真机登录清单](LAN_DEVICE_LOGIN_RUNBOOK.md)：用官方测试号 + 电脑局域网地址完成真机真实微信登录，无需域名/证书/云托管（L0–L8）。
- [微信云托管登录验收清单](CLOUDRUN_LOGIN_RUNBOOK.md)：`callContainer` 免配通讯域名路径的部署与验收（C0–C10），含"必须关闭公网访问"这条安全前提。
- [AI 管家接入清单](AI_CHAT_RUNBOOK.md)：DeepSeek 密钥配置、安全边界与 A0–A8 验收。
- [门店账号、员工与员工码运维](STORE_ACCOUNTS.md)：`MERCHANT_STAFF_ENABLED` 灰度顺序、店长/店员/技师账号发放、员工码签发轮换撤销、停用即时生效与故障处置对照。

2026-10-06 已核对本机环境与微信官方网络要求，形成[可信 HTTPS 部署与真机验收方案](../superpowers/specs/2026-10-06-https-device-design.md)。推荐复用现有接口部署自建 HTTPS；服务器、备案域名、证书和设备尚未确认，方案待复核，不代表已开通公网入口。

- [R6b2 奖励后台与迁移恢复](INVITATION_REWARD_RUNBOOK.md)：本地阶段，整R6完成后统一上传。

- [R6b3 本地海报与解锁使用](INVITATION_POSTER_AND_UNLOCK.md)：代码/文档本地保存，整R6验收后统一上传。

- [R7b 使用与运维](SERVICE_WORKSPACE.md)：本地完成，整R7完成后代码与文档统一上传。

- [R7c1 使用与投递恢复](SERVICE_NOTIFICATIONS.md)：本地阶段，整R7完成后代码与文档统一上传。

- [R7c2 诊断配置、处理与恢复](ASYNC_DIAGNOSIS.md)：本地完成，整R7完成后代码与文档统一上传。
