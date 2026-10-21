# 接口验收覆盖核对

本文件由 `python scripts/summarize_api_coverage.py` 生成。

- 当前契约：352 项；受保护接口：338 项。
- 四类用例定义覆盖：267 项；有匹配历史四类执行记录：267 项（接口去重）。
- 所选记录中未获完整四类证据：85 项；汇总过程新发起接口：0 项（各套实际执行见来源报告）。

历史证据不等于当前版本重跑通过，不等于完整权限矩阵或真实身份/资金验收。
匿名401不算业务四类覆盖；未列历史证据不等于功能未开发。
本工具只索引明确选定的25套记录，不否认其他单元、HTTP或历史验证；后续可增加经过校验的证据适配器。
全API验收仍为false。结构化来源、哈希及逐项状态见 [api-coverage.json](api-coverage.json)。

## 历史证据缺口（按领域分组）

### api/admin（19项）

- `GET /api/admin/assessment`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/community/images`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `POST /api/admin/community/images/{id}/moderate`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `GET /api/admin/community/images/{id}/preview`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `GET /api/admin/coupons`：缺 boundary, error, normal, unauthorized；实现标记 `r5a-inventory-claim-implemented`。
- `GET /api/admin/dashboard`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/invite/reward-coupons`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `GET /api/admin/invite/reward-policies`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `POST /api/admin/invite/reward-policies`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `PUT /api/admin/invite/reward-policies/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `PUT /api/admin/invite/reward-policies/{id}/status`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `GET /api/admin/merchant-applications/{id}/files/{file}/access`：缺 boundary, error, normal, unauthorized；实现标记 `merchant-onboarding-backend-implemented`。
- `POST /api/admin/merchant/audit`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/price-monitor/comparisons`：缺 boundary, error, normal, unauthorized；实现标记 `r8b3c2-price-comparison-implemented`。
- `GET /api/admin/price-monitor/comparisons/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `r8b3c2-price-comparison-implemented`。
- `GET /api/admin/project-applicability/{project}/{model}`：缺 boundary, error, normal, unauthorized；实现标记 `vehicle-plan-evidence-implemented`。
- `PUT /api/admin/project-applicability/{project}/{model}`：缺 boundary, error, normal, unauthorized；实现标记 `vehicle-plan-evidence-implemented`。
- `GET /api/admin/reconciliation`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/admin/standard-project`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/ai（5项）

- `POST /api/ai/chat`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/ai/plan`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/ai/service-plan`：缺 boundary, error, normal, unauthorized；实现标记 `catalog-based-service-plan-implemented`。
- `GET /api/ai/service-plan/catalog`：缺 boundary, error, normal, unauthorized；实现标记 `catalog-based-service-plan-implemented`。
- `POST /api/ai/service-plan/vehicle-evidence`：缺 boundary, error, normal, unauthorized；实现标记 `vehicle-plan-evidence-implemented`。

### api/auth（13项）

- `POST /api/auth/cloud-login`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/logout`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/merchant/code`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/merchant/login`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/operator/code`：缺 boundary, error, normal, unauthorized；实现标记 `operator-auth-implemented`。
- `POST /api/auth/operator/login`：缺 boundary, error, normal, unauthorized；实现标记 `operator-auth-implemented`。
- `POST /api/auth/operator/logout`：缺 boundary, error, normal, unauthorized；实现标记 `operator-auth-implemented`。
- `POST /api/auth/phone/bind`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/refresh`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/sms-code`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/staff-login`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/auth/technician/bind`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。
- `POST /api/auth/wx-login`：缺 boundary, error, normal, unauthorized；实现标记 `core-implemented`。

### api/check（1项）

- `POST /api/check/delivery/compare`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/community（2项）

- `GET /api/community/images`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `GET /api/community/images/{publicId}`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。

### api/coupon（3项）

- `GET /api/coupon/offers`：缺 boundary, error, normal, unauthorized；实现标记 `r5a-inventory-claim-implemented`。
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

