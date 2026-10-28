# 识别接口当前HTTP验收（R6b2）

当前默认覆盖VIN、行驶证，以及ASR/图文任务的创建、查询、取消，共八接口、四个测试类。最后两类里程识别仍待下一批；不把局部通过当全接口通过。

## 复现

准备Docker Desktop Linux Engine、Python3.11、可取得Maven/Java17与MySQL测试镜像及依赖的网络/缓存，仓库根目录执行：

```powershell
python scripts/run_recognition_http_acceptance.py --docker
```

Windows/macOS Docker Desktop模式设置host.docker.internal供Testcontainers连接映射端口。宿主有Java17/Maven时可不带--docker直接运行；原生Linux的Docker内Maven宿主网络配置须另行核对，不冒称跨平台都已运行。不能与共享backend/target的其他Maven任务并行。

运行器清除四类的旧JUnit和原始证据，正常构建并执行四个HTTP测试类，包括继承核心旅程；任一测试失败/错误/跳过、非零退出、缺文件或源码不匹配均失败。每次写独立.cache/recognition-http-随机值/report.json和私有Maven日志；原始现场证据位于backend/target/下的vin/license/asr/document-http-evidence.json。

只复核已有当前源码证据（不发送新请求）：

```powershell
python scripts/run_recognition_http_acceptance.py --check-only
```

复核模式明确AUDIT_EXISTING，不声明再执行或新核查Docker清理；新执行模式按现场记录的精确容器ID查询并核对四库已移除，不能把Docker连接失败当清理成功。

## 证据边界

现场helper直接发送真实TCP请求并记录名称、四类用途、角色、规范操作、实际路径、观察状态及请求/响应SHA256，不保存JWT、原始VIN/证件正文。VIN六例、行驶证七例分别覆盖候选、重复边界、无同意、非法查询、匿名和错误角色；行驶证另验他人文件404。ASR和图文各20例，创建/查询/取消各自具备四类场景；实际job_id从创建响应提取，审计要求有界整数且每条实际路径与绑定相符，查询显式为GET，取消及创建携带原幂等键验证重放。SQL核对任务归属/材料及取消状态，取消后候选不返回，车辆/档案仍无新增。来源摘要绑定全部Java业务/测试、SQL迁移、POM和运行器。

成功结果须CANDIDATE且人工确认，逐次SQL核查识别前后车辆和档案数量不变。行驶证通过真实multipart上传进入正式上传业务组件，但存储、扫描和OCR是明确替身；VIN解码器亦为替身；ASR/图文材料和模型输出同样是明确夹具，不能当作真实转写或文档提取效果。不得计为真实NHTSA覆盖、证件准确率、真实扫描/存储供应商或手机效果。

本批独立归档，不改历史317项或45独立结论；全部识别补证完成后再与统一运行器的当前覆盖合并。原始证据中的MySQL容器ID用于本轮清理核对，不是身份凭证。只上传脱敏汇总，保留私有失败日志供定位。

当前结果见[R6b2阶段报告](../progress/R6B2_RECOGNITION_JOBS_HTTP.md)及[机器记录](RECOGNITION_JOBS_HTTP_2026_10_28.json)。[R6b1](../progress/R6B1_RECOGNITION_HTTP.md)保留当时两项范围，不用历史结果冒称本轮已重跑。
