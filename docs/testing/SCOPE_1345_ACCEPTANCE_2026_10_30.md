> GitHub合并前补充：独立审查又修复VIN二次来源失败映射和车型对比挂起恢复两项P2。下方完整阶段数据为审查前来源快照；修复后新回归、性能、页面及准确提交CI口径见[合并验证](GITHUB_MERGE_VALIDATION_2026_10_30.md)。原证据不改写。

# 1、3、4、5项测试与修复（2026-10-30）

本报告区分当前执行、较早执行及未验证事项。所有身份/预约资金/车型规则使用隔离合成夹具；真实离线OCR明确单列。未验证正式微信/SMS/支付/合作方、真机或生产。Git基线2cee948，工作区本轮改动通过报告中的源码/JAR哈希绑定；不能仅用基线commit推算当前代码已提交。

本轮宿主实际工具为Node24.16.0/npm12.0.2/Python3.11.15、缓存Java17.0.20.1+1，Windows10.0.26200/24逻辑核，Docker运行Linux容器。开发指南建议Node22基线；没有声称本轮在两种Node版本各测一次。[环境记录](evidence/2026-10-30/environment.json)。

## 已完成的当前回归

- 全量Java：255套2641项，失败/错误/跳过0；Java17、真实MySQL/Testcontainers及65迁移，包含权限、数据库关联、事务/幂等/并发、异常和HTTP旅程。源码稳定，Maven退出0。[执行证据](evidence/2026-10-30/backend-full.json)。
- 九组核心覆盖率：LINE96.27%（1392/1446）、METHOD94.01%（549/584），每组两指标均≥70%。包含VIN候选100%行/方法、报价86.90%行/88.46%方法。仅固定范围，不称全平台覆盖率。[证据](evidence/2026-10-30/core-coverage.json)。Windows中文目录使用独占ASCII临时agent数据，原始exec/XML保留，未再以历史会话恢复替代新采集。
- 默认12组最终回归全部通过且源码稳定：小程序1246、Python623、OCR81（Windows跳过1项Linux专属检查）、ASR40、图文26、嵌入27；微信/H5构建、OpenAPI/迁移挂载/交接/契约一致性通过。[证据](evidence/2026-10-30/local-regression.json)。
- 四个保留Web应用在根Vue/Vite升级后构建全部通过；不恢复为PC交付目标，构建不等于旧Web业务全面验收。
- 当前识别HTTP严格复核：7套77请求，覆盖10个唯一操作（本地与NHTSA共用VIN接口）；其中本地VIN7请求含正常/边界/错误/匿名、错误角色、文件篡改等，确认无vehicle隐式写入。其余NHTSA/行驶证/ASR/图文/里程上游均为明确替身，不代表真实供应商。[证据](evidence/2026-10-30/recognition-http.json)。
- Linux真实OCR：固定模型/锁定wheel（Pillow13）、合成仪表图片识别123456、需要人工确认；401/422、健康、warm worker复用、内核过滤/资源/网络隔离与81项Linux测试无跳过通过，所有自有资源清理。[证据](evidence/2026-10-30/linux-ocr.json)。不代表真实用户仪表准确率。
- gstack浏览器：最终113注册路由游客内容/登录提示检查，390px无横向溢出；实际合成VIN→人工核对→精确目录复核→里程421→保存代理503→恢复重试→成功；DB1100→1101，唯一目标1条，其他车主2条不变，资源清理通过。当前管理员默认适配器请求正确到达后端并显示默认关闭503。390/1440布局保留现有配色。[当前UI](evidence/2026-10-30/ui-current.json) / [逐页结果](evidence/2026-10-30/guest-pages.json)。
- 最后补充UI同源码新库：登记后返回档案列表，实际回读显示421km/正确车型/掩码VIN；选中该车辆后GET档案返回空，旧车辆记录清空。目录503错误/重试和恢复后品牌可选通过，DB唯一新增/外车主不变及清理通过。[补充证据](evidence/2026-10-30/ui-readback-recovery.json)。
- 同轮较早UI（管理员修复前）：三类入口、双目录选择/相同车型提示、合成合作方域名/确认取消、四角色入口可见性已执行；目录页随后发现并修复默认适配器错误，最终UI另行复测。不要把较早报告当成最后代码所有页面都通过。[有边界的较早记录](evidence/2026-10-30/ui-feature-before-admin-fix.json)。

