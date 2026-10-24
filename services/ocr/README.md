# OCR 服务

S0 提供标准库 HTTP 健康端点：`python src/server.py` 在 8090 端口启动，`GET /health` 返回运行状态。默认关闭时`POST /recognize`返回501；行驶证和里程识别模型将在 S1/S4 接入，当前不产生伪识别结果。

交付文档 §6.1 指定自建 PaddleOCR 服务，用于行驶证和仪表盘里程识别。S0阶段仅预留位置；接口、模型和部署配置在 S0 定契约，S1/S4 分别接入对应场景。

2026-10-24新增纯文本候选解析模块`src/mileage.py`，供未来OCR模型输出复用，当前未接入HTTP或小程序。调用`parse_mileage([{"text":"ODO 123456 km","confidence":0.95}])`返回`status`、`candidates`、`requires_confirmation=true`及`image_recognition_performed=false`。只接受明确的km/公里/千米整数，范围0–2000000；重复值去重，多个值或可疑行返回AMBIGUOUS，单值置信度低于0.9返回LOW_CONFIDENCE，无可用值返回NO_CANDIDATE，否则返回CANDIDATE。阈值仅为候选分流规则，不代表模型置信度已校准。

速度、小计/短途、英里和有符号数字行拒绝；不补单位、不将O改0、不将小数截断或换算英里。最多100行、每行500字符，confidence须为0–1有限数值，非法结构抛ValueError。返回候选不等于本人车辆、真实总里程或自动落库；未来调用方仍须验证车主/车辆权限、让本人确认并使用已有里程写入校验。输入及结果不记录日志。

回归：`python -m unittest discover -s services/ocr -p 'test_*.py'`。此阶段只完成解析基础，默认关闭时`POST /recognize`继续返回501；模型依赖、图片解码/安全校验、识别适配、获授权样本效果及小程序确认页接入仍未完成。

## A4d35内部图片入口（2026-10-24）

新增真实图片解码与PaddleOCR 3.x本地模型适配代码。上段为A4d34历史边界；目前图片验证和推理适配已实现，真实模型效果、小程序及车主车辆权限网关仍待接入。默认仍关闭，普通镜像只安装固定版本Pillow及本服务，不包含Paddle推理引擎/模型，不会因部署而自动开启识别。

内部调用`POST /recognize`：`Content-Type: application/json`、单一Content-Length，头`Authorization: Bearer <服务端凭据>`；body严格为`{"scene":"mileage","image_base64":"<纯base64图片字节>"}`。拒绝URL、文件路径、额外键；JSON上限6MiB、原图4MiB，仅单帧JPEG/PNG，边长4096且总像素600万以内，完整解码后转RGB并应用EXIF方向，再转BGR ndarray交给本地模型。外部结果保留原候选状态，增加version=1、scene=mileage、image_recognition_performed=true，始终requires_confirmation=true；无可用内容返回NO_CANDIDATE，不编造里程。

关闭501；凭据不符401；未给定合法长度411；体积超限413；非JSON415；图片无效422；推理/结果不合规503。均no-store、关闭连接；不输出原文、凭据或模型异常详情，不写临时图片，不访问客户传入的地址。

启用需在独立私有环境安装经过验证的PaddleOCR 3.x、匹配CPU PaddlePaddle及numpy，预置PP-OCRv5_mobile_det/rec两个模型目录（各须有inference.yml），设置OCR_ENABLED=true、OCR_SERVICE_TOKEN（至少32位可打印ASCII随机凭据）、OCR_DETECTION_MODEL_DIR和OCR_RECOGNITION_MODEL_DIR。缺依赖/模型或凭据时启动失败，不退回虚构识别。凭据只能留服务端，不能写VITE变量或发送到小程序。依赖/模型版本及摘要需要在实际部署验收时锁定，本阶段未提供已验证的推理镜像或模型包。

参数和rec_texts/rec_scores接口依据[PaddleOCR官方3.x文档](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html)，经gstack浏览核对。显式指定PP-OCRv5和本地目录、关闭文档方向/展平/文本行方向模型；生产仍应通过出口隔离验证第三方库不下载额外资源。

