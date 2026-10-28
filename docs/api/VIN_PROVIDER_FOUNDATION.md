# VIN首阶段：内部来源适配

已实现内部`VinDecoder`候选协议与`VpicVinDecoder`有限来源适配器。它们不是REST接口、Spring生产Bean、后台自动任务或车型目录匹配；现有未实现GET `/api/vehicle/vin-decode`仍未开放。本阶段不改变354项契约数量，不自动建车或写数据库。

公共默认构造器关闭；只有后续受控服务显式开启且调用时明确同意，本次合法VIN才会被发送。这个布尔值不等于身份/归属授权；R2必须先实现OWNER当前会话、用途、限流、调用后重验及小程序核对，才能对用户开放。不要从客户端直接调用供应商。

输入接受完整17位ASCII VIN，不含I/O/Q，仅规范大小写与外部空白，不补字符/不将O改0/不从年份码猜30年循环，不把格式合法当成真实VIN已验证。可选年款1980–2100仅作提示，不补成缺失的供应商事实。

内部结果只有CANDIDATE/PARTIAL/NO_MATCH、来源、限定字段及requiresConfirmation=true，不生成内部model_id或置信度。品牌/车型/年款齐全才是候选；仍不代表精确配置。回指VIN、Count/Results、字段类型/长度、年款冲突或响应协议不符时拒绝；供应商非零错误码返回NO_MATCH且清空不可靠事实，缺失错误码是不可用而非成功。

当前来源为[NHTSA vPIC API](https://vpic.nhtsa.dot.gov/api/)的flat VIN端点。根据[官方FAQ](https://vpic.nhtsa.dot.gov/api/home/index/faq)，国外车辆数据取决于面向美国市场登记，不能视为中国全量车型库。后续中国授权数据源实现同一协议，并另行核对内部车型映射。

网络固定官方HTTPS地址，连接3秒、总截止10秒（包含正文）、响应64KiB，无应用级重试/重定向；仅接收单一JSON MIME及未编码/identity正文，重复JSON键/尾随文档拒绝。VIN、URL、供应商原文不出现在调用异常及cause中。包内测试构造器只支持固定路径的127.0.0.1 HTTP，不能作为公开URL参数。

本轮27项测试使用合成完整VIN和本机真实HTTP替身。浏览器另读取官方文档自带的部分VIN示例，看到即使有品牌/车型，仍可能带非零错误码；这只核对文档与字段，不代表Java适配器已通过真实完整VIN联调。

实施顺序、ASR/图文/向量架构与模型试点见[路线](../superpowers/plans/2026-10-26-recognition-vector-roadmap.md)，规格见[R1规格](../superpowers/specs/2026-10-26-vin-provider-foundation.md)，本轮验证见[执行记录](../progress/RECOGNITION_R1_VIN_FOUNDATION.md)。
