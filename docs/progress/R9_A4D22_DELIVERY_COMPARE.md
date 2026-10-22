# R9 A4d22 取车对比（接车基线 vs 完工证据）阶段执行记录

## 结果与范围

2026-10-22 完成实施与本地隔离验收。分支 `codex/r9-a4d22-delivery-compare`，基于 A4d21 的 `codex/r9-a4d21-contract-verdicts`；成果上传草稿 PR，远程 CI 结论以该 PR 实际结果为准。

本阶段**新写功能**，不是补证据：把 A4d21 结论表里 `not-implemented` 的 `POST /api/check/delivery/compare`（取车对比）实现出来，并给出四类证据。它也是多份缺口审计点名欠着的 R7a 交付项（"原文 §8.3 有接口，实际控制器未提供"）。

## 设计决策：只读投影，不造第二条写路径

`delivery_compare` 表在 V001 就存在但从未被写入。本阶段**故意不启用它**：

- 交付由**核销**完成（带支付、免佣、争议语义）；
- 若再让取车对比写一次表并推进状态，就会有两条路径竞争同一订单状态，锁序/幂等/审计都要重做；
- 对比只回答"证据齐不齐、能不能交付"，是可重算的投影，没有需要持久化的事实。

因此接口只读、不写库、不改状态；契约里原本要求的 `Idempotency-Key` 一并移除（读操作不该要幂等键）。风险由数据库不变量兜底：12 张表执行前后内容摘要逐字节不变，且 `delivery_compare` 始终 0 行。

## 实施与行为

- `DeliveryCompare`（服务）：本单车主或本店员工授权；聚合基线（接车单＋七槽位照片＋里程/油量/损伤）、防护（勾选项＋防护照）、报工（提交＋质检状态＋过程/故障件/完工/签字四类证据数），算出 `READY/INCOMPLETE`、`deliverable` 与 `missing` 缺口编码。
- `DeliveryCompareController`：严格正文 `{"order_id":正整数}`、拒绝任何 query、`40001/40100/40300/40400/43001/50300` 与单层信封 + `no-store`。
- 契约：把 `additionalProperties: true` 的空壳请求体改成真实 schema，补齐 `200/400/401/403/404/409/503`，标 `delivery-compare-implemented`，删幂等键参数；`docs/api/PICKUP_INSPECTION.md` 增补字段与缺口语义。
- 新增只读 Newman 套件 `scripts/delivery_compare_api_cases.py`（16 用例 / 60 断言 / 四类）与 9 项套件单测；接入 CI 运行与单测步骤、覆盖汇总。

## 实际验证

结论：PASS，319.130 秒。可提交脱敏记录：`docs/testing/reports/R9_A4D22_DELIVERY_COMPARE_2026_10_22.json`。

- 业务请求 16 / 断言 60 / 0 失败；匿名探针 338 / 1014 / 0 失败；验收说明见 `docs/testing/DELIVERY_COMPARE_ACCEPTANCE.md`。
- 13 项数据库检查全真（12 表摘要不变 + `delivery_compare` 0 行）；自有 MySQL / 后端容器 / 网络实际移除，源码哈希前后一致。
- 缺口是算出来的：缺证据订单返回 `[OWNER_CONFIRM, PROTECTION_PHOTO, FINISH, FAULT]`，而不是固定文案。
- 回归：套件单测 9 项、覆盖核对 32 项、契约结论门禁 11 项、接口生成资产 9 项全部通过。
- 覆盖核对：**35 套来源 / 316 项（共 352）具备四类历史证据 / 缺口 36 项**（本阶段 +1，`not-implemented` 从 7 降到 6）；全 API 验收仍为 false。

## 下一阶段与边界

剩余 6 项 `not-implemented` 与 10 项外部阻塞的处置建议：

1. `POST /api/check/delivery/compare` 的**页面**属于 R7a 剩余范围，与本后端配套做车主端取车对比页（证据齐备态、缺口提示、跳核销入口）；
2. `GET /api/admin/reconciliation` 需要先定财务口径与渠道账单来源，再立项；
3. OCR/VIN、真实渠道回调、云托管登录继续等外部条件，不以夹具冒充。

本阶段夹具为合成订单、接车单、防护与报工证据；无图像识别、无仪表盘 OCR，未做真机与页面渲染验收。新 PR 的 GitHub CI 状态以该 PR 实际结果为准。
