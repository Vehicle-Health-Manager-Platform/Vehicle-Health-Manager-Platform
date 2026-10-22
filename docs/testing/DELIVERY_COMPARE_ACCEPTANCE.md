# 取车对比（接车基线 vs 完工证据）验收

日期：2026-10-22。套件：`scripts/delivery_compare_api_cases.py`；执行器：`scripts/run_api_acceptance.py --delivery-compare-cases`；实现：`backend/src/main/java/com/autocare/platform/order/DeliveryCompare.java`；脱敏报告：[R9_A4D22_DELIVERY_COMPARE_2026_10_22.json](reports/R9_A4D22_DELIVERY_COMPARE_2026_10_22.json)。

## 实际结果

- 本地 PASS，**319.130 秒**；**16 个业务请求 / 60 条断言 / 0 失败**，1 个接口四类齐备（normal 2 / boundary 4 / error 6 / unauthorized 4）。
- 匿名探针 338 请求 / 1014 断言 / 0 失败。
- **13 项数据库不变量全真**：12 张业务表执行前后内容摘要逐字节不变，且 `delivery_compare` 始终 **0 行**（证明这个"POST"没有偷偷写库）。
- 证据齐备订单：`result=READY`、`deliverable=true`、`missing=[]`；基线 `photo_slots` 恰为排序后的七个槽位，`mileage=120`、`mileage_delta=0`、`owner_confirm=1`；防护 `{submitted:true,item_count:2,photo:true}`；报工 `SIGNED`、过程/完工/签字各 1、故障件 0 且声明无故障件。
- 缺证据订单：`result=INCOMPLETE`、`deliverable=false`、`missing=[OWNER_CONFIRM, PROTECTION_PHOTO, FINISH, FAULT]` —— 证明缺口是**逐项算出来的**，不是写死的字符串。
- 已完成订单：`result=READY` 但 `deliverable=false`（可回看，不再交付）。
- 无接车单：`409` `43001`；订单不存在：`404`。
- 自有 MySQL / 后端容器 / 网络实际移除；源码哈希执行前后一致。

## 用例矩阵

| 接口 | normal | boundary | error | unauthorized |
| --- | --- | --- | --- | --- |
| `POST /api/check/delivery/compare` | 车主读齐备订单；本店商家读同一订单 | 缺证据订单 `INCOMPLETE`；已完成订单 `READY` 但不可交付；无接车单 409 `43001`；订单不存在 404 | 空正文、多余字段、`order_id=0`、`order_id` 为字符串、数组正文、携带 query —— 全部 400 | 匿名 401；技师 403；他人车主 404；他店 404 |

## 复现

```bash
# 需要 Docker（自建后端镜像 + 隔离 MySQL）
python scripts/run_api_acceptance.py --delivery-compare-cases

# 生成/校验集合与清单（已提交，CI 会比对是否过期）
python scripts/delivery_compare_api_cases.py

# 套件自身的证据校验逻辑
python -m unittest discover -s scripts -p test_delivery_compare_api_cases.py -v
```

## 边界

- 身份、订单、接车单、防护、报工与证据关联均为 SQL 合成夹具；本阶段证明**读取投影与缺口判定**，不证明接车提交、防护上传、报工签字的真实链路（分别由 `--pickup-cases` 与 A4d-9 旅程覆盖）。
- 无图像识别、无仪表盘 OCR：只判定"证据齐不齐"，不判定"车况变没变"。
- `delivery_compare` 表保留未使用；不声称交付确认已持久化——交付仍由核销完成。
- 车主端页面未接入，不声明渲染通过。
- 本文件不主张全量接口验收通过；该结论见 [API_COVERAGE.md](API_COVERAGE.md)。