## 500并发结果与不通过记录

Linux独立负载容器500并发/10进程、池40、堆512MB、3轮预热、读各10轮/写5轮。首页P95/P99=205.573/237.730ms；商家=161.523/196.893ms；下单=382.508/451.030ms。计时12500样本、17500HTTP、0错误；订单/占位/幂等各4020，清理4项通过。[原报告](evidence/2026-10-30/performance-linux-steady.json)。

满足原交付文档9.3读300/800、写500门槛。12.1统一P95<300的写入要求未满足；Windows、短预热、冷启动、长稳及生产未通过。Windows报价SQL优化后首页406/商家297/写616ms的失败及Linux短预热写595/692ms失败原样保留在私有诊断报告，不选较低错误预算结案。原10月29性能失败证据也保留。

## 安全实测

最终扫描completed=true、sources_unchanged=true、清理通过；npm0漏洞；本地6规则SAST为0 ERROR、216 SQL拼接WARNING；Java2 CRITICAL、Python4 HIGH/7 MEDIUM/3 LOW。扫描及报告生成通过不等于漏洞门禁通过，vulnerability_gate_passed=false、production_security_accepted=false。[扫描证据](evidence/2026-10-30/security.json) / [逐项风险、可达性边界、责任与验收](SECURITY_FINDINGS_2026_10_30.md)。JSON-only/SSE缺失、固定离线模型/只读/无用户模型配置支持应用边界判断，不能替代上游补丁和正式风险评审。没有宣称完整OWASP/渗透或所有基础镜像扫描完成。

## 本轮修复与回归

1. 报价COUNT/latest查询扫描过多、只读身份多次查询、下单重复更新/读取：减少SQL和往返，保留快照/锁/审计/幂等；历史版本排序、区域、停用、分页和佣金持久值回归及全量测试通过。
2. 车型候选确认中取消/换账号后仍可能开始新账号请求：先校验任务生命周期再发目录读取，拒绝过期结果，补充取消和目录改名/恢复回归。
3. Vue升级后uni渲染器使用不兼容共享函数导致“Cannot assign to read only property '_'”白屏：固定到uni声明版本、规范插件互操作，增加slot更新回归并实际浏览器验证。
4. 管理员目录默认apiRuntime为对象却按函数调用，页面显示“l is not a function”：包装成运行时工厂，新增实际默认桥单测和浏览器真实503请求验证。
5. Windows中文目录JaCoCo agent目标误解码：独占ASCII临时文件→复制回target，完整2641项直接产生新覆盖率数据；统一回归包装不再把中文工作目录强制设成TEMP，工具测试失败已消除。
6. API历史汇总说明未同步当前VIN契约：重新生成契约/覆盖/交接资产，最终一致性与623项Python通过。
7. 最终识别汇总错误地拒绝两种来源共用VIN接口：仅允许已审核vin/local-vin这对来源在同一POST接口合并四类覆盖，请求数分别保留；其他来源或其他接口重复仍拒绝，新增6项正反向回归。29业务的实现/SQL/请求定义未变，重新严格核验全部原始请求、响应、私有身份上下文和数据库检查，明确标为AUDIT_EXISTING，原失败父报告保留。
8. npm已知漏洞及Java/Pillow旧依赖：完成兼容补丁更新、双平台构建、真实页面和LinuxOCR回归；仍有上游依赖风险不虚报修复。

首轮失败报告、源码变化导致不合格的回归/扫描、只构建未浏览的失败UI环境均保留在.cache，未覆盖为绿色。有效证据仅引用源码稳定且明确范围的最终记录。

## 当前统一API执行与最终复核

本轮29业务套件逐套新库执行全部通过：3207次业务HTTP、10092次匿名HTTP；七旅程原始HTTP518次也通过严格复核。最初父运行在最后识别汇总步骤因两来源共用VIN接口误判而失败，原报告保持false。修复仅涉及汇总/证据工具，业务实现、SQL和业务请求定义哈希没有变化。

