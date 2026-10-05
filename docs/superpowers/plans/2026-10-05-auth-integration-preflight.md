# S0 真实登录联调预检实施计划

> **供执行代理使用：** 必须逐项执行本计划，可使用 superpowers:executing-plans。以下步骤用复选框跟踪。

**目标：** 提供无需秘密值的 HTTPS 登录环境预检命令，以及可执行的真实登录验收清单。

**架构：** Python 标准库脚本仅请求健康检查和匿名受保护 GET，严格验证 URL、TLS、HTTP 与 JSON 契约；离线单元测试注入请求函数。运维文档负责私有配置核对及逐角色人工验收，明确自动预检的边界。

**技术栈：** Python 3.11 标准库、`unittest`、Markdown；现有 Spring Boot API。

**规格：** `docs/superpowers/specs/2026-10-05-auth-integration-preflight-design.md`

## 全局约束

- `--base-url` 仅接受 HTTPS 原点 URL，不接受路径、查询、片段或内嵌凭据。
- 请求只使用 `GET /actuator/health` 与匿名 `GET /api/vehicle/list`，超时有限，系统证书验证，任何重定向失败。
- 期望响应依次为 HTTP 200 + JSON `status=UP`、HTTP 401 + JSON `code=40100`。
- 脚本不接收或输出账号、令牌、密钥、请求头、响应体；任一检查失败时退出码非零。
- 真实微信、手机号、商家短信及数据库迁移仅由环境负责人在私有环境中验收。

## 文件分工

- `scripts/check_auth_readiness.py`：URL 校验、只读请求、契约判断与 CLI 输出。
- `scripts/test_check_auth_readiness.py`：无网络的行为测试。
- `docs/operations/AUTH_INTEGRATION_RUNBOOK.md`：环境预检、逐角色操作和脱敏证据模板。
- `docs/operations/README.md`、`docs/progress/S0_DEPENDENCIES.md`、`docs/progress/NEXT_STEPS.md`：入口和状态。

## 任务 1：只读预检脚本

**文件：** 创建 `scripts/check_auth_readiness.py`，创建 `scripts/test_check_auth_readiness.py`。

**接口：** `validate_base_url(value: str) -> str` 返回去掉尾随斜杠的 HTTPS 原点；`run_checks(base_url: str, fetch: Callable[[str], tuple[int, bytes]]) -> list[tuple[str, bool, str]]` 返回两项检查结果；`main(argv: list[str] | None = None) -> int` 给 CLI 返回退出码。

- [x] 写离线失败测试：拒绝 HTTP、嵌入凭据、路径/查询/片段；接受 HTTPS 主机及可选端口。用注入的 `fetch` 返回健康 200 JSON 和匿名 401 JSON，断言两个通过结果和确切 URL。
- [x] 运行 `python -m unittest scripts/test_check_auth_readiness.py -v`，确认因脚本缺失失败。
- [x] 用 `urllib.parse.urlsplit` 校验 URL；用 `urllib.request` 和禁用重定向的 handler 执行 GET，捕获 `HTTPError` 的 401 状态。读取有限长度正文，解析 JSON 后只比较目标字段。对 TLS、网络、超时、重定向和格式错误输出分类原因，不输出异常原文或响应数据。
- [x] 扩展测试：健康非 UP、匿名非 40100、非 JSON、重定向、请求异常，均为失败；`main` 对无效地址返回非零。
- [x] 运行完整 `unittest` 与 `python scripts/check_auth_readiness.py --base-url http://example.invalid`，确认前者通过、后者安全失败；提交脚本和测试。

## 任务 2：人工验收与进度

**文件：** 创建 `docs/operations/AUTH_INTEGRATION_RUNBOOK.md`；修改运维 README、S0 依赖与下一步规划。

**接口：** 文档提供 `python scripts/check_auth_readiness.py --base-url https://<测试域名>` 命令、前置条件核对表、逐角色验收矩阵和脱敏结果模板。

- [x] 对照现有 `WechatAuthController`、`MerchantAuthController`、`SecurityConfig` 和 V002/V003，写出操作与期望响应，不假设未实现的商家短信通道可用。
- [x] 写前置条件：HTTPS/合法域名、迁移、私有微信和 JWT 配置、测试设备/账号、员工码；商家另需短信发送器。标明当前均待真实环境核实。
- [x] 写车主、技师、刷新/退出、权限隔离和商家短信验收表；结果只记录脱敏证据、日期、验证人及失败编号。
- [x] 从运维 README 链接新文档，在 S0 依赖和下一步规划中记录本预检与真实登录联调的区别。
- [x] 检查文档无真实密钥、手机号、验证码或令牌；运行 `git diff --check`，提交文档。

## 任务 3：交付核验

**文件：** 上述全部文件。

- [x] 复查规格与计划的检查项已落实；运行 `python -m unittest scripts/test_check_auth_readiness.py -v` 和 CLI 负例。
- [x] 运行 `git status --short`、`git diff --check`；创建独立 PR，记录 CI 与未执行的真实联调项。
