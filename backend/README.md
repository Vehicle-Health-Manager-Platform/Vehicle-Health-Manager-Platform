# 后端

Java 17 + Spring Boot 3.x 模块化单体。`src/main/java/com/autocare/platform/` 下的子包对应交付文档 §6.5。设置至少 32 字节的 `JWT_SECRET` 后执行 `mvn test` 和 `mvn spring-boot:run`，默认监听 8080；`GET /actuator/health` 用于健康检查。

本地权限样例需设置 `SPRING_PROFILES_ACTIVE=local`。`POST /api/dev/token` 的 JSON 请求为 `{ "user_id": "1001" }` 或 `2001`，返回 15 分钟 JWT；以 `Bearer` 令牌访问 `GET /api/demo/vehicles/1001` 时本人成功，用户 1001 访问车辆 2001 返回 HTTP 403、业务码 `40300`。`local` profile 仅供开发环境使用，不可用于生产部署。正式业务登录、数据访问、上传和幂等服务尚未实现。
