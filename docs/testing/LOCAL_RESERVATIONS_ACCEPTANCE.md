# 本机预约与订单验收

2026-10-07。只使用固定隔离项目 `vehicle-auth-local` 和合成项目/商家。真实微信车主调用本机后端；商家登录响应使用显式启用的10分钟合成会话桥接，不代表真实短信登录。没有模拟支付成功。

1. 构建后端与微信/H5，按 V001–V007 迁移。已有数据卷先备份，不删除或重建原卷。
2. 启动本机后端监听127.0.0.1:18080，使用私有配置；微信开发者工具导入当前产物并开启自动化9420。私有密钥/code/token不输出或提交。
3. 运行 `node scripts/prepare_local_service_catalog.cjs --allow-local-test-writes`，再运行 `node scripts/local_business_harness.cjs --allow-local-test-writes`。工具核对固定隔离容器与合成 ID 冲突，补 V006/V007，监听回环4317。
4. 使用 gstack `/browse` 打开 `http://127.0.0.1:4317/?reservations=唯一标识#/pages/merchant/index`，执行 `eval apps/miniapp/test/local-reservations-browser-flow.js`。
5. 脚本验证商家页面发布时段、重叠拒绝、车主选择车辆/日期/时段、提交后丢响应再原键重试、快照与待支付、本人取消释放、订单列表、旧报价版本拒绝及角色边界。业务请求/MySQL真实，日期选择方式按实际H5控件记录。
6. 结束撤销本轮商家/车主会话，停止专用回环验收服务，保留Docker后端与测试卷。

小程序71项测试与微信/H5构建、后端4项HTTP/无数据库针对性测试已通过。完整MySQL并发/回滚/超时测试由独立PR的完整CI执行，实际结果补入执行记录；未执行项不记通过。

MySQL时间测试注入可控时钟验证15分钟/时段开始到期，无需等待真实15分钟。正式短信、支付、微信原生操作与手机/公网另行验收。
