# API 文档

交付文档 §8 给出统一响应、错误码和 43 个核心接口。[openapi.json](openapi.json) 是 S0 草案，现含 66 个操作（原始核心接口、规划中的支撑接口和当前已实现的认证接口）；用 `python scripts/generate_openapi.py` 从追踪表、[业务契约](S0_BUSINESS_CONTRACT.md)及已实现认证接口定义重新生成。草案中的大多数路径尚未实现，不能将文档操作数当作已交付接口数；已实现认证接口标有 `x-implementation-status: core-implemented`。

原始方案以 `POST /api/auth/wx-login` 为微信授权登录入口。S0-7.1d/e 已实现服务端身份数据库仓储、车主/技师 JWT、`POST /api/auth/technician/bind`、`POST /api/auth/phone/bind`、`POST /api/auth/refresh` 与 `POST /api/auth/logout`；请求与权限边界见 [微信身份设计](WECHAT_IDENTITY_DESIGN.md) 和 [后端说明](../../backend/README.md)。S0-7.1f-2 另实现商家密码加短信码身份核心，接口与尚未接入的短信发送边界见[商家身份说明](MERCHANT_AUTH.md)。这些接口已纳入生成的 OpenAPI 草案，但不表示真实微信和短信联调已完成；前置条件见[依赖清单](../progress/S0_DEPENDENCIES.md)。`local` profile 另提供 `POST /api/dev/token` 与 `GET /api/demo/vehicles/{id}` 作为 JWT 和资源归属校验样例；默认 profile 不暴露演示接口。支付和其他业务 API 尚未实现。
