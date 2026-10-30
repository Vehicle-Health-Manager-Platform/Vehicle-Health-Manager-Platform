# 2026-10-30 安全扫描与风险处置

本轮以固定镜像Semgrep本地6条规则、Trivy Java/Python SBOM和npm audit进行可复跑扫描。报告记录漏洞门禁，不自动豁免风险。此项不等同完整渗透测试或全部OWASP规则认证，未验证真机、正式供应商和生产部署。

## 已修复及验证范围

npm原47项漏洞（26项high）经兼容性升级归零，保留package-lock。Spring Boot升级3.5.16，Jackson2.21.8、Tomcat10.1.59及相关基础依赖更新；Pillow13.0.0的Windows/Linux wheel按官方文件SHA固定，Linux真实OCR和81项测试通过。Vue根依赖与uni定制渲染器共享函数差异导致的白屏已定位并使用其声明版本解决，不以仅构建成功判断页面可用。

Python清单合并OCR/ASR/embedding三份Linux wheel锁，按包/版本去重并保留profile归属；这不是宿主当前安装情况或所有服务已部署的证明。嵌入等可选能力仍默认关闭，具体部署必须按实际profile重新扫描。

## 仍未关闭的依赖风险

Java spring-webmvc6.2.19仍报告2项CRITICAL：CVE-2026-47884、CVE-2026-47890。扫描库列修复版本7.0.9；当前Boot3/Spring6兼容线不能直接强制替换Spring7。需要负责人评估受支持补丁/商业支持或Boot4迁移，再全量回归。当前应用仅JSON REST，`JsonApiViewBoundaryTest`在真实配置中检查全部应用handler为RestController、无XsltViewResolver/ModelAndView/View/SSE/FragmentsRendering/event-stream。此证据支持当前视图渲染/分片流攻击入口缺失的判断，但不是库漏洞修复或正式风险豁免。今后增加视图或SSE必须重新评估。

Python模型依赖transformers4.52.4仍有4项HIGH：CVE-2026-4372（扫描列5.3.0修复）、CVE-2026-5241（5.5.0）、CVE-2026-80047（未列修复）、CVE-2026-9856（5.10.0）；另7项MEDIUM、3项LOW，完整包名/版本/公告见证据JSON。不能跨主版本盲升并宣称真实模型仍兼容。负责人需对每项公告评估、选定修复线、重锁所有平台wheel及SHA，并复测实际模型、离线隔离、worker故障恢复和效果样本。

当前BGE使用固定SHA资源、限定BERT配置、无auto_map、直接BertModel、local_files_only、safetensors和显式eager attention；服务接口不接受模型路径/仓库/配置，模型只读且无外网。ASR固定本地资源；OCR不使用外来用户模型。可降低恶意模型/配置输入的可达性，不能证明这些库无漏洞。具体入口边界见 `services/embedding/src/embedding_model.py`、各assets/lock及Linux隔离报告。

SAST的SQL拼接WARNING必须结合参数来源复核：内部固定列名/枚举/占位符/锁片段不等于用户输入SQL；0 ERROR也不代表0安全缺陷。实际数量以最终扫描报告为准。

## 验收与移交

本阶段只接受“扫描已完整执行、发现已记录、有隔离证据与升级责任项”。高危/严重漏洞门禁保持false，生产安全验收保持false。生产负责人应在上线前取得正式应用可达性/风险审批或完成升级消除漏洞，并安排独立渗透测试；不能把report-only退出0写成安全全通过。

复现：先设宿主Java17/Maven及绝对缓存，执行 `python scripts/run_security_audit.py --report-only --maven-repository <cache>`。去掉report-only时发现高危会非零退出；两种模式都保留vulnerability_gate_passed=false。源码扫描在本地容器执行，不上传源码到云规则服务。原始日志留.cache，不对外复制JWT/SQL/环境文件。
