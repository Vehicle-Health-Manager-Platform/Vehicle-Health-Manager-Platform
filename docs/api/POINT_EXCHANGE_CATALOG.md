# 积分兑换目录配置（R8a3a）

> R8a3b已实现[原子兑换接口](POINT_EXCHANGES.md)，目录配置本身仍不扣分发券；[车主兑换页面已接入](OWNER_POINT_EXCHANGES.md)。

仅独立 `can_points` 运营权限，当前会话每次重验，所有响应 `Cache-Control: no-store`。只配置目录，不提供实际兑换接口，不扣分、不发券。未配置无默认积分价格。

## 接口

- `GET /api/admin/point/exchange-sources`：分页读取真实已发布精选池来源；门店、标准项目与门店项目在售且未删除，券未过期。允许尚未开始、库存为零的来源供提前配置，兑换事务后续另验库存。返回现有 `CouponInventory` 格式，不授予 `can_coupon` 或修改券的能力。
- `GET /api/admin/point/exchange-catalog`：分页目录，含草稿/发布/停止、冻结券条款、来源说明、积分价、模板终身限额及版本。
- `POST /api/admin/point/exchange-catalog`：创建草稿。
- `PUT /api/admin/point/exchange-catalog/{id}`：按 `expected_revision` 修改草稿。
- `PUT /api/admin/point/exchange-catalog/{id}/status`：`{expected_revision,status}`，仅 DRAFT→PUBLISHED→STOPPED，不重开。

两个 GET 只接受 `page=1..1000000`、`page_size=20`，默认第一页。写不接受查询参数，必须提供 UUID `Idempotency-Key`；未知字段、重复字段、尾随 JSON、伪造账号或余额均拒绝。

## 创建及更新

精确字段：`coupon_id`、`publication_revision`、`pool_revision`、`points_cost`、`per_owner_limit`、`source_note`、`valid_from`、`valid_to`；更新另加 `expected_revision`。

前三项绑定真实券及发行/精选池版本；价和限额为 1–1000000 整数，来源说明 2–256 字，时间使用 UTC RFC3339（原有时间校验）。目录窗口须在原券窗口内且截止仍在未来。创建/修改/发布按门店→项目→券来源→目录顺序锁定与重验；精选取消后再精选会改变池版本，旧目录不能直接发布。

返回对象含输入字段及 `id`、`revision`、`status`、`rule_version=R8A_EXCHANGE_V1`、`limit_scope=OWNER_COUPON_LIFETIME`、`terms`、`created_at`、`published_at`。terms 来自真实发行冻结条款。`per_owner_limit` 意味本人在同模板的成功兑换终身累计，跨目录修订不清零，实际计数/扣分在 R8a3b 实现。

同一券最多一条 PUBLISHED 目录，由数据库唯一索引保证；即使已过期也须先停止旧目录。发布后不可改价、限额或窗口；来源失效后仍可停止目录。

## 回执与失败

创建人＋UUID、正文哈希、首次结果永久保存于同一事务；通用 24 小时回执失效、目录已发布或来源已失效时，同键同正文仍返回原始创建结果，不新增目录/重复审计。改正文报错，权限撤销或会话撤销优先拒绝。

400 参数无效；401 会话失效；403 缺少独立权限；404 目录/来源不可用；409 来源版本、时间、CAS 或单一发布冲突；503 数据库/审计异常。业务写、永久创建回执和审计同事务，异常全部回滚。503/断线保留原请求同键重试。

完整结构见 [OpenAPI](openapi.json)，运行说明见[运维文档](../operations/POINT_EXCHANGE_CATALOG.md)。
