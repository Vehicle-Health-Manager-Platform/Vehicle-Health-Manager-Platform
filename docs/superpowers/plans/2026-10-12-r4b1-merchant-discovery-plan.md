# R4b1 中文实施计划

1. 后端分支codex/r4b1-merchant-discovery基于R4a4收口#136：规格独立提交；报价兼容区域参数、MerchantDiscovery读服务/控制器/配置；JDBC+HTTP测试；OpenAPI与中文接口/执行文档；推送阶段PR。
2. 微信分支codex/r4b1-merchant-discovery-miniapp：严格详情响应、区域选择解析与分页状态机；原生picker、报价详情入口、门店详情和预约复用；生命周期/弱网/空态测试；原生/兼容构建；阶段PR。
3. 验收分支codex/r4b1-merchant-discovery-acceptance：接口/隔离数据库/原生产物与CI记录，更新进度/三端欠项/下一步R4b2；阶段PR。全部README不改，原始交付文件不上传。

每支最新六项CI全部通过后可审查。仍堆叠#121→…→#136→本阶段，前支合main后下一支base改main复核；本轮不执行GitHub合并或部署。正式短信/身份/资金/OCR/VIN及真机最后收口。
