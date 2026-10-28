# 接车仪表里程候选

`POST /api/check/pickup/mileage-recognition`，JSON、Bearer，响应`Cache-Control: no-store`。只读、无幂等写键；不保存接车单、不改车辆里程或订单状态。与车主档案识别入口分开授权，共用内部推理能力和同一实例两项并发限额。

请求严格包含：

```json
{"order_id":1,"file_id":7,"consent":true,"consent_version":"PICKUP_MILEAGE_OCR_V1"}
```

ID须为正安全整数，小于9007199254740991；拒绝额外/重复字段、尾随JSON、query及缺少/错误用途同意。不接受客户端传入URL、对象key、图片字节或车主身份。

权限及状态：当前有效MERCHANT/STAFF门店会话、员工/门店有效；本店未删除PAID订单，车辆仍归订单所记车主；当前员工本人未删除的CLEAN JPEG/PNG、1–4194304字节、尚未用于接车单。调用前后重新核对。前端只从本次DASHBOARD上传照片中选择；文件内容属于哪块真实仪表仍需员工检查，服务端归属校验不能证明照片内容。

成功信封`code=0,data`包括`order_id,file_id,review_status,requires_confirmation=true,recognition`。`recognition`沿现有候选协议：`version=1,scene=archive,status=CANDIDATE|EMPTY,fields`，仅允许`mileage`字段，值为公里整数字符串，置信度保留原值。单候选状态为CANDIDATE或LOW_CONFIDENCE；AMBIGUOUS/NO_CANDIDATE返回EMPTY，不自动挑值。推理候选范围0–2000000公里，不妨碍原接车表单的人工范围0–9999999。

错误：400正文/用途/query错误；401会话或当前门店身份失效；403非门店执行角色；404本店/车辆/文件归属或安全条件不符；409订单不可接车、在途事实变化；429实例并发已满；503关闭/数据库/私有存储/供应商错误、响应无效或超时。均为错误响应，不包装成空候选成功；不向客户端透露供应商原文。

配置复用`MILEAGE_OCR_ENABLED`（默认false）、`MILEAGE_OCR_URL`、`MILEAGE_OCR_SERVICE_TOKEN`及私有HTTP部署声明；服务凭据不可进入VITE变量。读取私有字节前核对大小/MIME，调用无自动重试，供应商网络截止与体积上限复用现有客户端。真实部署依照[OCR说明](../../services/ocr/README.md)及[本地指南](../delivery/LOCAL_SETUP.md)。

界面必须先独立同意识别用途，识别后逐项核对并确认，才带入原里程框。仍需八个独立位置照片、预约码、回退/到店偏差原因和原提交按钮；车主确认与派工防护不变。提交保存的是员工最终核对值，现有`mileage_source=MANUAL`保持真实含义，不新增“机器已确认”标识。

本轮测试有真实TCP HTTP/JWT/MySQL及客户端状态测试，存储/OCR上游为明确替身；不代表真实仪表、正式模型/账号或手机通过。
