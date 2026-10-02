# API 文档

交付文档 §8 给出统一响应、错误码和 43 个核心接口。[openapi.json](openapi.json) 是 S0 草案，含 61 个操作；用 `python scripts/generate_openapi.py` 从追踪表和 [业务契约](S0_BUSINESS_CONTRACT.md) 重新生成。草案中的大多数路径尚未实现，不能将文档操作数当作已交付接口数。

原始方案以 `POST /api/auth/wx-login` 为微信授权登录入口。S0-7.1d 已实现服务端身份数据库仓储、车主/技师 JWT 签发及 `POST /api/auth/technician/bind`；请求与权限边界见 [微信身份设计](WECHAT_IDENTITY_DESIGN.md) 和 [后端说明](../../backend/README.md)。真实微信联调尚未完成，前置条件为轮换后的私有 AppSecret、已迁移的数据库和可访问的 HTTPS 后端。`local` profile 另提供 `POST /api/dev/token` 与 `GET /api/demo/vehicles/{id}` 作为 JWT 和资源归属校验样例；默认 profile 不暴露演示接口。手机号绑定、支付和其他业务 API 尚未实现。
