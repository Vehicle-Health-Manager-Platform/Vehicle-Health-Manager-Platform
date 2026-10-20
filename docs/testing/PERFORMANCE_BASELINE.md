# 核心接口性能基线（500 并发）

## 复跑

项目根目录执行，要求 Python 3.11+ 与可用的 Docker（脚本会用到 `mysql:8.0` 与 `maven:3.9-eclipse-temurin-17` 两个镜像，首次会自动拉取）：

```powershell
python scripts/run_performance_baseline.py --report docs/testing/reports/R9_A4C2_PERFORMANCE_BASELINE.json
```

不需要本机 Maven/JDK，也不需要任何业务凭据。脚本没有"已有数据库/已有容器"选项：每轮都新建带随机标签的网络、MySQL 和后端容器，所有 SQL/启停/清理都会先核对完整容器 ID、名称、角色和本轮标签。结束时回收容器与网络，`--rm` 负责删除匿名卷，脚本再确认对象确实不存在。

退出码 0 且报告 `passed=true` 才算通过；失败时按日志指出的**本轮目录**查看，不能拿历史成功报告代替。

输出：

- `.cache/performance-baseline-<随机编号>/report.json`：完整证据。
- `--report` 指定的路径：同一份内容的副本（用于提交到仓库或作为 CI artifact）。

## 报告字段

- `started_from_commit`、`duration_seconds`、`implementation_inputs_sha256`：可追溯到具体提交与脚本/迁移哈希；执行期间源码或迁移发生变化即判失败。
- `environment`：宿主平台、CPU 型号与逻辑核数、内存、Python 版本、Docker Server 版本、运行时镜像、JVM 参数、MySQL 版本与 `max_connections`、连接池大小、端口发布方式、客户端位置。
- `dataset`：车主/车辆/档案/附件/门店/报价/时段/会话行数。
- `method`：并发数、各场景轮次、预热轮次、分位算法、测量口径、连接复用方式、订单分布。
- `scenarios[]`：请求数、错误数与错误分类、错误率、每秒采样数、`latency_ms`（min/mean/p50/p90/p95/p99/max）、阈值、`checks[]`、`latency_target_met`、实际生效的检查（`enforced_checks`）。
- `database_after_load`：订单数、被占用时段数、幂等记录数。
- `cleanup`：每个自有对象的回收结论。

## 门禁策略

`--gates all`（默认）：错误率 + 延迟共同判定，用于固定硬件的验收执行。
`--gates errors`：只判错误预算，延迟仍然测量并写入报告，同时给出 `latency_target_met`。CI 使用这一档，因为 GitHub 托管 runner 的核数与邻居负载不受本项目控制，把 4 核机器的分位当验收结论会得出错误判断；真正对原文 300/800/500ms 门禁负责的是记录硬件的验收报告。

`python scripts/run_performance_baseline.py --check-only <报告路径>` 只校验已有报告：场景齐全、阈值未漂移、采样数等于并发×轮次、检查项与结论自洽、清理与数据量证据存在、并发确为 500。它不代表重新执行过一次。

## 调参

| 参数 | 默认 | 用途 |
| --- | --- | --- |
| `--concurrency` | 500 | 并发工作线程数（原文门禁值，报告校验要求 500） |
| `--client-processes` | 10 | 负载端进程数，必须能整除并发数（每进程再开若干线程，绕过 Python GIL） |
| `--read-rounds` / `--write-rounds` | 3 / 2 | 测量轮次 |
| `--warmup-rounds` | 1 | 预热轮次，不计入统计 |
| `--pool-size` | 40 | 后端 `MYSQL_POOL_SIZE`，写入报告 |
| `--gates` | `all` | `all` / `errors` |

## 本轮为达标修正的服务端实现

基线的价值在于暴露真实瓶颈；下面四处是测量过程中定位到、并在本轮修正的实现问题，都改的是加锁范围或查询次数，没有放宽任何业务校验。

| 位置 | 现象 | 修正 |
| --- | --- | --- |
| `ArchiveService.list` | 档案列表 20 行逐行查附件（N+1） | 改为一次 `IN (...)` 批量取回后按档案归组 |
| `ReservationStore.quote` | 带 `FOR UPDATE` 的 join 对共享目录行 `standard_project` 取排他锁，全平台下单被一行串行（实测约 50 单/秒） | 目录行改 `FOR SHARE`，仅对门店报价与版本行 `FOR UPDATE OF q,v` |
| `ReservationOrders.create` / `ReservationExpiry.closeOrder` | 席位判定用 `SELECT ... FOR UPDATE` 统计 `order`，小表时优化器选全表扫描（`EXPLAIN type=ALL`），行锁覆盖整表与 supremum 记录，并发下单互相死锁 | 席位改按时段行 `reserved_count` 计数（持时段行锁时增减），统计只用于展示且不加锁 |
| `ReservationExpiry.expireSlot` | 关闭过期占位用范围 `FOR UPDATE`，即使没有过期订单也会锁住相邻时段的间隙，与并发插入死锁（证据见执行记录） | 先无锁取候选 id，再按主键逐行 `FOR UPDATE` 关闭 |

回归覆盖：`JdbcReservationsTest` 新增"并发抢名额恰好 capacity 个成功其余 `40903`"与"过期占位在下次下单前释放"两例；`JdbcReservationsTest`、`JdbcOrderRefundsTest`、`JdbcPaymentsTest` 共 80 项通过。

## 已知边界

单机单容器测量，不等于生产容量；不做长稳、故障注入、冷启动与真实网络；订单创建按 300 家门店分布，单一门店仍会在门店行锁上串行；AI、微信、短信与真实支付不在计时路径内。小程序界面与真机需在 R9-C 单独验收。
