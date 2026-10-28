# R1 VIN内部适配规格

用户已要求规划后开始实施，本轮按[五类能力路线](../plans/2026-10-26-recognition-vector-roadmap.md)的R1执行。只增加内部Java协议/Provider/严格解析/受限HTTP客户端，下一阶段才加OWNER权限网关和小程序。当前未实现GET契约的结论不改成已实现，不增加公共路由/业务写入或生产Bean。

- `VinDecoder`定义只读候选结果：来源、CANDIDATE/PARTIAL/NO_MATCH、限定字段、requires_confirmation=true；无内部车型ID，无虚构置信度，无VIN写入结果toString或日志。
- `VpicVinDecoder`为有限来源实现，默认构造关闭。调用需显式开启且获得本次发送VIN用途同意；完整17位ASCII字母数字（不含I/O/Q），只规范前后空白/大小写，不修复字符、不从年份码猜年款。可选1980–2100年款仅作提示，不填充缺失的供应商事实。
- 固定NHTSA官方HTTPS flat VIN endpoint，不接受客户端URL；测试只在包内构造器接受127.0.0.1本机HTTP。每次一次客户端GET调用，连接3秒、总截止10秒、响应64KiB，无应用级重试/重定向。中断保留中断标记，异常不包含VIN/URL/上游文本。
- 严格JSON重复键与尾随检查；Count=1、Results恰好一个对象、回指同一VIN。只接受ErrorCode文本0为可用结果；其他非空错误码返回NO_MATCH且无字段，缺失/类型错误是协议失败。仅提取Make/Model/ModelYear/Trim/VehicleType，文本长度有限/无控制字符，未知字段丢弃。Make/Model/ModelYear齐全才CANDIDATE，否则PARTIAL。与明确年款提示冲突不返回候选。
- 客户端不缓存、不自动建车、不写模型目录，不向界面承诺中国全量覆盖或精确配置。模拟服务只用于明确测试；真实VPIC样本联调、OWNER当前权限与限流、页面人工核对均保持待验。

验收：关闭/缺同意/非法输入零网络请求；本机真实HTTP成功、错误/不完整、错VIN/Count/多行、重复JSON/尾随、超大/慢body、MIME/非200/302、空/控制字符字段、年款冲突、错误信息不泄露、中断与无自动重试。测试通过后上传首阶段PR，后续依次按路线实施。
