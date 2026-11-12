# API 覆盖口径统一规格（A1）

## 背景

API 覆盖索引漏掉识别 HTTP 套件，导致生成物显示 317/362 项有四类历史证据、45 项有结论，而 2026-10-30 的验收口径为 327/362 与 35 项。差额对应 10 个识别 API 操作。

## 范围与验收条件

- 将识别 HTTP 审计报告纳入 `scripts/summarize_api_coverage.py` 的受校验输入。
- 报告必须包含 runner 清单中的 7 个测试套件，按当前 OpenAPI 契约映射为 10 个唯一操作；每项都须有 normal、boundary、error、unauthorized 四类证据。
- VIN 与本地 VIN 两个测试套件可覆盖同一 `POST /api/vehicle/vin-decode` 操作，但其他重复操作必须拒绝。
- 只将经过源哈希、JUnit 结果、请求/响应哈希、权限检查及 SQL 检查核验的记录计为**历史证据**。
- 生成覆盖索引和契约逐项结论，使数字收敛至 327 项历史四类证据、35 项明确结论。
- `full_api_acceptance_passed`、`production_acceptance_passed` 与当前重跑计数仍为 false/0。

## 证据边界

识别套件使用明确的本地 provider fixture 和合成请求。该证据可用于描述本地 HTTP 软件行为，不证明真实 OCR/ASR/VIN 供应商、授权样本、真机或生产环境已验收。历史报告不等于当前版本重跑。

## 交付物

- 汇总器、契约结论校验器及对应回归测试。
- [API 覆盖索引](../../testing/API_COVERAGE.md)、[结构化覆盖数据](../../testing/api-coverage.json)和[契约逐项结论](../../api/CONTRACT_RECONCILIATION.md)。
- [A1 测试与证据记录](../../testing/API_COVERAGE_RECONCILIATION_2026_11_12.md)。
