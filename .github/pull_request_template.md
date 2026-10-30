## 改动说明

<!-- 一句话说明这个 PR 做了什么 -->

## 为什么改

<!-- 对应的问题、Issue 编号，或用户可感知的收益 -->

Closes #

## 改动范围

- [ ] 小程序 `apps/miniapp`
- [ ] 后端 `backend`
- [ ] 数据库迁移 `docs/sql/migrations`
- [ ] 接口契约 `docs/api`（需重新生成 `openapi.json`）
- [ ] 部署/Compose `deploy`
- [ ] 识别服务 `services`
- [ ] 文档 `docs` / 根目录治理文件
- [ ] 其他：

## 验证方式

<!-- 勾选实际跑过的，并在下方贴关键输出或说明；不要勾未执行的项 -->

- [ ] `npm test --workspace @autocare/miniapp`
- [ ] `npm run build:miniapp`
- [ ] `npm run build:h5 --workspace @autocare/miniapp`
- [ ] `npm run build:legacy-web`
- [ ] `cd backend && mvn -B test`（需要 Docker）
- [ ] `cd backend && mvn -B -Pcore-coverage verify`
- [ ] `python scripts/run_local_delivery.py`
- [ ] `python scripts/run_local_delivery.py --backend`
- [ ] `python scripts/generate_openapi.py --check`
- [ ] `python scripts/verify_compose_migrations.py`
- [ ] 微信开发者工具模拟器手工验证
- [ ] 真机验证（请说明机型与网络环境）

关键输出 / 截图：

```text

```

## 安全与边界检查

- [ ] 新增接口在**服务端**复核了会话、角色与资源归属（不是只隐藏前端入口）
- [ ] 写接口支持幂等，重复提交不会重复记账/重复发券
- [ ] 失败路径会回滚，不留半成品数据
- [ ] 新增外部依赖默认关闭，未配置时返回明确错误
- [ ] 没有提交 `.env`、`*.local`、密钥、员工码、真实用户数据或构建产物
- [ ] 截图来自真实运行的项目，已去除敏感信息

## 文档同步

- [ ] 接口/字段变更已更新 `docs/api`
- [ ] 表结构变更已新增迁移并重新生成 `init.sql` 与追踪表
- [ ] 新开关已同步 `.env.example`、`deploy/compose/compose.yml` 与运维手册
- [ ] README 中的命令、路径、能力状态仍然准确

## 未验证 / 已知限制

<!-- 如实列出：哪些没跑、为什么没跑、留待谁在什么环境补验 -->
