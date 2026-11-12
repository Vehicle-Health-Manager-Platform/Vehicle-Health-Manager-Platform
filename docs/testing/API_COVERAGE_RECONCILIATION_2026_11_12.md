# A1 API 覆盖口径收敛测试记录

## 结果

- OpenAPI 契约：362 项，其中 348 项受保护。
- 四类用例定义：327 项；匹配历史四类证据：327 项；明确结论但不计证据：35 项。
- 当前完整 API 重跑：0 项；`full_api_acceptance_passed=false`；生产验收仍为 false。
- 10 个新增计入的唯一操作来自识别 HTTP 套件；7 个测试套件共记录 77 个请求。VIN 与本地 VIN 两套共享一个契约操作。
- 35 项结论分布：superseded 17、internal-only 1、blocked-external 11、not-implemented 2、no-success-path 1、dev-only 3。

## 识别证据边界

登记的快照为 [R9_A4D47_RECOGNITION_HTTP_2026_10_30.json](reports/R9_A4D47_RECOGNITION_HTTP_2026_10_30.json)，模式是 `AUDIT_EXISTING`，源哈希以结构化报告为准。它验证的是当时存在的源绑定 JUnit/HTTP 证据；不是当前代码重跑，不代表真实 OCR、ASR 或 VIN 上游验收。`provider_fixture=true` 是计入本地识别行为的边界。

2026-11-12 尝试在当前源码上运行 `python scripts/run_recognition_http_acceptance.py --docker`，但 Docker Desktop Linux Engine 管道不存在，Maven 未启动。因此本轮没有新产生识别 HTTP 执行证据，也没有验证当前版本的数据库容器清理。

## 校验命令

| 命令 | 状态 |
| --- | --- |
| `python scripts/test_api_coverage_summary.py` | 通过，35 项 |
| `python scripts/test_reconcile_contract.py` | 通过，11 项 |
| `python scripts/summarize_api_coverage.py --check` | 通过，362 项 / 327 历史证据 / 35 结论 / 37 套登记来源 |
| `python scripts/reconcile_contract.py --check` | 通过，362 = 327 + 35 |

## 后续限制

- Docker Engine 可用后，重新运行识别 HTTP 套件并记录当前源码的执行结果。
- 真实车型供应商、授权证件/语音样本、真机和生产接入仍需外部资源。
- A5 已按既有路线图政策收口为“§9.3 分项压测门限 + §12.1 严格发布门限”；本次没有放宽门限。当前源码压测另见[决议记录](../operations/PERFORMANCE_THRESHOLD_DECISION_2026_11_12.md)，尚未执行。
