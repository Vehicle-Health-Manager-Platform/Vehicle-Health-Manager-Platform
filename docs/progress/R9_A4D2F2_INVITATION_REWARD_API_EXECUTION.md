# R9 A4d-2f2 奖励政策、资格冻结与后台发券验收

## 计划与设计

2026-10-19：承接PR178 head 51c0769，六项CI成功、backend待结束。本轮A4d-2f2奖励政策/冻结/后台发券：8奖励本域接口+6串联码/券管理及到账接口，共14接口四类真实HTTP场景。单JUnit启动真实Spring和全新MySQL/62迁移131表，HTTP发布政策前后通过生产InvitationRegistration bean注入明确合成的身份/手机号上游结果，真正执行资格和冻结；不SQL种奖励批次/发券结果、不新增生产夹具接口。实际定时worker开启，验证UNKNOWN_POLICY不追补、审计失败双向回滚/重试、停用旧政策不改冻结条款、缺库存不部分发、停券WAIT_SOURCE、员工与车主ID重叠不误发、隐私及幂等。Python独立清单审计JUnit/源码/真实方法路径角色状态/数据库检查和容器移除，补CI/定向回归/中文文档/脱敏报告并上传草稿PR。之后梳理剩余API缺口→A5→B正式微信/电话/外部供应商→C真机。

选用真实Spring随机TCP端口/独立MySQL的JUnit旅程。Newman单阶段SQL预置无法覆盖HTTP发布前后内部资格冻结；新增测试旅程能够使用生产bean接收合成上游结果，保持HTTP业务链和实际定时worker。没有新增生产身份绕过。注册/电话核验仍未调用真实微信，合成VERIFIED_WECHAT仅协议夹具。

先码与无政策资格→HTTP创建/编辑/发布券与OWNER政策→真实资格冻结→审计故障触发定时worker回滚→停用/换政策后重试旧快照→新快照缺库存/来源停用→STAFF单边奖励及同ID隔离→HTTP奖励/到账/政策隐私和幂等→清理与源码绑定→CI/文档/草稿PR。测试只重设next_attempt_at模拟生产5分钟退避到期，不修改生产退避时长。原始JWT/推广码/日志不提交。

## 证据门禁

144条真实HTTP场景，8奖励本域和6串联码/券管理/到账接口共14项，每项正常/边界/错误/未授权。Python独立清单按方法+路径计接口，避免GET/POST同路径合并；严格核对实际方法/完整路径query/角色/状态/顺序，要求16项资格/冻结/回滚/定时重试/隐私/唯一性检查、完整JUnit执行及独立MySQL容器实际移除。

Java执行摘要与Python审计均绑定主程序所有Java和资源配置、Java测试、62迁移、pom及本轮运行器字节。运行前后必须稳定、证据源码摘要匹配；只发布白名单字段，无JWT/手机号/推广码/原始响应。CI clean verify执行旅程后--check-only只检查本次JUnit和证据，失败/跳过/不完整/源码变化/容器未移除不能通过。

## 执行记录

首轮构建期间发现独立审计按路径合并GET/POST不够严格，改为方法+路径；补足主程序资源配置的源码绑定。因输入已变化，首轮仅诊断旅程，不作为最终PASS证据；修正完成后必须完整重跑固定代码。9项新增/27项既有Python证据门禁测试已通过。

首轮190.64秒FAIL：首张券创建返回400，CouponInput.time明确只接受整秒UTC，测试的Instant.now含纳秒。修正测试为截断至秒；接口契约不变。另加强最终库存断言（现金券issued=3、服务券issued=0）。首轮JUnit/日志保留.cache，固定代码后完整重跑。

## 复跑与后续

`python scripts/run_reward_acceptance.py --docker` 在本地Docker运行；CI full clean verify后 `python scripts/run_reward_acceptance.py --check-only` 校验。`python -m unittest discover -s scripts -p test_reward_acceptance.py` 验证证据门禁。真实微信/电话核验仍在B阶段；本轮不调用供应商或真实资金，不新增生产身份开关。下一先梳理剩余API业务缺口，再A5文档→B真实外部接入→C真机，全量API业务验收和性能延迟达标仍未完成。


## 最终执行结果

2026-10-19：A4d-2f2 本地PASS（184.0秒）：14接口144真实HTTP请求、16资格/快照/事务/隐私检查通过。实际生产资格bean接收合成上游结果，7注册、4批次（2 COMPLETE/1 UNKNOWN_POLICY/1 WAIT_SOURCE）；真实定时worker处理审计失败整对回滚/旧政策快照重试、缺库存不部分发、来源停用及员工单边奖励。3到账券/3发券/3库存事件，现金券issued=3、服务券0，3政策均STOPPED；旧回执不复活、不追补未知政策、完成批次不重发，员工/车主同ID不误发、仅本人券ID可见。8项Java定向（含144请求旅程）及9项新增/27项既有Python、编译/diff通过；源码含资源配置绑定、执行前后稳定，独立MySQL容器移除成功。前置PR178后端CI待结束。身份/手机号仅合成协议结果，0真实供应商/支付/积分事件；全量API验收和性能延迟达标仍未完成。下一人工诊断服务/处理队列/消息通知→受控资料与施工授权等剩余独立缺口→A5→B正式微信/电话/外部服务→C真机。

- [脱敏实际HTTP/worker报告](../testing/reports/R9_A4D2F2_INVITATION_REWARD_API_2026_10_19.json)
- [Java定向回归摘要](../testing/reports/R9_A4D2F2_JAVA_REGRESSION_2026_10_19.json)
- 源码SHA256：`51174c7b542debe3fc716b0e238d269b46b958606a297da8d1bee08fa4b26e0a`。Java证据/Python审计一致。
- 本轮新增真实验收资产、失败注入和CI门禁，生产邀请逻辑按既有实现执行。只重设测试任务重试到期时间，生产5分钟退避未改。
- 本轮没有重新运行全量338匿名探测；14接口各有未授权场景，完整后端和所有业务域以新PR完整CI为准。
- 对照现有案例定义与OpenAPI，人工诊断服务/队列/通知、受控资料和施工授权等仍缺业务场景。下一先接车主请求→运营分派/答复/关闭→本地消息已读，外部AI继续后置。此清单比较不是全量验收通过声明。


## GitHub交付

本阶段成果已上传 [PR179](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/179)，验收代码 `9290538`，依赖PR178；本轮完整远程CI待结束，保持草稿，不合并或部署。
