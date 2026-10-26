# R2a VIN 车主闭环（2026-10-26）

分支 codex/vin-owner-gateway，依赖 PR242（0362fc4）；PR242 此轮核对8项CI全部成功、仍OPEN。本轮不合并或部署。设计与顺序见 [规格](../superpowers/specs/2026-10-26-vin-owner-gateway-design.md)、[计划](../superpowers/plans/2026-10-26-vin-owner-gateway.md)、[五类路线](../superpowers/plans/2026-10-26-recognition-vector-roadmap.md)。

实现：默认关闭的固定NHTSA来源车主POST网关、严格VIN/年款/同意、共享分钟限流与实例并发上限、调用前后当前会话复核；页面明确外发说明，候选核对及手动车型保存，取消与迟到保护，账号/60秒单次传递。无自动车型映射/车辆写入。

最终后端验证：后端最终5套54项通过（JdbcVinRecognitionTest 11、VinHttpJourneyTest 4含继承的3条核心旅程、VinProtocolTest 13、VpicVinDecoderTest 14、JdbcMileageRecognitionTest 12）；全部0失败/错误/跳过。MySQL/应用HTTP/JWT为真实本地隔离运行，身份/资金及VIN来源均明确合成或LOCAL_TEST；没有向真实NHTSA提交合成VIN。前端增量26项加原录车8项通过。

测试中修正：新增HTTP录车用例与继承核心用例同一VIN，正常重复保护返回409；改为独立合成VIN，完整54项复测通过。不是放宽登记唯一性。

契约当前355项/341受保护；选定历史317项四类证据加38项具名结论，新增POST为implemented-local、旧GET为superseded。全API最终版本验收仍为false，不将本轮真实HTTP局部测试扩为355项通过。

后续：R2b行驶证专用提取/新车上传授权，随后R3持久异步任务与ASR、R4图文提取、R5授权嵌入/版本索引及向量评估。国内精确车型来源、获授权样本效果、微信真机、正式身份/短信/生产发布均未验收。

## 其余最终回归

统一运行器9组全部通过，执行前后源码摘要一致：小程序1123/1123、Python538/538、OCR64项中63通过/1项Linux限定跳过；微信/H5构建、普通微信无身份测试桥与原生共享样式门禁、OpenAPI/迁移挂载/交接索引/契约结论检查通过。后端5套54项是独立选定回归，本轮未重跑整个后端，不合并历史2201项冒充最终版本全量。

gstack browse在390×844的H5开发页使用仅内存合成会话和明确接口替身，13项断言通过：用途说明、默认未同意、POST正文、候选展示、核对门槛、修改年款清候选、无匹配与未配置、加载/取消、迟到字节、摘要带入及手动目录选择；无横向溢出，截图已人工检查。开发依赖有既有vue-router深路径弃用警告，无新增运行错误。不是微信原生/手机或真实供应商验收。

浏览器测试脚本初期选择器不适配uni-input/uni-label，且gstack异步eval未显式return await、短命CLI导致会话重启；仅修正本地测试脚本/运行配置，最终必须取得13项明确结果才计通过。

脱敏机器报告见[VIN_OWNER_GATEWAY_2026_10_26.json](../testing/VIN_OWNER_GATEWAY_2026_10_26.json)。本机原始日志与截图在.cache/stage-handover，统一回归日志在.cache/local-delivery-3efd68dacc53，均不上传。

下一具体实施单元为R2b行驶证：复用现有Paddle进程隔离，添加专用字段协议/用途同意与新车上传授权，识别候选仍走人工车型选择；先做专用本地提取和误识别/无结果/私有文件权限，再接页面。ASR、图文异步任务和向量按既有路线逐阶段实施，当前均未标记完成。
