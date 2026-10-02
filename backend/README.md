# 后端

Java 17 + Spring Boot 3.x 模块化单体。`src/main/java/com/autocare/platform/` 下的子包对应交付文档 §6.5。设置至少 32 字节的 `JWT_SECRET` 后执行 `mvn test` 和 `mvn spring-boot:run`，默认监听 8080；`GET /actuator/health` 用于健康检查。

本地权限样例需设置 `SPRING_PROFILES_ACTIVE=local`。`POST /api/dev/token` 的 JSON 请求为 `{ "user_id": "1001" }` 或 `2001`，返回 15 分钟 JWT；以 `Bearer` 令牌访问 `GET /api/demo/vehicles/1001` 时本人成功，用户 1001 访问车辆 2001 返回 HTTP 403、业务码 `40300`。`local` profile 仅供开发环境使用，不可用于生产部署。正式业务登录、数据访问、上传和幂等服务尚未实现。

微信登录的服务端 `code2Session` 适配器位于 `gateway/wechat`。通过私有环境变量 `WECHAT_APP_ID` 和 `WECHAT_APP_SECRET` 配置；仓库的 `.env.example` 只保留公开测试 AppID 和不可用的密钥占位值。适配器向微信官方接口交换一次性 `wx.login` code，解析 `openid`/可选 `unionid`，不把 `session_key` 放入返回对象。

S0-7.1d 提供 `POST /api/auth/wx-login`：请求 `{ "code": "...", "role": "owner|technician" }`。车主按 `user.openid` 查找/创建，并返回 15 分钟 JWT、`user.phone_bound`；技师已绑定且员工与商家有效时返回 JWT，未绑定时返回 `status=BIND_REQUIRED`、5 分钟 `binding_token`。使用该凭证调用 `POST /api/auth/technician/bind`，请求 `{ "employee_code": "..." }`，成功后获得技师 JWT。员工码按现有 `staff_account.employee_code_hash` 的 SHA-256 十六进制摘要匹配，必须由受信任的商家员工管理流程预先设置为高熵码。绑定冲突返回 409；禁用或解绑后已有业务 JWT 在下次请求时失效。绑定凭证不能访问业务 API。

数据库需要先应用 V001 与 V002。新建 Compose 数据卷会依次自动执行两个脚本；**已有数据卷须人工执行 `docs/sql/migrations/V002__staff_wechat_identity.sql`**，不能靠 MySQL 初始化目录补迁移。Compose 从私有环境读取 MySQL 与微信变量；其他运行方式设置 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD` 后才启用身份仓储。缺少仓储或微信凭据时登录返回 503。此前在聊天中披露过的 AppSecret 须先轮换，再通过私有环境配置真实联调。手机号绑定、商家登录、刷新/撤销令牌、绑定限流及真实微信联调仍未完成。
