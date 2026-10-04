# API 文档

交付文档 §8 给出统一响应、错误码和 43 个核心接口。[openapi.json](openapi.json) 是 S0 草案，现含 69 个操作（原始核心接口、规划中的支撑接口、当前认证接口与本地写入示例）；用 `python scripts/generate_openapi.py` 从追踪表、[业务契约](S0_BUSINESS_CONTRACT.md)及已实现接口定义重新生成。草案中的大多数路径尚未实现，不能将文档操作数当作已交付接口数；已实现认证接口标有 `x-implementation-status: core-implemented`。

`POST /api/demo/vehicles/{id}/mileage` 为 local-only 的幂等与成功变更审计验证入口，标有 `x-implementation-status: local-example`，契约与事务边界见[写入说明](WRITE_INTEGRITY.md)。不代表正式车辆业务完成。

[私有上传核心](PRIVATE_UPLOAD_CORE.md)是内部应用服务，核心自身不暴露 HTTP；PR #12 的独立适配层增加上传/访问入口及两个 OpenAPI 操作；真实适配器已提供，业务附件关系及真实小程序联调尚未接入。

真实 MinIO/ClamAV 与内部签名服务的配置和部署见[上传适配器说明](UPLOAD_ADAPTERS.md)。内部服务不等于 HTTP 上传或小程序联调完成；验收结果以[S0 执行记录](../progress/S0_EXECUTION_LOG.md)为准。

原始方案以 `POST /api/auth/wx-login` 为微信授权登录入口。S0-7.1d/e 已实现服务端身份数据库仓储、车主/技师 JWT、`POST /api/auth/technician/bind`、`POST /api/auth/phone/bind`、`POST /api/auth/refresh` 与 `POST /api/auth/logout`；请求与权限边界见 [微信身份设计](WECHAT_IDENTITY_DESIGN.md) 和 [后端说明](../../backend/README.md)。S0-7.1f-2 另实现商家密码加短信码身份核心，接口与尚未接入的短信发送边界见[商家身份说明](MERCHANT_AUTH.md)。这些接口已纳入生成的 OpenAPI 草案，但不表示真实微信和短信联调已完成；前置条件见[依赖清单](../progress/S0_DEPENDENCIES.md)。`local` profile 另提供 `POST /api/dev/token` 与 `GET /api/demo/vehicles/{id}` 作为 JWT 和资源归属校验样例；默认 profile 不暴露演示接口。支付和其他业务 API 尚未实现。

HTTP 上传和本人短时图片访问见 [HTTP 接入说明](UPLOAD_HTTP.md)：仅正式车主会话，需 V004 及真实适配器；PR #13 已接入小程序图片操作，真实私有环境仍未联调。

PR #14 实现[本人车辆列表、有效车型查询与手动添加](VEHICLE_MANUAL.md)。OpenAPI五个既定操作已标明实现状态，添加仅支持add_type=4；其他车辆录入、档案和驾驶舱路径继续属于规划。操作总数仍69，不将接口数量视为业务完成。
