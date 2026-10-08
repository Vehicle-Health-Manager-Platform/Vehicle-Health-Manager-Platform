# A6 防护与报工阶段总记录

日期：2026-10-08。目标链路：商家防护→本人完整报工→本人质检签字→待核销。用户已授权实施、编码与每阶段文档/GitHub 上传。

| 子阶段 | 成果与证据 | GitHub |
| --- | --- | --- |
| A6.1 | V014/54 表、私有证据、严格门禁、幂等/事务/竞争、禁用通用完工；后端 348 项 | [PR #42](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/42)，最新 b383f5a [六项 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37791373515) 通过；[执行](A6_1_SERVICE_BACKEND_EXECUTION.md) |
| A6.2 | 商家防护/只读施工、技师报工/手写 PNG 签名；小程序 169 项、微信/H5 构建、gstack 实际交互 | [PR #43](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/43)，9254e86 [六项 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37797778774) 通过；[执行](A6_2_SERVICE_UI_EXECUTION.md) |
| A6.3 | 可复现真实 HTTP 65/65、实际上传/原字节/权限/争议/缓存/双审计；文档收口 | [PR #44](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/44)，代码/验收提交 504e99a；最终六项检查见 [PR #44 Checks](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/44/checks)；[执行](A6_3_SERVICE_E2E_EXECUTION.md)、[验收](../testing/LOCAL_SERVICE_WORK_ACCEPTANCE.md) |

所有成功施工写入不可修改；未防护、证据不齐、非本人、未确认、未解决争议都不能送核销。车主确认 1/3 均支持。签字只迁 PENDING_VERIFY，不自动完成或核销，不回写档案/卡片。文件 ID 进入业务记录，短期 URL 只在访问响应返回；原图/原键和迟到回调均有客户端验证。

A6.1–A6.3 代码与本机完整链路已完成并按阶段上传，PR 均未合并；最新 GitHub 六项检查见各 PR Checks。后端 348、小程序 169、HTTP 65 属于不同层次，不能相加作为单套测试数。真实相机、真机触摸/权限与真实微信、本机自签证书直连图片预览仍待独立验收。

[设计](../superpowers/specs/2026-10-08-service-work-design.md)、[中文计划](../superpowers/plans/2026-10-08-service-work.md)、[接口](../api/SERVICE_WORK.md)。下一业务阶段 A7.1 核销验码/防猜测/状态及审计；退款/取消争议订单也归 A7 独立规格，二次异议与超时不在 A6。下一阶段尚未开始编码。

## 堆叠合并

#41 base 仍为 codex/a5-dispatch-e2e。先完成 #35/#36 依赖，再按 #37→#38→#39→#40→#41→#42→#43→A6.3 顺序，在前一依赖合入 main 后逐支改当前 base 为 main、核对差异与 CI，再合并。**没有执行合并、没有提前批量改 base**。
