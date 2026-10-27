# 当前微信小程序页面与模板索引

由 `python scripts/miniapp_handover.py` 生成；请修改来源后重生成。

注册页面110项，三份环境模板共139条变量记录（同名跨模板分别计数）。
这是源码存在性与文档链接核对，不是入口可达性、权限、视觉、真实身份或真机通过证明。按目录归入阅读指南，共用页可能服务多个角色，实际权限以服务端为准。

[车主](../user-guides/OWNER.md) · [商家](../user-guides/MERCHANT.md) · [技师](../user-guides/TECHNICIAN.md) · [受限运营](../user-guides/OPERATOR.md) · [剩余验收](REMAINING_ACCEPTANCE.md)

## 注册页面

- `pages/index/index` — 汽车健康管家；[源码](../../apps/miniapp/src/pages/index/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/finance` — 资金记录；[源码](../../apps/miniapp/src/pages/operator/finance.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/merchant/commissions` — 本店佣金账单；[源码](../../apps/miniapp/src/pages/merchant/commissions.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/community/knowledge` — 有来源专业资料；[源码](../../apps/miniapp/src/pages/community/knowledge.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/knowledge` — 许可资料管理；[源码](../../apps/miniapp/src/pages/operator/knowledge.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/community/public-images` — 图片独立公开授权；[源码](../../apps/miniapp/src/pages/community/public-images.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/community/public-questions` — 问答公开授权与同款读取；[源码](../../apps/miniapp/src/pages/community/public-questions.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/diagnosis/notifications` — 诊断站内消息；[源码](../../apps/miniapp/src/pages/diagnosis/notifications.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/diagnosis/mine` — 非实时人工诊断；[源码](../../apps/miniapp/src/pages/diagnosis/mine.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/diagnosis/detail` — 诊断留言与答复；[源码](../../apps/miniapp/src/pages/diagnosis/detail.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/diagnosis-service` — 诊断受理配置；[源码](../../apps/miniapp/src/pages/operator/diagnosis-service.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/points-policies` — 积分任务规则；[源码](../../apps/miniapp/src/pages/operator/points-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/assessment-policies` — 门店月度考核政策；[源码](../../apps/miniapp/src/pages/operator/assessment-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/monthly-assessment` — 门店月度结算；[源码](../../apps/miniapp/src/pages/operator/monthly-assessment.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/experience-curations` — 同款经验运营精选；[源码](../../apps/miniapp/src/pages/operator/experience-curations.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/price-policies` — 价格监控来源依据；[源码](../../apps/miniapp/src/pages/operator/price-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/price-comparisons` — 只读价格比较；[源码](../../apps/miniapp/src/pages/operator/price-comparisons.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/price-comparison` — 报价比较详情；[源码](../../apps/miniapp/src/pages/operator/price-comparison.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/business-dashboard` — 门店经营看板；[源码](../../apps/miniapp/src/pages/operator/business-dashboard.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/merchant/business-dashboard` — 本店经营看板；[源码](../../apps/miniapp/src/pages/merchant/business-dashboard.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/customers` — 本店服务关系；[源码](../../apps/miniapp/src/pages/merchant/customers.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/customer-relation` — 本店订单关系；[源码](../../apps/miniapp/src/pages/merchant/customer-relation.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/assessment` — 本店月度考核；[源码](../../apps/miniapp/src/pages/merchant/assessment.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/operator/points-exchange` — 积分兑换目录；[源码](../../apps/miniapp/src/pages/operator/points-exchange.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/points/index` — 我的积分；[源码](../../apps/miniapp/src/pages/points/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/points/exchange` — 积分兑换券；[源码](../../apps/miniapp/src/pages/points/exchange.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/diagnosis-cases` — 诊断处理工作区；[源码](../../apps/miniapp/src/pages/operator/diagnosis-cases.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/workspace/notifications` — 站内消息；[源码](../../apps/miniapp/src/pages/workspace/notifications.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/coupons` — 本店券发行；[源码](../../apps/miniapp/src/pages/merchant/coupons.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/operator/coupons` — 运营券池；[源码](../../apps/miniapp/src/pages/operator/coupons.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/coupon/offers` — 领取券；[源码](../../apps/miniapp/src/pages/coupon/offers.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/coupon/mine` — 我的券与有效期；[源码](../../apps/miniapp/src/pages/coupon/mine.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/coupon-book` — 金额券预约；[源码](../../apps/miniapp/src/pages/order/coupon-book.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/coupon/service` — 服务券授权；[源码](../../apps/miniapp/src/pages/coupon/service.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/merchant/coupon-redeem` — 服务券独立核销；[源码](../../apps/miniapp/src/pages/merchant/coupon-redeem.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/ai/plan-booking` — 按本车方案预约；[源码](../../apps/miniapp/src/pages/ai/plan-booking.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/reminder-preference` — 本人提前提醒偏好；[源码](../../apps/miniapp/src/pages/archive/reminder-preference.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/health-policies` — 精确车型健康规则；[源码](../../apps/miniapp/src/pages/operator/health-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/order/refund` — 取消与全额退款；[源码](../../apps/miniapp/src/pages/order/refund.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/owner/index` — 车主端；[源码](../../apps/miniapp/src/pages/owner/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/owner/onboarding` — 商家入驻申请；[源码](../../apps/miniapp/src/pages/owner/onboarding.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/merchant/index` — 商家端；[源码](../../apps/miniapp/src/pages/merchant/index.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/projects` — 本店服务；[源码](../../apps/miniapp/src/pages/merchant/projects.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/slots` — 本店预约时段；[源码](../../apps/miniapp/src/pages/merchant/slots.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/orders` — 本店订单；[源码](../../apps/miniapp/src/pages/merchant/orders.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/order-detail` — 本店订单详情；[源码](../../apps/miniapp/src/pages/merchant/order-detail.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/pickup` — 接车检查；[源码](../../apps/miniapp/src/pages/merchant/pickup.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/redeem` — 核销验码；[源码](../../apps/miniapp/src/pages/merchant/redeem.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/protection` — 施工防护；[源码](../../apps/miniapp/src/pages/merchant/protection.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/staff` — 本店员工与员工码；[源码](../../apps/miniapp/src/pages/merchant/staff.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/profile` — 门店资料；[源码](../../apps/miniapp/src/pages/merchant/profile.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/merchant/location` — 门店位置；[源码](../../apps/miniapp/src/pages/merchant/location.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/check/pickup-detail` — 接车单；[源码](../../apps/miniapp/src/pages/check/pickup-detail.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/order/book` — 预约服务；[源码](../../apps/miniapp/src/pages/order/book.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/list` — 我的订单；[源码](../../apps/miniapp/src/pages/order/list.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/workspace/tasks` — 服务工作台；[源码](../../apps/miniapp/src/pages/workspace/tasks.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/workspace/context` — 本单服务资料；[源码](../../apps/miniapp/src/pages/workspace/context.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/order/archive-grants` — 本单档案授权；[源码](../../apps/miniapp/src/pages/order/archive-grants.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/work-evidence` — 本人施工证据；[源码](../../apps/miniapp/src/pages/order/work-evidence.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/delivery-compare` — 取车对比；[源码](../../apps/miniapp/src/pages/order/delivery-compare.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/detail` — 订单详情；[源码](../../apps/miniapp/src/pages/order/detail.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/review` — 本人订单评价；[源码](../../apps/miniapp/src/pages/order/review.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/review-publication` — 公开星级授权；[源码](../../apps/miniapp/src/pages/order/review-publication.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/public-review-text` — 独立公开评价文字；[源码](../../apps/miniapp/src/pages/order/public-review-text.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/order/review-summary-consent` — 评价AI用途授权；[源码](../../apps/miniapp/src/pages/order/review-summary-consent.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/service/review-summary` — 评价原文要点；[源码](../../apps/miniapp/src/pages/service/review-summary.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/service/review-texts` — 门店公开评价文字；[源码](../../apps/miniapp/src/pages/service/review-texts.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/review-texts` — 公开评价文字审核；[源码](../../apps/miniapp/src/pages/operator/review-texts.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/service/ratings` — 门店公开星级；[源码](../../apps/miniapp/src/pages/service/ratings.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/community/my-cards` — 我的经验卡片；[源码](../../apps/miniapp/src/pages/community/my-cards.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/community/questions` — 我的私有问答；[源码](../../apps/miniapp/src/pages/community/questions.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/community/question` — 私有问题与答复；[源码](../../apps/miniapp/src/pages/community/question.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/questions` — 私有问答工作区；[源码](../../apps/miniapp/src/pages/operator/questions.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/question-service` — 私有问答受理政策；[源码](../../apps/miniapp/src/pages/operator/question-service.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/community/experiences` — 同款施工经验；[源码](../../apps/miniapp/src/pages/community/experiences.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/operator/login` — 运营审核登录；[源码](../../apps/miniapp/src/pages/operator/login.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/catalog` — 标准库维护；[源码](../../apps/miniapp/src/pages/operator/catalog.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/project-applicability` — 车型适用性规则；[源码](../../apps/miniapp/src/pages/operator/project-applicability.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/review` — 经验卡片审核；[源码](../../apps/miniapp/src/pages/operator/review.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/onboarding` — 商家入驻审核；[源码](../../apps/miniapp/src/pages/operator/onboarding.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/quotas` — 区域品类配额；[源码](../../apps/miniapp/src/pages/operator/quotas.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/technician/index` — 技师端；[源码](../../apps/miniapp/src/pages/technician/index.vue)；[阅读指南](../../docs/user-guides/TECHNICIAN.md)。
- `pages/technician/orders` — 我的工单；[源码](../../apps/miniapp/src/pages/technician/orders.vue)；[阅读指南](../../docs/user-guides/TECHNICIAN.md)。
- `pages/technician/order-detail` — 工单详情；[源码](../../apps/miniapp/src/pages/technician/order-detail.vue)；[阅读指南](../../docs/user-guides/TECHNICIAN.md)。
- `pages/technician/work` — 施工报工与质检；[源码](../../apps/miniapp/src/pages/technician/work.vue)；[阅读指南](../../docs/user-guides/TECHNICIAN.md)。
- `pages/merchant/dispatch` — 派工；[源码](../../apps/miniapp/src/pages/merchant/dispatch.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/home/index` — 首页；[源码](../../apps/miniapp/src/pages/home/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/service/index` — 服务；[源码](../../apps/miniapp/src/pages/service/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/service/merchant` — 商家详情；[源码](../../apps/miniapp/src/pages/service/merchant.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/service/detail` — 服务详情；[源码](../../apps/miniapp/src/pages/service/detail.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/ai/index` — AI 管家；[源码](../../apps/miniapp/src/pages/ai/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/ai/service-plan` — 结构化项目建议；[源码](../../apps/miniapp/src/pages/ai/service-plan.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/index` — 档案；[源码](../../apps/miniapp/src/pages/archive/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/shares` — 档案分享管理；[源码](../../apps/miniapp/src/pages/archive/shares.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/shared` — 档案只读摘要；[源码](../../apps/miniapp/src/pages/archive/shared.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/health` — 本人当前读数；[源码](../../apps/miniapp/src/pages/archive/health.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/health-history` — 读数历史与修正；[源码](../../apps/miniapp/src/pages/archive/health-history.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/health-observation` — 来源与读数；[源码](../../apps/miniapp/src/pages/archive/health-observation.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/structured` — 本人档案资料；[源码](../../apps/miniapp/src/pages/archive/structured.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/reminder` — 个人档案目标设置；[源码](../../apps/miniapp/src/pages/archive/reminder.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/reminders` — 个人目标与提醒；[源码](../../apps/miniapp/src/pages/archive/reminders.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/mine/index` — 我的；[源码](../../apps/miniapp/src/pages/mine/index.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/vehicle/entry` — 选择录车方式；[源码](../../apps/miniapp/src/pages/vehicle/entry.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/vehicle/manual` — 手动添加车辆；[源码](../../apps/miniapp/src/pages/vehicle/manual.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/archive/record-add` — 录入车辆档案；[源码](../../apps/miniapp/src/pages/archive/record-add.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/welfare/invite` — 邀请记录与进度；[源码](../../apps/miniapp/src/pages/welfare/invite.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/welfare/invite-entry` — 好友邀请注册；[源码](../../apps/miniapp/src/pages/welfare/invite-entry.vue)；[阅读指南](../../docs/user-guides/OWNER.md)。
- `pages/merchant/invite` — 我的店员推广；[源码](../../apps/miniapp/src/pages/merchant/invite.vue)；[阅读指南](../../docs/user-guides/MERCHANT.md)。
- `pages/operator/invite-policies` — 邀请奖励规则；[源码](../../apps/miniapp/src/pages/operator/invite-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。
- `pages/operator/diagnosis-policies` — 人工诊断邀请条件；[源码](../../apps/miniapp/src/pages/operator/diagnosis-policies.vue)；[阅读指南](../../docs/user-guides/OPERATOR.md)。

## 环境模板变量

只枚举三份受版本控制的模板，不读取.env、*.local或部署环境；不声称覆盖后端全部环境变量。模板字面布尔值仅说明示例取值，不代表运行默认值或已开启。其他值请查模板，真实密钥只在私有环境填写。

### .env.example

[来源模板](../../.env.example)

- `SERVER_PORT`。
- `OCR_PORT`。
- `MYSQL_DATABASE`。
- `MYSQL_PORT`。
- `MYSQL_USER`。
- `MYSQL_PASSWORD`。
- `MYSQL_POOL_SIZE`。
- `MYSQL_ROOT_PASSWORD`。
- `REDIS_PASSWORD`。
- `RABBITMQ_USER`。
- `RABBITMQ_PASSWORD`。
- `MINIO_ROOT_USER`。
- `MINIO_ROOT_PASSWORD`。
- `JWT_SECRET`。
- `WECHAT_APP_ID`。
- `WECHAT_APP_SECRET`。
- `WECHAT_CLOUD_RUN_ENABLED`；模板值 `false`。
- `SMS_PROVIDER`。
- `AI_PROVIDER`。
- `DEEPSEEK_API_KEY`。
- `DEEPSEEK_BASE_URL`。
- `DEEPSEEK_MODEL`。
- `DEEPSEEK_THINKING`。
- `UPLOAD_STORAGE_ENABLED`；模板值 `false`。
- `UPLOAD_SCAN_ENABLED`；模板值 `false`。
- `UPLOAD_ALLOW_INSECURE`；模板值 `false`。
- `MINIO_ENDPOINT`。
- `MINIO_PUBLIC_ENDPOINT`。
- `UPLOAD_MINIO_BUCKET`。
- `UPLOAD_MINIO_ACCESS_KEY`。
- `UPLOAD_MINIO_SECRET_KEY`。
- `UPLOAD_MINIO_REGION`。
- `UPLOAD_STORAGE_TIMEOUT_MS`。
- `UPLOAD_SIGNED_URL_SECONDS`。
- `CLAMAV_HOST`。
- `CLAMAV_PORT`。
- `CLAMAV_CONNECT_TIMEOUT_MS`。
- `CLAMAV_SCAN_TIMEOUT_MS`。
- `UPLOAD_HTTP_CONCURRENCY`。
- `UPLOAD_CLEANUP_INTERVAL_MS`。
- `INPUT_MATERIAL_UPLOAD_ENABLED`；模板值 `false`。
- `SERVICE_ARCHIVE_ENABLED`；模板值 `false`。
- `SERVICE_ARCHIVE_INTERVAL_MS`。
- `SERVICE_NOTIFICATION_DELAY_MS`。
- `EXPERIENCE_CARD_ENABLED`；模板值 `false`。
- `EXPERIENCE_PUBLICATION_ENABLED`；模板值 `false`。
- `MERCHANT_ONBOARDING_ENABLED`；模板值 `false`。
- `INVITE_VERIFIED_IDENTITY_ENABLED`；模板值 `false`。
- `INVITE_REWARD_ENABLED`；模板值 `false`。
- `MERCHANT_STAFF_ENABLED`；模板值 `false`。
- `PAYMENT_QUERY_RECOVERY_ENABLED`；模板值 `false`。
- `PAYMENT_QUERY_RECOVERY_INTERVAL_MS`。
- `PAYMENT_LOCAL_TEST_QUERY_URL`。
- `REFUND_RECOVERY_ENABLED`；模板值 `false`。
- `REFUND_RECOVERY_INTERVAL_MS`。
- `REVIEW_TEXT_PUBLICATION_ENABLED`；模板值 `false`。
- `REVIEW_SUMMARY_ENABLED`；模板值 `false`。
- `AI_SERVICE_PLAN_ENABLED`；模板值 `false`。
- `POINTS_EXCHANGE_ENABLED`；模板值 `false`。
- `POINTS_EARNING_ENABLED`；模板值 `false`。
- `POINTS_VERIFIED_IDENTITY_ENABLED`；模板值 `false`。
- `POINTS_AI_VERIFIED_ENABLED`；模板值 `false`。
- `POINTS_RECOVERY_DELAY_MS`。
- `COMMUNITY_PUBLICATION_ENABLED`；模板值 `false`。
- `PUBLIC_IMAGES_ENABLED`；模板值 `false`。
- `KNOWLEDGE_ENABLED`；模板值 `false`。
- `KNOWLEDGE_AI_ENABLED`；模板值 `false`。
- `MILEAGE_OCR_ENABLED`；模板值 `false`。
- `MILEAGE_OCR_URL`。
- `MILEAGE_OCR_SERVICE_TOKEN`。
- `MILEAGE_OCR_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `OCR_MODELS_ROOT`。
- `OCR_WHEELHOUSE_ROOT`。
- `VIN_DECODE_ENABLED`；模板值 `false`。
- `VIN_DECODER_PROVIDER`。
- `LICENSE_OCR_ENABLED`；模板值 `false`。
- `LICENSE_OCR_URL`。
- `LICENSE_OCR_SERVICE_TOKEN`。
- `LICENSE_OCR_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `ASR_ENABLED`；模板值 `false`。
- `ASR_URL`。
- `ASR_SERVICE_TOKEN`。
- `ASR_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `ASR_JOB_INTERVAL_MS`。
- `ASR_CLEANUP_INTERVAL_MS`。
- `DOCUMENT_ENABLED`；模板值 `false`。
- `DOCUMENT_URL`。
- `DOCUMENT_SERVICE_TOKEN`。
- `DOCUMENT_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `DOCUMENT_JOB_INTERVAL_MS`。
- `DOCUMENT_CLEANUP_INTERVAL_MS`。
- `DOCUMENT_SERVICE_ENABLED`；模板值 `false`。
- `DOCUMENT_CORE_ENABLED`；模板值 `false`。
- `DOCUMENT_PORT`。

### apps/miniapp/.env.example

[来源模板](../../apps/miniapp/.env.example)

- `VITE_API_BASE_URL`。
- `VITE_WECHAT_CLOUD_ENV_ID`。
- `VITE_WECHAT_CLOUD_SERVICE`。

### deploy/cloudrun/env.example

[来源模板](../../deploy/cloudrun/env.example)

- `SERVER_PORT`。
- `MYSQL_HOST`。
- `MYSQL_DATABASE`。
- `MYSQL_USER`。
- `MYSQL_PASSWORD`。
- `JWT_SECRET`。
- `WECHAT_APP_ID`。
- `WECHAT_APP_SECRET`。
- `WECHAT_CLOUD_RUN_ENABLED`；模板值 `true`。
- `SMS_PROVIDER`。
- `CATALOG_GOVERNANCE_ENABLED`；模板值 `false`。
- `AI_PROVIDER`。
- `DEEPSEEK_API_KEY`。
- `DEEPSEEK_BASE_URL`。
- `DEEPSEEK_MODEL`。
- `DEEPSEEK_THINKING`。
- `UPLOAD_STORAGE_ENABLED`；模板值 `false`。
- `UPLOAD_SCAN_ENABLED`；模板值 `false`。
- `INPUT_MATERIAL_UPLOAD_ENABLED`；模板值 `false`。
- `UPLOAD_ALLOW_INSECURE`；模板值 `false`。
- `REVIEW_TEXT_PUBLICATION_ENABLED`；模板值 `false`。
- `REVIEW_SUMMARY_ENABLED`；模板值 `false`。
- `AI_SERVICE_PLAN_ENABLED`；模板值 `false`。
- `INVITE_REWARD_ENABLED`；模板值 `false`。
- `VIN_DECODE_ENABLED`；模板值 `false`。
- `VIN_DECODER_PROVIDER`。
- `LICENSE_OCR_ENABLED`；模板值 `false`。
- `LICENSE_OCR_URL`。
- `LICENSE_OCR_SERVICE_TOKEN`。
- `LICENSE_OCR_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `ASR_ENABLED`；模板值 `false`。
- `ASR_URL`。
- `ASR_SERVICE_TOKEN`。
- `ASR_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `ASR_JOB_INTERVAL_MS`。
- `ASR_CLEANUP_INTERVAL_MS`。
- `DOCUMENT_ENABLED`；模板值 `false`。
- `DOCUMENT_URL`。
- `DOCUMENT_SERVICE_TOKEN`。
- `DOCUMENT_ALLOW_PRIVATE_HTTP`；模板值 `false`。
- `DOCUMENT_JOB_INTERVAL_MS`。
- `DOCUMENT_CLEANUP_INTERVAL_MS`。

网络配置和启用前置条件见[网络运维](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)及[剩余验收](REMAINING_ACCEPTANCE.md)。
