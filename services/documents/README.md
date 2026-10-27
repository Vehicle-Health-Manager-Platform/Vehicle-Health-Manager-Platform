# 图文提取服务：当前接入与R4a内核

R4b已新增私有`POST /extract`、Java持久任务和车主逐块核对；[公开接口/状态/错误](../../docs/api/DOCUMENT_JOBS.md)、[R4b交付记录](../../docs/progress/RECOGNITION_R4B_DOCUMENT_JOBS.md)。下方R4a范围与旧待办保留为历史说明，以此处和R4b文档为当前接入依据。

## 私有HTTP配置

服务启动：`python services/documents/src/document_server.py`，仅支持已经准备JDK/PDFBox闭包、固定Paddle模型和Linux受限运行时的环境。DOCUMENT_SERVICE_ENABLED=false默认不构造引擎；开启还需DOCUMENT_CORE_ENABLED=true和DOCUMENT_SERVICE_TOKEN（32..512可见ASCII）。DOCUMENT_PORT默认8092。关闭时/extract返回503，不输出测试结果；/health只说明CONFIG_VERIFIED或UNAVAILABLE，**不表示模型已推理通过**。

严格Bearer、JSON/长度/编码/重复头、最大正文/3秒读取截止；4条连接、1个执行槽，忙时429，无无界队列。不接受URL/路径、query、重试或重定向。安全错误只含码；私有token只在父进程，子进程继承环境白名单。服务单元测试使用明确Engine替身；实际HTTP验证运行在无网络容器内，真实PDFBox/Paddle处理生成英文夹具，不能代替车主全栈或真实中文样本验收。

Java后端独立配置DOCUMENT_ENABLED=false、DOCUMENT_URL、DOCUMENT_SERVICE_TOKEN、DOCUMENT_ALLOW_PRIVATE_HTTP=false、DOCUMENT_JOB_INTERVAL_MS=1000、DOCUMENT_CLEANUP_INTERVAL_MS=60000。关闭时创建503，已存在候选读取失效且清空；恢复开关不会复活已取消候选。先部署V064；它保留132表并以原子ALTER扩展约束与按类型领取索引。不是可用DROP COLUMN回退的迁移，需停写、备份恢复至新库并核对。

## 本地/测试完整接线

先按下方实际内核入口生成并验收本地`autocare-document-core:acceptance`镜像，它只使用已经核验的本机资源；不要把验收镜像称为生产已发布。默认Compose不启动模型。需要显式在隔离测试项目追加覆盖：

```powershell
# token由测试环境管理者生成并放在本机环境中，不要提交或输出。
$env:DOCUMENT_MODELS_DIR=(Resolve-Path '.cache/ocr-prepared').Path
# DOCUMENT_SERVICE_TOKEN须已配置；其余MySQL/扫描/私有存储见项目本地启动指南。
docker compose -f deploy/compose/compose.yml -f deploy/compose/document-test.yml config --quiet
docker compose -f deploy/compose/compose.yml -f deploy/compose/document-test.yml up -d
```

覆盖只让后端加入独立internal网络，模型服务无宿主端口、无外网，非root/只读rootfs和模型、cap-drop ALL、2GiB/CPU2/PID128、256MiB受限tmpfs。Java通过固定document-extractor:8092/extract访问；服务和内核都显式开启。该覆盖是可检查的测试配置；是否完整启动及真实私有存储/扫描验证需看本轮报告，不能用config --quiet通过代替启动通过。

使用微信开发配置指向测试后端，在本人当前车辆的AI/档案页，选择材料→单独上传→同意提取→逐段加入→编辑核对→重新验证后带入→原发送/保存。不要用业务file_id、第三方文档或生产身份做本地夹具。停止模型时关闭DOCUMENT_ENABLED及两个服务开关，按同一项目和覆盖停止document-extractor；**不要使用down -v删除数据库**。TTL/取消不删除原文件；本人删除/保留政策仍需验收。

`python scripts/run_document_core_acceptance.py --allow-isolated-document-core`现在除内核分项，还执行linux_http_acceptance.py实际私有HTTP/模型源绑定、错误/单槽和清理验证；仍没有把它当作公开OWNER路由/真实上传扫描/真机联调证据。

---

# R4a 私有文档提取内核

本目录是内部提取库与本地验收工具，没有公开HTTP接口、任务记录或小程序入口。默认不启用，不能把本轮内核实测称为完整车主图文业务已交付。R4b才复用持久任务的原会话/材料重验、同意/撤销/TTL与页面人工核对；正式微信、短信和生产发布继续后置。

## 输入、输出与预算

内部请求固定为`version:1`、`content_type`和`document_base64`，不接受URL、文件路径、模型名或额外字段。PDF最多10MiB且须通过现有静态PDF策略；JPEG/PNG最多4MiB、单边4096、总600万像素，按EXIF规范化。不支持WebP/动画，不改变既有上传白名单。

提取最多4页、80个文字块、总4096 UTF16单元、每块500单元；Java解析过程的格式化文字输出也受4096单元预算约束。栅格每页最长边2048、400万像素、4MiB PNG，120DPI上限并按像素预算缩放。超限整次返回LIMIT_EXCEEDED，不返回截断后的“成功”候选。现有PDF上传最多50页并不代表提取也接受50页。

