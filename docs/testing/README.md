# 测试资料

按交付文档 §9 与 Spec §5 保存功能、接口、兼容、性能与安全测试方案和结果。当前证据入口如下：

- [逐步执行记录](../progress/S0_EXECUTION_LOG.md)：各步离线、容器、模拟器和真实联调结果；历史“待 CI”状态以随后补充证据为准。
- [真实登录验收清单](../operations/AUTH_INTEGRATION_RUNBOOK.md)：前置条件、只读 HTTPS 预检和逐角色验收矩阵。
- [开发与上线网络说明](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)：模拟器、手机调试和常规真机的网络边界。
- [CI 定义](../../.github/workflows/ci.yml)：后端、Web、小程序、生成文件/OCR、MySQL 结构和 Compose 六项检查。

小程序测试在仓库根目录运行 `npm test --workspace @autocare/miniapp`；登录预检离线测试运行 `python -m unittest scripts/test_check_auth_readiness.py -v`；后端在 `backend` 目录运行 `mvn test`，完整集成测试需 Docker。

2026-10-05 已验证开发者工具真实微信登录、本机后端和数据库，以及车主按钮进入首页；真机手机号、真实私有图片/相机和商家短信仍未验收。未执行项不以 CI 或响应替身测试代替。

2026-10-06：[本地车辆与无图档案](LOCAL_BUSINESS_ACCEPTANCE.md) 22 项真实检查通过；[私有图片联调](LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md) 26 项后端与 11 项 H5 交互通过，使用真实服务/官方库及明确的合成 PNG。物理相机、手机可信 HTTPS、第二个真实身份、手机号与短信仍独立验收。

- [本机标准服务项目验收](LOCAL_SERVICE_CATALOG_ACCEPTANCE.md)：合成项目准备、真实微信会话/H5 页面和接口检查。
