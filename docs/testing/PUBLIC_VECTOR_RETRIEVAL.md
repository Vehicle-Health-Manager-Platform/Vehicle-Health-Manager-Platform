# 当前版本验收复现（R5c2）

结果以[阶段报告](../progress/RECOGNITION_R5C2_PUBLIC_VECTOR.md)和[机器汇总](PUBLIC_VECTOR_RETRIEVAL_2026_10_28.json)为准。只有已结束且通过的报告计入证据；运行中、首轮中断和失败记录继续保留。本地身份/资料/资金为合成或LOCAL_TEST，真实模型与模拟AI分开验证。

## 默认检查和完整后端

准备Java17/Maven3.9、Node22/npm锁文件、Python3.11及所需协议测试依赖、可用Docker Linux Engine。先参照[本地配置](../delivery/LOCAL_SETUP.md)；首次镜像/依赖下载需要网络。当前终端使用UTC，避免JVM本地日期产生不同边界语义。

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.timezone=UTC'
python scripts/run_local_delivery.py
mvn -B -f backend/pom.xml -Pcore-coverage clean verify
python scripts/check_core_coverage.py
```

默认12组不含完整后端。完整核心模式执行普通后端测试，只排除三个需要显式实际资源的opt-in套件；其真实模型执行分别记录，不把排除写成已经验完。check_core_coverage核对本次JUnit无失败/错误/跳过及六个指定核心组的行/方法覆盖门限，不能据此宣称全代码均达到同一覆盖率。

Windows运行器使用仓库内独立TEMP/TMP，网络和Docker访问须被宿主允许。禁止同时运行两个共享backend/target的Maven过程。若已有经核验的测试存储/扫描镜像，可使用仓库原支持的-Dupload.test.images.prebuilt=true；记录autocare-upload-minio-ci、autocare-upload-init-ci、clamav/clamav:1.4.3_base的实际镜像ID。该配置仍运行真实上传/扫描测试，并非跳过它们。

本机Windows JDK在中文路径下的Java agent选项曾出现编码错位/截断，导致真实JaCoCo数据写到乱码路径的core-coverage.exec,a，Maven本身仍可能BUILD SUCCESS。覆盖率任务优先使用Linux或ASCII路径的隔离工作副本；必须另跑check_core_coverage并确认数据与测试源码对应。本轮保留缺文件失败并以SHA256恢复同次实际数据后重新生成报告，不以重命名空文件/旧文件代替采集。

只复现本轮选定回归：

```powershell
mvn -B -f backend/pom.xml '-Dtest=JdbcKnowledgeVectorRetrievalTest,JdbcKnowledgeVectorIndexTest,JdbcKnowledgeEmbeddingRuntimeTest,KnowledgeEmbeddingConfigurationTest,LicensedKnowledgeHttpTest,EmbeddingHttpClientTest,EmbeddingFoundationTest,KnowledgeEmbeddingHttpJourneyTest' test
```

此命令范围173执行项（含继承）；不得代替完整核心模式。

## 实际模型

按[嵌入服务README](../../services/embedding/README.md)准备固定官方资源、完整CPU依赖及受限镜像，执行run_linux_embedding_acceptance.py的Java联调模式。运行器拒绝源码变化、缺报告、失败/跳过及资源清理失败；最终应分别记录16项实际模型检查、34项内部Java/MySQL执行和5项公开HTTP/JWT执行。公开测试包含真实Spring自动索引→BGE→MySQL→公开查询/精确详情与权限。

AI上游在该旅程中是明确替身，知识许可和中文正文也是合成夹具。实际BGE推理通过不等于中文汽车领域召回、真实授权手册或AI供应商通过。ASR、OCR、图文内核的真实模型另按各服务README和独立阶段报告复现。

## 业务API逐套新库

run_api_acceptance.py每次构建当前后端、启动自有MySQL/后端/网络，执行348次匿名探测、默认禁用零请求检查、所选业务集合、数据库结果核对和清理。每套一个新库，不能合并业务参数复用数据库。使用正常构建模式；--ci-built-jar只能由真实CI的预构建证明使用，不能在本地伪造CI环境绕过源码绑定。

完整后端结束后，可在仓库根目录当前终端顺序执行：

```powershell
$env:AUTOCARE_FINANCE_NATIVE_QA='0'
$env:AUTOCARE_DELIVERY_NATIVE_QA='0'
$env:AUTOCARE_PICKUP_SCAN_NATIVE_QA='0'
$apiFailures=@()
$apiSuites=@('community','owner','fulfillment','coupon','merchant','publication','experience','health','export','points','exchange','invitation','diagnosis','discovery','quotes','reviews','dispute','workflow','finance','assessment','plan-evidence','fulfillment-reads','governance','relations-price','coupon-catalog','auth-sessions','rating-quotes','delivery-compare','local-finance')
foreach ($apiSuite in $apiSuites) {
  python scripts/run_api_acceptance.py "--$apiSuite-cases"
  if ($LASTEXITCODE -ne 0) { $apiFailures += $apiSuite }
}
if ($apiFailures.Count -gt 0) { throw "API suites failed: $($apiFailures -join ', ')" }
```

保存各次.cache/api-acceptance-*/report.json及其SHA256、源码摘要、jar摘要、业务/匿名请求和断言、四类覆盖及数据库检查。私有会话/newman原始报告不上传。348匿名探测重复29次仍只代表348个不同受保护操作，业务四类覆盖必须按operation_id去重，不能用请求总数代替接口覆盖。两套原生UI辅助项在上述命令明确不启用，返回未执行，不计为原生通过。

本轮为避免与正在执行的完整Maven共用target，使用了逐字节核对的独立源码副本并在其中正常构建；每套结束仍校验源码和清理。最终汇总再次核对副本与当前代码，不拿历史317项覆盖充当当前版通过。

## 页面与交接门禁

页面使用gstack /browse，验证VECTOR/降级提示、实际摘录、chunk详情和复制后的独立深链接、AI明确用途确认及实际提供材料、畸形协议拒绝、账号切换迟到与390px布局。本轮15项不同检查/21执行项使用合成API夹具；不等于微信真机或整条模型UI联调。

文档/模板更新完成后最后执行：

```powershell
python scripts/miniapp_handover.py
python scripts/miniapp_handover.py --check
python scripts/reconcile_contract.py --check
python scripts/verify_compose_migrations.py
```

handover清单包含四角色手册以及REMAINING_ACCEPTANCE.md的内容摘要，更新这些文档必须重新生成；只在文档修改前跑过默认组不足以证明最终清单未失配。接口定义更改另须重新生成OpenAPI、Postman及API覆盖契约摘要并检查漂移。

正式微信/SMS/资金、完整Compose业务链路、真机、领域效果及当前版容量/恢复仍按[统一清单](../delivery/FINAL_ACCEPTANCE_2026_10_28.md)逐项验收，不能把此文件当作这些项目已经通过。