服务只应在私有网络由后端调用，绝不能把此服务凭据下发客户端或直接暴露为小程序接口。后端尚须复核本人车辆、CLEAN文件及当前会话、读取受控对象字节后转发，并把单候选映射到现有确认组件；禁止自动落库。当前单进程串行推理，10秒限制仅针对socket读写，不是硬推理超时；上线前还需独立worker的硬截止、CPU/内存/队列限额及真实模型/授权样本验收。READY仅表示引擎初始化，不代表识别准确率通过。

验证：安装requirements.txt后运行上述unittest。PNG/JPEG解码和本机HTTP是真实执行；推理以及Paddle构造/输出契约用明确替身测试，未执行真实Paddle模型或手机识别，不可据此标记识别能力已交付。

## A4d39 固定模型资产与运行环境准备

当前共享后端归属网关和微信档案候选入口已在A4d36/37实现；上文“尚未接入”描述属于历史阶段。网关仍默认关闭，真实模型与微信全链路验收状态见阶段记录。

固定模型清单在 `models.lock.json`：官方 PP-OCRv5_mobile_det/rec 两个推理包、包长度/SHA-256和各三个模型文件的长度/SHA-256。摘要来自2026-10-24按官方文档链接实际下载的字节，不是发布方签名。模型二进制留在本机.cache，不提交GitHub；相同URL内容变化时校验失败，应重新审查清单，不能直接跳过摘要。

从仓库根目录，在PowerShell执行以下准备步骤（父目录.cache需已存在）：

```powershell
python -m venv .cache/ocr-runtime
.cache/ocr-runtime/Scripts/python.exe -m pip install --require-hashes --only-binary=:all: -r services/ocr/requirements-runtime-windows.lock.txt
New-Item -ItemType Directory -Force .cache/ocr-models
$modelLock = Get-Content services/ocr/models.lock.json -Raw | ConvertFrom-Json
foreach ($model in $modelLock.models) {
    Invoke-WebRequest -Uri $model.url -OutFile (Join-Path .cache/ocr-models ($model.name + '.tar'))
}
python services/ocr/model_assets.py --archives .cache/ocr-models --output .cache/ocr-prepared
python services/ocr/model_assets.py --output .cache/ocr-prepared --check
.cache/ocr-runtime/Scripts/python.exe services/ocr/runtime_check.py --models-root .cache/ocr-prepared --report .cache/ocr-runtime-check.json
```

模型准备工具离线运行，不会联网或覆盖已有输出。先检查固定压缩包摘要，再只读取精确清单中的普通文件，拒绝路径穿越、链接、额外/重复文件，在临时目录验证成功后才发布完整模型目录。重复使用已有目录请执行 `--check`；发生篡改或丢失时校验失败。

`requirements-runtime.txt` 固定直接依赖 PaddleOCR 3.7.0、CPU PaddlePaddle 3.3.1和Pillow 12.3.0；它不是所有平台的传递依赖锁。本轮另按安装解析报告记录71个Windows CPython3.11运行wheel的完整版本与SHA-256到 `requirements-runtime-windows.lock.txt`，上述命令只面向该平台；包含运行依赖setuptools 79.0.1，不包含安装工具pip，不能作为Linux锁使用。其他平台需重新解析并验收，Windows本地成功不能替代Linux镜像验收。普通Dockerfile继续只安装默认关闭服务所需依赖，不自动加入大型推理引擎或开启网关。

自检先核对直接依赖版本和模型文件，再初始化真实引擎，对程序生成的 `ODO 123456 km` 图片执行真实推理，直接调用和临时回环HTTP /recognize均须返回唯一正确里程、保留人工确认且HTTP no-store才报告成功；临时服务结束后关闭，凭据为进程内随机值，不写报告；失败报告会覆盖旧报告，不把缺依赖/模型或识别失败当成功。生成图片不含个人材料，该检查不能证明车主仪表识别准确率、人工核对流程或微信旅程通过。自检只设置跳过模型来源可用性探测，不能据此声称已完成第三方库网络出口隔离。

生产启用前仍须独立worker硬截止、资源限额、授权仪表样本效果及微信全链路验证。依赖或镜像网络失败应保留实际错误与待验状态，不用替身模型标记完成。

本轮实际发现Paddle 3.3.1默认oneDNN路径抛ConvertPirAttribute2RuntimeAttribute未实现错误；只关闭 `enable_mkldnn` 后相同模型/图片通过。因此适配器固定普通CPU配置 `enable_mkldnn=False`，没有异常后自动换模型或返回空成功。未做加速性能验收。
