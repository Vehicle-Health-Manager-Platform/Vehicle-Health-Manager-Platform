# R9 A4d21 契约逐项结论（缺口销项）阶段执行记录

## 结果与范围

2026-10-22 完成实施。分支 `codex/r9-a4d21-contract-verdicts`，基于 A4d20 的 `codex/r9-a4d20-rating-quotes`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。

本阶段不改生产行为，只做三件事：给 37 项"无四类证据"的契约操作**逐项结案**、把结论做成**可校验的门禁**、顺手修正三处契约元数据不实。

## 结论分布

352 项契约 = **315 项有四类历史证据** + **37 项有明确结论**；未解释操作 **0** 项。

| 结论 | 项数 | 代表条目 |
| --- | --- | --- |
| `superseded`（已被具名实现替代） | 15 | `/api/home/dashboard`→`/api/vehicle/{vehicle}/health-dashboard`；`/api/notifications`→`/api/owner/notifications`；`/api/order/verify`→`/api/merchant/orders/{id}/redeem`；`/api/merchant/apply`→`/api/merchant-applications` |
| `internal-only`（服务内部事务完成） | 1 | `/api/coupon/release`：订单取消/退款事务内的 `CouponRecovery`，无独立 HTTP 入口 |
| `blocked-external`（依赖外部上游） | 10 | `/api/ai/chat`、`/api/knowledge/answer`（大模型）；商家/运营验证码与登录、手机号绑定、技师绑定、微信登录、`sms-code`（微信/短信） |
| `not-implemented`（尚未实现） | 7 | `/api/admin/reconciliation`、`/api/check/delivery/compare`、`/api/vehicle/ocr-license`、`/api/vehicle/vin-decode`、真实渠道支付/退款回调、`/api/auth/cloud-login` |
| `no-success-path`（无 200 路径） | 1 | `/api/merchant/orders/{id}/actions`：四个动作分别 `43001`/`43004`/`43005`/`43006` |
| `dev-only`（仅本地演示） | 3 | `/api/dev/token`、`/api/demo/vehicles/{id}`、`/api/demo/vehicles/{id}/mileage` |

完整明细与每条的判断依据见生成文件 [契约逐项结论](../../api/CONTRACT_RECONCILIATION.md)。

## 实施与行为

- 新增 `docs/api/CONTRACT_VERDICTS.json`（人工结论表）与 `scripts/reconcile_contract.py`（对齐 + 生成 + `--check` 门禁）。
- 门禁 fail-closed：结论必须覆盖全部"未同时具备实现标记与四类证据"的操作；**不得**出现在已有证据的操作上（防止把真实缺口用一句"已替代"抹掉）；`superseded` 的替代必须在契约内且带实现标记；契约总数必须为 352、证据数不得低于 315。
- 三处契约元数据修正（不改路径、不改行为）：`/api/ai/chat` 标为 `ai-chat-implemented-requires-provider`，`/api/input-materials/{purpose}/{vehicleId}/upload` 与 `.../{id}/access` 标为 `controlled-input-material-*-implemented`。
- 覆盖核对器只在报告里增加一行指向结论表，**数字口径不变**：仍报 315 有证据 / 37 无证据，不把"有结论"算成"有证据"。
- 重新生成 `acceptance_inventory.json`（含 352 条实现状态）与 `api-coverage.json`（契约哈希）以保持一致。

## 实际验证

- `python scripts/reconcile_contract.py`：PASS，`352 = 315 evidenced + 37 verdicts`；`superseded=15, internal-only=1, blocked-external=10, not-implemented=7, no-success-path=1, dev-only=3`。
- `python scripts/reconcile_contract.py --check`：PASS（生成文件与提交文件逐字节一致）。
- `test_reconcile_contract.py`：**11 项**通过，覆盖缺结论、未知/重复操作、小写方法、`superseded` 无替代/替代在契约外/替代未分类/替代等于自身、非 `superseded` 携带替代、结论落在已有证据操作上、非法结论类型、过短理由、契约总数与证据下限守护、`--check` 检出过期文件。
- 回归：`test_api_coverage_summary.py` 32 项通过（仍然是 34 套 / 315 项 / 缺口 37）。

## 下一阶段与边界

"补证据"这条线到此为止：剩下的 37 项不是缺证据，而是缺功能或缺外部依赖。下一阶段起按结论表推进：

1. `not-implemented` 的 7 项逐项做产品决策（是否仍在路线图；`ocr-license`/`vin-decode` 需先定供应商与合规存储方案）；
2. `blocked-external` 的 10 项等真实微信/短信/大模型接入后补证据，接入前不得以夹具冒充；
3. `no-success-path` 的通用商家动作接口如需恢复写入，应先改契约并移除专用入口的分流，而不是补一个 200。

结论是判断而非验收；全 API 验收仍为 false。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。
