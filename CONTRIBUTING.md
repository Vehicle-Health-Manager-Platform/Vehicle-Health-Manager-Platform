# 贡献指南

感谢你愿意参与汽车健康管家平台。本仓库是一个 **npm workspaces + Maven** 的混合仓库：微信小程序与历史网页在 `apps/`，共用后端在 `backend/`，Python 识别服务在 `services/`，验收与构建脚本在 `scripts/`。

提交前请先阅读 [README](README.md) 的"快速开始"与"能力现状"两节，确认你要改的部分是**已实现**还是**默认关闭/未验收**。

---

## 1. 环境准备

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| Node.js | 22.x | CI 使用的版本，`package.json` 的 workspaces 覆盖 `apps/*` |
| Java | 17 | `backend/pom.xml` 的 `maven.compiler` 目标 |
| Maven | 3.9+ | 后端构建；CI 使用 `mvn -B` |
| Docker | Linux Engine / Desktop | 后端完整测试需要容器化 MySQL/MinIO；`compose-smoke` 也需要 |
| Python | 3.11+ | `scripts/` 与 `services/` 的测试与生成脚本 |
| 微信开发者工具 | 稳定版 | 仅在小程序界面/真机验证时需要 |

首次准备：

```bash
npm ci
python -m venv .cache/qa-runtime
.cache/qa-runtime/Scripts/python.exe -m pip install -r services/ocr/requirements.txt
.cache/qa-runtime/Scripts/python.exe -m pip install -r scripts/requirements-export-qa.txt
```

配置：根目录 `.env.example` 与 `apps/miniapp/.env.example` 只是模板。复制为 `.env` / `apps/miniapp/.env.local` 后填**本机私有值**；这两个文件已被 `.gitignore` 忽略。

---

## 2. 分支与提交

仓库当前工作副本未包含 `.git` 元数据，历史提交规范无法从本地核实；以下是本仓库要求的贡献约定：

- **分支**：从 `main` 切出，命名建议 `feat/<简短描述>`、`fix/<简短描述>`、`docs/<简短描述>`、`test/<简短描述>`。
- **提交信息**：使用 `type(scope): 摘要` 形式，例如
  - `feat(order): 增加取车对比只读视图`
  - `fix(auth): 修复刷新凭证轮换后旧会话仍可用`
  - `docs(readme): 补全小程序上线步骤`
  - `test(coupon): 覆盖服务券核销的跨店隔离`
- **粒度**：一个提交只做一件事；业务代码、迁移脚本、接口契约、测试与文档应放在同一 PR 内，便于评审时对照。
- **不要**在提交里包含：`.env`、任何 `*.local` 文件、真实 AppSecret、数据库口令、员工码、运营密码、真实用户数据、`dist/`、`.cache/`、`node_modules/`、`target/`。这些路径已在 `.gitignore` 中声明。

---

## 3. 改动边界

| 改动类型 | 需要同步更新 |
| --- | --- |
| 新增/修改 HTTP 接口 | `docs/api/` 契约；然后 `python scripts/generate_openapi.py` 重新生成 `docs/api/openapi.json` |
| 新增/修改表结构 | `docs/sql/migrations/` 新版本脚本；`python scripts/build_init_sql.py`、`python scripts/generate_traceability.py`；`python scripts/verify_compose_migrations.py` |
| 新增开关/环境变量 | `.env.example`、`deploy/compose/compose.yml` 的 `environment`、相关运维手册（`docs/operations/`） |
| 新增页面 | `apps/miniapp/src/pages.json`（含标题）、对应 service 与离线测试 |
| 权限变更 | 服务端 `SecurityConfig` 与该资源的归属校验、正反向测试（本人/跨用户、本店/跨店） |
| 外部依赖接入 | 默认 `false` 开关 + 未配置时的明确降级 + `docs/delivery/EXTERNAL_INTEGRATIONS.md` |

本仓库有一条硬性约定：**默认关闭的能力不能写成已完成**。新增依赖外部资质的功能时，请保持开关默认关闭，并让接口在未配置时返回明确错误（通常是 `50300`）。

