# 部署配置

现有 Compose 是此前网页方案的 S0 本地基础设施与过渡原型部署。复制 `.env.example` 为仓库根目录的 `.env` 并设置私有凭据，执行 `npm ci && npm run build`，再运行 `docker compose --env-file .env -f deploy/compose/compose.yml up --build -d`。Nginx 的 8081–8083 端口目前服务旧版车主/商家/技师网页原型，8084 端口服务运营 PC 网页；Prometheus 为本机 9090，MinIO 控制台为本机 9001。小程序代码上传、提审与发布、车主 H5 兜底域名和 HTTPS 均尚未配置。

MySQL 首次创建卷时执行 `docs/sql/init.sql`；再次启动不会重跑初始化，后续结构变更必须执行版本化迁移。`docs/sql/seed_test.sql` 仅用于测试环境，不能通过 Compose 自动导入。OCR `/health` 可用，但识别接口仍返回 501。

MinIO 社区版原 Docker Hub 镜像已无法拉取；Compose 现从官方 GitHub Release 的固定版本二进制构建本地 AMD64 镜像，并校验发布页 SHA-256。该上游仓库已归档，正式部署前需确定持续维护的对象存储方案。

CI 的 `compose-smoke` 作业会启动 MySQL、Redis、RabbitMQ、MinIO、OCR、后端和 Nginx，验证过渡网页入口、匿名请求的 401 响应以及本地演示用户的本人/跨用户车辆权限。这些检查保留作为后端与容器回归，不代表微信小程序构建、授权登录或真机通过。`deploy/compose/ci.override.yml` 仅在 CI 中启用演示账号，正式部署不可加载。Milvus、Prometheus 尚未覆盖。

## 支付测试开关

正式Compose不启用测试支付。`LOCAL_TEST` 仅用于独立隔离环境，须同时配置 `SPRING_PROFILES_ACTIVE=local-payment-test`、`PAYMENT_LOCAL_TEST_ENABLED=true` 和独立私有密钥 `PAYMENT_LOCAL_TEST_SECRET`（至少32字节）；与prod/production组合或缺少必要参数会拒绝启动。不可把本机私有测试文件加载到生产。V008需在已有库备份后按序迁移，新增事件/异常表，总表数48。正式微信渠道本步返回503，未发生真实扣款/退款。