修复后重新严格审核全部29套私有请求/响应/身份上下文/数据库检查，并复核七旅程和7套77次识别HTTP，共37项全部通过。此次复核自身new_business_http_executed=false、executed_business_requests=0、audited_existing_business_requests=3802，不冒充重发3207次业务请求。累计四类覆盖327/362个唯一操作，有效本地软件接口缺口0；剩余35项为superseded17、dev-only3、blocked-external11、internal-only1、no-success-path1、真实支付/退款回调not-implemented2。348个受保护操作均有匿名拒绝证据，记录断言41223项。资源清理、来源稳定和互斥锁释放通过。

[原始执行及失败汇总](evidence/2026-10-30/current-api-original-aggregation-failed.json) / [最终严格复核](evidence/2026-10-30/current-api.json)。full_api_acceptance_passed和production_acceptance_passed继续false；没有宣称所有362操作、正式服务或真机通过。

## 复现顺序

先按本地指南准备Node22/Python3.11/Java17/Maven/Docker及模型/依赖缓存。所有命令从仓库根目录执行，Maven缓存使用绝对路径；不要并行运行会改backend/target的任务。

1. `python scripts/run_local_delivery.py`：12组普通回归；`python scripts/run_core_coverage.py --full`：完整后端和覆盖率。有本轮明确准备的缓存时可加 `--offline --prebuilt-upload-images --maven-repository <绝对缓存>`；缺缓存应失败，不可假装已构建。
2. `python scripts/run_isolated_journeys.py --check-only` 与 `python scripts/run_recognition_http_acceptance.py --check-only`：严格复核刚执行的原始HTTP证据；随后 `python scripts/run_business_api_acceptance.py --journeys audit --recognition audit`：完整29业务新库执行及复核。不要清除活跃的互斥锁。若只修复了编排/汇总工具，且业务实现、SQL、业务脚本哈希完全一致，可运行 `python scripts/reaudit_current_api_acceptance.py --source-run <原完整父报告>` 严格重审；它拒绝缺少/失败/变更的业务执行或篡改的子报告，并明确标AUDIT_EXISTING，不能用于替代业务实现变化后的重测。
3. `python scripts/run_performance_baseline.py --native-build --maven-repository <绝对缓存> --linux-client --warmup-rounds 3 --read-rounds 10 --write-rounds 5 --gates all --report <报告路径>`：其他测试/扫描结束后执行，保留失败和JAR SHA。
4. `python scripts/run_linux_ocr_acceptance.py --allow-isolated-linux-ocr` 与 `python scripts/run_security_audit.py --report-only --maven-repository <绝对缓存>`：分别需已准备真实模型/wheel和扫描镜像/漏洞数据库。安全report-only不改变门禁结论。
5. `python -u scripts/run_car_life_ui_acceptance.py --allow-synthetic-ui --jar <本轮JAR> --source-report <通过的性能报告>`：校验同源码/JAR后创建独立H5/MySQL，只用于测试。终端显示origin后输入 `{"browse":["goto","该origin/#/pages/mine/index"]}`，再 `{"login":"owner"}` 使用明确合成会话；其他角色为merchant/technician/operator。后续用gstack浏览器检查，`{"fault":true}`只模拟本地代理503，恢复设false。`{"finish":true}`结束并检查唯一VIN登记/外车主记录不变与资源清理；只启动环境或未完成检查不会被计为通过。标准微信/H5构建不含该身份桥。

## 未验证与交接

正式身份/短信/支付退款/真实合作服务与生产按用户要求后置。真实国内授权车型、车牌新车型来源、真实证件/声音/文档/领域效果、iOS/Android硬件及弱网后台恢复仍待外部条件。113页检查属于游客基本可访问性，未声称每页所有登录分支/视觉原型/真机均验收。材料保留/本人删除政策、运维告警投递及未在本次范围中的旧审计任务继续开放。

资料入口：[本轮交接](../delivery/SCOPE_1345_HANDOVER_2026_10_30.md)。.cache含私有SQL、令牌、环境和原始日志，只交接已审核的证据JSON/合成截图及哈希，不打包整个.cache。
