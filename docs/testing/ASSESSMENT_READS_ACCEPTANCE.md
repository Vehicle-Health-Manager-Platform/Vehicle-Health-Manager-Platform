# 考核政策、月结与经营看板读接口验收

日期：2026-10-21。执行器：`scripts/run_api_acceptance.py --assessment-cases`；脱敏报告：[R9_A4D10_ASSESSMENT_READS_2026_10_21.json](reports/R9_A4D10_ASSESSMENT_READS_2026_10_21.json)；设计说明：[2026-10-21-assessment-reads-design.md](../superpowers/specs/2026-10-21-assessment-reads-design.md)。

## 实际结果

- 本地 PASS，228.873 秒；153 个业务请求、530 条断言、0 失败；20 个接口全部覆盖 normal/boundary/error/unauthorized 四类。
- 同批匿名探针 338 请求 / 1014 断言 0 失败；匿名流量前后表行数完全不变；默认禁用的集合实测 0 请求。
- 政策写入链：创建 → 同键重放（响应逐字节相同）→ 同键异体 400 → 修订（revision 2）→ 发布（revision 3）→ 重复发布 409 → 非法状态 400 → 停止（revision 4）→ 重复停止 409。7 个幂等作用域全部核对，2 组"同键异体"实测被拒。
- 月结链：已结束月份 0 号修订 → 1 → 2（两次独立结算），过期修订 409、未结束月份 409、未知门店 404、同键异体 400；门店 2 无政策月份结算落库 `policy_snapshot = JSON null`。
- 13 项数据库不变量全部为真：2 份政策（`PUBLISHED`/`STOPPED`）、每店至多 1 份已发布、2 个 `assessment_month` 头且只在 `2024-01`、3 份快照修订 `1,2,1`、4 条事实、7 条幂等记录、7 条审计；3 份快照摘要全为 `UNKNOWN` 且 `total_score`/`grade` 均为 JSON null；事实明细从未进入 `audit_log`。
- 自有 MySQL / 后端容器与网络实际移除；源码哈希执行前后一致。身份、订单与历史政策为 SQL 合成夹具，正式身份、真实资金与真机未验收。

## 用例矩阵

| 接口 | normal | boundary | error | unauthorized |
| --- | --- | --- | --- | --- |
| `GET /api/admin/assessment/merchants` | 2 家启用门店 | `page=1000000` 空页 | `page=0` 400 | 匿名 401、`can_analytics` 403、门店账号 403 |
| `GET /api/admin/assessment/policies` | 种子历史政策全字段 | `page=2` 空页 | `page_size=10` 400 | 同上 |
| `POST /api/admin/assessment/policies` | 创建门站 2 未来窗口政策 + 重放 | 过去窗口 409 | 目标 0、重复维度、等级非严格递减、末档非 0.00、月中窗口、未知字段、query、未知门店 404、同键异体 400 | 匿名 401、车主 403、`can_analytics` 403 |
| `PUT /api/admin/assessment/policies/{id}` | 修订到 revision 2 + 重放 | 过期修订 409、未知 id 404 | id 0 400、空正文 400 | 匿名 401、车主 403 |
| `PUT /api/admin/assessment/policies/{id}/status` | 发布到 revision 3、停止到 revision 4 + 重放 | 重复发布 409、重复停止 409、未知 id 404 | `DRAFT` 400、空正文 400 | 匿名 401、车主 403 |
| `GET /api/merchant/assessment/policy` | 本店 `CONFIGURED`、他店 `NOT_STARTED` | 发布前 `UNKNOWN`、停止后 `UNKNOWN` | 未知 query 400 | 匿名 401、店员 403、车主 403 |
| `GET /api/admin/assessment/month` | `2024-01` `READY`、`can_settle=true` | `2999-01` `MONTH_OPEN`、`can_settle=false` | 缺月份/缺门店/非法月份 400、未知门店 404 | 匿名 401、`can_analytics` 403 |
| `POST /api/admin/assessment/snapshots` | 本店结算两次（revision 1、2）+ 他店无政策结算 + 重放 | 过期修订 409、未结束月份 409 | 未知门店 404、空正文 400、同键异体 400 | 匿名 401、车主 403 |
| `GET /api/admin/assessment/snapshots` | 3 份快照 | `page=2` 空页 | `page=0` 400 | 匿名 401、车主 403 |
| `GET /api/admin/assessment/snapshots/{id}` | revision 2 明细 | 未知 id 404 | id 0 400 | 匿名 401、车主 403 |
| `GET /api/admin/assessment/snapshots/{id}/facts` | 2 条最小事实 | `page=2` 空页 | `page=0` 400 | 匿名 401、车主 403 |
| `GET /api/admin/assessment/history` | revision `[2,1]` | `page=2` 空页 | `page_size=10` 400 | 匿名 401、车主 403 |
| `GET /api/merchant/assessment/month` | `READY`、`can_settle=false` | `2999-01` `MONTH_OPEN` | 缺月份 400 | 匿名 401、店员 403、车主 403 |
| `GET /api/merchant/assessment/history` | revision `[2,1]` | `page=2` 空页 | `page=0` 400 | 匿名 401、店员 403、车主 403 |
| `GET /api/merchant/assessment/snapshots/{id}` | 本店快照 | 他店快照 404 | id 0 400 | 匿名 401、店员 403、车主 403 |
| `GET /api/merchant/assessment/snapshots/{id}/facts` | 2 条最小事实 | `page=2` 空页 | `page=0` 400 | 匿名 401、店员 403、车主 403 |
| `GET /api/merchant/assessment/staff` | 店长 `FROZEN`、店员本人 `FROZEN` | 无快照月份 `NO_SNAPSHOT` | 缺月份 400、`merchant_id` 参数 400 | 匿名 401、车主 403 |
| `GET /api/admin/business/merchants` | 2 家启用门店 | `page=2` 空页 | `page=0` 400 | 匿名 401、`can_assessment` 403、门店账号 403 |
| `GET /api/admin/business/dashboard` | 本店分桶/金额/未分类/月结引用 | 无来源门店仍返回结构 | 未开始月份 409、缺门店 400 | 匿名 401、`can_assessment` 403 |
| `GET /api/merchant/business/dashboard` | 本店同源数据 | 无来源门店 | `merchant_id` 参数 400 | 匿名 401、店员 403、车主 403 |

