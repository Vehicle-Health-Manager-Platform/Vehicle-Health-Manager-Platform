# R2a VIN 车主候选闭环

沿用 R1 的 VinDecoder 和既有手动录车；本阶段完成 VIN，行驶证另列 R2b。用户已授权规划后直接实施，沿用现有页面样式。方案比较：只开放供应商接口无法保护当前身份；直接自动匹配内部车型会把有限覆盖误当精确结果；采用车主网关加人工确认，复用现有车型目录和保存接口。

## 接口与数据

新增 POST /api/vehicle/vin-decode，仅 JSON 正文，禁止查询参数。必填 vin、consent=true、consent_version=VIN_VPIC_V1；可选整数 model_year（1980..2100），不接受 null、额外字段、重复键或尾随内容。VIN 必须原始 ASCII 17 位合法字符，允许首尾空白和小写。旧 GET 不执行解码。

VIN_DECODE_ENABLED 默认 false；启用必须显式 VIN_DECODER_PROVIDER=NHTSA_VPIC。客户端地址固定，不接受请求地址或环境 URL。候选响应包含 vin、model_year_hint（未传时 null）、status、source、coverage=US_MARKET_REGISTRATION_ONLY、requires_confirmation=true、fields。无内部 model_id、置信度或自动写库。

有效车主会话在调用前后查库，供应商失败也复核身份。每车主每 UTC 分钟最多 5 次（共享 MySQL 固定窗口，边界可有突发），实例同时最多 2 次，无排队。限流仅存账号哈希及时间计数，当前账号过期超过一天的 VIN 限流行在准入时删除；不持有数据库事务跨越 HTTP，不存 VIN/供应商正文，不写车辆、档案或审计业务事件。

## 页面与确认

VIN 模式解释将 VIN 和可选年款发送给美国 NHTSA、其美国市场登记覆盖限制、候选需人工核对，默认不同意。保留不调用解码的手动入口。请求使用 POST，超时 13 秒，隐藏/卸载、账号/模式/VIN/年款/同意变更会中止并丢弃迟到结果。

候选只展示品牌/车系/年款/配置/车辆类型文本。CANDIDATE/PARTIAL 核对勾选后可带入 VIN 与候选摘要，NO_MATCH 只提示手动录入。候选通过现有仅内存、账号隔离、60 秒单次消费的 prefill 传递。手动页仍由车主明确选品牌、车系、年款、配置及里程，再调用原有保存；不从供应商产生内部车型 ID。

## 验收

测试真实 HTTP/JWT/MySQL 网关和合成 VinDecoder，覆盖禁用、参数、角色、注销/停用、限流、响应后身份变化、错误、无车辆写入。R1 的回环 HTTP 协议测试继续回归。小程序验证响应关联、状态、取消/迟到、人工确认与账号/TTL 隔离，再跑全量小程序及 MP/H5 构建。契约和证据同步更新，既有 317 项历史 API 证据不扩称本轮通过。不发送合成 VIN 到真实供应商，不声称中国覆盖或真实服务验收。ASR/图文异步任务、向量检索与外部接入仍按路线待实施。
