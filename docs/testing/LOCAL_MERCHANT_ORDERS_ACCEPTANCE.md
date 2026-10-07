# 本机商家订单只读验收

日期：2026-10-07。源码分支：`codex/s2-merchant-orders`。本机后端镜像：`vehicle-auth/backend:merchant-orders`，仅绑定 `127.0.0.1:18080`；隔离项目 `vehicle-auth-local` 的 MySQL 使用原有 V008 测试卷。MinIO、ClamAV 与更新容器在 Docker 重启后恢复，后端健康接口返回 UP。旧支付后端镜像与容器保留作回滚。

## 复现

1. 配置本机私有后端环境，保持密钥不入库。构建当前后端 JAR 与微信/H5；本机缺少可解析的 terser 时可用 `npx uni build --minify esbuild` 和 `npx uni build -p mp-weixin --minify esbuild`，干净 CI 运行仓库默认构建。
2. 用当前产物启动微信开发者工具自动化端口 9420，运行 `node apps/miniapp/test/simulator-smoke.cjs`。
3. 确认 `vehicle-auth-local` 为固定隔离项目，启动本机业务桥：`node scripts/local_business_harness.cjs --allow-local-test-writes`。桥仅监听 `127.0.0.1:4317`，使用固定合成商家会话；订单读取透传真实后端/MySQL。H5 不发送真实短信。
4. 使用 gstack `/browse` 打开 `http://127.0.0.1:4317/#/pages/merchant/index`，运行 `browse eval apps/miniapp/test/local-merchant-orders-browser-flow.js`。再次运行前刷新页面，以清除已撤销的内存会话。

## 本轮结果

- 小程序 78 项 Node 回归通过；微信/H5 本机构建通过；微信模拟器 18 条路由通过，包含商家订单列表与详情。模拟器结果仅证明路由加载。
- gstack H5 17 项通过：真实订单分页/计数、快照与详情、北京时间预约日期、状态筛选、一次网络失败后重试、另一店列表与详情隔离、匿名 401、客户端商家 ID 拒绝、历史付款异常提示和测试支付非收款凭据。浏览器业务请求均到达当前后端/MySQL；登录由显式合成商家会话桥接。
- [PR #26 修正提交 CI 37580419462](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37580419462) 六项成功，后端 201 项、无失败/错误/跳过，小程序 78 项、Schema/MySQL 与 Compose 检查通过。首次 CI 的四项失败仅为新测试的 Long/Integer 总数断言不匹配，已修正。
- 本轮未创建正式支付、退款或接车操作。支付标记来自隔离 LOCAL_TEST 历史记录，不表示真实扣款。商家短信仍未接生产发送器；微信完整业务 UI 与真机仍未验收。

私有环境、会话令牌、完整客户数据和截图不提交。验收桥测试结束撤销合成商家会话；本机镜像/卷保留以便复核与回滚。
