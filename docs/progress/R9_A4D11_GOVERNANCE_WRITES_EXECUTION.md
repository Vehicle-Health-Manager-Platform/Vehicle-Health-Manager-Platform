# A4d-11 执行记录：运营目录治理与价格监测证据链

## 结果与范围

补齐 9 个已实现但无四类证据的运营写/读接口：目录治理 3 项（`GET`/`POST`/`PUT /api/admin/catalog/*`）、价格监测政策 6 项（报价证据读、政策清单/明细/创建/修订/状态）。生产代码零变更。

本地真实隔离环境 **PASS**：56 个业务请求 / 218 条断言 / 0 失败，9 个接口四类齐备；匿名 338 请求 / 1014 断言通过且业务表行数不变；6 个幂等作用域重放逐字节相等、2 组同键异体被 400 拒绝；14 项数据库不变量全真；容器与网络实际移除；源码前后一致。耗时 205.068 秒。

覆盖核对从 24 套 / 258 项提升到 25 套 / 267 项，缺口 94 → 85。

## 实施与行为

新增 `scripts/governance_api_cases.py`（56 用例 / 9 操作）与 `scripts/test_governance_api_cases.py`（14 项单测），并把套件接入运行器、覆盖核对器与 CI（`--governance-cases`）。

核心是这条证据链：报价（`merchant_project` 1 + 版本 1 + 一条同形 `MERCHANT_PROJECT_SAVE` 审计）→ 目录来源说明为空时 `GET /quotes` 只能 `UNKNOWN` → `PUT /catalog/projects/1` 补齐 `source_note` → 同一报价变 `READY` 并给出 64 位哈希 → 政策用这个哈希建立、修订、发布、停止。政策正文的 `{{quote_hash}}` 只允许来自那次读取，伪造哈希与过去窗口都 409。

权限用三个互斥运营身份（`catalog` / `monitor` / `plain`）与商家令牌交叉验证：目录只认 `can_catalog`，价格监测只认 `can_price_monitor`，`plain` 一律 403。目录新增只写草稿（正文带 `status`/`revision` 即 400）；政策状态机 `DRAFT→PUBLISHED→STOPPED` 每次带 `expected_revision`，重复发布/重复停止均 409。

目录维护在生产默认关闭，套件通过运行器的环境注入 `CATALOG_GOVERNANCE_ENABLED=true` 显式开启；这是本阶段唯一打开的开关。默认关闭时新套件 0 请求（`prepared_default_disabled_verified=true`）。

## 实际验证

- `python scripts/governance_api_cases.py` → 56 用例 / 218 断言，资产与清单已刷新。
- `python -m unittest discover -s scripts -p test_governance_api_cases.py -v` → 14 项全绿（约 26 秒）。
- `python scripts/run_api_acceptance.py --governance-cases` → PASS（205.068 秒）。
- `python scripts/summarize_api_coverage.py --check` → PASS，25 套 / 267 项 / 缺口 85。
- `python -m unittest discover -s scripts -p test_api_coverage_summary.py -v` → 23 项全绿（期望值同步为 25 / 267 / 85）。
- 报告归档 `docs/testing/reports/R9_A4D11_CATALOG_GOVERNANCE_2026_10_21.json`。

## 第一次执行的一处失败

首跑（`.cache/api-acceptance-dfcae3fbb20b`）唯一失败是 `projects_list_page_size`：我用 `?page_size=10` 期望 400，实际返回 200。原因是目录列表的上限是 50（`CatalogGovernance.list` 只校验 `size<1 || size>50`），而订单域的 `InvitationController.query` 才把 `page_size` 锁死为 20——两个域的分页规则不同。改用 `?page_size=51` 后通过。

另有一处自查缺陷（未进 CI）：主写用例最初没有显式传幂等键，重放用的是 `replay_key(name)`，两端键不同，审计里因此只统计到 2 个重放作用域而不是 6 个。修正为 6 条主写各自带 `key=replay_key(...)`，并把审计收紧为"`_replay`/`_changed_body` 必须与唯一主写共享同一键"，否则抛错。单测里补了对应断言。

## 遗留

- 价格比对（`/comparisons`，2 项）未覆盖：它读比对作业产物，没有可复跑入口。
- 认证组（13 项）未覆盖，其中：`wx-login`、`phone/bind` 需真实微信上游；商家/运营的 `code`/`login` 需生产短信适配器（仓库只有接口与测试替身）；`sms-code`、`staff-login` 在契约里没有映射实现。
- 契约中另有 27 项 `unspecified` 条目（如 `GET /api/admin/assessment`、`/api/admin/dashboard`、`/api/admin/reconciliation`）代码无映射，造用例只会得到 404，不计入可覆盖范围。
- 真机、外部短信、正式资金未验收；全 API 验收仍为 false。