### api/invite（2项）

- `GET /api/invite/reward-policy`：缺 boundary, error, normal, unauthorized；实现标记 `r6b1-reward-policy-implemented`。
- `GET /api/invite/rewards`：缺 boundary, error, normal, unauthorized；实现标记 `r6b2-reward-delivery-implemented`。

### api/knowledge（1项）

- `POST /api/knowledge/answer`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。

### api/merchant（15项）

- `POST /api/merchant/apply`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/merchant/coupons`：缺 boundary, error, normal, unauthorized；实现标记 `r5a-inventory-claim-implemented`。
- `PUT /api/merchant/coupons/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `r5a-inventory-claim-implemented`。
- `GET /api/merchant/customer-relations`：缺 boundary, error, normal, unauthorized；实现标记 `r8b3b-local-customer-relations-implemented`。
- `GET /api/merchant/customer-relations/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `r8b3b-local-customer-relations-implemented`。
- `GET /api/merchant/invite/rewards`：缺 boundary, error, normal, unauthorized；实现标记 `r6b2-reward-delivery-implemented`。
- `POST /api/merchant/orders/{id}/actions`：缺 boundary, error, normal, unauthorized；实现标记 `order-fulfillment-state-machine-implemented`。
- `GET /api/merchant/orders/{id}/assignment`：缺 boundary, error, normal, unauthorized；实现标记 `technician-dispatch-backend-implemented`。
- `GET /api/merchant/orders/{id}/redemption`：缺 boundary, error, normal, unauthorized；实现标记 `redemption-backend-implemented`。
- `GET /api/merchant/orders/{id}/work`：缺 boundary, error, normal, unauthorized；实现标记 `service-work-backend-implemented`。
- `GET /api/merchant/slots`：缺 boundary, error, normal, unauthorized；实现标记 `local-reservations-core-implemented`。
- `POST /api/merchant/slots/{id}/close`：缺 boundary, error, normal, unauthorized；实现标记 `local-reservations-core-implemented`。
- `GET /api/merchant/technicians`：缺 boundary, error, normal, unauthorized；实现标记 `technician-dispatch-backend-implemented`。
- `GET /api/merchant/{id}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/merchant/{id}/reviews/summary`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/notifications（1项）

- `GET /api/notifications`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/order（6项）

- `GET /api/order/list`：缺 boundary, error, normal, unauthorized；实现标记 `local-reservations-core-implemented`。
- `POST /api/order/plan-booking-context`：缺 boundary, error, normal, unauthorized；实现标记 `plan-booking-context-implemented`。
- `GET /api/order/slots`：缺 boundary, error, normal, unauthorized；实现标记 `local-reservations-core-implemented`。
- `POST /api/order/verify`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/order/{id}/redemption`：缺 boundary, error, normal, unauthorized；实现标记 `redemption-backend-implemented`。
- `GET /api/order/{id}/work`：缺 boundary, error, normal, unauthorized；实现标记 `r7a-owner-evidence-implemented`。

### api/owner（6项）

- `GET /api/owner/community/image-sources`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `GET /api/owner/community/images`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `POST /api/owner/community/images`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `POST /api/owner/community/images/preview`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `GET /api/owner/community/images/{id}/preview`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。
- `POST /api/owner/community/images/{id}/withdraw`：缺 boundary, error, normal, unauthorized；实现标记 `r8-remaining-implemented`。

### api/payments（1项）

- `POST /api/payments/callback/{channel}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/refunds（2项）

- `POST /api/refunds/callback/{channel}`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `POST /api/refunds/create`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。

### api/service（1项）

- `GET /api/service/project/{id}/rating-quotes`：缺 boundary, error, normal, unauthorized；实现标记 `r4-completion-implemented`。

### api/vehicle（2项）

- `GET /api/vehicle/ocr-license`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
- `GET /api/vehicle/vin-decode`：缺 boundary, error, normal, unauthorized；实现标记 `unspecified`。
