# 图文提取持久任务（R4b）

这是当前有效OWNER、本人车辆、同一AI/ARCHIVE用途的可选候选流程。默认关闭；不返回对象地址、材料原文件，不自动发送AI或写入档案。内核协议见[内部协议](DOCUMENT_EXTRACTION_CORE.md)，公开严格结构见[OpenAPI](openapi.json)。

## 材料和创建

先用`POST /api/input-materials/{purpose}/{vehicleId}/upload`上传，Bearer及UUID幂等键不变，multipart字段仅file。`X-Material-Kind=image`走独立辅助材料通道：服务器解码JPEG/PNG，最多4MiB、单边4096、600万像素；拒绝GIF、PNG动画/额外尾随内容和扩展名不符。PDF仍最多10MiB/50页；**提取最多4页**，超页整份拒绝。扫描CLEAN并持久化后获得material_id，不可拿业务file_id、URL或对象key代替。图片元数据为duration_ms=0/pages=1。

`POST /api/recognition/documents/jobs`，Bearer和UUID `Idempotency-Key`，JSON仅：

```json
{"vehicle_id":101,"material_id":21,"purpose":"AI","consent":true,"consent_version":"DOCUMENT_EXTRACT_V1"}
```

同车辆/用途材料授权及原创建会话必须有效；额外/重复字段、尾随JSON、query、其他同意版本拒绝。同键同正文返回原引用，不重复排队；同键改正文拒绝。每类型每车主每UTC分钟最多5个新任务，ASR与DOCUMENT合计最多2个活动任务。返回data仅job_id/vehicle_id/material_id/purpose/task=DOCUMENT，普通回执/审计没有候选原文。

## 查询、候选与取消

- `GET /api/recognition/documents/jobs/{id}`：固定身份字段、state、expires_at、error_code、result。只在SUCCEEDED交付经严格校验的候选，其他状态result=null。所有响应no-store，不支持query。
- 任务状态QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/EXPIRED。领取、访问、取消、租约清理按task隔离；ASR入口不能读取或取消文档任务。
- 候选result固定DOCUMENT/PDFBOX_PADDLE及原字节SHA-256，1..4个连续页；页方法NATIVE_TEXT/OCR/EMPTY。总4096 UTF16单元/80块，每块500；超限不截断或返回部分成功。
- 原生块的TEXT_OFFSET是本页规范化text内的UTF16区间，末端不含，完整覆盖非空行；confidence=null。OCR块用实际RASTER_POLYGON四点及模型分数，坐标相对规范化/渲染后的raster_size，不能用原图片尺寸直接解释PDF坐标。
- `POST /api/recognition/documents/jobs/{id}/cancel`：严格`{}`及独立UUID幂等键。本人新有效会话可取消自己的旧任务；即使车辆已转移仍可清除自己的候选，但不能继续读取。取消可清空成功候选，旧worker不能覆盖；不承诺立即中断计算或删除原文件。

任务租约180秒、候选自创建起最多10分钟，不自动重试。TTL和取消清空result_json；材料、任务元数据、审计、幂等记录各自按保留政策管理，不能宣传“撤销即删除所有资料”。存储读取前后、推理后及GET重新校验来源/当前归属/扫描和原会话；关闭服务、撤销、转移、来源变化和旧租约不再交付。

## 页面行为及错误

车主在AI或档案新增页上传后单独同意图文用途。页号、文字偏移/坐标和可信度可查看；候选框初始为空，逐段“加入候选文字”，超过1000字明确拒绝该段，不截断。修改清除核对勾选；最终带入前重新GET并核对来源签名、授权、TTL和原表单未改变。仍需原发送/保存按钮，日期、金额、里程不会由候选自动生成。

未知创建保留原正文与原键，可确认原请求；恢复后立即取消需重新同意，可能先创建后取消。已知任务取消重试不创建新任务。页面隐藏/换账号/用途/材料后丢弃迟到结果；TTL清除本页候选，不删除已明确带入原表单的文字。恢复句柄仅当前会话内存保留，关闭应用后不保证可见。

HTTP400参数/同意/幂等，401会话，403角色，404归属/材料/任务，429限额，503关闭/不可用。任务失败仅安全码UNAVAILABLE/SOURCE_CHANGED/WORKER_LOST/INVALID_RESULT/INVALID_DOCUMENT/LIMIT_EXCEEDED；取消AUTH_REVOKED/USER_CANCELLED/FEATURE_DISABLED；过期EXPIRED。预算和格式错误有明确手动回退，不显示私有异常。

## 配置、启动与验收

[运行说明](../../services/documents/README.md)包含已准备资源、私有HTTP开关、测试Compose覆盖和停止步骤。主开关DOCUMENT_ENABLED=false；DOCUMENT_SERVICE_ENABLED和DOCUMENT_CORE_ENABLED独立默认false。Java地址只固定/extract，HTTPS或明确允许的document-extractor/localhost/127.0.0.1私有HTTP；token32..512可见ASCII。客户端总75秒/64KiB、无重试重定向，内核整体60秒/Java25秒。

验证必须分开记录：前端/HTTP/MySQL合成供应商、实际内核与私有HTTP模型、真实存储扫描、授权中文文档与真机。当前全部API同版完整验收、本人原文件删除政策、复杂表格/旋转/噪声/中文单据质量及生产容量尚待，不能据source标签或清晰英文夹具关闭这些待办。新增3项公开操作纳入362操作/348保护入口契约；历史317项证据保持原范围，45项有独立结论。