---

## 4. 本地验证要求

提交 PR 前至少跑完与你改动相关的检查；改动越靠近核心链路，要求越完整。

### 4.1 前端 / 小程序

```bash
npm test --workspace @autocare/miniapp     # Node --test 离线测试
npm run build:miniapp                      # 微信构建 + 原生样式门禁
npm run build:h5 --workspace @autocare/miniapp
npm run build:legacy-web                   # 改了 apps/owner|merchant|technician|admin 时
```

`build:mp-weixin` 结束会执行 `apps/miniapp/test/check-native-styles.cjs`：它校验共享样式确实被合进 `app.wxss`，并扫描构建产物中**不得**出现测试身份桥（`__autocareNativeAcceptance`）。

### 4.2 后端

```bash
cd backend
mvn -B test                    # 需要 Docker：含真实 MySQL / MinIO / ClamAV 容器测试
mvn -B -Pcore-coverage verify  # 需要覆盖率时
```

没有 Docker 的运行结果不能作为"完整后端测试通过"的证据。

### 4.3 生成物与迁移

```bash
python scripts/generate_openapi.py --check
python scripts/verify_compose_migrations.py
python scripts/build_init_sql.py && git diff --exit-code -- docs/sql/init.sql
python scripts/generate_traceability.py && git diff --exit-code -- docs/progress/S0_TRACEABILITY.csv
```

### 4.4 一键本地回归（推荐）

```bash
python scripts/run_local_delivery.py
python scripts/run_local_delivery.py --backend   # 追加真实 MySQL/HTTP 旅程
```

### 4.5 界面改动

界面改动请在微信开发者工具中导入 `apps/miniapp/dist/build/mp-weixin` 实际走一遍，并在 PR 描述里说明验证范围（模拟器 / 真机 / 未验证）。**模拟器截图不能替代真机结论。**

---

## 5. 问题反馈

请使用仓库的 GitHub Issues，并按模板填写（Bug 报告 / 功能建议）。有效的 Bug 报告应包含：

1. 运行形态：后端直连 / 云托管；开发者工具 / 真机 / 仅单元测试；
2. 复现步骤与期望结果、实际结果；
3. 相关日志片段与 `request_id`（**去掉 token、手机号、车牌、VIN、密钥**）；
4. 环境：Node/Java/Maven/MySQL 版本，是否使用 Compose。

安全问题**不要**开公开 Issue，请按 [SECURITY.md](SECURITY.md) 的私下渠道报告。

---

## 6. Pull Request 流程

1. Fork 或在本仓库切分支，完成改动与本地验证。
2. 按 `.github/pull_request_template.md` 填写：改了什么、为什么、怎么验证的、有哪些未验证项。
3. 确认 CI 的 8 个作业全部通过：`web`、`miniapp`、`backend`、`schema-and-ocr`、`schema-mysql`、`database-recovery`、`compose-smoke`、`performance-baseline`。
4. 涉及接口/数据库/权限的改动，请在描述中贴出对应的正反向测试名称。
5. 评审意见通过"新增提交"解决，不要强推覆盖历史（除非评审人明确要求）。
6. 合并由维护者执行；**不要在 PR 中附带发布、提审或生产部署动作**。

### 评审关注点

- 是否有服务端权限复核，而不只是前端隐藏入口？
- 写接口是否带幂等键、重复提交是否安全？
- 失败路径是否回滚、是否留下半成品数据？
- 是否有真实数据被写进仓库（截图、日志、测试夹具）？
- 文档是否与代码一致（接口路径、开关名、命令）？

---

## 7. 文档与截图

- 文档用中文，命令、路径、变量名与代码保持一致。
- 截图只能来自真实运行的项目（开发者工具或真机），不得使用生成图片或设计稿冒充；合成数据要在说明中标注。
- 新增截图请放到 `docs/ui/screenshots/`，并在该目录的 `README.md` 里登记来源与更新方式。
- 不提交包含真实手机号、车牌、VIN、头像、账号或内部地址的截图。

---

## 8. 行为规范

参与本项目即表示你同意遵守 [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)。