原生文字页输出`NATIVE_TEXT`，来源`TEXT_OFFSET`的start/end是该页规范化提取文字中的UTF16偏移，end不包含尾字符；不是原PDF像素位置。页资源含XObject、内容含内联图像/绘图或无原生文字的非空页走OCR，避免只读页脚而漏掉扫描主体。混合页采用整页栅格OCR，不把同一文字层重复拼接成两份。文字层可能与可见视觉不一致，仍须核对原件。

OCR输出实际Paddle rec_polys和rec_scores。`RASTER_POLYGON`的四点以最终栅格左上为原点，X向右、Y向下，像素范围由该页raster_size给出；含PDF CropBox/旋转或图片EXIF处理，不能直接当原始文件坐标。缺失/越界/退化多边形、异常分数、控制字符、错误页号或不匹配文字来源都失败，不伪造位置或可信度。

结果含源字节SHA-256、页号/方法/来源、原生未知可信度null或实际OCR分数、requires_confirmation=true、image_recognition_performed及all_pages_processed。最后一项仅指每页均处理，**不表示OCR全部正确或文字层完整代表视觉内容**。没有推测维修事实，也没有把日期/金额/里程自动写成业务字段。原生与OCR候选仍需人工确认后经原按钮提交。

错误只允许INVALID_DOCUMENT、INVALID_RESULT、LIMIT_EXCEEDED、UNAVAILABLE，不暴露路径、文件内容或模型诊断；没有外部服务时不返回模拟成功文本。

## 本地实际验收

先按[既有OCR资源](../ocr/README.md)准备经hash核验的模型目录和本机`autocare-ocr-runtime:acceptance`，以及缓存的`maven:3.9-eclipse-temurin-17`。验收镜像从已准备的本地OCR镜像复制当前完整OCR源码与JDK，不安装/下载新模型。它是本地验证入口，不是已验收的生产镜像/部署overlay。

当前入口按Windows宿主及项目本机Java17/Maven缓存布局实现，其他宿主需适配工具位置。PowerShell：

```powershell
$env:JAVA_HOME=(Resolve-Path '.cache/host-tools/jdk/jdk-17.0.20.1+1').Path
$env:PATH=$env:JAVA_HOME+'/bin;'+$env:PATH
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
python scripts/run_document_core_acceptance.py --allow-isolated-document-core --models-root .cache/ocr-prepared
```

入口先运行选定Java测试并打包，再只从本机产物选出固定PDFBox3.0.5/Jackson闭包及必要类，排除应用配置和其他后端模块，记录每项SHA-256。生成无个人资料的英文图片及原生、扫描、混合、旋转、空白、坏/加密/活动内容/超限PDF。不要用用户文档替换这些夹具后上传报告。

容器无网络/无公开端口、非root、只读rootfs/模型/输入/源码、2GiB内存/swap、CPU2/PID128和256MiB tmpfs。每次请求只有一个受控Python子进程，CPU/文件预算、seccomp socket禁止与父退出绑定；固定Java exec继承限制，堆256MiB，最多25秒，整个请求最多60秒。超时清理整个进程组，父进程负责删除任务临时目录。子进程仅继承运行环境白名单，不带服务凭据或Java启动选项。

JDK的NIO文件通道和ImageIO磁盘缓存会初始化socketpair；私有CLI使用普通文件流并关闭ImageIO磁盘缓存以兼容严格socket禁止，不放宽网络规则。缓存受JVM堆和容器上限约束。原生PDF不加载OCR；需要OCR时才核验并加载固定Paddle模型。

运行报告在忽略的.cache/document-core-*，包含真实结果/分项检查、源码摘要、资源与清理结果；源码变化、缺失/跳过JUnit或残留容器都不计通过。记录旋转页等真实识别遗漏，不用模型来源标签或单个合成样本代表真实中文/票据/复杂表格准确率。

## 开发回归与接入顺序

```sh
python -m unittest discover -s services/documents -p 'test_*.py'
python -m unittest discover -s scripts -p 'test_document_core_acceptance.py'
python scripts/run_local_delivery.py
```

统一入口新增第11组文档协议/进程回归并覆盖本目录源码摘要，默认不执行真实容器或下载模型。现有OCR的mileage/license HTTP场景保持原限制，未增加无鉴权document场景。

接下来R4b需要持久任务类型与幂等/限额、当前OWNER和车辆/材料/用途授权、处理前后来源/扫描状态和原会话重验、租约/CAS、取消/TTL清理、预算错误页面以及逐块核对带入。图片仍需明确绑定私有材料来源，不能仅凭file_id当作任意车辆授权。获授权中文/真实单据、保留/删除政策、手机与完整业务保存另验，R5向量仍需独立embedding许可/版本及撤回处理。

验收镜像只COPY当前.py源码，不带宿主__pycache__；Python父/子进程使用新的/tmp字节码缓存前缀且禁止写缓存，避免执行未纳入源码摘要的宿主字节码。运行证据还记录所需Java类/依赖的独立hash和实际版本。
