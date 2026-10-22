# 接口验收覆盖核对

本文件由 `python scripts/summarize_api_coverage.py` 生成。

- 当前契约：352 项；受保护接口：338 项。
- 四类用例定义覆盖：315 项；有匹配历史四类执行记录：315 项（接口去重）。
- 所选记录中未获完整四类证据：37 项；汇总过程新发起接口：0 项（各套实际执行见来源报告）。

历史证据不等于当前版本重跑通过，不等于完整权限矩阵或真实身份/资金验收。
匿名401不算业务四类覆盖；未列历史证据不等于功能未开发。
本工具只索引明确选定的34套记录，不否认其他单元、HTTP或历史验证；后续可增加经过校验的证据适配器。
全API验收仍为false。结构化来源、哈希及逐项状态见 [api-coverage.json](api-coverage.json)。

## 历史证据缺口（按领域分组）

### api/admin（5项）

- `GET /api/admin/assessment`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/dashboard`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/admin/merchant/audit`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/reconciliation`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/standard-project`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/ai（2项）

- `POST /api/ai/chat`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/ai/plan`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/auth（10项）

- `POST /api/auth/cloud-login`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/merchant/code`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/merchant/login`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/operator/code`：缺 boundary, error, normal, unauthorized；实现标记 `operator-auth-implemented`。
- `POST /api/auth/operator/login`：缺 boundary, error, normal, unauthorized；实现标记 `operator-auth-implemented`。
- `POST /api/auth/phone/bind`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/sms-code`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/staff-login`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/technician/bind`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/wx-login`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。

### api/check（1项）

- `POST /api/check/delivery/compare`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/coupon（2项）

- `POST /api/coupon/release`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/coupon/reserve`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/demo（2项）

- `GET /api/demo/vehicles/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/demo/vehicles/{id}/mileage`：缺 boundary, error, normal, unauthorized；实现标记 `local-example`。

### api/dev（1项）

- `POST /api/dev/token`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/home（2项）

- `GET /api/home/dashboard`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/home/reminders`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/knowledge（1项）

- `POST /api/knowledge/answer`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。

### api/merchant（4项）

- `POST /api/merchant/apply`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/merchant/orders/{id}/actions`：缺 boundary, error, normal, unauthorized；实现标记 `order-fulfillment-state-machine-implemented`。
- `GET /api/merchant/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/merchant/{id}/reviews/summary`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/notifications（1项）

- `GET /api/notifications`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/order（1项）

- `POST /api/order/verify`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/payments（1项）

- `POST /api/payments/callback/{channel}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/refunds（2项）

- `POST /api/refunds/callback/{channel}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/refunds/create`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/vehicle（2项）

- `GET /api/vehicle/ocr-license`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/vehicle/vin-decode`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
