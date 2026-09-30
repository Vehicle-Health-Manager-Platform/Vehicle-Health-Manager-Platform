# 部署配置

S0 本地环境：复制 `.env.example` 为仓库根目录的 `.env` 并设置私有凭据，执行 `npm ci && npm run build`，再运行 `docker compose --env-file .env -f deploy/compose/compose.yml up --build -d`。Nginx 的车主、商家、技师、运营入口分别为本机 8081–8084 端口；Prometheus 为本机 9090，MinIO 控制台为本机 9001。此配置仅绑定本机端口，生产域名和 HTTPS 证书需单独配置。

MySQL 首次创建卷时执行 `docs/sql/init.sql`；再次启动不会重跑初始化，后续结构变更必须执行版本化迁移。`docs/sql/seed_test.sql` 仅用于测试环境，不能通过 Compose 自动导入。OCR `/health` 可用，但识别接口仍返回 501。

MinIO 社区版原 Docker Hub 镜像已无法拉取；Compose 现从官方 GitHub Release 的固定版本二进制构建本地 AMD64 镜像，并校验发布页 SHA-256。该上游仓库已归档，正式部署前需确定持续维护的对象存储方案。
