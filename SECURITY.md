# 安全政策

本文件说明本仓库的**支持范围**、**漏洞报告方式**与**敏感信息处理约定**。它只描述仓库现状，不代表任何生产环境已通过安全验收。

---

## 1. 支持范围

| 项 | 现状 |
| --- | --- |
| 维护分支 | `main`（仓库未发布版本标签，也没有长期维护的旧版本分支） |
| 安全修复 | 只针对 `main` 的最新提交 |
| 生产部署 | 仓库不包含生产环境配置；部署方需自行承担上线前的安全评估 |
| 依赖风险 | CI 的 `web` 作业会执行 `npm audit --audit-level=high` 拦截高危/严重依赖；后端依赖未做同等强制门禁 |

> 如果你使用的是从本仓库 Fork 出去并自行改造的版本，请在你自己的仓库中维护安全策略。

---

## 2. 报告漏洞

**请勿通过公开 Issue、Discussion、PR 评论或社交媒体披露未修复的安全问题。**

推荐渠道（按优先级）：

1. **GitHub 私密漏洞报告**：仓库页面 → `Security` → `Report a vulnerability`（GitHub Security Advisories）。这条通道只在仓库维护者开启后可用。
2. 若上述入口不可用，请开一个**不含技术细节**的 Issue，仅说明"需要私下安全联系渠道"，由维护者提供后续方式。

本仓库**没有**登记安全邮箱、应急电话或外部响应团队，因此：

- 不承诺固定的响应时限或修复 SLA；
- 不接受通过第三方平台转交的漏洞报告；
- 请勿在报告中附带真实用户数据、生产凭据或可直接利用的攻击载荷（PoC 请做最小化、脱敏处理）。

### 报告内容建议

- 受影响的组件与版本（提交 SHA 或分支）；
- 影响面：是否能绕过权限、读取他人数据、伪造身份、越权写资金/券/积分；
- 复现条件与前置状态（是否需要有效会话、是否依赖特定开关）；
- 你希望的处理方式（例如"请确认是否已知"）。

---

## 3. 本项目的敏感面（报告时优先关注）

以下位置在代码中承担安全职责，评审与报告时值得重点关注：

| 面 | 位置 | 关注点 |
| --- | --- | --- |
| 身份与会话 | `backend/.../gateway/SecurityConfig.java`、`AuthTokens`、`AuthSessionRepository` | JWT 会话是否被正确撤销；刷新凭证轮换是否让旧凭证失效；`subject_type` 是否可被伪造 |
| 角色与归属 | 各 Controller 的 `VehicleOwner.from(jwt)`、merchant/technician 归属校验 | 是否存在只校验角色、不校验资源归属的路径 |
| 云托管身份头 | `gateway/identity/CloudRunAuthController.java`、`WECHAT_CLOUD_RUN_ENABLED` | 若服务仍可公网访问，网关注入的 `X-WX-OPENID` 可被伪造 |
| 私有文件 | `file/*`（`PrivateUploadService`、`MinioPrivateObjectStore`、`PrivateFileAccessService`） | 签名 URL 期限、归属校验、扫描状态是否为 `CLEAN` |
| 写幂等 | `common/write/WriteIntegrityService.java` | 重放时是否复核权限；是否可能重复扣分/重复发券 |
| 资金 | `order/LocalTestPaymentChannel.java`、`OrderRefunds*` | `PAYMENT_LOCAL_TEST_*` 是否被误用于生产 |
| 识别服务凭据 | `MILEAGE_OCR_SERVICE_TOKEN`、`ASR_SERVICE_TOKEN`、`DOCUMENT_SERVICE_TOKEN`、`KNOWLEDGE_EMBEDDING_TOKEN` | 是否被下发到客户端或写进日志 |
| 运营/员工码 | `OperatorAdminCli`、`StaffCodeCli` | 是否只能离线执行；权限变更是否撤销旧会话 |

---

## 4. 敏感信息处理

### 4.1 绝不提交到仓库

- `.env`、`*.local`、任何部署平台的密钥导出文件；
- `WECHAT_APP_SECRET`、`JWT_SECRET`、`DEEPSEEK_API_KEY`、`MINIO_*` 应用凭据、`CLAMAV_*`、短信供应商凭据；
- 员工码、运营账号密码、数据库 root 口令；
- 真实用户的手机号、车牌、VIN、证件照片、施工照片；
- 生产域名、内网 IP、服务器账号。

`.gitignore` 已忽略 `.env*`（保留 `.env.example`）、`*.local`、`target/`、`dist/`、`.cache/`、`logs/`、`test-results/` 等路径。**忽略规则不是安全边界**，提交前仍需自行检查。

### 4.2 前端可见即公开

Vite 的 `VITE_*` 变量会被打进小程序/H5 产物，等同于公开信息。`apps/miniapp/.env.example` 里只有地址、云环境 ID 与公开合作入口；任何密钥都不得放进去。

### 4.3 日志与错误信息

- 后端错误响应带 `request_id`，但不返回上游异常原文；新增代码请沿用这一约定。
- 上传、识别、AI 相关日志不得输出文件内容、凭据、完整车牌/VIN 或手机号。
- 报告 Bug 时请先脱敏，再贴日志。

### 4.4 默认关闭不是安全措施

`.env.example` 中大量开关默认 `false`（私有上传、扫描、识别、券、积分、内容公开、向量检索等）。这是**功能边界**，不是安全边界：一旦开启，仍需按对应运维手册配置最小权限账号、私有桶、扫描服务与迁移。

### 4.5 已知的历史注意事项

- 后端文档提示：早期曾在本仓库/沟通中出现过的微信 AppSecret，**必须先在微信后台重置，再用私有环境变量配置**，不要继续使用旧值。
- Compose 的 `ci.override.yml` 会启用仅供 CI 的演示身份，**不得**用于任何对外环境。
- `deploy/compose/cached-test.yml` 只用于离线测试，不得与生产配置叠加。

---

## 5. 披露流程

1. 报告人通过第 2 节的私密渠道提交；
2. 维护者确认问题是否存在、影响范围与修复方案（不承诺时限）；
3. 修复在私有分支或受限 PR 中完成，验证通过后合入 `main`；
4. 由维护者决定是否发布安全公告；**在修复合入前不公开细节**；
5. 如需致谢，由维护者在公告中征得报告人同意后署名。

如报告涉及已泄露的真实凭据，请在第一封报告中说明"凭据可能已泄露"，以便优先安排轮换，而不是先复现利用。
