# 后端

Java 17 + Spring Boot 3.x 模块化单体。`src/main/java/com/autocare/platform/` 下的子包对应交付文档 §6.5。设置至少 32 字节的 `JWT_SECRET` 后执行 `mvn test` 和 `mvn spring-boot:run`，默认监听 8080；`GET /actuator/health` 用于健康检查。

本地权限样例需设置 `SPRING_PROFILES_ACTIVE=local`。`POST /api/dev/token` 的 JSON 请求为 `{ "user_id": "1001" }` 或 `2001`，返回 15 分钟 JWT；以 `Bearer` 令牌访问 `GET /api/demo/vehicles/1001` 时本人成功，用户 1001 访问车辆 2001 返回 HTTP 403、业务码 `40300`。`local` profile 仅供开发环境使用，不可用于生产部署。正式车辆业务尚未实现；车主私有图片 HTTP 入口见下文。

S0 写入完整性提供可复用的 24 小时幂等事务服务：业务变更、成功响应缓存和成功审计一同提交，失败全部回滚；缓存重放前仍复核权限。仅 `local` profile 的 `POST /api/demo/vehicles/{id}/mileage` 首次接入，需车主 JWT、UUID `Idempotency-Key` 和已配置的测试数据库，不自动创建车辆。请求契约、复现与接入限制见[写入完整性说明](../docs/api/WRITE_INTEGRITY.md)。完整 `mvn test` 需要可运行的 Docker，以实际执行 MySQL Testcontainers 测试；未连接 Docker 的运行不能作为完整测试通过证据。

S0 [私有上传核心](../docs/api/PRIVATE_UPLOAD_CORE.md)提供内部文件校验、扫描与存储接口、JDBC 元数据仓储及失败删除补偿；仅明确扫描通过的文件可存储，内部读取检查归属和扫描状态。[真实 MinIO/ClamAV 适配器及内部签名服务](../docs/api/UPLOAD_ADAPTERS.md)通过显式配置启用，缺少依赖时拒绝操作；HTTP 上传/签名路由见 [HTTP 接入说明](../docs/api/UPLOAD_HTTP.md)；业务附件和真实部署联调尚未完成。

微信登录的服务端 `code2Session` 适配器位于 `gateway/wechat`。通过私有环境变量 `WECHAT_APP_ID` 和 `WECHAT_APP_SECRET` 配置；仓库的 `.env.example` 只保留公开测试 AppID 和不可用的密钥占位值。适配器向微信官方接口交换一次性 `wx.login` code，解析 `openid`/可选 `unionid`，不把 `session_key` 放入返回对象。

S0-7.1d 提供 `POST /api/auth/wx-login`：请求 `{ "code": "...", "role": "owner|technician" }`。车主按 `user.openid` 查找/创建，并返回 15 分钟 JWT、`user.phone_bound`；技师已绑定且员工与商家有效时返回 JWT，未绑定时返回 `status=BIND_REQUIRED`、5 分钟 `binding_token`。使用该凭证调用 `POST /api/auth/technician/bind`，请求 `{ "employee_code": "..." }`，成功后获得技师 JWT。员工码按现有 `staff_account.employee_code_hash` 的 SHA-256 十六进制摘要匹配，必须由受信任的商家员工管理流程预先设置为高熵码。绑定冲突返回 409；禁用或解绑后已有业务 JWT 在下次请求时失效。绑定凭证不能访问业务 API。

S0-7.1e 进一步提供 `POST /api/auth/phone/bind`，需车主业务 JWT 和微信手机号按钮返回的独立一次性 `code`；服务端用稳定版 access_token 兑换手机号，并校验返回水印 AppID 后写入 `user.phone`。`POST /api/auth/refresh` 用 30 天不透明刷新凭证轮换，返回新刷新凭证及 15 分钟访问 JWT；旧刷新凭证立即失效。`POST /api/auth/logout` 撤销当前会话，旧访问 JWT 随即失效。技师绑定按微信身份和员工码在数据库中共享 15 分钟窗口限流；车主手机号绑定按账号在一小时窗口限流。小程序预览入口提供手机号授权和退出按钮，但尚未做持久化登录态。

员工码由受信任的运维终端发放/回收，**没有公开发码 HTTP 接口**。在后端 JAR 运行环境中设置私有 `MYSQL_*` 与 `JWT_SECRET` 后执行：

```bash
java -jar app.jar --spring.profiles.active=staff-admin --spring.main.web-application-type=none --staff.action=issue --staff.id=31
java -jar app.jar --spring.profiles.active=staff-admin --spring.main.web-application-type=none --staff.action=revoke --staff.id=31
```

发码命令只打印一次高熵员工码；安全交付给对应员工，不写入仓库、日志或工单。重新发码和回收都会解除该员工现有微信绑定；员工须重新绑定。命令必须在无 HTTP 服务的模式运行。数据库先应用 V001、V002、V003；新建 Compose 数据卷会依次自动执行 V001–V005 脚本，**已有数据卷须人工按顺序补迁移 V002–V005**。Compose 从私有环境读取 MySQL 与微信变量；其他运行方式设置 `MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD` 后才启用身份仓储。缺少仓储或微信凭据时相关请求返回 503。此前在聊天中披露过的 AppSecret 须先轮换，再通过私有环境配置真实联调。微信手机号能力还要求符合[官方主体资质与额度条件](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/getPhoneNumber.html)。真机微信联调和完整业务 API 仍未完成。

S0-7.1f-2 新增[商家账号身份核心](../docs/api/MERCHANT_AUTH.md)：已预置且审核通过的 `MERCHANT` 员工账号可在密码校验后请求短信验证码，再用一次性短信码登录，获得可刷新、可撤销的商家会话；员工或商家禁用后旧令牌失效。短信服务商尚未确定，`MerchantSmsSender` 暂无生产实现，因此请求短信码会返回 503；不应将测试验证码发送器部署到生产环境。

HTTP 上传和本人短时图片访问见 [HTTP 接入说明](../docs/api/UPLOAD_HTTP.md)：仅正式车主会话，需 V004 及真实适配器；小程序图片操作尚待后续一步接入。
