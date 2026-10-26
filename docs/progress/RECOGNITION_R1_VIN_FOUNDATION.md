# 识别/检索路线及R1 VIN内部适配执行记录

2026-10-26。前置1df7dd7已上传[草稿PR241](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/241)，依赖尚未合并的PR240；PR240已核对8项CI成功，main仍afa7e93。当前阶段在独立`codex/recognition-vin-foundation`实施，不合并、部署或开通外部服务。

## 规划已完成

[五类能力路线](../superpowers/plans/2026-10-26-recognition-vector-roadmap.md)按VIN内部协议→车主网关/页面与行驶证→ASR→图片/PDF→授权向量检索分解前置、数据流、退出标准与后续负责人依赖。

复用现有Java/MySQL、私有上传、受限Paddle和人工核对；ASR本地试点faster-whisper CPU/int8、多页文档/音频走异步任务与取消/TTL；向量试点BGE-small-zh-v1.5与MySQL精确检索，独立embedding许可默认false，保留KEYWORD真实降级。试点模型尚未安装/下载或测效果，不把选型当成接入完成。

## R1实际完成

- 内部VinDecoder协议、NHTSA响应校验与受限VpicVinDecoder；默认关闭，无Spring Bean/REST/自动任务/数据库写入。
- 输入只接受完整ASCII VIN，规范大小写但不猜车型；返回有限来源候选/部分/无匹配，不接受供应商内部model_id，不捏造置信度，始终要求确认。
- 一次调用一次客户端请求，固定官方HTTPS、连接3秒/总10秒/64KiB，无应用级重试或重定向；错误不泄露VIN、URL、上游文本。包内loopback仅供明确测试。
- 错位VIN、Count/Results不符、错误码、缺失/不完整/不安全字段、提示年款冲突、重复JSON/尾随、MIME/编码、超大/慢正文、中断均按规格拒绝。

## 验证

实际命令（配置宿主Java17/Maven缓存后）：

```powershell
mvn -f backend/pom.xml -Dtest=VinProtocolTest,VpicVinDecoderTest test
```

最终27项通过，0失败/错误/跳过：VinProtocolTest 13、VpicVinDecoderTest 14；后者运行真实本机TCP HTTP，所有VIN及供应商响应均为合成夹具。首轮26项通过后，针对新增重复MIME/编码检查重新运行最终27项；不叠加为53项。未复跑上一阶段2201项/63项、小程序/OCR等无关套件，它们仍只属于PR241当时的证据。

gstack /browse读取NHTSA官方API/FAQ及官方公开部分VIN示例（ErrorCode6），核对来源、响应字段及不完整结果边界；读取faster-whisper实现与BGE模型卡用于后续选型。这些浏览不属于真实完整VIN/模型/国内车型/手机验收，未发送用户车辆材料。只计量本机HTTP测试请求，未计量真实供应商TCP连接或底层重连，不声称物理网络始终恰好一次。

本轮机器证据见[脱敏测试记录](../testing/VIN_FOUNDATION_2026_10_26.json)。Java完整测试源码已编译，新模块没有外部包依赖；当前354操作契约和未实现GET结论保持真实，交接/契约一致性另检。

## 下一阶段及限制

R2先补带明确用途同意的POST网关、当前OWNER授权/限流和候选页面；旧GET具名替代。国内车型映射/供应商数据许可、真实VIN样本、授权行驶证、中文ASR/embedding实际模型和iOS/Android验收仍待完成。ASR、图文与向量本轮是可执行路线，不能声称这些端到端功能已实现。

完整进度以阶段提交/PR及最终head检查为准，上传不等于合并/远程CI通过/发布。
