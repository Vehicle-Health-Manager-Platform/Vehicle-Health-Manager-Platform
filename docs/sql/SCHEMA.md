# S0 数据模型与字段字典

> 2026-10-02。`init.sql` 和 `migrations/V001__baseline.sql` 由 `python scripts/build_init_sql.py` 按恢复的原始交付文档 v1.0 生成。16 张表取自 §7.2，缺失的公共字段及 23 张扩展表按 [决策记录](../DECISIONS.md) G01–G03 补齐。

`migrations/V002__staff_wechat_identity.sql` 是独立版本化迁移，不改变上述生成的 V001 基线。它为技师添加按 AppID 区分的微信绑定历史；同一 AppID 下一个微信身份和一个员工账号各只能有一条有效绑定。解除绑定保留历史记录。应用层仍需校验员工角色、账号状态和商家状态；唯一索引不能代替授权。

## 当前迁移版本（2026-10-11）

当前结构为 V001–V021，共 63 张表；生成的 V001 仍是 39 表基线，后续结构通过独立迁移叠加。新 Compose 数据卷按版本顺序初始化，已有数据库先备份再逐项补迁移。

| 版本 | 当前业务结构 |
| --- | --- |
| V003–V004 | 会话生命周期、上传请求及私有文件接入 |
| V005–V006 | 档案私有附件关系、不可变商家报价版本 |
| V007–V008 | 预约与订单快照、支付事件去重和异常记录 |
| V009 | 履约前置时间列与 `order_status_transition` |
| V010 | 商家上传主体类型、七图接车单、`pickup_check_file` |
| V011 | `pickup_check.dispute_reason` 为 500 字；`order_status_transition.note` 扩为 500 字，保存完整车主异议原因 |
| V012 | 既有派工表补可空 `assigned_by` 与 `accepted_at`，不填历史假凭证，保留订单唯一键 |
| V013 | 新增 `order_dispute`（一单一争议）与 `order_dispute_record`（只追加时间线）；`pickup_check.owner_confirm` 注释补 `3争议已解决` |
| V014–V015 | 施工私有证据/完整报工提交关系；订单唯一可信核销、防猜计数 |
| V016–V017 | 不可变本人评价及私有附件；可靠施工归档任务，订单/评价/输出唯一 |
| V018 | experience_card：私有结构化摘要，订单/档案各唯一；授权版本/时间、撤回、revision；不回填历史、不自动公开 |
| V019 | operator_account独立密码/权限与experience_card_moderation只追加审核，card/revision及公共UUID唯一；累计61表，历史不补授权或审核 |
| V020 | merchant_application扩展（地址/联系电话/revision/merchant_id/驳回码）、新增只追加merchant_application_review与merchant_region_category_quota、operator_account.can_onboard、merchant.region_code；累计63表 |
| V021 | staff_account补display_name与created_by，供店长维护本店店员/技师；不新建平行表，员工码哈希沿用V001列；只扩列不改旧数据 |

V011 复用既有 `pickup_check.owner_confirm`（0 待决定、1 确认、2 异议）、`confirm_at` 和 `order.owner_confirmed_at`。单据决定、订单变化、审计与幂等响应同事务提交，契约见[车主接车单决定](../api/PICKUP_OWNER_DECISION.md)。CI 同时检查 V011 重复执行与两处原因容量。

V012 已有数据补迁移前先备份并盘点异常派工；新派工写派工人，新接单写接单时间。历史空字段、未知状态、逻辑删除占唯一键、归属或时间不一致返回 40905，人工核对后处置，不自动覆盖/改派。回滚应用保留新增列与成功审计。见[派工契约](../api/TECHNICIAN_DISPATCH.md)。

V013 明确 `owner_confirm` 第三态：**0 未决定 / 1 已确认 / 2 已异议 / 3 争议经复核接受**，`1` 与 `3` 同等地满足派工的车主确认前置。`order_dispute` 用 `uk_order` 保证一单只有一条争议单，`from_status` 记录争议前状态（恢复目标只能是 `PAID`/`RECEIVED`/`IN_SERVICE`/`PENDING_VERIFY`），`status` 只有 `OPEN`/`RESOLVED`。`order_dispute_record` 只追加，`idx_dispute` 支撑时间线读取。**不回填历史异议**：A4 之前没有争议单的订单恢复一律拒绝（fail-closed），由人工处理。CI 同时检查 V013 重复执行后唯一键、时间线索引与列注释。见[争议处理契约](../api/DISPUTE_RESOLUTION.md)。

## 表清单

| 领域 | 表 | 关键字段及作用 |
| --- | --- | --- |
| 账号和车型 | `user`, `brand`, `series`, `model`, `maintenance_rule` | `openid` 唯一、手机号可空且唯一；品牌车系车型关系、保养规则 |
| 车辆档案 | `vehicle`, `vehicle_archive`, `vehicle_archive_file`（V005） | `user_id` 归属、VIN/车牌、档案类型与录入来源；档案图片以有序私有文件 ID 引用 |
| 商家与项目 | `merchant`, `standard_project`, `merchant_project`, `package` | 商家状态、标准项目、商家报价及套餐 |
| 订单履约 | `order`, `pickup_check`, `order_dispute`（V013）, `order_dispute_record`（V013）, `repair_protection`, `technician_report`, `delivery_compare` | 订单快照、接车确认、争议单与处理/复核时间线、防护、报工、取车 |
| 券与增长 | `coupon`, `user_coupon`, `assessment`, `invite_record`, `point_flow`, `point_exchange` | 发行/预占/核销、月考核、邀请与积分账本 |
| 社区与 AI | `community_content`, `community_interaction`, `circle_follow`, `ai_plan_rule` | 内容审核、互动、车型关注、推荐规则 |
| 支付与预约 | `appointment_slot`, `payment`, `payment_event`, `payment_exception`, `refund`, `reconciliation` | 时段容量、支付事件去重与异常、支付/退款状态；对账任务尚未交付 |
| 商家履约支撑 | `merchant_application`, `technician_assignment`, `merchant_commission_policy` | 入驻审核、派工归属、带生效期的佣金政策 |
| 安全与通知 | `notification`, `audit_log`, `idempotency_record`, `file_object`, `staff_account`, `sms_code` | 站内消息、审计、24 小时幂等、私有文件、员工与验证码 |

V001 基线共 39 张表：来源给出 16 张 DDL、另行点名 10 张、S0 业务契约新增 13 张；应用 V002 后共 40 张。来源正文写“25 张”与实际列名不符，不作为建表数量约束。所有表使用 InnoDB、`utf8mb4`、无物理外键；应用层在同一事务校验归属和存在性。

V005 在 V004 之后新增 `vehicle_archive_file`，以 `(archive_id,position)` 保留图片顺序，唯一 `(archive_id,file_id)` 阻止同一档案重复关联。文件归属、未删除及 `CLEAN` 状态由写入事务验证；旧 `attach_urls` 列不用于新手动录入流程。当前新建 Compose 数据卷自动应用 V001–V012；已有库须按版本顺序补迁移。

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

本次按原始小程序文档重生的 V001 已在 [CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36974338523) 通过 MySQL 8 空库与重复迁移验证。已存在的网页方案开发数据库不会因 `CREATE TABLE IF NOT EXISTS` 自动增加 `openid` 或放宽 `phone`，需要单独的版本化迁移或重建可丢弃的开发库，不可直接用于生产升级。
