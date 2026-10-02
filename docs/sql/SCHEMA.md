# S0 数据模型与字段字典

> 2026-10-02。`init.sql` 和 `migrations/V001__baseline.sql` 由 `python scripts/build_init_sql.py` 按恢复的原始交付文档 v1.0 生成。16 张表取自 §7.2，缺失的公共字段及 23 张扩展表按 [决策记录](../DECISIONS.md) G01–G03 补齐。

## 表清单

| 领域 | 表 | 关键字段及作用 |
| --- | --- | --- |
| 账号和车型 | `user`, `brand`, `series`, `model`, `maintenance_rule` | `openid` 唯一、手机号可空且唯一；品牌车系车型关系、保养规则 |
| 车辆档案 | `vehicle`, `vehicle_archive` | `user_id` 归属、VIN/车牌、档案类型与录入来源 |
| 商家与项目 | `merchant`, `standard_project`, `merchant_project`, `package` | 商家状态、标准项目、商家报价及套餐 |
| 订单履约 | `order`, `pickup_check`, `repair_protection`, `technician_report`, `delivery_compare` | 订单快照、接车确认、防护、报工、取车 |
| 券与增长 | `coupon`, `user_coupon`, `assessment`, `invite_record`, `point_flow`, `point_exchange` | 发行/预占/核销、月考核、邀请与积分账本 |
| 社区与 AI | `community_content`, `community_interaction`, `circle_follow`, `ai_plan_rule` | 内容审核、互动、车型关注、推荐规则 |
| 支付与预约 | `appointment_slot`, `payment`, `refund`, `reconciliation` | 时段容量、支付/退款状态、每日对账 |
| 商家履约支撑 | `merchant_application`, `technician_assignment`, `merchant_commission_policy` | 入驻审核、派工归属、带生效期的佣金政策 |
| 安全与通知 | `notification`, `audit_log`, `idempotency_record`, `file_object`, `staff_account`, `sms_code` | 站内消息、审计、24 小时幂等、私有文件、员工与验证码 |

共 39 张表：来源给出 16 张 DDL、另行点名 10 张、S0 业务契约新增 13 张。来源正文写“25 张”与实际列名不符，不作为建表数量约束。所有表使用 InnoDB、`utf8mb4`、无物理外键；应用层在同一事务校验归属和存在性。

## 公共字段和数据规则

- 每表 `id BIGINT UNSIGNED` 主键，`created_at`、`updated_at`、`is_deleted`。时间存 UTC；展示时转换为用户时区。软删除记录仍须受唯一索引约束，因此账号、业务编号等唯一键不允许删除后直接复用。
- 金额用 `DECIMAL(10,2)` 元；佣金比例使用 `DECIMAL(5,2)`，未配置时为 `NULL`，不能把源示例 `3.00%` 当作真实政策。支付与退款金额以服务器计算和渠道回调校验结果为准。
- `order.project_snapshot/merchant_snapshot/price_snapshot` 在创建订单时写入，当项目或价格下架后仍保留历史事实。`order.status` 与支付、退款各自的状态分开；状态图见 [业务契约](../api/S0_BUSINESS_CONTRACT.md)。`order.coupon_id` 指用户券记录，不能从模板 ID 直接扣减。
- `user_coupon` 通过 `user_id` 与 `status` 查询；预占时记录 `order_id/reserved_until`。同一用户券同一时刻只能处于一个有效状态，服务层在事务中锁定该行。`coupon.issued_quantity` 通过事务条件更新，防止超发。
- `point_flow` 是积分变动账本，`user.point_balance` 是缓存余额；同一事务更新，两者不一致时以账本核对。订单完工生成的 `community_content` 对 `order_id/content_type` 有唯一约束，避免重复卡片。
- `user.openid` 按原始交付文档恢复为可空唯一字段，`user.phone` 可空且唯一，以支持微信授权后再绑定手机号；具体绑定与账号合并规则须在 S1 明确，不能仅凭客户端提交的 openid 建号。
- `file_object.object_key` 仅保存私有对象存储键；客户端取得文件时经归属校验后签发短期 URL。`sms_code` 仅存验证码哈希，`staff_account` 仅存密码或工号哈希，不存明文。手机号、VIN、车牌在日志和对外响应按权限脱敏。
- 索引优先覆盖归属和状态/时间过滤：车辆按 `user_id`、订单按 `user_id/status` 或 `merchant_id/status`、通知按接收人/时间、技师派工按 `technician_id/status`。生产查询中出现超过 200ms 的慢查询时再依据实际执行计划调整索引。

## 迁移与种子数据

`migrations/V001__baseline.sql` 是版本化初始迁移，`init.sql` 是空库快速初始化的同内容副本。两者**择一执行**；不要顺序执行以掩盖迁移问题。重跑 V001 只会跳过已有表，不会修改旧结构；后续字段变化必须新增 V002 等迁移，并记录回滚/灰度策略。测试环境可单独执行 `seed_test.sql`；生产环境不得执行该文件。

此前网页方案的 39 表脚本已在 MySQL 8 CI 空库通过。本次按原始小程序文档重生 V001 后，须重新验证空库与重复迁移；已存在的网页方案开发数据库不会因 `CREATE TABLE IF NOT EXISTS` 自动增加 `openid` 或放宽 `phone`，需要单独的版本化迁移或重建可丢弃的开发库，不可直接用于生产升级。