## 关键口径

- **不凭空出分**：本店 `2024-01` 只有 1 条 `EXCLUDED`（`LOCAL_TEST` 履约）与 1 条 `UNKNOWN`（无核销回执的完成订单）过程事实。于是拉新与券月维度用政策目标作分母，给 `KNOWN/0.00`；过程维度分母只数非 `EXCLUDED`（=1），因存在 `UNKNOWN` 事实整体 `UNKNOWN`，`score=null`、`reason=SOURCE_UNKNOWN`；总分与等级都是 `null`。
- **没有政策就没有分母**：门店 2 的 `2024-01` 结算落库 `policy_snapshot` 为 JSON `null`，三个维度全部 `POLICY_UNKNOWN`、`fact_count=0`，看板引用的也是这份 `UNKNOWN` 快照。
- **不猜归因**：看板把无法验证来源的订单计入 `unclassified_count`（本店 2 笔），正式桶与测试桶同时标记 `UNKNOWN`，不把未验证订单塞进任何一桶。
- **事实最小化**：每条事实固定 9 个字段，`evidence` 为空对象、`proof_hash` 为 SHA256；响应正文不含订单号、车牌或手机号；`audit_log` 中不含 `proof_hash` 或事实正文。

## 覆盖核对

24 套来源在 352 项契约中确认 258 项具备四类历史证据，缺口 94 项；全 API 验收仍为 false。

## 复现

```bash
# 需要 Docker 与 Node（Newman 6.2.1 由脚本按需拉取）
python scripts/run_api_acceptance.py --assessment-cases

# 只校验归档证据与当前定义/哈希是否一致（CI 使用）
python scripts/summarize_api_coverage.py --check
python -m unittest discover -s scripts -p test_assessment_api_cases.py -v
```

产物：`.cache/api-acceptance-*/report.json`（原始，未入库）与本目录 `reports/` 下脱敏副本。

## 边界

- 本阶段不证明"能算出 100 分"：完整四段过程证据（接车确认、防护、报工、质检签字）属于履约旅程范围。
- `assessment_month.revision` 与最新快照修订不一致时接口返回 503，该状态只能由并发写入或人工改库制造，本阶段不伪造。
- `GET /api/admin/assessment`（无 `x-roles`、无实现标记）在代码中无映射，不在本阶段范围；给它造用例只会得到 404。
