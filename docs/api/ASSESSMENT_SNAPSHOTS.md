# R8b2a 月度考核冻结与修订

2026-10-16。后端及[R8b2b运营/门店页面](MONTHLY_ASSESSMENT_VIEWS.md)已接通。按用户确认的次月结算、真实使用事实、全退原月剔除和不可变修订执行，整个R8完成后才统一上传。当前四接口仅独立`can_assessment`运营可用，无HTTP提权或门店选择器越权。

## 接口

所有响应no-store。统一ApiResponse单层data，写入返回服务器回执；未知/重复query、正文额外字段、重复属性和尾随JSON拒绝。

- `GET /api/admin/assessment/snapshots?page=1&page_size=20`：所有门店/月/修订按id降序分页，每页20、页码1–1000000；保留每个历史修订，不把旧快照当实时成绩。
- `POST /api/admin/assessment/snapshots`：UUID `Idempotency-Key`，严格`merchant_id`、`period_month`（YYYY-MM）、`expected_revision`（0至9007199254740990）。只允许已经结束的北京自然月；首次期望0，复算使用当前版本。冲突重新读取，不换键覆盖未知请求。
- `GET /api/admin/assessment/snapshots/{id}`：完整最小冻结记录。
- `GET /api/admin/assessment/snapshots/{id}/facts?page=1&page_size=20`：原顺序逐项事实分页，不返回私有报工正文、电话、URL或核销码。

每次请求含永久回执重放均实时核验运营账号/会话/独立权限。门店、车主、技师及只有积分/券等权限的运营不能调用。店长和员工本人读取已由[R8b2b范围接口](MONTHLY_ASSESSMENT_VIEWS.md)接通；本篇四个运营接口仍不能由门店身份调用。

## 政策及未知

服务器按门店/月选择覆盖整月、月开始前已发布的政策；PUBLISHED且未停，或有证明停止时点不早于该月截止的STOPPED可用。无唯一可信政策则policy=null，三维度UNKNOWN，总分/等级null。不为旧STOPPED补停止时间，不导入旧assessment，不给政策生效前历史补分。

结果字段：id、merchant_id、period_month、revision、timezone=Asia/Shanghai、policy、summary、facts_hash、calculation_version=R8B_CALC_V1、captured_from/to。policy为R8b1冻结政策加stopped_at（可null），不是对当前政策再次查询。

summary含state=CALCULATED|UNKNOWN、metrics.acquisition/coupon/process、total_score（两位小数字符串或null）、grade（code/label或null）、fact_count。每维度numerator/denominator、state=KNOWN|UNKNOWN、score、reason。UNKNOWN时分子/分母是候选统计，不能当作已核验完整经营事实；无有效过程分母未知，有明确正目标而实际0次可为真实0分。任一维度未知则总分和等级均null。

## 冻结事实与公式

逐项仅dimension、source_type、source_id、occurred_at、staff_id、state、reason、evidence、proof_hash。维度ACQUISITION/COUPON/PROCESS；来源INVITE/ORDER/CASH_ORDER/UNPROVEN_ORDER/SERVICE_REDEMPTION；状态INCLUDED（计入）、INCOMPLETE（过程缺项）、EXCLUDED（测试/不合格或全退）、UNKNOWN（来源无法核验）。员工归因保留staff_id，邀请用永久record_id，不暴露新客账号。

接车/防护/报工/签字证据为PRESENT/MISSING/UNKNOWN；过程evidence另有私有源元数据的指纹，金额券有full_refund标记。图片文件名、路径及文字本体均不随事实输出。

拉新与券为`min(numerator/target,1)×40`，过程为`complete/completed×20`。BigDecimal维度两位HALF_UP，总分为三项已舍入分之和，按总分匹配最高达成的配置等级。所有分母和等级来自冻结政策/事实，无默认等级分界。

订单以可信完成凭证redeemed_at归月，正式WECHAT付款/事件与完成流转匹配，LOCAL_TEST明确排除；孤立完成状态不当可信完成。金额券每完成订单一次，原发行/分配/消费事件匹配，可信全退及返券后原月券次数剔除；过程保留原完成施工事实。服务券按独立核销receipt一次，无支付，永久服务器身份为TEST则排除、来源未知则未知。拉新按R6资格时点和原员工门店归因，不擅自分配OWNER邀请。

## 事务、重试与限制

永久创建者/UUID/规范化正文哈希/首次结果，不受通用24h回执过期影响；同键异文400。旧成功回执在权限复核后恢复原结果，不重复审计或改变版本，门店后续停用不会改旧事实。实际新建/复算要求门店当前有效。

月份头串行CAS，修订、逐项事实、版本、回执和审计同事务；原事实读取为独立REPEATABLE_READ同一快照，记录采集区间。源在采集后变化通过下次显式新增修订体现，不自动改旧包。每个候选查询最多10000项，超过容量/超时503且整笔回滚，不截断出分。

400无效输入/原键异文，401会话失效，403缺独立权限，404资源不可用，409月份未结束/CAS冲突，503数据库/审计/来源容量不可用。503或响应丢失保留原键及完整正文重试。

详见[设计](../progress/R8B2A_MONTHLY_SNAPSHOT_DESIGN.md)、[操作](../operations/ASSESSMENT_SNAPSHOTS.md)。
