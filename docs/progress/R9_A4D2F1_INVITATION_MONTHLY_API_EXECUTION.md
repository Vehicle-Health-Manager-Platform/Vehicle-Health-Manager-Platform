# R9 A4d-2f1 邀请关系、月度进度与诊断解锁资格验收

## 范围和执行顺序

2026-10-19：前置PR177 head b82fa93 八项CI全绿。邀请域拆为A4d-2f1关系/月度资格14接口与A4d-2f2奖励政策/冻结/发券。当前先车主/商家推广码创建读取、记录/进度及本人归因9接口，再运营诊断解锁政策CRUD/状态和本人资格5接口。采用既有真实HTTP/MySQL隔离运行器；注册及手机号资格仅SQL合成夹具，不宣称真实微信接入。验证推广码唯一及回执、跨车主门店/角色、11个有效邀请跨周期、旧月份与TEST/UNKNOWN/PENDING/REJECTED不解锁、规则CAS/停用/未来时窗/旧回执与服务不可用状态。新增证据审计、CI、定向Java/Python回归，完成后中文文档/脱敏报告上传草稿PR；随后奖励冻结与后台发券，其他缺口→A5→B外部→C真机。

复用既有隔离Newman/真实后端/62迁移131表，逐条审计实际方法/路径/query/JWT/状态/正文/幂等键；新增JUnit旅程会重复运行设施。SQL仅造只读归因与资格边界数据，推广码/政策变更均实际HTTP；不通过新增生产接口注入资格。原始环境/JWT/推广码仅.cache，提交脱敏摘要。

先检查空推广码和固定邀请夹具→创建码与同键/异键唯一→月度统计及他人隔离→政策草稿编辑/发布/停用和旧回执→资格锁定/可用时窗/服务不可用→数据库只增预期码/政策、无奖励发放→CI/文档/草稿PR。下一A4d-2f2单独完成奖励政策与后台发券。

## 调查记录

初始路径猜测InvitationPolicies.java、V041__invitation_attribution.sql、V040__invitation_registration.sql不存在；rg --files定位实际InviteRewardPolicies/InviteDiagnosisPolicies及V040__invitation_attribution.sql，改读实际文件。未修改业务代码或执行验收。

首轮真实验收203.86秒PASS。复核SQL夹具发现既有码创建时间初始设为当前时间、晚于上月注册记录；将其改为最早注册前一天，避免夹具时间线不可能成立。业务逻辑未改，重新完整运行以绑定最终源码，首轮报告保留.cache不充当最终版本证据。

Java定向执行3分23秒PASS：JdbcInviteDiagnosisPoliciesTest 48项（含继承的邀请/奖励/券回归）、InvitationHttpTest 5项、InviteDiagnosisHttpTest 2项、InvitationLoginHttpTest 6项，共61项，失败/错误/跳过均0。未改生产代码或放宽测试条件。

## 场景和夹具边界

14接口136请求462断言，每接口正常/边界/错误/未授权全覆盖。隔离SQL夹具19条归因：车主16条（当月合格11、上月合格1、PENDING/UNKNOWN/TEST/REJECTED各1），员工当月合格3；15条合成手机号证据和2个既有推广码。实际HTTP为另一车主及另一门店新建2码，既有码同键/异键重放不增码；记录和进度始终按本人/本店隔离，TEST归因不会因创建码而被升级。

当月11个有效邀请显示完成1个十人周期、当前tier2、下一门槛13；员工3个显示tier3/门槛6。解锁政策先13改12发布，11个邀请仍LOCKED；停用后旧发布回执不复活，再发11门槛规则得到ELIGIBLE，但未配置诊断服务，service_available必须false。最后替换为未来规则，立即NOT_STARTED且无可用截止时间。Java时钟用例另验证北京时间月界和有效期端点，不把SQL夹具当真实微信注册结果。

9项新增证据测试及27项既有脚本回归通过，覆盖运营权限/门店JWT替换、正文门槛与版本/时窗变化、原幂等键丢失、重复query、方法/状态/跳过/缺失断言。默认集合实际Newman运行0请求0失败。真实注册、手机号核验、奖励政策及本轮HTTP发券另在后续阶段验收；本轮INVITE_VERIFIED_IDENTITY_ENABLED/INVITE_REWARD_ENABLED和积分/外部AI开关保持false。

## 复跑

生成资产：`python scripts/invitation_api_cases.py`；真实后端验收：`python scripts/run_api_acceptance.py --invitation-cases`；证据门禁：`python -m unittest discover -s scripts -p test_invitation_api_cases.py`。CI clean verify后以--ci-built-jar --invitation-cases检查新构建来源并使用新数据库；与其他业务suite互斥。原始日志、环境、推广码和JWT不提交，仅提交脱敏report.json。


## 最终执行结果

2026-10-19：前置PR177八项CI全绿。A4d-2f1 本地PASS（209.61秒）：14接口136业务请求/462断言、338匿名请求/1014断言通过。实际新建2推广码，最终4码唯一；19条SQL合成归因/15条电话证据不变。车主当月11合格/1周期、员工3合格；上月和TEST/UNKNOWN/PENDING/REJECTED不解锁，跨车主/门店隔离，TEST不因建码被升级。3解锁政策最终STOPPED revision4/STOPPED revision3/PUBLISHED revision2；旧回执不复活，12门槛LOCKED→11门槛ELIGIBLE但service_available=false→未来规则NOT_STARTED。源码稳定、后端/MySQL/网络清理成功；61项Java定向与9项新增/27项既有Python测试、编译/diff通过，新集合默认实际0请求0失败。真实微信注册/电话核验与本轮HTTP奖励发券未验，0奖励批次/发券/积分事件/支付。下一A4d-2f2奖励政策/冻结与后台发券→其余缺口→A5→B真实外部接入→C真机；全量API业务验收和性能延迟达标仍未完成。

- [脱敏实际接口报告](../testing/reports/R9_A4D2F1_INVITATION_MONTHLY_API_2026_10_19.json)
- [Java定向与默认禁发摘要](../testing/reports/R9_A4D2F1_JAVA_REGRESSION_2026_10_19.json)
- 本轮交付真实HTTP验收资产和CI门禁，生产路径按既有邀请实现执行；完整远程CI以新PR为准。
- 源码SHA256：`319ce95b3f62f5f9ee933568d548cb8df1f5f006c9744c79d85635ca2cf64443`；集合SHA256：`a0512cacfba5e3da22cff67a2e8b91acb4b5c03242126124fb143593a913d582`。


## GitHub交付

本阶段成果已上传 [PR178](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/178)，验收代码 `dc25914`，依赖PR177；本轮完整远程CI待结束，保持草稿，不合并或部署。
