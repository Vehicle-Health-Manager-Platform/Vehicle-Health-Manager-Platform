# 取车对比（接车基线 vs 完工证据）设计

## 为什么做这个

契约逐项结论（A4d21）把 37 项缺口归档后，`not-implemented` 只剩 7 项。其中 6 项要么依赖外部上游（OCR/VIN/真实渠道回调/云托管登录），要么是大口径财务报表；唯一既有明确交付要求、又能完全离线实现的是 `POST /api/check/delivery/compare`——多份缺口审计都点过它：「原文 §8.3 出现在 OpenAPI，但实际后端控制器未提供该业务」「车主完整施工展示及取车对比欠缺」「取车对比有实际后端与页面」是 R7a 的验收条件。

## 方案：只读投影，不造第二条写路径

`delivery_compare` 表在 V001 就存在（`order_id`/`photo_url`/`compare_result`/`owner_confirm`），但从没有代码写入。核心决策是**不启用它的写路径**：

- 交付本身由**核销**完成（核销带支付、免佣与争议语义，`OrderRedemption`）；
- 如果再让"取车对比"写一次 `delivery_compare` 并推进状态，就有两条路径竞争同一个订单状态，锁序、幂等与审计都要重做；
- 对比回答的是"证据齐不齐、能不能交付"，本质是一个**投影**，没有需要持久化的业务事实。

所以本阶段实现为只读投影：不写库、不改状态、不需要幂等键（契约里原本要求的 `Idempotency-Key` 已随之移除）。`delivery_compare` 表保持空表，并用数据库不变量断言它一直是空的——**证明的是"没有偷偷写"**。

## 投影口径

| 部分 | 来源 | 关键规则 |
| --- | --- | --- |
| 基线 | `pickup_check` + `pickup_check_file` | 里程、油量、损伤状态与损伤条目数、排序后的七槽位名、车主确认态；`mileage_delta` 相对接车时写入的 `mileage_baseline` |
| 防护 | `repair_protection` + `service_evidence_file(kind=PROTECTION)` | 是否提交、勾选项数、是否有防护照 |
| 报工 | `service_report_submission` + `technician_report` + `service_evidence_file` | 是否提交、质检状态（`MISSING/SUBMITTED/SIGNED`）、过程/故障件/完工/签字四类证据数、`no_fault_parts`/`no_parts` |
| 结论 | 上述合并 | `READY`（`missing` 为空）或 `INCOMPLETE`；`deliverable` 追加要求订单处于 `PENDING_VERIFY` |

缺口编码是固定七类，`FAULT` 只在"既没照片也没声明无故障件"时出现——这样商家不会因为没坏件就被判不齐备。

## 权限

本单车主或本店 `MERCHANT/STAFF`。技师（`TechnicianActor` 不是 `MerchantActor`）、他人车主、他店一律 `404`（不暴露订单存在性），其它角色 `403`。与接车/施工附件的既有语义一致。

## 错误码

`40001`（正文不是恰好 `{"order_id":正整数}`、或携带 query）、`40100`、`40300`、`40400`、`43001`（接车检查未完成，没有基线）、`50300`。全部 `no-store`。

## 契约变更

`/api/check/delivery/compare` 原本是 `additionalProperties: true` 的空壳请求体，且要求幂等键。本阶段把它写成真实契约：正文 schema（`order_id` 整数，1..2^53-1，不允许其它字段）、`200/400/401/403/404/409/503` 语义、`x-implementation-status: delivery-compare-implemented`、`x-roles`，并删除幂等键参数。

## 边界

- 页面未接入：车主端取车对比页仍属 R7a 剩余范围，本阶段只交付后端与四类证据。
- 对比是证据齐备性的判定，**不做图像差异识别**（没有像素比对，也不声称能发现新损伤）；仪表盘 OCR 仍未实现。
- `delivery_compare` 表保留但未使用，是否删除留给后续 schema 清理，不在本阶段动表。
- 全 API 验收仍为 false；本阶段把该操作从"未实现"变为"有可复现四类证据"。
