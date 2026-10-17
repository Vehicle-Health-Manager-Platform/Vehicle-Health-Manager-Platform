# R8b1 考核来源政策本地验收

2026-10-16。本步只配置规则，scoring_available=false；不声明整R8或正式渠道、真机验收完成。

## 后端

主组43/43通过、无失败/错误/跳过，日志`.cache/r8b1-backend.log`，Maven BUILD SUCCESS：

- JdbcAssessmentPoliciesTest 25（新政策10、继承既有券库存15）。
- JdbcOperatorAdminAssessmentTest 12（新授权4、继承既有运营身份8）。
- AssessmentPolicyHttpTest 3；AssessmentPolicyNoDatabaseTest 1。
- JdbcOperatorAdminPointsTest定向旧积分权限与登录兼容2。

合计43不同用例，新18/原25，继承不重复计数。最终强化登录精确11字段/九权限位断言后定向再验1/1通过（`.cache/r8b1-login.log`），属同一用例，不累计为44。

覆盖独立权限/当前会话及回执重验、门店/店长范围和普通员工拒绝、有效门店来源、目标/证据/等级严格输入、北京未来整月/截止排除、草稿CAS/发布冻结/停止、跨运营同店并发唯一发布、永久回执/通用过期/嵌套对象规范化与同键异文、审计失败原子回滚、默认关闭/显式离线授予撤销与旧会话撤销、无HTTP提权、no-store/单层回执和无库503。旧assessment合成99分保留而不出现在政策响应，不发券或积分。

## 前端、构建及迁移

- `npm test`709/709通过（新增8），日志`.cache/r8b1-miniapp.log`。覆盖目标/证据/等级/固定权重/年月转换、精确API与原请求响应、嵌套键顺序语义、独立权限与已知旧登录形状、静态回跳、账号隔离恢复/多页比较、未知结果重建、存储失败及切号迟到响应。
- 微信构建及全局原生样式/无测试身份桥检查通过（`.cache/r8b1-mp.log`）；H5通过（`.cache/r8b1-h5.log`）。实际Vue编译器核对配置页表单、冻结确认、受测flow及全部处理函数绑定。
- 真实MySQL V001–V053初始化与重复V053、112表、默认can_assessment=0、无政策种子、既有积分/券凭证保留通过（`.cache/r8b1-schema.log`）。
- OpenAPI276操作、927引用全部解析；登录user精确11项、role=operator、九布尔权限位。90页面及原首路由、53有序唯一SQL初始化挂载、Compose config解析通过。
- README、141条追踪文件和原44表init.sql基线内容保留；文档相对链接及git diff --check通过。当前HEAD仍d4c7763，R8未提交或上传。

## 后续门禁

本步未执行完整后端全量、实际Compose运行、四兼容Web、正式短信/微信身份/资金、真机及发布；整R8统一收口再做完整门禁和同批上传。下一[R8b2样本与计算](../progress/R8B2_ASSESSMENT_SAMPLES_NEXT_PLAN.md)，不得将配置成功当作正式出分。
