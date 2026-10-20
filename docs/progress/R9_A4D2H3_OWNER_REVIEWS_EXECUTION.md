# R9 A4d-2h3：车主评价与独立公开授权验收

2026-10-20。前置PR185；分支 `codex/r9-a4d2h3-owner-reviews`。目标是把"提交评价 → 本人查看 → 独立公开星级 → 独立投稿公开文本 → 运营审核 → 公开展示 → 摘要第三方用途"这条件链路用真实 HTTP 跑通，并修复过程中暴露的校验缺陷。

## 本轮实现

- 新增 `scripts/reviews_api_cases.py`：生成 139 条独立用例（465 条断言）覆盖 14 个操作，默认禁发；`test_reviews_api_cases.py` 11 项门禁单测。
- 接入 `scripts/run_api_acceptance.py`（`--reviews-cases`）与 CI `schema-and-ocr` job：生成资产 + 单测。
- 夹具：身份/订单/支付/核销/图片为 SQL 合成，**评价与四种授权全部走 HTTP**。

## 夹具矩阵

| 订单 | 状态 | 渠道 | 核销 | 争议 | 验证点 |
| --- | --- | --- | --- | --- | --- |
| 1 | COMPLETED | LOCAL_TEST | 有 | 无 | 可评价，但星级/文本不能公开（TEST_ORDER） |
| 2 | COMPLETED | WECHAT | 有 | 无 | 完整公开链路 |
| 3 | PENDING_PAYMENT | — | 无 | 无 | ORDER_NOT_COMPLETED |
| 4 | COMPLETED | WECHAT | 无 | 无 | REDEMPTION_UNVERIFIED |
| 5 | COMPLETED（他人） | WECHAT | 有 | 无 | 越权 404 |
| 6 | COMPLETED | WECHAT | 有 | OPEN | OPEN_DISPUTE |

图片 `file_object` 另插 3 行：两张属车主 1（CLEAN，PNG/JPEG）、一张属车主 2（验证他人文件 422）。

## 判定口径

- 每个操作必须覆盖 `normal/boundary/error/unauthorized` 四类，缺一类审计直接拒绝。
- 审计逐项核对方法、路径、真实 query、真实身份令牌、状态码，以及幂等键是否就是计划的那一个。
- 落库核对：评价不可变、`test_mode` 跟随核销、图片位置顺序、公开记录撤回后 `consented=0` 且版本递增、文本撤回后正文与公开编号清空但审核履历保留、摘要授权绑定 `text_version`。
- 两个开关（`REVIEW_TEXT_PUBLICATION_ENABLED`、`REVIEW_SUMMARY_ENABLED`）在隔离环境显式开启；单测同时断言生产代码里 `@Value` 回退值仍为 `false`。

## 结果

A4d-2h3 本地PASS（218.992秒）：4条评价/公开链路共14接口139业务请求/465断言、338匿名请求/1014断言通过；新评价集合及352模板默认执行均实际0请求。评价/星级/文本/摘要四条独立授权由HTTP逐条驱动：TEST订单与未完成/无核销/开放争议/他人订单的资格投影、评价不可修改（新键重发返回同一review且request_id不同）、图片位置顺序、脱敏预览与投稿逐字一致否则422、审核通过后版本2、重复审核按幂等重放返回200且不二次写审计、版本冲突与决定冲突返回409、星级与文本撤回后公开列表归零而审核履历保留。最终2条评价、2条图片、10条幂等记录、8条审计，11项数据库检查通过。62项Java定向（9组HTTP/JDBC）、11项新增/既有Python门禁测试通过；源码前后稳定，隔离后端/MySQL/网络清理成功。两个公开开关仅在隔离环境显式开启、生产回退值仍为false；单测同时断言这两件事。评价/订单/支付/核销/图片为SQL合成夹具，评价与四种授权全部走HTTP；正式微信身份与资金、真实对象存储、真实第三方模型、评分统计显著性与真机未覆盖。 下一公开文本及展示→其他独立缺口→A5交付→B外部接入→C真机。

固定用例串行执行，不能替代并发下的公开资格竞态与审核并发；脱敏为规则式替换，漏匹配不等于合规认证。

## 未覆盖

正式微信身份与资金、真实对象存储签名、真实第三方模型调用、评分聚合统计显著性、真机与 UI 展示。脱敏为规则式替换，会有漏匹配，不等于合规认证。

## 实际执行与证据

- `python scripts/run_api_acceptance.py --reviews-cases`：新网络 + 新 MySQL（62 迁移/131 表）+ 当前后端 jar，结果 `PASS`；`reviews_database.checks` 11 项全为 true。
- `python scripts/reviews_api_cases.py` 生成默认禁发的集合与 139 条独立 manifest；审计逐项核对方法、路径、真实 query、角色令牌、状态码、实际正文（占位符字面部分必须逐字相同、`clean_text` 必须回指本次预览结果）与实际启用的幂等键。
- `test_reviews_api_cases.py` 11 项；CI 的 `schema-and-ocr` job 生成资产并跑这组门禁，`backend` job 以 `--ci-built-jar --reviews-cases` 执行真实隔离验收（与其他业务域同一条链）。
- Java 九组共 62 项：`OrderReviewsHttpTest`、`OrderReviewsNoDatabaseTest`、`PublicRatingsHttpTest`、`PublicReviewTextsHttpTest`、`ReviewSummariesHttpTest`、`JdbcOrderReviewsTest`、`JdbcPublicRatingsTest`、`JdbcPublicReviewTextsTest`、`JdbcReviewSummariesTest`。
- 默认 reviews 集合另行不提供环境或令牌执行 Newman，exit0/requests0/failures0，且集合哈希与验收报告记录的一致；352 模板默认禁发由隔离运行器另测。
- 三份脱敏报告：`R9_A4D2H3_OWNER_REVIEWS_2026_10_20.json`、`R9_A4D2H3_JAVA_REGRESSION_2026_10_20.json`、`R9_A4D2H3_DEFAULT_DISABLED_2026_10_20.json`，位于 `docs/testing/reports/`。
- 原始 Newman/JWT/环境/日志只落忽略目录；没有在失败后放宽状态码预期或生产开关。

## 本轮修正的用例认知错误

- 各域正文上限不同（评价 8192、公开文本 4096、星级查询 512、报价 2048）：写"超限"用例必须按目标域上限，否则请求合法并真的落库，会把后续状态机全部带偏。
- 公开文本版本是 1 提交 / 2 通过 / 3 撤回，摘要授权把授权时的 `text_version` 冻结在授权行里，撤回文本后授权行仍是 `consented=1, text_version=2`。
- 同决定重复审核是幂等重放（200），不是 409；409 只出现在版本既非当前也非当前前版、或同版本改相反决定。
- `ratings` 列表没有 `total` 字段（聚合数在 `summary.count`），`d.total` 断言会直接 undefined。

## 后续

先公开评价文本的展示面（列表/详情/空态与撤回后的一致性），再补其他独立缺口，然后 A5 交付、B 外部接入、C 真机。正式身份、外部资金/识别/地图与第三方模型继续后置。新 PR 依赖 PR185，完整远程 CI 独立核对，保持草稿，不合并或部署。
