# 私有上传真实适配器与内部签名

本步接入官方 MinIO Java SDK 9.0.3（Maven 明确引入 OkHttp JVM 5.3.2、Kotlin 2.2.21）和 ClamAV INSTREAM。本适配器阶段不包含 HTTP 路由；后续 PR #12 的车主入口见 [HTTP 接入说明](UPLOAD_HTTP.md)。适配器自身不新增 OpenAPI 操作；公开入口、幂等、限流和对象清理在下一步完成。本机官方库及 H5 图片预览链路已通过，见[复现说明](../testing/LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)；微信实际图片 UI 和正式部署尚未验收。

## 服务接口

现有 `PrivateUploadService` 自动发现显式启用的 `MinioPrivateObjectStore` 和 `ClamdVirusScanner`；默认不开启。存储使用固定桶及服务生成的 `uploads/<UUID>`，检查桶存在与策略读取权限，仅“未设置桶策略”允许上传；任何非空策略均拒绝。不支持任意 S3 服务的访问控制判定，目标限定 MinIO。删除允许在策略变化后继续补偿。

内部 `PrivateFileAccessService.sign(actor, fileId)` 先复核本人、未删除、CLEAN，再检查桶私有性及对象大小/MIME 与元数据一致，签发 GET URL。主体来自受信任调用方，不接受客户端指定对象键；未来 HTTP 接口还需复核身份会话与业务附件关系。返回 URL 和到期时间，默认 `toString` 隐藏 URL；调用方仍不得记录 `url()`、完整签名或客户端响应正文。

签名默认 120 秒，可配置 1–300 秒；已有签名在有效期内仍可使用，业务权限撤销不会立即使 URL 失效。内部端点与客户端端点必须指向同一 MinIO、桶和 region。对客户端端点直接签名，不在签名后替换 hostname，不回退到 Docker 内部地址。

## 私有环境变量

| 配置 | 用途 |
| --- | --- |
| `UPLOAD_STORAGE_ENABLED` / `UPLOAD_SCAN_ENABLED` | 默认 false，分别启用真实存储/扫描实现 |
| `MINIO_ENDPOINT` | 后端可达的 S3 服务地址，不含用户信息、非根路径、查询或片段 |
| `MINIO_PUBLIC_ENDPOINT` | 客户端可达的 S3 地址；未配置时可上传，但拒绝签名 |
| `UPLOAD_MINIO_BUCKET` / `UPLOAD_MINIO_REGION` | 固定上传专用桶与明确 region，默认部署示例 region 为 us-east-1 |
| `UPLOAD_MINIO_ACCESS_KEY` / `UPLOAD_MINIO_SECRET_KEY` | 独立上传账号，不复用 root 或 Milvus 凭据 |
| `UPLOAD_ALLOW_INSECURE` | 默认 false；开发叠加配置允许内部 HTTP，以及公共回环 HTTP，真实客户端地址要求 HTTPS |
| `UPLOAD_STORAGE_TIMEOUT_MS` | 默认 10000，范围 100–60000；连接最多 3000，读/写/整个 HTTP 调用均有限制 |
| `UPLOAD_SIGNED_URL_SECONDS` | 默认 120，范围 1–300 |
| `CLAMAV_HOST` / `CLAMAV_PORT` | 内部扫描地址，默认端口 3310 |
| `CLAMAV_CONNECT_TIMEOUT_MS` / `CLAMAV_SCAN_TIMEOUT_MS` | 默认 3000 / 30000；扫描总超时最多 60000，须不小于连接超时 |

启用后缺少必需配置或仍使用 `unconfigured`/`change-me` 占位时启动失败，错误仅指配置名。ClamAV 主机在初始化时解析，地址变动后需重建应用实例；每次扫描的连接、发送与接收受总截止时间约束，超时主动关闭 socket。响应最多 4 KiB；仅精确正常响应放行，感染、错误、截断、未知回复和故障均阻止存储。运行时配置固定启用 VERSION 检查：须存在可解析的官方数据库编号与 UTC 日期，更新时间不超过 48 小时且不晚于当前时间 5 分钟。检查与扫描共享总超时；短暂更新失败且已有库仍新鲜时可继续，超过新鲜度上限或无法确认时拒绝。部署将扫描和更新容器时区设为 UTC。

## 可选开发部署

`deploy/compose/upload.yml` 为显式启用的开发叠加配置，原 Compose 默认行为不变。以下命令使用自己管理的私有 env 文件，勿提交该文件。

```powershell
docker compose --env-file .env -f deploy/compose/compose.yml -f deploy/compose/upload.yml --profile uploads --profile upload-init run --rm --build upload-init
docker compose --env-file .env -f deploy/compose/compose.yml -f deploy/compose/upload.yml --profile uploads up -d --build
```

首次初始化显式创建/检查专用私有桶、新建仅能查询该桶策略、列举该桶并读写删除 `uploads/*` 的独立账号。已有账号或同名 IAM 策略时拒绝覆盖；已有桶策略不私有时拒绝更改。部分初始化失败可能留下新建资源，需要操作方检查并恢复，不能直接删除用户已有配置。已人工配置好桶/账号时跳过初始化命令，使用已有配置直接启动。root 配置只传给初始化容器；应用只收到上传专用凭据。初始化工具使用官方固定版本 `RELEASE.2025-08-13T08-35-41Z` 的 linux-amd64 发布资产并校验官方 SHA-256；对应 Docker Hub 镜像已无法拉取，不依赖它。

开发叠加配置仅把 MinIO S3 端口映射到 `127.0.0.1:9000`，可配 `MINIO_PUBLIC_ENDPOINT=http://127.0.0.1:9000` 做本机验证。真实微信客户端不能访问开发机回环地址；实际部署须提供合法 HTTPS S3 域名，反向代理保留签名涉及的 host、路径及查询，不共用仅代理 `/api/` 的前端地址。

ClamAV 固定 `clamav/clamav:1.4.3`，独立 freshclam 更新进程与 clamd 共享官方签名卷，扫描进程只读该卷；不映射 TCP 端口到宿主外网。流限制 10 MiB，文件/总扫描限制 12 MiB并启用超限告警。官方签名未就绪时扫描不放行；更新、内存容量及真实环境可达性需部署验收，容器健康检查不能替代这些证据。

## 验证范围

协议测试使用真实 TCP 对端校验二进制分块与字节、正常/感染/错误/截断/超长回复，以及读取和阻塞发送的整体截止时间；配置/端点与签名权限使用离线测试。真实 MinIO 容器验证匿名 GET/list 拒绝、签名下载、过期、公开策略拒绝、错误凭据、缺失桶、对象不一致与专用账号初始化。

真实 ClamAV 1.4.3_base 引擎容器使用仅供测试的合成 HDB 签名；联合真实 MinIO 和 MySQL 验证正常上传与感染拒绝后没有对象/元数据。该签名只挂载到可丢弃测试容器，不进入生产部署，不证明官方病毒库覆盖率或生产更新状态。容器测试无 Docker 时必须失败，不允许跳过。

原上传核心的跨存储补偿限制仍在；不能因签名与适配器测试通过就开放尚无幂等/限流/清理流程的公共上传入口。后续先完成该入口，再优先交付 S1 小程序车辆与档案页面。
