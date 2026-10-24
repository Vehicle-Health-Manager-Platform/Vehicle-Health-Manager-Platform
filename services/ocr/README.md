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
