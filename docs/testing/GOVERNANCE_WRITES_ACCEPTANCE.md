# 运营目录治理与价格监测证据链接收说明（A4d-11）

真实隔离 HTTP + MySQL + Newman 6.2.1 运行结果：**PASS**。

- 业务套件：56 个请求 / 218 条断言 / 0 失败，9 个接口各覆盖正常、边界、错误、未授权四类。
- 幂等：6 个作用域的同键重放响应逐字节相等；2 组"同键异体"被 400 拒绝。
- 匿名基线：338 请求 / 1014 断言通过，前后业务表行数不变（`business_table_counts_unchanged=true`）。
- 默认关闭基线：不注入开关时新套件 0 请求（`prepared_default_disabled_verified=true`）。
- 数据库不变量：14 项全部为真；行数 `brand=2 / standard_project=1 / price_monitor_policy=1 / idempotency_record=6 / audit_log=7`。
- 资源清理：backend / mysql / network 三个角色均 `passed=true`；源码哈希前后一致（`sources_unchanged=true`）。
- 耗时 205.068 秒；`source_sha256=51aa0a0f…`、`jar_sha256=22256e24…`。

## 本次覆盖的 9 个操作

| 操作 | 正常 | 边界 | 错误 | 未授权 |
| --- | --- | --- | --- | --- |
| `GET /api/admin/catalog/{kind}` | 项目清单 1 条、来源说明为空 | 超末页空列表 | `page_size=51` | 匿名 401 / 无目录权限 403 |
| `POST /api/admin/catalog/{kind}` | 新建品牌固定草稿（`status=0, revision=1`） | 同键重放逐字节相等 | 同键异体 400、重复车系 409 | 匿名 401 / 无权限 403 |
| `PUT /api/admin/catalog/{kind}/{id}` | 补来源说明并发布，`revision 1→2` | 同键重放逐字节相等 | 版本落后 409、金额格式 400 | 匿名 401 / 商家 403 |
| `GET /api/admin/price-monitor/quotes` | 来源说明补齐后 `READY` + 64 位哈希 | 来源说明为空时 `UNKNOWN`、`source_hash=null`；超末页空列表 | `page_size=10` | 匿名 401 / 无权限 403 |
| `POST /api/admin/price-monitor/policies` | 用读取到的哈希建政策（`DRAFT, revision=1`） | 同键重放逐字节相等 | 同键异体 400、伪造哈希 409、过去窗口 409、区间倒挂 400 | 匿名 401 / 无权限 403 |
| `GET /api/admin/price-monitor/policies` | 清单 1 条 | 超末页空列表 | `page_size=10` | 匿名 401 / 无权限 403 |
| `GET /api/admin/price-monitor/policies/{id}` | 明细含报价快照 | 不存在 404 | `id=0` 400 | 匿名 401 / 无权限 403 |
| `PUT /api/admin/price-monitor/policies/{id}` | 草稿修订 `revision 1→2` | 同键重放逐字节相等 | 版本落后 409、缺 `expected_revision` 400 | 匿名 401 / 无权限 403 |
| `PUT /api/admin/price-monitor/policies/{id}/status` | 发布 `revision 2→3`、停止 `3→4` | 重复发布 409、重复停止 409、重放逐字节相等 | 非法状态值 400 | 匿名 401 / 无权限 403 |

## 关键口径

1. **先证明"不可确认"，再证明"可确认"**。同一份报价（`merchant_project` 1，`price=12.34`）在目录来源说明为空时只能读到 `state=UNKNOWN`、`source_hash=null`；用 `PUT /catalog/projects/1` 补齐 `source_note` 之后才变 `READY` 并给出哈希。政策正文里的 `{{quote_hash}}` 只允许来自这次读取。
2. **政策只肯认当前报价**。伪造 64 个 `0` 的来源哈希返回 409；`valid_from` 落在过去返回 409（"确认当前区间"不可能是历史）。发布时服务端会重新校验报价仍是 `READY` 且哈希未变。
3. **目录新增不接受客户端自带状态**。正文里出现 `status`/`revision` 即 400；新建行固定 `status=0`、`revision=1`，审计里不会出现"草稿被记成已发布"。
4. **权限互斥**。`catalog`（`can_catalog=1`）、`monitor`（`can_price_monitor=1`）、`plain`（都 0）三个身份交叉验证：目录读写只认 `catalog`，价格监测只认 `monitor`，`plain` 一律 403；商家令牌在运营接口上一律 403。
5. **状态机单调**。`DRAFT→PUBLISHED→STOPPED`，每次带 `expected_revision`；重复发布与重复停止都是 409，非法状态值 400。

## 覆盖核对

`python scripts/summarize_api_coverage.py` 重建后：

```
{"contract_operations": 352, "protected_operations": 338, "four_kind_definitions": 267,
 "historical_four_kind_operations": 267, "without_complete_historical_evidence": 85,
 "current_run_operations": 0, "suites": 25}
```

本阶段把 9 项历史四类完整从 258 提升到 267，缺口从 94 降到 85。全 API 验收仍为 `false`（`current_run_operations=0`，本次只跑了本套件）。

## 复现

```bash
python scripts/governance_api_cases.py
python -m unittest discover -s scripts -p test_governance_api_cases.py -v
python scripts/run_api_acceptance.py --governance-cases
python scripts/summarize_api_coverage.py --check
```

CI 对应步骤为 `Catalog governance and price monitor evidence chain`（构建产物 + 本套件）。

## 边界

- 报价版本与 `MERCHANT_PROJECT_SAVE` 审计凭证是 SQL 夹具（按生产写入同形构造）；本阶段证明的是价格政策对证据的校验，不是审计写入本身——后者由报价套件负责。
- 价格比对（`/comparisons`）不在本批：它读的是比对作业产物，仓库内没有可复跑的作业入口。
- 认证组不在本批：`wx-login`、`phone/bind` 需要真实微信上游，商家/运营验证码发放需要生产短信适配器（仓库只有接口与测试替身）。
- 真机、外部短信与正式资金均未验收。
