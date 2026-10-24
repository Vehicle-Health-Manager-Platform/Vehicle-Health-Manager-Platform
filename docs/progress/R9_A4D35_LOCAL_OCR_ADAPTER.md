# A4d35 私有OCR图片入口与本地推理适配

2026-10-24，基线ba03b7a，分支codex/r9-a4d35-local-ocr-adapter，依赖PR225。初始其backend仍运行，其余可见CI成功，最终状态另核。微信识别链路范围，无PC开发。

本轮把原先始终501的私有识别入口扩展为显式启用模式，默认501保持。新增凭据、长度/类型/大小限制，完整JPEG/PNG解码、帧数/像素限制与EXIF方向处理，无用户URL/路径或原图落盘。PaddleOCR适配显式使用本地PP-OCRv5检测/识别目录及CPU，禁用辅助方向/展平模型，输出经既有里程候选规则验证，不把推理异常当空成功。结果保持人工确认，版本1；不新增公开后端契约。

通过gstack浏览[PaddleOCR官方文档](https://www.paddleocr.ai/main/en/version3.x/pipeline_usage/OCR.html)核对构造参数及结果；首次浏览器启动超时，重开后200取得文档。此环境无Paddle/PaddleOCR和模型，未执行真实推理。17项回归包括实际图片解码与回环HTTP，模型调用/库构造明确替身，不宣称正式识别通过。首个补丁因同一文件删除/新增冲突未落地，改为正常写入后验证；不计失败补丁为实现。

普通Docker镜像增加Pillow固定依赖并复制所有src模块，CI安装依赖后执行OCR回归。操作及协议见[OCR说明](../../services/ocr/README.md)。只有服务端凭据，不在VITE或客户端传递；不记录材料文本/异常内容，默认配置不启用模型。

尚未完成：真实依赖/模型锁定与推理/授权样本效果、硬推理截止和资源/队列隔离、后端会话/本人车辆/CLEAN对象转发，以及小程序选图与候选确认连接。当前不应公开服务端口，不构成端到端小程序OCR上线验收。历史契约覆盖不增加，全API仍false。

## 本地验证与限制

17项OCR测试、Python编译、差异格式、交接索引与API覆盖索引检查通过。两次Docker构建均在拉取python:3.11-slim前因auth.docker.io网络超时失败，本机没有该基础镜像，未验证镜像构建成功或容器启动。没有通过更换来源/镜像隐藏失败，记录为待CI及网络恢复复验。首阶段PR225最新核查backend仍运行，其余已出现六项成功，compose尚未出现；不宣称全CI通过。

## GitHub交付

实现e280435已整批上传[草稿PR226](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/226)，base=codex/r9-a4d34-mileage-candidates，依赖PR225。镜像构建及真实推理保持待验，最终head CI另核，不宣称全部通过；未合并部署。
