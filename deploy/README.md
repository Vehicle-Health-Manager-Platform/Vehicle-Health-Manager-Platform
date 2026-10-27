# 部署配置

2026-10-26当前说明：[本地启动](../docs/delivery/LOCAL_SETUP.md)、[部署与交接顺序](../docs/delivery/LOCAL_HANDOVER.md)、[外部接入步骤及验收](../docs/delivery/EXTERNAL_INTEGRATIONS.md)。最新范围暂缓生产部署；以下保留早期Compose说明。现行Compose包含全部63项有序迁移；OCR默认关闭返回501，另有显式开启的受限推理镜像及隔离验证，不能将默认关闭状态误写为识别未实现，详见[OCR说明](../services/ocr/README.md)。

现有 Compose 是此前网页方案的 S0 本地基础设施与过渡原型部署。复制 `.env.example` 为仓库根目录的 `.env` 并设置私有凭据，执行 `npm ci && npm run build`，再运行 `docker compose --env-file .env -f deploy/compose/compose.yml up --build -d`。Nginx 的 8081–8083 端口目前服务旧版车主/商家/技师网页原型，8084 端口服务运营 PC 网页；Prometheus 为本机 9090，MinIO 控制台为本机 9001。小程序代码上传、提审与发布、车主 H5 兜底域名和 HTTPS 均尚未配置。

MySQL 首次创建卷时执行 `docs/sql/init.sql`；再次启动不会重跑初始化，后续结构变更必须执行版本化迁移。`docs/sql/seed_test.sql` 仅用于测试环境，不能通过 Compose 自动导入。OCR `/health` 可用，但识别接口仍返回 501。

MinIO 社区版原 Docker Hub 镜像已无法拉取；Compose 现从官方 GitHub Release 的固定版本二进制构建本地 AMD64 镜像，并校验发布页 SHA-256。该上游仓库已归档，正式部署前需确定持续维护的对象存储方案。

CI 的 `compose-smoke` 作业会启动 MySQL、Redis、RabbitMQ、MinIO、OCR、后端和 Nginx，验证过渡网页入口、匿名请求的 401 响应以及本地演示用户的本人/跨用户车辆权限。这些检查保留作为后端与容器回归，不代表微信小程序构建、授权登录或真机通过。`deploy/compose/ci.override.yml` 仅在 CI 中启用演示账号，正式部署不可加载。Milvus、Prometheus 尚未覆盖。

## 支付测试开关

正式Compose不启用测试支付。`LOCAL_TEST` 仅用于独立隔离环境，须同时配置 `SPRING_PROFILES_ACTIVE=local-payment-test`、`PAYMENT_LOCAL_TEST_ENABLED=true` 和独立私有密钥 `PAYMENT_LOCAL_TEST_SECRET`（至少32字节）；与prod/production组合或缺少必要参数会拒绝启动。不可把本机私有测试文件加载到生产。V008需在已有库备份后按序迁移，新增事件/异常表，总表数48。正式微信渠道本步返回503，未发生真实扣款/退款。

## 可选行驶证候选识别

R2b默认关闭的LICENSE_OCR开关可复用受限Paddle服务，先准备私有上传/扫描和离线模型。可选ocr-runtime overlay仍需显式LICENSE_OCR_ENABLED=true；内部URL固定、token复用overlay的MILEAGE_OCR_SERVICE_TOKEN，并沿用ocr_private内部网络；不会因里程OCR启用而自动开放行驶证。服务端配置/回滚与授权证件验收见[行驶证接入](../docs/api/LICENSE_RECOGNITION.md)。本功能没有新增数据库迁移；照片不自动关联档案，也不承诺取消即删除。

## ASR持久任务（默认关闭）

基础Compose挂载V063并转发ASR_ENABLED/URL/SERVICE_TOKEN/ALLOW_PRIVATE_HTTP及调度/清理间隔，当前132表。它不会自动启动或下载ASR模型；R3b已提供Linux完整Python wheel/hash锁和实际镜像合成样本验收，尚无已验生产overlay；R3c已有Java真实模型合成任务联调/故障证据；真实中文/用户效果、存储扫描及生产路由仍待验。先完成[离线准备](../services/asr/README.md)，再按[任务接入](../docs/api/ASR_JOBS.md)启用，token仅在私有后端。关闭开关及回滚应用前核对候选、lease/TTL清理与schema兼容，不能反向删除业务数据或音频。
