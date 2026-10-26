# 运营目录治理与价格监测证据链验收设计

本阶段补齐 R8B 交付、但尚未进入四类证据的两组运营写接口：目录治理（`/api/admin/catalog/{kind}` 的读、建、改，3 项）与价格监测政策（报价证据读、政策读/建/改/状态/明细，6 项），共 9 项已实现操作。

同期没有纳入本阶段的三组，理由写在最后一节：价格比对（`/comparisons`）依赖比对作业产物、认证组多数端点依赖真实微信上游与生产短信适配器、`/api/admin/*` 里另有 27 项契约条目根本没有映射实现。

## 方案选择

Newman 业务套件（A4d-2 起的默认做法）足够：这两组不碰对象存储、不碰渠道回调，风险集中在"目录草稿不会被当成已发布"、"价格政策只认当前报价证据"和"两套权限互不串用"，全部可由 MySQL + HTTP 证明。Java 旅程测试只会重复同一件事，因此不再起 Testcontainers。

目录维护在生产默认关闭（`CATALOG_GOVERNANCE_ENABLED=false`），本套件通过运行器注入 `CATALOG_GOVERNANCE_ENABLED=true` 显式开启；这是本阶段唯一打开的开关，价格监测没有任何开关。

## 被测流程（证据链是主体）

九项操作里最有价值的是这条链：**报价证据 → 来源哈希 → 政策 → 发布 → 停止**。

```
商家报价（当前版本 + MERCHANT_PROJECT_SAVE 审计凭证）
        │
        ├─ 目录来源说明为空  ⇒ GET /price-monitor/quotes 只能是 state=UNKNOWN、source_hash=null
        │
        └─ PUT /catalog/projects/1 补 source_note
                 ⇒ GET /price-monitor/quotes 变 READY 并给出 source_hash
                          ⇒ POST /policies 用这个哈希建立政策
                                   ⇒ PUT /policies/1 修订（乐观锁）
                                            ⇒ PUT /policies/1/status 发布 → 停止
```

夹具固定为：门店 1、标准项目 1（`source_note` 初始为空字符串）、`merchant_project` 1（`price=12.34`、`on_shelf=1`）、版本 1（`actor_staff_id=1`）。报价版本与 `MERCHANT_PROJECT_SAVE` 审计凭证由 SQL 种子按生产写入同形构造（`after_state` 含 `merchant_project_id`、`version_id`、`version`、`price`、`status`），因此本阶段证明的是"价格政策只肯认当前报价证据"，不是"审计写入正确"——后者已由报价套件（A4d-2H2）负责。

目录侧夹具：品牌 1、车系 1、车型 1、标准项目 1 均已发布；本阶段新建品牌 2 保持草稿，并通过"重复车系创建必须 409"证明同级唯一性只在同一父级内生效。

## 权限矩阵

三个互斥的运营身份全部由 SQL 种子与自签会话建立，接口逐条验证：

| 身份 | `can_catalog` | `can_price_monitor` | 预期 |
| --- | --- | --- | --- |
| `catalog` | 1 | 0 | 目录读写 200；价格监测 403 |
| `monitor` | 0 | 1 | 价格监测读写 200；目录 403 |
| `plain` | 0 | 0 | 两者一律 403 |
| 商家令牌 | — | — | 目录写 403（运营接口不接受商家身份） |
| 匿名 | — | — | 401 |

## 输入严格性与状态机

- 查询串只允许字面量（`page`、`page_size=20`），因为 `audit_owner_cases` 要求实际 query 与声明 query 逐字相同；`page_size=10` 一律 400。
- 目录新增只写草稿：正文里出现 `status`/`revision` 即 400，新建行固定 `status=0, revision=1`。
- 目录修订必须带当前 `revision`，落后即 409；金额不是 `x.xx` 形式即 400。
- 价格政策正文必须包含 `scope_confirmed=true`、`[a-f0-9]{64}` 形态的来源哈希、`lower_price ≤ upper_price`、`valid_from < valid_to`；`valid_from` 必须晚于当前时间（否则 409），因为"确认当前区间"不可能是过去。
- 来源哈希只允许来自 `GET /quotes` 的返回：用例正文写 `{{quote_hash}}`，由读取步骤写入集合变量；伪造 64 个 `0` 必须 409。
- 状态机只允许 `DRAFT→PUBLISHED→STOPPED`，每次带 `expected_revision`；重复发布、重复停止都 409，非法状态值 400。

## 幂等与审计

所有写请求带 UUID 幂等键：6 条主写各配一条"同键重放"（响应逐字节相等），另有 2 组"同键异体"（400，不得落库）。`uk_price_submission(created_by, submission_key)` 与 `idempotency_record` 一起保证失败请求回滚后不留记录，因此数据库中应当恰好 6 条幂等记录。

审计期望为 `CATALOG_SAVE:2`（新建品牌、修订项目）+ `MERCHANT_PROJECT_SAVE:1`（种子）+ `PRICE_POLICY_CREATE:1` + `PRICE_POLICY_UPDATE:1` + `PRICE_POLICY_STATUS:2`，共 7 条。

## 生产代码零变更

本阶段只新增验收资产，`backend/` 一行未改：这两组接口在 A4d 阶段就已实现，缺的是证据。

## 边界

- 报价版本与其审计凭证是 SQL 夹具，不代表商家侧写入验收。
- 价格比对（`/comparisons`）不在此批：它读的是比对作业写出的产物，本阶段没有可复跑的作业入口。
- 认证组不在此批：`wx-login` 与 `phone/bind` 需要真实微信上游，`merchant/*`、`operator/*` 的验证码发放需要生产短信适配器（仓库内只有接口与测试替身），强行造证据等于伪造。
