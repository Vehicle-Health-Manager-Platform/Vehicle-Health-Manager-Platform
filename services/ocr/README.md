# OCR 服务

S0 提供标准库 HTTP 健康端点：`python src/server.py` 在 8090 端口启动，`GET /health` 返回运行状态。`POST /recognize` 明确返回 501；行驶证和里程识别模型将在 S1/S4 接入，当前不产生伪识别结果。

交付文档 §6.1 指定自建 PaddleOCR 服务，用于行驶证和仪表盘里程识别。当前仅预留位置；接口、模型和部署配置在 S0 定契约，S1/S4 分别接入对应场景。

2026-10-24新增纯文本候选解析模块`src/mileage.py`，供未来OCR模型输出复用，当前未接入HTTP或小程序。调用`parse_mileage([{"text":"ODO 123456 km","confidence":0.95}])`返回`status`、`candidates`、`requires_confirmation=true`及`image_recognition_performed=false`。只接受明确的km/公里/千米整数，范围0–2000000；重复值去重，多个值或可疑行返回AMBIGUOUS，单值置信度低于0.9返回LOW_CONFIDENCE，无可用值返回NO_CANDIDATE，否则返回CANDIDATE。阈值仅为候选分流规则，不代表模型置信度已校准。

速度、小计/短途、英里和有符号数字行拒绝；不补单位、不将O改0、不将小数截断或换算英里。最多100行、每行500字符，confidence须为0–1有限数值，非法结构抛ValueError。返回候选不等于本人车辆、真实总里程或自动落库；未来调用方仍须验证车主/车辆权限、让本人确认并使用已有里程写入校验。输入及结果不记录日志。

回归：`python -m unittest discover -s services/ocr -p 'test_*.py'`。此阶段只完成解析基础，`POST /recognize`继续返回501；模型依赖、图片解码/安全校验、识别适配、获授权样本效果及小程序确认页接入仍未完成。
