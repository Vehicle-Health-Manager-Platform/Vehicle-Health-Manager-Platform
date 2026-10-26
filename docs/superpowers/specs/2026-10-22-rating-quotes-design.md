# 车主公开星级服务项目评分报价读取验收设计

本阶段补齐最后一个**仍可闭合**的四类证据缺口：车主侧读取某标准项目的公开报价与星级汇总（`GET /api/service/project/{id}/rating-quotes`）。同时把剩余 37 项缺口按成因登记，避免它们被当成"待补证据"反复排队。

## 为什么是这个接口

覆盖清单里 38 项缺口（A4d19 后）分三类：27 项契约映射为 `unspecified`、代码里没有对应实现（造用例只会 404/400，不产生证据）；7 项需要真实微信上游或生产短信适配器；剩下 4 项是实现存在但此前认为"没人写证据"。逐一核对后：

| 实现存在的缺口 | 实际可达性 | 处理 |
| --- | --- | --- |
| `GET /api/service/project/{id}/rating-quotes` | 四类全部可达 | **本阶段补证据** |
| `POST /api/merchant/orders/{id}/actions` | 四个动作全部被拦截，**无 200 路径** | 登记契约偏差，改契约文档 |
| `POST /api/knowledge/answer` | 需已配置的大模型供应商，未配置即 `503` | 登记为外部依赖阻塞 |
| `POST /api/demo/vehicles/{id}/mileage` | 本地示例端点（`local-example`） | 不计入生产验收 |

## 方案选择

Newman 业务套件（A4d-2 起的默认做法）：真实后端 + 真实 MySQL + 真实 HTTP，只读。与 Java 旅程相比这里不需要对象存储与杀毒引擎，因此不引入 MinIO/ClamAV 容器，也不需要用 Testcontainers。

本套件与 A4d16（客户关系与价格比较只读）同形：一次执行只跑一个套件、独占一套全新数据库与后端容器，快照校验"执行前后所选表内容逐字节不变"。

## 被测输出与排序规则

`quotes(owner, project, page, region)` 的投影：

```
{items:[{merchant_project_id, merchant_id, merchant_name, address, price, version_id, version,
         rating_summary:{count, rating_sum, average_rating}}], total, page, page_size}
```

- 只统计 `merchant_project.on_shelf=1` 且**最新版本** `price>0` 的报价；项目必须 `status=1`，门店必须 `status=1`。
- 评分只来自"合格公开评价"：订单 `COMPLETED`、支付渠道 `WECHAT` 且成功、核销非测试模式、评价 `test_mode=0`、公开授权 `consented=1`，且无支付异常、无未结争议、无退款。
- 排序：**有评价的先于无评价**，再按平均分降序、样本数降序，最后按价格升序、报价 id 升序。
- `average_rating` 在零样本时为 `null`（不是 `0` 或 `0.00`）。

## 用例设计

| 类别 | 用例 |
| --- | --- |
| normal（2） | 项目 1 首页：三家在架报价，顺序 `[1,3,2]`，评分 `5.00`/`2.00`/`null`；另一车主读同一项目结果相同（公开读不按车主隔离） |
| boundary（6） | 空页；`region_code=310000` 只留两店；`region_code=999999` 空；项目 2 只有一条在架且最新价 > 0；项目不存在 404；项目 `status=0` 404 |
| error（6） | `page=0`；`page_size=21`（该接口只接受 20）；`region_code` 5 位；未知参数 `project_id`；重复 `page`；项目 id 0 |
| unauthorized（4） | 匿名 401；商家 403；技师 403；他店商家 403 |

夹具刻意构造三条"应被排除"的报价：下架报价（`on_shelf=0`）、最新版本价为 0 的报价、`status=0` 的项目。它们若被算进去，`total` 会从 3 变成 4/5，断言立即失败。

## 权限与开关

接口只认车主正式会话（`VehicleOwner.from`：`subject_type=user`、`role=OWNER`）；商家/技师/运营令牌一律 `403`；未知与重复 query 一律 `40001`；没有任何开关会关闭该读接口，因此环境只注入 `WECHAT_APP_ID`。

## 生产代码变更

只改契约文档 `docs/api/MERCHANT_ORDERS.md`，把"通用动作接口可写"与实现的偏差写明（四个动作各自的 409 码与正确入口），不改任何生产行为与 schema。

## 边界

- 身份、订单、支付、核销、评价、公开授权与报价版本均为 SQL 合成夹具；本阶段证明的是**读取投影与排除规则**，不证明评价提交、授权、支付或核销的真实链路（这些另有套件）。
- 支付渠道写成 `WECHAT` 只是协议字段，没有真实资金、没有调用微信支付。
- 未做并发、真机、真实地区数据；`region_code` 只是六位数字模式。
- 全 API 验收仍为 false；本阶段只把该接口从"无四类证据"变为"有可复现四类证据"。
