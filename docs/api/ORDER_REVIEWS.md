# 本人订单评价 API（A7.2a）

对应 [规格](../superpowers/specs/2026-10-09-a7-owner-review-design.md)。只有正式 OWNER 的有效会话可调用，归属从服务端订单取得；所有响应 `Cache-Control: no-store`。商家、技师、绑定态无权访问；他人订单 404。

## 提交

`POST /api/order/review`，`Content-Type: application/json`，必需 UUID `Idempotency-Key`，禁止查询参数。

```json
{"order_id":9206401,"rating":5,"content":"维修后复检正常，沟通清楚。","photo_file_ids":[123,124]}
```

正文必须恰含四个字段；拒绝重复键、尾随 JSON、额外身份/匿名/修改/测试字段。ID 为 1–9007199254740991 整数；rating 为 1–5 整数；content 首尾去空白后 1–500 Unicode 码点（禁非法代理字符及非换行/制表控制字符）；photo_file_ids 数组必填，0–3 个不重复本人安全 JPEG/PNG 文件，1–10MiB，顺序为展示顺序。上传/预览复用本人私有文件接口，不存图片 URL。

成功 `data`：

```json
{"order_id":9206401,"review":{"review_id":1,"rating":5,"content":"维修后复检正常，沟通清楚。","photo_file_ids":[123,124],"submitted_at":"2026-10-09T02:00:00Z","test_mode":true}}
```

COMPLETED、无 OPEN 争议、核销/订单商家/历史商家员工一致、唯一成功付款与金额/CNY/完整流水/时间一致、唯一同通道有效 PAID 事件、无任何付款异常，才可提交。test_mode 继承核销；LOCAL_TEST 未真实扣款、不计商家评分。评价只供本人查看，提交后不可修改，不会回写档案或发布经验卡片。

同键同规范化载荷返回原响应；同键异载荷 400；新键同内容返回同一记录且无重复审计；新键异内容 44002。重放仍检查会话、归属、资格及图片。评价、文件关系、ORDER_REVIEW_CREATE 审计和成功缓存同事务；并发只创建一个，任一写入失败全部回滚，原键可重试。订单状态保持 COMPLETED。

## 查询

`GET /api/order/{id}/review`，无查询参数。

```json
{"order_id":9206401,"can_submit":true,"unavailable_reason":null,"test_mode":true,"review":null}
```

已评价返回 `can_submit=false`、`unavailable_reason="ALREADY_REVIEWED"`、已有 review 和相同 test_mode；已有反馈只要求当前本人会话/订单归属有效，即使后来图片失效/付款异常仍可查看历史。新评价资格不足时 review=null、test_mode=null，reason 为 ORDER_NOT_COMPLETED / REDEMPTION_UNVERIFIED / OPEN_DISPUTE / PAYMENT_UNVERIFIED。历史 COMPLETED 缺核销记录不能评价，不自动回填。

读写投影不含 user_id、staff_id、merchant_id、payment_id、核销码、流水、对象键或签名 URL。图片访问 `GET /api/file/{id}/access` 再复核本人归属/扫描/删除和真实存储；失效图片显示不可预览，保留已有文字。

## 错误

| HTTP / code | 含义 |
| --- | --- |
| 400 / 40001 | 输入、UUID、查询参数错误或同键异载荷 |
| 401 / 40100 | 未登录、过期/撤销会话、停用用户 |
| 403 / 40300 | 非 OWNER 正式身份 |
| 404 / 40400 | 订单不存在、非本人、已删除 |
| 409 / 44001 | 核销/付款/状态/争议资格不成立 |
| 409 / 44002 | 已有不同评价，不可修改 |
| 422 / 42200 | 图片非本人、未 CLEAN、删除或格式/大小不符 |
| 503 / 50300 | 数据库未配置、写入失败；原键原载荷重试 |

## 存储与后续

V016 新建 order_review/order_review_file，订单唯一，图片位置及关系唯一，V001–V016 共57表；重复执行不改历史数据。评分范围、字数、图数、不可修改是当前明确实施假设；匿名公开、商家聚合/AI 总结独立规格。档案 A7.2b 和卡片 A7.2c 分别实现。
