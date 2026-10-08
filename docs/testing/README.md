# 测试资料

按交付文档 §9 与 Spec §5 保存功能、接口、兼容、性能与安全测试方案和结果。当前证据入口如下：

- [逐步执行记录](../progress/S0_EXECUTION_LOG.md)：各步离线、容器、模拟器和真实联调结果；历史“待 CI”状态以随后补充证据为准。
- [真实登录验收清单](../operations/AUTH_INTEGRATION_RUNBOOK.md)：前置条件、只读 HTTPS 预检和逐角色验收矩阵。
- [开发与上线网络说明](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)：模拟器、手机调试和常规真机的网络边界。
- [CI 定义](../../.github/workflows/ci.yml)：后端、Web、小程序、生成文件/OCR、MySQL 结构和 Compose 六项检查。
- [A4 车主决定执行记录](../progress/A4_OWNER_DECISION_EXECUTION.md)：确认/异议的 HTTP、真实 MySQL、并发、回滚及阶段 GitHub 验证结果。
- [A5.2 派工后端执行记录](../progress/A5_2_DISPATCH_BACKEND_EXECUTION.md)：六个接口的 HTTP、真实 MySQL 权限/幂等/竞争/回滚验证结果。
- [A5.3 派工界面执行记录](../progress/A5_3_DISPATCH_UI_EXECUTION.md)：商家派工与技师工单的小程序协议测试、构建与未验收项；真机与真实技师登录属 A5.5。
- [A5.4 派工安全与竞争执行记录](../progress/A5_4_DISPATCH_VERIFICATION_EXECUTION.md)：员工码/停用/会话撤销与派工接单的真实 MySQL 并发、无死锁、载荷与历史异常证据。
- [A5 本机派工与本人接单端到端验收](LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)：合成身份接真实后端容器与隔离 MySQL 的 37 项检查、复现步骤与未验收项。

小程序测试在仓库根目录运行 `npm test --workspace @autocare/miniapp`；登录预检离线测试运行 `python -m unittest scripts/test_check_auth_readiness.py -v`；后端在 `backend` 目录运行 `mvn test`，完整集成测试需 Docker。

2026-10-05 已验证开发者工具真实微信登录、本机后端和数据库，以及车主按钮进入首页；真机手机号、真实私有图片/相机和商家短信仍未验收。未执行项不以 CI 或响应替身测试代替。

2026-10-06：[本地车辆与无图档案](LOCAL_BUSINESS_ACCEPTANCE.md) 22 项真实检查通过；[私有图片联调](LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md) 26 项后端与 11 项 H5 交互通过，使用真实服务/官方库及明确的合成 PNG。物理相机、手机可信 HTTPS、第二个真实身份、手机号与短信仍独立验收。

- [本机标准服务项目验收](LOCAL_SERVICE_CATALOG_ACCEPTANCE.md)：合成项目准备、真实微信会话/H5 页面和接口检查。

2026-10-07：车主端 AI 管家在本机完成真实链路验证——上游直连 HTTP 200（`deepseek-flash`），
平台端到端 **19 项全部通过**（[AI 管家接入清单](../operations/AI_CHAT_RUNBOOK.md) 第 6 节）。
复现脚本 `scripts/verify_ai_chat_local.py`，只用在本机、用 `JWT_SECRET` 自签令牌，**不是可用的鉴权途径**。
真机提问与上游限流场景仍未验收。

2026-10-07：修复图片链路在统一传输层改造中丢失原生方法的问题——`chooseImage` / `uploadFile` /
`previewImage` 未随 `runtime` 一起透传，导致拍照、上传、预览全部不可用，且 `uploadFile` 的失败被
误报成「无法连接图片服务，请检查网络后重试」，排查方向被带偏。取图升级为官方推荐的 `chooseMedia`
（旧基础库自动回退 `chooseImage`），区分用户取消与权限被拒，并在开发者工具中如实降级为相册。
`apps/miniapp/test/api-runtime.test.js` 用**真实装配**做契约测试，防止同类漏方法再次发生。
真机验收见[真机相机拍照验收](REAL_CAMERA_ACCEPTANCE.md)，**尚未在真机执行**。
