# 后端

Java 17 + Spring Boot 3.x 模块化单体。`src/main/java/com/autocare/platform/` 下的子包对应交付文档 §6.5。设置至少 32 字节的 `JWT_SECRET` 后执行 `mvn test` 和 `mvn spring-boot:run`，默认监听 8080；`GET /actuator/health` 用于健康检查。

本地权限样例需设置 `SPRING_PROFILES_ACTIVE=local`。`POST /api/dev/token` 的 JSON 请求为 `{ "user_id": "1001" }` 或 `2001`，返回 15 分钟 JWT；以 `Bearer` 令牌访问 `GET /api/demo/vehicles/1001` 时本人成功，用户 1001 访问车辆 2001 返回 HTTP 403、业务码 `40300`。`local` profile 仅供开发环境使用，不可用于生产部署。正式业务登录、数据访问、上传和幂等服务尚未实现。

微信登录的服务端 `code2Session` 适配器位于 `gateway/wechat`。通过私有环境变量 `WECHAT_APP_ID` 和 `WECHAT_APP_SECRET` 配置；仓库的 `.env.example` 只保留公开测试 AppID 和不可用的密钥占位值。适配器向微信官方接口交换一次性 `wx.login` code，解析 `openid`/可选 `unionid`，不把 `session_key` 放入返回对象。当前尚无 `/api/auth/wx-login` 控制器、用户持久化或业务 JWT 签发；前端请求该接口仍不能完成登录。此前在聊天中披露过的密钥应先轮换，再通过私有环境配置进行真实联调，不要提交或发送密钥。
