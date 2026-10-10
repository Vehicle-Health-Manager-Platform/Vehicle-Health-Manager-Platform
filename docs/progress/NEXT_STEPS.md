# 下一步规划

更新日期：2026-10-09。需求与范围以 Spec 和对应阶段规格为准。

## 当前交付

A4–A6 的 #35–#44 已全部按顺序改回 main 后合并，main 基线 `43903c9` 六项 CI 全绿。A7.1 已完成规划、核销后端、小程序页面与真实本机联调，逐阶段上传 [#45](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/45)→[#46](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/46)→[#47](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/47)→[收口 #48](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/48)。规格/后端/页面均六项 CI 全绿，后端 369/369、小程序 181/181、微信/H5 构建、V015 重放 55 表；真实 HTTP 51/51、gstack 实际核销完成。见[A7 总记录](A7_REDEMPTION_EXECUTION.md)、[本机验收](../testing/LOCAL_ORDER_REDEMPTION_ACCEPTANCE.md)。核销为隔离测试付款，未真实扣款。

## 下一业务步骤：A7.2a 本人订单评价

先补中文规格与计划，复核后分阶段编码、验证、文档和上传。最低验收目标：

- 仅车主本人、已完成且有可信核销记录的订单可评价；争议、取消、历史缺核销记录不能凭状态补资格。
- 同订单唯一评价，UUID 幂等、并发只保存一次，审计与评价同事务；测试核销评价仍明确测试来源。
- 评分范围、文字/图片、匿名与展示范围、修改窗口先对照来源文档确定；不把未确认的公开展示规则作为既定需求。
- 本人查询/提交、无权限隔离、小程序失败重试与身份切换成立；每阶段中文契约/OpenAPI/迁移/验收同步上传。

## 后续拆分

| 阶段 | 范围与验收目标 |
| --- | --- |
| A7.2a | 本人唯一订单评价、授权、幂等、页面与审计 |
| A7.2b | 施工证据写回车辆档案，来源可追溯、唯一写回、失败可恢复，保留用户已有档案 |
| A7.2c | 经验卡片生成与发布规则，脱敏、车主授权、去重、失败恢复及内容审核边界 |
| 资金与争议终结 | 正式收款、退款/取消争议订单的资格、资金通道、回调/对账、状态与审计独立规格；不能用 LOCAL_TEST 代替真实退款 |

第二次异议、超时自动处理继续不实施；历史缺争议单/核销记录不自动回填。佣金、增长能力、运营后台与 AI 后续能力按角色缺口另排。

## 阶段上传与堆叠合并

每阶段：验证→中文文档→提交→推送 codex/ 分支→PR→最终 CI。前序未合并时指向直接依赖分支，后续按顺序将下一支 base 改回 main，复核 head/差异/CI 后再合并，禁止合入上一功能分支。当前 #45→#46→#47→[收口 #48](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/48) 等待合并授权；本次未合并 A7 PR 或部署生产。

## 独立验收

真实相机、手机真机/真实微信账号、本机测试证书下直连图片预览、正式短信、微信收款/退款、正式公网与运维仍分别待验收。继续本机优先推进，外部凭据和手机操作不伪造通过。

- [真实相机](../testing/REAL_CAMERA_ACCEPTANCE.md)、[真机登录](../operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。
- [外部依赖](S0_DEPENDENCIES.md)、[角色缺口](ROLE_GAP_ANALYSIS.md)、[阶段交付规则](STAGE_DELIVERY.md)。
- A5/A6 历史阶段执行见 [A5 总记录](A5_DISPATCH_EXECUTION.md)、[A6 总记录](A6_SERVICE_WORK_EXECUTION.md)，历史堆叠状态不代表当前 main 状态。
