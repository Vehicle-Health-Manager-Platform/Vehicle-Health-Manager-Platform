# R9 A4b：隔离核心业务 HTTP 旅程

日期：2026-10-18。前置 PR160；实施分支 `codex/r9-a4b-isolated-journeys`。本地核心旅程已通过，补充9项身份回归亦通过，代码、测试、报告与文档已上传[PR161](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/161)（代码提交 `7dd43a8`），依赖PR160；完整远程CI待结束，保持草稿，不合并或部署。

## 实施内容

- `IsolatedCoreJourneysTest` 使用真实随机 TCP 端口、真实生产业务 Bean/JWT 校验器、Testcontainers 新 MySQL，依序执行 V001–V062，确认131表。
- 三条独立旅程：车主建车/档案/问诊准备；预约/本地测试支付/接车/确认/派工/施工/签字/核销/归档；商家申请/审核开店/待激活拒绝登录。
- 加入重复请求、同键异载荷、角色/用户/门店隔离、不可用正式支付/AI、无效用券不占名额、异步归档有界等待及关键数据库事实断言。
- 后端支持 `MYSQL_PORT`，默认3306不变，隔离测试使用动态端口。无业务 API、SQL 迁移或前端页面变更。
- 复跑脚本、JUnit证据校验及6项校验器测试；默认全量CI包括新测试并保存机器报告。完整使用说明见[隔离旅程验收](../testing/ISOLATED_CORE_JOURNEYS.md)。

## 前置阶段核实

PR160 当前头796ded7的六项CI全部SUCCESS。后台完整2138项测试、208套件，无失败/错误/跳过；声明核心范围LINE96.14%、METHOD92.90%，六组达标。与A4a本地431项子集报告分别保存，见[完整CI摘要](../testing/reports/R9_A4A_FULL_CI_2026_10_18.json)。完整CI仍存在单类不足：CouponOrderInput行/方法57.14%，InvitePolicyInput方法66.67%；原门禁是六组及整体≥70%，不得描述为每一类均≥70%。本轮增加真实HTTP无效券预约检查，新的覆盖率以本轮完整CI为准。

## 调试记录

1. 首次失败：PER_CLASS测试实例注入发生在Testcontainers beforeAll之前，读取了未启动容器的端口。动态属性初始化中显式启动同一容器，仍由Testcontainers回收。
2. 第二次失败：合成会话复用了refresh_hash占位值，触发唯一约束。改为每个合成会话独立哈希，撤销测试只撤销自己创建的会话。
3. 第三次执行车主与履约两条通过；入驻审批正确返回配额未配置49010。旅程改为先验拒绝且无审核记录，通过真实运营配额PUT接口配置后，原审核键重试；不通过SQL绕过配额。
4. 最终执行：`python scripts/run_isolated_journeys.py --docker` PASS；真实3条旅程，无失败/错误/跳过，耗时175.81秒，Java与迁移源码运行前后哈希一致。见[实际报告](../testing/reports/R9_A4B_ISOLATED_JOURNEYS_2026_10_18.json)。新增校验器6项、既有覆盖率门禁14项Python测试通过，62个Compose迁移挂载检查及diff空白检查通过。补充 `MerchantAuthTest,WechatAuthTest` 共9项通过，无失败/错误/跳过；仍为已有替身/HTTP回归，不是正式身份联调。所有交付Java与迁移文件与成功报告哈希再次核对一致。

## 保留缺口和下一步

- 正式账号激活**尚未实现**：审核开店后PENDING_ACTIVATION且无密码，现有登录必须拒绝。真实激活、短信和微信统一B1，不用SQL改ACTIVE或注入短信成功冒充完成。
- 身份、CLEAN图片元数据是合成夹具，资金为LOCAL_TEST；不证明真实身份、文件上传/病毒扫描、正式支付、真实识别或真机。归档消费采用真实调度任务。
- 三条后端HTTP旅程不等于小程序UI端到端，也不等于原文要求全部API的Postman/Newman正常/边界/错误/未授权验收；该工具化全API覆盖仍保留差距。
- 下一步A4c：隔离核心读写/500并发负载、备份恢复和迁移回滚演练，记录实际结果；之后A5资料、B外部接入、C真机发布。
