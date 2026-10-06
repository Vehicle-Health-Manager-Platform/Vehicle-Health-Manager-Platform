# 商家报价与选品接口

2026-10-06。本步实现 F06 商家报价及本店选品、定价、上下架。预约与订单另行交付。

## 身份与展示

车主使用有效 `user/OWNER` 会话；商家使用 `staff_account/MERCHANT`、`app_id=merchant-account` 会话。服务端逐次核对会话未撤销、未过期、员工有效且属于令牌商家，以及商家已审核、未删除。商家归属不接受请求正文参数。

车主只看已审核商家、启用标准项目、未删除且上架的报价。只提供价格排序；地址为店铺地址，没有计算距离或生成评分。

## 路由

| 方法与路径 | 身份 | 参数 |
| --- | --- | --- |
| `GET /api/service/project/{id}/merchants` | 车主 | `sort=price_asc/price_desc`，默认升序 |
| `GET /api/merchant/standard-projects` | 商家 | 可选 `category=1..6` |
| `GET /api/merchant/projects` | 商家 | 仅本店，包含上下架报价 |
| `POST /api/merchant/projects` | 商家 | UUID `Idempotency-Key`，严格正文见下 |

三个列表均支持 `page=1..1000000`、`page_size=1..100`，默认 1/20；返回 `items,total,page,page_size`。报价同价时按报价 ID 升序，其他列表按 ID 升序。读事务使用可重复读，确保单次请求的总数与列表一致。

```json
{"standard_project_id": 1, "price": "299.00", "status": 1}
```

只接受这三个字段：项目 ID 为安全正整数；金额必须为 `0.01–99999999.99` 的两位小数字符串，禁止数字类型、指数、前导零、零或负数；`status` 为整数 0 下架、1 上架。参考价只供对照，未定义的浮动比例不作为限价依据。

保存返回 `merchant_project_id,version_id,version,price,status`。车主列表还带 `merchant_id,merchant_name,address`；本店列表带 `standard_project_id,project_name,available`。所有金额使用字符串。

## 事务与版本

保存复用 24 小时幂等服务：同主体、方法、路径、键与正文返回已保存响应；同键改正文返回 400。缓存命中仍复核权限。每个新键提交创建下一版，当前报价、不可变版本、幂等响应与成功审计同事务提交；任一失败整体回滚。会话、员工、商家和项目锁保证并发操作顺序。

停用/删除的标准项目禁止新增、改价与上架；已有未删除报价可以保持原价下架。软删除报价不自动恢复。历史版本保留原价格和上下架状态；本步没有修改订单，也没有把报价版本接入订单价格快照。

## 迁移与错误

按 V001–V006 顺序迁移。V006 新建 `merchant_project_version`，已有未删除报价补一次初始版本；重复迁移不改已有历史。新 Compose 数据卷自动执行；已有数据卷需要人工补迁移。迁移不会回补此前审计。

统一响应和 `Cache-Control: no-store`：400 参数/键冲突；401 身份失效；403 角色无权；404 项目/报价不可用；503 数据库或事务不可用。失败响应不暴露 SQL、凭据或店铺内部身份。

本机验收方法与真实短信边界见[验收说明](../testing/LOCAL_MERCHANT_QUOTES_ACCEPTANCE.md)。
