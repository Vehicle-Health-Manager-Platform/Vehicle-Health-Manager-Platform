# 后端

Java 17 + Spring Boot 3.x 模块化单体。`src/main/java/com/autocare/platform/` 下的子包对应交付文档 §6.5。执行 `mvn test` 验证上下文，`mvn spring-boot:run` 在 8080 端口启动，`GET /actuator/health` 用于健康检查。业务接口、鉴权、数据访问将在 S0 后续步骤加入。
