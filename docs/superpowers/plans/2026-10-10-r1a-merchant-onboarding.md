# R1a 商家入驻实施计划

依据[规格](../specs/2026-10-10-r1a-merchant-onboarding-design.md)。按"规格→后端→页面→收口"拆四支堆叠 PR，前序未合并时逐支指向直接依赖分支。

1. 核对既有事实并冻结迁移设计：基线 `merchant_application`（V001）扩展字段、新增 `merchant_application_review` 与 `merchant_region_category_quota`、`operator_account.can_onboard`、开关默认关闭；确认与 A5 锁序及 `uk_account` 唯一约束一致。
2. 后端 PR：V020 迁移；仓储/服务/控制器（车主提交/查询/重提、运营列表/详情/审核/配额）；开店单事务与固定锁序；`Idempotency-Key` 幂等；MockMvc 输入/身份矩阵 + MySQL Testcontainers 并发/满额/回滚/唯一/无死锁测试；新增 `docs/api/MERCHANT_ONBOARDING.md` 契约并更新 OpenAPI。
3. 小程序 PR：车主申请表单（品类/区域/资质私有上传）、申请进度与驳回重提页；运营入驻审核列表/详情/固定码驳回与配额维护页（复用 operator 独立会话模式）；服务层与离线测试，微信/H5 构建通过。
4. 真实联调收口 PR：真实后端容器 + 隔离库 + 微信开发者工具模拟器走全验收矩阵（开店/驳回重提/满额/并发/重复/过期会话/越权/不安全文件/失败回滚），清理合成身份数据，整理执行与验收中文记录。
5. 每阶段更新 `CURRENT_STATUS.md`/`NEXT_STEPS.md` 并上传 GitHub；最终 head 六项 CI 全绿后进入 R1b。

后续 R1b：店员/技师维护、员工码受控签发与撤销、门店资料与标准项目/车型治理（见[业务优先规划](../../progress/THREE_ROLE_BUSINESS_PLAN_2026-10-10.md)）。正式身份、真实短信、账号激活与真机统一 R9 收口。
