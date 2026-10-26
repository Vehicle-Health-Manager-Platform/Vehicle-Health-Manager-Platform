# 本人车辆里程识别候选网关

`POST /api/vehicle/mileage-recognition`，正式OWNER JWT、application/json、无query。不写车辆里程或档案，不需要写幂等键；用户最终仍须走既有档案保存。

```json
{"vehicle_id":101,"file_id":1,"consent":true,"consent_version":"MILEAGE_OCR_V1"}
```

两个ID须为正整数且小于9007199254740991，只接受这四个字段，拒绝重复键、额外键和缺失同意。请求是从本人已有上传图片为本人选定车辆提取里程的用途确认；上传归属不能证明照片实际拍的是该车辆，仍须本人对照。

默认`MILEAGE_OCR_ENABLED=false`。启用必须有私有对象存储、有效本地OCR服务，以及仅后端持有的`MILEAGE_OCR_URL`和`MILEAGE_OCR_SERVICE_TOKEN`。URL固定以`/recognize`结尾，不允许userinfo/query/fragment；HTTPS为默认。私有HTTP需显式`MILEAGE_OCR_ALLOW_PRIVATE_HTTP=true`且主机仅ocr、localhost、127.0.0.1，不跟随重定向。凭据为32–512位可打印ASCII，不能进入VITE、URL、小程序请求或公开报告。主模板/Compose仅提供关闭和空值示例，不安装模型或自动启用私有OCR。

服务在独立只读事务中检查当前会话/用户、本人有效车辆，以及本人未删CLEAN JPEG/PNG文件（1字节至4MiB），然后离开数据库事务。仅服务端读取私有对象，核对存储类型/大小和读取长度；不接受客户端URL或对象key。最多两个实例内处理同时进入存储/OCR阶段，繁忙返回429。私有HTTP连接3秒、整个HTTP等待15秒硬截止，响应上限64KiB，超时取消请求且不重试。此限额不等于私有模型进程取消或分布式全局限流；模型worker仍需独立硬截止和资源隔离。

识别响应严格检查版本、场景、真实调用标记、人工确认标记、状态、候选数量、唯一读数、0–200万公里和0–1有限置信度。调用返回后开启新的只读事务，重新检查会话/用户/车辆及文件事实；撤销、转属、删除或不合规不返回候选，仍可用但事实变化返回409。此阶段无持久化同意记录、识别缓存或写入。

成功为标准ApiResponse，data包含vehicle_id、file_id、review_status、requires_confirmation=true及recognition。recognition遵循既有候选组件结构：version=1、scene=archive、status=CANDIDATE或EMPTY、fields。单一高/低置信候选映射为字符串mileage和原confidence；歧义/无候选不自动择值，fields为空。review_status保留CANDIDATE、LOW_CONFIDENCE、AMBIGUOUS、NO_CANDIDATE，供后续页面解释。小程序接线尚未实现，不能据此宣称页面能识别图片。

400为正文/用途/query错误；401为会话/用户失效；403为非正式车主；404为车辆/图片不属于本人或不可用；409为仍可用事实变化；429为本实例繁忙；503为关闭、数据库/存储/私有OCR故障、结果无效或超时。响应no-store，不返回对象key、签名URL、服务凭据或OCR原文。照片、真实模型和当前车辆对应关系必须在获授权样本/真机验收中独立核对。

本轮测试使用真实MySQL验证归属/撤销与无写入，存储与推理是明确替身；传输测试的回环HTTP服务也是替身，不证明MinIO或Paddle推理端到端通过。私有服务实现及待验条件见[OCR说明](../../services/ocr/README.md)。

## A4d40 本地真实模型联调

在Windows/Docker Desktop下，先按[OCR运行准备](../../services/ocr/README.md)校验本地模型和隔离依赖，并准备已有上传验收镜像。仓库根目录显式执行：

```powershell
.cache/ocr-runtime/Scripts/python.exe scripts/run_real_mileage_acceptance.py --allow-isolated-real-ocr --models-root .cache/ocr-prepared
```

缺少显式参数、复用运行身份或与原生材料身份混用均在创建资源前拒绝。仅测试类读取本轮 `.cache/real-mileage-<id>/config.private.json`；真实OCR只监听宿主回环，JVM测试转发器使用受限本机目的地址，生产配置不变。默认材料旅程不启用此钩子。

