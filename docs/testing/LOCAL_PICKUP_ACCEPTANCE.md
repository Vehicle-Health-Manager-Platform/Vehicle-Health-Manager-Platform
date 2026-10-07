# A3 本机接车验收

日期：2026-10-08。范围：[接车契约](../api/PICKUP_INSPECTION.md)。

## 前置

本机隔离 Docker 测试库、A3 后端、MinIO 和 ClamAV，私有配置不入 Git。备份后执行 V010；保留旧容器及数据卷。后端镜像 vehicle-auth/backend:pickup-inspection，0.0.0.0:18080，健康 UP；不改回环发布或执行 LAN --restore。

H5 编译后运行现有 `node scripts/local_business_harness.cjs --allow-local-test-writes`，仅回环 4317。微信开发者工具自动化提供真实 code，车主会话来自真实 /api/auth/wx-login。商家会话为固定合成店 A/B 的隔离桥接；不代表真实短信验收。

## 复现步骤

1. 通过真实本人详情选择测试渠道已支付、本店 PAID 的合成订单。本人详情有六位码，商家详情没有码；店 B 查询店 A 上下文返回 404。
2. 商家详情进入接车检查，检查七个槽位。逐项取图/上传，均经过真实文件校验、ClamAV、MinIO 和 CLEAN 元数据写入。测试图标有 TEST，只是隔离夹具，不代表实际车辆照片。
3. 填写手动里程与必要原因，明确油量和损伤；点选照片位置并填写说明。填写本人提供的预约码。
4. 可通过回环辅助 `/__local/drop-once` 设置 `/api/check/pickup/submit`，使服务器提交成功后断开响应。页面显示网络失败，再点提交，原内容与原键重放。
5. 本店和本人接车单均返回 200，并在 H5 两端正确展示七图、手动里程、损伤与待确认状态。其他店铺读取/签发图片 404。
6. 尝试 START_SERVICE，返回 HTTP409/43003，不放行施工。
7. 真库核对只有一份 pickup_check、七个关联、一次迁移审计与一次 ORDER_CHECK_IN 业务审计。check_in_completed_at 有值，owner_confirm=0，车辆 current_mileage 未覆盖，名额未释放。
8. 本人图片签名 200，实际对象下载校验。当前本机自签名 TLS 转发需启动既有本地助手；CA 验证的辅助下载 200。浏览器直接使用该测试证书的下载未通过，本次不计作直连预览或真机图片验收；不关闭 TLS 验证。

## 已执行结果

以上业务链路使用测试订单 7 执行成功。七次商家上传 200，关联文件全部 CLEAN；损伤点选生成一条标注。两次提交均 200，键和请求摘要完全一致，第一次成功响应被辅助故意中断；最终 RECEIVED，owner_confirm=0。两端接车单页面通过，其他店铺 404，施工拒绝 43003。

车主图片签名 200，按既有 CA 校验的 TLS 辅助下载 200 / 805 bytes。直接浏览器下载因本机测试 TLS 环境不可用，未计作通过。取图使用浏览器生成的七个合成 PNG 替身；相机真机、真实门店照片、真实短信、订阅通知、正式扣款均未验收。

## 自动检查

- 首次实现 cd6131b：[CI 37649048049](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37649048049)，六项成功，后端 259 项、失败/错误/跳过均 0。
- 小程序 119 项通过，H5/mp-weixin 构建成功。
- 补充 HTTP 回归：接车 3 项、真实 multipart 10 项通过，包括商家新路径身份与其他角色拒绝。
- 真库检查包含一单一单并发、错码限流、图片归属与七槽、档案基线重读、预约原因、权限隔离、双审计/缓存回滚及占位保留。
- 最终提交检查与合并状态见 [PR #34](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/34)。
