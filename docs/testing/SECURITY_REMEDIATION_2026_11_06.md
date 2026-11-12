# 2026-11-06 依赖漏洞修复与复验

## 修复内容

- 后端使用 Spring Boot 4.0.9 和 Spring MVC 7.0.10，修复 CVE-2026-47884 与 CVE-2026-47890。继续使用 Jackson 2.21.8 序列化现有业务 API，迁移 Web MVC 测试入口，并保留 Testcontainers 1.21.4 测试夹具。参见 [XsltView 公告](https://spring.io/security/cve-2026-47884)和 [SSE 片段公告](https://spring.io/security/cve-2026-47890)。
- 嵌入服务使用 Transformers 5.22.1 和 PyTorch 2.13.0+cpu。四项 Transformers 高危发现及中间复扫新增的 PyTorch CVE-2026-24747 均已从最终扫描中消失。Linux CPU wheel 清单和安装锁均使用官方制品 SHA-256。参见 [CVE-2026-4372](https://github.com/advisories/GHSA-29pf-2h5f-8g72)、[CVE-2026-5241](https://github.com/advisories/GHSA-fgcw-684q-jj6r)、[CVE-2026-80047](https://github.com/advisories/GHSA-x9r9-c232-4q39)、[CVE-2026-9856](https://github.com/advisories/GHSA-xrqw-3rrv-vx5w)和 [PyTorch 公告](https://github.com/pytorch/pytorch/security/advisories/GHSA-63cw-57p8-fm3p)。
- OCR 的 Windows 和 Linux 运行锁更新到 setuptools 84.2.1，消除旧锁中的 CVE-2026-59890 与 GHSA-xmj3-9gf7-h52w。嵌入运行锁也使用同一已验证的通用 wheel。参见 [CVE-2026-59890](https://nvd.nist.gov/vuln/detail/CVE-2026-59890)和 [setuptools 公告](https://github.com/advisories/GHSA-xmj3-9gf7-h52w)。

## 扫描与验证

- 严格安全扫描完成，Java 和 Python SBOM 均无漏洞发现，npm audit 为 0，`vulnerability_gate_passed=true`。6 条本地 Semgrep 规则保留 216 条既有 `java-concatenated-jdbc` WARNING，需结合参数来源复核。报告中的 `production_security_accepted` 保持 `false`。
- 后端 58 项定向测试全部通过，涵盖鉴权、车辆、VIN、真实 MySQL 上传、测试支付与 JSON 视图边界。嵌入 27 项已有测试及安全扫描器 2 项测试通过。
- 固定权重 BGE 在无网络、只读 Linux 容器中通过 16 项检查，覆盖真实推理、输入预算、私有 HTTP 鉴权和内核拒绝联网。实际模型联调另通过 34 项 Java/MySQL 索引测试与 5 项公开检索 HTTP 测试。
- OCR 的 Windows 固定模型及私有 HTTP 合成图验证通过。Linux 通过 81 项测试，失败、错误和跳过均为 0。Linux 固定模型正确读取合成里程 123456 km，隔离、预热复用和资源清理通过。
- Docker Desktop 同步 `exec` 曾在较长的模型验收中返回 500。同配置下直接启动容器通过。验收脚本改为后台执行并轮询退出码，保留原有超时、资源限制、日志和清理检查，随后完整联调通过。
- 后端全量首轮完成 55 项后，主机物理内存不足导致测试 JVM 的 JIT 编译线程无法申请本地内存。重跑限制 JVM 堆为 1 GiB、可见处理器为 4、编译线程为 2，并限制代码缓存为 128 MiB。全量套件和覆盖率门禁继续使用原运行器。
- 11 月 7 日完成后端全量核验。主机停顿使 `JdbcOrderRedemptionTest` 首次尝试在 MySQL 容器启动时超时，其余 254 套共 2,625 项通过。临时阻止自动睡眠后，该套件独立复跑 17 项全通过，源码与其余 JUnit 报告的 SHA-256 均保持一致。用 JaCoCo 0.8.12 合并两段执行数据后，严格检查通过，共 255 套、2,642 项，失败、错误和跳过均为 0。九组声明核心范围的行覆盖率为 95.87%，方法覆盖率为 93.86%。原始失败报告和补测来源保存在本地 `.cache/security-remediation/segmented-backend-evidence/`。
- 后端可运行 JAR 构建成功，已检查其内置 Spring Boot 4.0.9、Spring MVC 7.0.10 和 Jackson Databind 2.21.8。构建产物位于 `backend/target/platform-backend-0.1.0-SNAPSHOT.jar`。

## 2026-11-12 SQL 拼接告警人工复核

- 对严格扫描报告中的 216 条 `java-concatenated-jdbc` 告警按调用点和 SQL 片段来源完成复核，覆盖 74 个 Java 文件。该规则只检测 JDBC 调用参数中出现字符串拼接，不追踪输入来源，因此会同时报告格式化 SQL 常量和内部片段。
- 复核到的可变 SQL 片段来自固定分支、枚举白名单、内部布尔锁标记、固定谓词和由数量生成的 `?` 占位符。分页、日期、搜索词、身份 ID 等运行时值均作为 JDBC 绑定参数传入。目录表名/列名、车型查询类型和排序方向均由白名单或封闭分支选择；搜索文本经过 LIKE 转义后仍以绑定参数传入。
- 未发现将 HTTP 请求、数据库值或环境输入直接拼入 SQL 的可利用路径；本轮没有 SQL 注入代码修复。保留这 216 条扫描告警，不通过忽略规则或降低严重级别来隐藏它们。扫描器当前只能提示人工复核，不能据此宣称 SQL 注入扫描为零。
- 后续改进项：将该规则升级为可追踪 Spring 请求输入到 JDBC SQL 结构的污点分析，并为当前已复核片段建立精确的规则排除或安全构造器；调整后应使用故意构造的恶意输入回归验证，确保真正的动态 SQL 注入仍会触发告警。

## 复跑与升级

在 Java 17、Maven、Node.js、Python 3.11 和本地 Docker 环境中执行严格扫描。扫描器在发现高危或严重漏洞时会非零退出。

```powershell
python scripts/run_security_audit.py --maven-repository <本地Maven缓存绝对路径>
```

旧嵌入向量使用旧 profile 指纹。启用升级后的嵌入镜像时，从实际验收报告取得新 profile，按[知识嵌入维护手册](../operations/KNOWLEDGE_EMBEDDING_LIFECYCLE.md)重建对应索引，再切换到新 profile。固定模型的合成输入检查用于验证升级兼容性，真实语料质量和生产安全另行验收。
