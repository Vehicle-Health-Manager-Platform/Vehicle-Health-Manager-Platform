# 车主公开星级服务项目评分报价读取验收

日期：2026-10-22。套件：`scripts/rating_quotes_api_cases.py`；执行器：`scripts/run_api_acceptance.py --rating-quotes-cases`；脱敏报告：[R9_A4D20_RATING_QUOTES_2026_10_22.json](reports/R9_A4D20_RATING_QUOTES_2026_10_22.json)。

## 实际结果

- 本地 PASS，**201.923 秒**；**18 个业务请求 / 67 条断言 / 0 失败**，1 个接口各覆盖 normal / boundary / error / unauthorized。
- 匿名探针 338 请求 / 1014 断言 / 0 失败，执行前后业务表行数不变；默认（未开套件）配置下本套件 0 请求。
- 排序与评分如实：在架且最新价 > 0 的三家报价按「有评价优先 → 平均分降序 → 价格升序」返回 `[1,3,2]`，评分分别为 `5.00` / `2.00` / `null`（零样本为 `null`，不是 `0`）。
- 三条应被排除的夹具未进入结果：下架报价、最新版本价为 0 的报价、`status=0` 的项目（后者整体 404）。
- 14 张所选业务表执行前后内容摘要逐字节不变（只读作业）；行数恰为 `order=2 / payment=2 / order_review=2 / review_publication=2 / merchant_project=6`。
- 自有 MySQL / 后端容器 / 网络全部实际移除；源码哈希执行前后一致。
- 覆盖核对：**34 套来源 / 315 项（共 352）具备四类历史证据 / 缺口 37 项**（本阶段 +1）；全 API 验收仍为 false。

## 用例矩阵

| 接口 | normal | boundary | error | unauthorized |
| --- | --- | --- | --- | --- |
| `GET /api/service/project/{id}/rating-quotes` | 项目 1 首页三家报价与评分；另一车主读同项目结果相同 | 空页；`region_code=310000` 两店；`region_code=999999` 空；项目 2 仅一条；项目不存在 404；项目 `status=0` 404 | `page=0`、`page_size=21`、`region_code` 5 位、未知参数、重复 `page`、项目 id 0 | 匿名 401；商家 403；技师 403；他店商家 403 |

## 排除规则（夹具刻意构造）

| 夹具 | 期望 | 若被算入的表现 |
| --- | --- | --- |
| `merchant_project` 4 下架（`on_shelf=0`） | 不出现 | 项目 2 的 `total` 会从 1 变成 2 |
| `merchant_project` 5 在架但最新版本价 0 | 不出现 | 同上 |
| `standard_project` 3 `status=0` | 整个项目 404 | 会返回 200 并列出报价 |
| 门店 2 的报价 `on_shelf=1` 但区域 440100 | 只出现在 `region_code=440100` | `region_code=310000` 的 `total` 会从 2 变成 3 |

## 复现

```bash
# 需要 Docker（自建后端镜像 + 隔离 MySQL）
python scripts/run_api_acceptance.py --rating-quotes-cases

# 生成/校验集合与清单（已提交，CI 会比对是否过期）
python scripts/rating_quotes_api_cases.py

# 套件自身的证据校验逻辑
python -m unittest discover -s scripts -p test_rating_quotes_api_cases.py -v
```

## 本轮修正

首轮 FAIL：`quotes` 的 5 条业务断言里，后 4 条复用了第一条 `const d` / `const i`，而每条 `extra` 是独立的 `pm.test` 作用域，运行时报 `d is not defined`。改为每条断言自己取 `pm.response.json().data`，断言数量不变（仍是 9 条）。

## 边界

- 身份、订单、支付、核销、评价、公开授权与报价版本均为 SQL 合成夹具；本阶段证明读取投影与排除规则，不证明评价提交、授权、支付或核销链路（另有套件）。
- 支付渠道字段写 `WECHAT` 只是协议值，无真实资金、未调用微信支付。
- 未做并发、真机、真实行政区划数据；`region_code` 只校验六位数字模式。
- 本文件不主张全量接口验收通过；该结论见 [API_COVERAGE.md](API_COVERAGE.md)。
