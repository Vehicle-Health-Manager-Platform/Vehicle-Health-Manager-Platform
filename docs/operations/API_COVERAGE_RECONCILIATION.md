# API 覆盖索引维护说明

本说明用于本地重生成和核对 API 覆盖及逐项结论。它不执行生产验收，也不把历史记录升级为当前版本验收。

## 环境

- Python 3.11（仓库开发环境）。
- 只重生成和校验索引时不需要 Docker、数据库或第三方账号。
- 重新执行识别 HTTP 套件需要 Maven、可用的 Docker Engine 及项目测试依赖；套件使用本地 provider fixture，不访问真实模型或车型供应商。

## 重生成和核对

在仓库根目录运行：

```powershell
python scripts/summarize_api_coverage.py
python scripts/reconcile_contract.py
python scripts/summarize_api_coverage.py --check
python scripts/reconcile_contract.py --check
python scripts/test_api_coverage_summary.py
python scripts/test_reconcile_contract.py
```

汇总器只读取明确登记的报告、测试清单和当前接口契约。不要手工编辑生成的 `docs/testing/api-coverage.json`、`docs/testing/API_COVERAGE.md` 或 `docs/api/CONTRACT_RECONCILIATION.md`。当报告或契约输入改变时，先运行生成命令，再运行 `--check` 和对应测试。

## 刷新识别 HTTP 证据

需要可用的 Docker Engine：

```powershell
python scripts/run_recognition_http_acceptance.py --docker
```

只有 runner 成功且报告确认测试通过、源码未变、拥有的测试数据库已清理，才可将新执行报告登记为历史证据。`AUDIT_EXISTING` 只核查已存在的 JUnit 和证据文件，不会启动 Maven，也不会复核数据库容器清理。失败或缺少 Docker 时，不要改写报告来伪造 `EXECUTED`。

识别报告必须继续明确标记本地 provider fixture。无论本地套件是否通过，都不能据此宣称真实 OCR/ASR/VIN 上游、授权样本或生产环境通过。
