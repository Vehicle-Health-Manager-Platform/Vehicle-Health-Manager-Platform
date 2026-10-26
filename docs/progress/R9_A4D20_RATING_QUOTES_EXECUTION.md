# R9 A4d20 车主公开星级项目评分报价读取阶段执行记录

## 结果与范围

2026-10-22 完成实施和本地隔离验收。分支 `codex/r9-a4d20-rating-quotes`，基于 A4d19 的 `codex/r9-a4d19-onboarding-files`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。

本阶段补齐 `GET /api/service/project/{id}/rating-quotes` 的四类执行证据——这是覆盖清单里**最后一个仍可闭合**的已实现缺口。同时把剩余 37 项缺口按成因登记，避免继续把它们当成"待补证据"排队。生产业务代码零变更，只改了商家订单契约文档里与实现不符的一段。

## 实施与行为

- 新增 Newman 套件 `scripts/rating_quotes_api_cases.py`：1 个操作、18 个用例、67 条断言，四类各 2/6/6/4。
- 套件为只读：每个请求都断言不得带正文与 `Idempotency-Key`，执行前后对 14 张表做内容摘要比对；行数精确校验 `order=2 / payment=2 / order_review=2 / review_publication=2 / merchant_project=6`。
- 夹具刻意构造三类"应被排除"的报价（下架、最新版本价为 0、项目 `status=0`）与一条跨区域门店，用来证明投影的过滤与排序规则真的生效，而不是恰好数据对。
- 评分来自"合格公开评价"：已完成订单 + `WECHAT` 成功支付 + 非测试模式核销 + `test_mode=0` 评价 + `consented=1` 授权，且无支付异常/未结争议/退款。零样本时 `average_rating` 为 `null`。
- 接入 CI：`--rating-quotes-cases` 运行步骤、`test_rating_quotes_api_cases.py` 单测步骤、覆盖汇总 `POSTMAN` 新登记一套。

## 剩余缺口的成因登记（37 项）

| 成因 | 数量 | 说明 | 能否补证据 |
| --- | --- | --- | --- |
| 契约映射 `unspecified`，代码无实现 | 27 | `/api/admin/assessment`、`/api/admin/dashboard`、`/api/admin/merchant/audit`、`/api/admin/reconciliation`、`/api/admin/standard-project`、`/api/ai/chat`、`/api/ai/plan`、`/api/auth/cloud-login`、`/api/auth/sms-code`、`/api/auth/staff-login`、`/api/check/delivery/compare`、`/api/coupon/release`、`/api/coupon/reserve`、`/api/demo/vehicles/{id}`、`/api/dev/token`、`/api/home/dashboard`、`/api/home/reminders`、`/api/merchant/apply`、`/api/merchant/{id}`、`/api/merchant/{id}/reviews/summary`、`/api/notifications`、`/api/order/verify`、`/api/payments/callback/{channel}`、`/api/refunds/callback/{channel}`、`/api/refunds/create`、`/api/vehicle/ocr-license`、`/api/vehicle/vin-decode` | 否：造用例只得到 404/400，不产生业务证据 |
| 需真实外部上游 | 7 | `merchant/code`、`merchant/login`、`operator/code`、`operator/login`、`phone/bind`、`technician/bind`、`wx-login`：需真实微信上游或生产短信适配器 | 否（当前形态） |
| 已实现但**无 200 路径** | 1 | `POST /api/merchant/orders/{id}/actions`：四个动作分别被控制器与服务层拦截为 `43001`/`43004`/`43005`/`43006`，未知动作 `40001` | 否：已改契约文档登记偏差 |
| 已实现但需大模型供应商 | 1 | `POST /api/knowledge/answer`：未配置即 `503`，配置后需真实上游生成 | 否（当前形态） |
| 本地示例端点 | 1 | `POST /api/demo/vehicles/{id}/mileage`（`local-example`） | 不计入生产验收 |

## 契约与实现偏差（本阶段唯一文档变更）

`docs/api/MERCHANT_ORDERS.md` 原本写"成功返回本店订单详情投影加 `action`/`from_status`/`changed`"，但实测该接口对四个动作全部返回 409，200 不可达。已改为逐动作列出实际错误码与正确入口，并注明"因此该接口的验收证据只有 boundary/error/unauthorized，normal 不可达；不以伪造 200 补齐"。生产行为未改。

## 实际验证

结论：PASS，201.923 秒。可提交脱敏记录：`docs/testing/reports/R9_A4D20_RATING_QUOTES_2026_10_22.json`。

- 业务请求 18 个 / 67 条断言 / 0 失败；匿名探针 338 请求 / 1014 断言 / 0 失败。
- 验收说明见 `docs/testing/RATING_QUOTES_ACCEPTANCE.md`。
- 自有 MySQL / 后端容器 / 网络实际移除，源码哈希执行前后一致。
- 回归：新增单测 10 项、覆盖核对 32 项全部通过。
- 覆盖核对：34 套所选记录、352 项契约中 315 项具备完整四类历史证据、缺口 37 项；全 API 验收仍为 false。

## 下一阶段与边界

剩余 37 项按上表分类：27 项需先补实现或修订契约，7 项需真实上游，2 项受实现设计封堵，1 项是本地示例。下一阶段建议把这些"缺口"转成**功能缺口**逐项立项（例如 `/api/home/dashboard`、`/api/notifications`、`/api/refunds/create` 是否仍在路线图内），而不是继续在证据清单上找可跑项。

本阶段夹具全部合成；未覆盖真实评价/支付/核销链路、真实行政区划、真机与外部云存储。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。


阶段已上传[草稿PR211](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/211)，实现提交 `38ad29f`，base=`codex/r9-a4d19-onboarding-files`，依赖PR210；远程CI已触发，结果以PR实际检查为准，未合并。
