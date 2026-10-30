# 2026-10-29 脱敏证据索引

结论与边界以[阶段报告](../../STAGE_ACCEPTANCE_2026_10_29.md)为准。不同来源/阶段不能拼成一次所有接口均通过，也不能把模拟身份或上游替身算作正式服务验收。

- `local-regression.json`：最终12组，明确未请求后端；源码摘要绑定。
- `backend-full-run.json`：同次完整252套/2627项，源码未变、Maven退出0、无失败/错误/跳过。
- `backend-full-coverage-missing.json`：原覆盖率采集失败，保留不覆盖。
- `backend-full-coverage.json`、`coverage-recovery-provenance.json`：仅本次完整会话，使用保留class及JUnit恢复并严格审计。历史两次会话已排除；不表示整个平台覆盖率。
- `coverage-ascii-path-probe.json`：同POM/JDK的纯ASCII路径环境对照，仅一项代理探针，不是业务测试。
- `backend-first-failure.json`、`backend-native-stopped.json`：初始嵌套Docker失败及宿主中止失败；不能计为完整通过。
- `embedding-retry-selected.json`、`pooled-fixture-selected.json`：早期独立98/140项对照，最终集成以完整执行为准。
- `business-api-baseline.json`：29套首轮汇总原样保留失败；`auth-session-fixed.json`是会话修复后独立新库通过，不能改写原汇总。
- `community-final.json`：最终业务实现的社区176次新HTTP；`post-core-api.json`为随后同最终输入的新社区执行及完整后端原始七旅程/十识别接口再审，AUDIT_EXISTING不另算新发请求。
- `core-journeys.json`：同次完整后端三条核心旅程的JUnit复核。
- `compose-smoke.json`、`compose-business.json`：缓存测试镜像的本地整栈与LOCAL_TEST闭环，不能证明生产Dockerfile构建成功；业务照片CLEAN元数据为夹具。
- `guest-pages.json`、`role-pages.json`、`vehicle-database.json`及四张PNG：gstack辅助H5关键页面、同一订单三角色状态及本人录车/他人数据不变，非原生手机或正式身份验收。
- `database-recovery.json`：65迁移/134表、19项恢复/回退检查；不是生产PITR或对象存储恢复。
- `performance-all-gates.json`、`performance-pool20.json`：两轮500并发均未通过延迟门限；零错误/写入一致不能代替整体容量通过，池20不采纳。
- `delivery-integrity.json`、`resource-cleanup.json`：最终摘要/链接/凭证格式检查和容器/卷/网络只读清理核验。

原始Newman、合成会话、私有环境、SQL转储、执行数据和调查日志保留在本机`.cache`。不要整目录上传；也不要因误编码覆盖率目录含历史数据而整体删除它。
