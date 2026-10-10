# R1b 门店员工与资料实施计划

依据[规格](../specs/2026-10-11-r1b-staff-store-design.md)。按"规格→后端→页面→收口"拆四支堆叠 PR，前序未合并时逐支指向直接依赖分支。

1. 冻结设计：新增角色 `STAFF`；确认权限矩阵（执行面同级、管理面限店长）；核对授权放宽 9 个落点（`SecurityConfig`、`MerchantIdentityRepository.active()`、`AuthTokens`、`MerchantActor`、`ReservationStore.merchant()`、`MerchantQuotes`、`JdbcUploadRequests`、`OrderReviews`、`ServiceWork`）；确认 V021 只加两列、不建平行表；确认员工码走"轮换语义、无幂等键、明文不落库"。
2. 后端 PR：V021 迁移；`com.autocare.platform.merchant` 包下的员工维护服务/控制器（创建、列表、启停、员工码签发/撤销）与门店资料服务/控制器（读、店长写+审计）；停用单事务撤销会话与微信绑定；按上表放宽 9 处授权（`MerchantQuotes` 写保持店长专属）；MockMvc 输入/身份矩阵 + MySQL Testcontainers 覆盖跨店 404、店员 403、停用后原会话即刻 401、启停幂等、账号并发唯一、员工码轮换与重放不返回旧码、门店资料白名单与审计；新增 `docs/api/MERCHANT_STAFF.md` 契约并更新 OpenAPI。
3. 小程序 PR：店长端员工列表、新增员工、启停、员工码签发/撤销、门店资料编辑页；店员复用同一商家登录入口（文案改为"门店账号"）；技师侧工作台在账号停用/绑定撤销时给出明确下一动作；服务层与离线测试，mp-weixin 与 H5 构建通过。
4. 真实联调收口 PR：真实后端容器 + 隔离库 + 合成身份，写 `scripts/local_merchant_staff_e2e.cjs` 走全验收矩阵（本店归属、停用即时阻断会话、员工码签发/撤销/旧码失效、跨店拒绝、店员越权 403、门店资料白名单与审计、无残留），清理合成数据，整理执行与验收中文记录。
5. 每阶段更新 `CURRENT_STATUS.md`/`NEXT_STEPS.md` 并上传 GitHub；最终 head 六项 CI 全绿后进入 R1c。

后续 R1c：标准项目/品牌车系车型维护与停用审计（见[业务优先规划](../../progress/THREE_ROLE_BUSINESS_PLAN_2026-10-10.md)）。真实短信、真实微信员工绑定与账号激活统一 R9 收口。
