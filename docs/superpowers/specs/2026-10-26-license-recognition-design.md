# R2b 行驶证候选录车

承接用户规划后实施授权。复用现有车主私有图片上传、受限Paddle子进程与手动录车，不接云OCR、不自动匹配车型。相比仅增加格式校验，此方案实际调用图片模型；相比云服务，无须采购账号且材料可在自有测试环境处理。

## 流程与授权

现有 POST /api/file/upload 已校验当前OWNER、私有存储、扫描、幂等与限流，且不需要车辆ID；直接复用。不得套用需要既有车辆的input-materials或里程接口。选图可以本地进行；明确同意上传及本次新车用途后才能上传/识别。照片仍按现有私有附件策略保留，不承诺自动删除或自动作为车辆档案附件。

新增 POST /api/vehicle/license-recognition，仅JSON `{file_id, purpose:"NEW_VEHICLE", consent:true, consent_version:"NEW_VEHICLE_LICENSE_OCR_V1"}`；拒绝query、额外/重复/尾随字段。本人CLEAN JPEG/PNG、1..4MiB；不接受URL或对象key，不接受车辆ID。调用前后复核会话、账号、文件归属/扫描/元数据，失败也重验；每车主UTC分钟5次，实例并发2。只写去标识化账号哈希限流计数，不写车辆/档案。

## OCR协议

新增license场景，复用8秒推理硬截止、有限帧、单worker/无排队/进程资源与网络限制，原mileage协议保持。只从明确车牌/VIN标签同行或紧邻下一行提取完整合法值；不猜O/0、不删内部分隔符、不用VIN计算车型，不返回姓名住址/原文/坐标。每字段保留最多10个不同候选及真实OCR置信度；超出预算报失败，不静默截断。标签分行时取标签和值置信度较低值，重复值取较低值。

字段固定plate/vin；各自status为CANDIDATE/LOW_CONFIDENCE/AMBIGUOUS/NO_CANDIDATE，candidates含value/confidence。阈值0.9；多值不自动选。整体状态优先AMBIGUOUS、LOW_CONFIDENCE；两个高置信单值才CANDIDATE，一个高置信为PARTIAL，均无值为NO_CANDIDATE。version=1、scene=license、source=LOCAL_OCR、requires_confirmation=true、image_recognition_performed=true；原始文字只在受限worker内存，不进普通日志。

## 页面

现有添加车辆行驶证模式增加独立组件。上传失败可同图同键重试；识别失败保留已上传的本人file_id供明确重试，无需重复上传。候选展示所有值与置信度，用户手填/修正车牌和VIN、核对勾选后带入。手填修正不得标为机器识别置信度。纯手动入口一直可用；候选经账号隔离、60秒单次内存预填传给手动页；不产生model_id，也不自动调用VIN外部解码。

隐藏/卸载/模式或身份变化清空本地照片、同意、候选；同意撤回/选图变化取消接收并丢弃迟到字节，运行时支持时abort。已到服务端的上传/识别可能继续，不承诺取消即删除图片。进入手动页不会把照片关联车辆或档案。

## 验收与边界

Python解析/场景HTTP/真实子进程正负测试及原里程回归；Java有限来源HTTP、真实MySQL权限/竞争/无业务写入及应用TCP/JWT候选后手动保存；前端同意、上传同键重试、全部状态、修正/确认、迟到、账号/TTL。全量小程序/Python/OCR、MP/H5构建和契约/索引。真实Paddle合成图运行若具备环境则另记；替身、合成图与授权真实行驶证/手机验收分别记录。

新功能默认LICENSE_OCR_ENABLED=false。私有来源URL/凭据独立后端配置，固定recognize路径、10秒来源期限与64KiB响应，无应用级重试/重定向；私有HTTP仅显式允许本机/ocr容器。真实证件效果、图片保留/删除运营政策、真机、国内车型源、ASR/图文/向量后续；正式微信/短信/生产后置。