报告在本轮cache目录，随机凭据配置在结束时删除。不得上传私有日志、配置或身份；交付仅使用脱敏阶段报告。真实OCR观测器仅验证PNG解码像素并计数，然后调用原模型，不返回替身结果；前置模型自检的调用与网关旅程计数分开。权限拒绝不得推理，成功网关调用只产出人工确认候选，不自动保存档案或修改登记里程。

12项新增JSON HTTP用例和原80项材料/工作台用例分开记账，签名对象下载字节另核，不当成全API验收。合成图片及测试扫描库不证明真实仪表效果、正式恶意软件覆盖、微信交互或手机效果；Linux镜像、worker硬截止和授权样本仍另验。见[阶段记录](../progress/R9_A4D40_REAL_MILEAGE_GATEWAY.md)。

## A4d41 微信原生旅程

在上述环境准备之外，先构建并重新打开固定 `.cache/r0-native/mp-weixin` 隔离微信工程，设置 `R0_NATIVE_TEST_BUILD=1`、`UNI_OUTPUT_DIR`为该绝对目录、`VITE_API_BASE_URL=http://127.0.0.1:18080`，云托管变量留空；开发者工具自动化端口9420。该构建仅供本机验收，普通微信包须另构建并通过无身份桥检查。

```powershell
.cache/ocr-runtime/Scripts/python.exe scripts/run_real_mileage_acceptance.py --allow-isolated-real-ocr --native --models-root .cache/ocr-prepared
```

脚本创建独立合成车主992，通过真实原生文件上传、明确选图与用途同意、真实OCR网关、候选检查/本人核对、原保存按钮。第一次已返回候选取消后需重新同意，第二次确认带入前后均不自动保存；三阶段SQL检查点验证零档案，最终仅一条正确JSON里程/图片引用，车辆登记里程不变。

原生界面验收9项；预设上传1、识别2、保存1个UI JSON请求，网关模型调用共3次（原后端991一次、原生992两次），前置模型自检另记。原80项基础用例中的无档案检查属于原生保存之前；最终本轮允许992通过原保存按钮写入一条合成档案。选图、picker/switch/input事件由开发者工具辅助，不等于手机触摸、权限、在途取消或真实仪表识别效果。临时下载文件由WeChat管理，只核对页面引用清除，不承诺物理删除。详见[阶段记录](../progress/R9_A4D41_NATIVE_MILEAGE.md)。

## A4d45 Linux OCR与宿主JVM验收入口

仅用于Windows Docker Desktop本机隔离验收，后端JVM运行在Windows，OCR运行在Linux容器。准备[离线Linux模型/依赖](../../services/ocr/README.md)、两个现有上传验收镜像及宿主Java17/Maven。将工作区缓存或已有工具的三个绝对路径写入私有`.cache/host-tools/host-tools.private.json`：`java_home`（含bin/java.exe）、`maven_home`（含boot/plexus-classworlds-*.jar、bin/m2.conf）、`maven_repository`（本地Maven仓库）。工具包应从官方来源取得并核对发布摘要；脚本不安装全局工具或修改系统PATH。Python需具备模型/wheel校验模块所需环境及Pillow12.3.0。

```powershell
python scripts/run_linux_mileage_acceptance.py --allow-isolated-linux-mileage
python scripts/run_linux_mileage_acceptance.py --allow-isolated-linux-mileage --native
```

原生模式仍需上述独立微信构建、自动化端口9420及固定SDK3.17.2。不能与其他材料/里程验收并行运行，共享本地证据文件和18080端口。缺开关、错平台或复用身份先拒绝。测试后端只通过宿主Docker Desktop命名管道创建测试资源，Ryuk关闭；新增容器均不挂Docker控制接口。固定目标字节转发容器使用本次双网络，唯一宿主端口仅127.0.0.1，OCR仍只连接internal网络且无发布端口。宿主curl.exe透传真实微信请求和响应。

网关成功上游TCP连接预期1次，原生模式3次；这不是模型内部调用计数。真实模型预检两次另记，同一worker启动身份/READY/seccomp与来源稳定单独核对；80项材料/工作台、12项网关、9项原生和三SQL节点分别计证。JVM正常关闭资源；运行器按本次标签兜底清理，即使某项清理失败仍继续其他资源，探测故障不当作已删除。运行报告与日志保留在本次cache目录，凭据控制文件必须清理，只发布脱敏结果。
