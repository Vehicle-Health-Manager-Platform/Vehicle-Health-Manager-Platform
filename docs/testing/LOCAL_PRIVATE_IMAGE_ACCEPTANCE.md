# 本地私有图片与拍照方式联调

日期：2026-10-06。源码基线 `d072f11`（PR #20）；后端复用 `vehicle-auth/backend:business-0237aba`。#20 未修改后端源码。本步运行现有真实适配器与接口，不更改上传或档案契约。

## 结果与边界

- 后端 **26 项**：真实微信车主会话、真实 PNG 上传及 CLEAN 元数据、原图同键改名重放、同键异字节 409、成功审计唯一、HTTPS 签名原字节下载、匿名对象/列桶 403、签名到期 403 与重新获取、两种方式归档及引用/审计、拍照无图 400、合成他人图片访问/归档 404、本人合成 PENDING 预览 409/归档 404，以及扫描故障 503 后原图原键恢复。
- H5 实际交互 **11 项**：原生选图/上传 API、拍照无图禁止保存、上传提交后截断响应再原图原键重试、仅相机来源、原生预览解码、拍照来源列表、手动相册/相机兼容、手动图片保存及首页最近来源。
- 微信 code、后端、MySQL、MinIO 和 ClamAV 均真实运行。UI 用合成 PNG 填充 H5 原生文件输入，并保留原生上传和预览；没有使用业务响应替身。无界面浏览器会立即取消系统选择器，测试仅在提供文件期间抑制其打开。
- 自签证书只在 Node 测试传输中显式信任该证书；没有修改系统信任或关闭生产 HTTPS 校验。H5 预览通过本地桥接取真实签名字节再解码，不等于浏览器或微信接受该自签域名。
- **未验收**：物理相机拍摄、真实相机权限/取消、微信完整图片 UI、手机可达且证书可信的 HTTPS、第二个真实微信车主和官方病毒库的全面覆盖率。合成他人/PENDING 元数据不代表第二个真实身份或真实异步扫描任务。

## 环境证据

| 组件 | 本步版本/状态 |
| --- | --- |
| MinIO | 仓库固定 `RELEASE.2025-07-23T15-54-02Z`，官方二进制 SHA-256 校验通过；私有桶 `owner-archives-local`，独立上传账号；S3/控制台仅回环 9000/9001 |
| 初始化工具 | 仓库固定 mc `RELEASE.2025-08-13T08-35-41Z`，官方 SHA-256 校验通过；只首次显式初始化，拒绝覆盖账号/策略 |
| ClamAV | 官方 `1.4.3_base`，digest `sha256:629a3050df6a706aedb31859fbb8139e9aaf8f56b0ffefbf251b8358a7c9e76c`；扫描 TCP 不映射宿主端口 |
| 官方病毒库 | freshclam 下载并验签：daily 28144（2026-10-05 06:24 UTC）、main 63、bytecode 339；首次验收处于 48 小时新鲜度范围；不采用 CI 合成签名放行 |
| 本机网络 | `vehicle-auth-local_default`；MySQL 测试卷保留，后端 18080、TLS 下载 9443、H5 服务 4317 均只监听回环 |
| TLS | 本机自签证书/私钥放于被忽略的 `test-results`；签名直接针对 `https://127.0.0.1:9443`，转发保留 host/path/query |

freshclam 本次提示推荐引擎为 1.4.6；本步复用仓库固定 1.4.3。正式部署须独立核对受支持引擎、官方更新状态与资源容量，不能只检查 PONG。

## 本机准备与启动

已有本次容器和私有文件时，启动 MinIO、updater、clamd 和后端即可继续开发；不要再次执行初始化，也不要删除数据库/存储卷。新隔离环境的部署配置与专用账号初始化见[适配器说明](../api/UPLOAD_ADAPTERS.md)，本机使用下列固定名称，与脚本的隔离检查一致：

```powershell
docker volume create --label com.docker.compose.project=vehicle-auth-local vehicle-auth-local_upload_minio_data
docker volume create --label com.docker.compose.project=vehicle-auth-local vehicle-auth-local_clamav_data
docker run -d --name vehicle-auth-local-minio --label com.docker.compose.project=vehicle-auth-local --env-file .env.minio.local --network vehicle-auth-local_default -p 127.0.0.1:9000:9000 -p 127.0.0.1:9001:9001 -v vehicle-auth-local_upload_minio_data:/data vehicle-health/minio:RELEASE.2025-07-23T15-54-02Z server /data --console-address :9001
docker run --rm --network vehicle-auth-local_default --env-file .env.upload-admin.local vehicle-health/upload-init:local
docker run -d --name vehicle-auth-local-clamav-updater --label com.docker.compose.project=vehicle-auth-local --network vehicle-auth-local_default -e TZ=UTC -v vehicle-auth-local_clamav_data:/var/lib/clamav --entrypoint freshclam registry-1.docker.io/clamav/clamav:1.4.3_base --daemon --foreground=true
docker run -d --name vehicle-auth-local-clamav --label com.docker.compose.project=vehicle-auth-local --network vehicle-auth-local_default -e TZ=UTC -v vehicle-auth-local_clamav_data:/var/lib/clamav:ro -v "${PWD}/deploy/clamav/upload-clamd.conf:/etc/clamav/upload-clamd.conf:ro" --entrypoint clamd registry-1.docker.io/clamav/clamav:1.4.3_base --config-file=/etc/clamav/upload-clamd.conf
```

这些创建命令仅在对应容器尚不存在时运行。固定网络与 MySQL 来自[本地业务联调](LOCAL_BUSINESS_ACCEPTANCE.md)。先确认更新与扫描就绪，再启动后端。

私有文件职责：

- `.env.minio.local`：只含 MinIO root 配置。
- `.env.upload-admin.local`：只给初始化工具，含 root、专用上传账号、桶及 `MINIO_ENDPOINT=http://vehicle-auth-local-minio:9000`。
- `.env.auth-backend.local`：仅后端登录/数据库所需参数，从现有私有登录配置中提取；不传 MinIO root、Redis 或 RabbitMQ 凭据。
- `.env.upload-app.local`：只含专用上传凭据、桶/region 和上传配置。启用存储/扫描，内部 endpoint 如上，`MINIO_PUBLIC_ENDPOINT=https://127.0.0.1:9443`、`UPLOAD_ALLOW_INSECURE=true` 仅支持本机内部 HTTP，`CLAMAV_HOST=vehicle-auth-local-clamav`。后端脚本验签到期时设置 `UPLOAD_SIGNED_URL_SECONDS=5`，正常本地开发恢复 120 并重建后端容器。原私有登录文件不被修改。

仅创建/重建无状态后端时，保留 MySQL 和上传卷，按登录运维流程停止旧后端后执行：

```powershell
docker run -d --name vehicle-auth-local-backend --env-file .env.auth-backend.local --env-file .env.upload-app.local --network vehicle-auth-local_default -e MYSQL_HOST=vehicle-auth-local-mysql-1 -p 127.0.0.1:18080:8080 vehicle-auth/backend:business-0237aba
```

## Docker 加速源问题与 Windows 换行

本机加速源曾给 ClamAV/Alpine 镜像请求返回 HTML，镜像无法解包。直接从官方 `registry-1.docker.io` 拉取同版本成功，没有改动全局 Docker 设置或切换不明镜像。若构建 MinIO/初始化工具也遇到此错误，可在被忽略目录制作只替换 FROM 主机的 Dockerfile：

```powershell
New-Item -ItemType Directory -Force test-results | Out-Null
(Get-Content deploy/minio/Dockerfile -Raw).Replace('FROM alpine:3.21','FROM registry-1.docker.io/library/alpine:3.21') | Set-Content test-results/minio.Dockerfile
(Get-Content deploy/upload-init/Dockerfile -Raw).Replace('FROM alpine:3.21','FROM registry-1.docker.io/library/alpine:3.21') | Set-Content test-results/upload-init.Dockerfile
docker build -t vehicle-health/minio:RELEASE.2025-07-23T15-54-02Z -f test-results/minio.Dockerfile deploy/minio
docker build -t vehicle-health/upload-init:local -f test-results/upload-init.Dockerfile deploy/upload-init
```

Windows CRLF 曾使 clamd 报 `Incorrect argument format for option Foreground`。本步新增 `.gitattributes`，把部署 shell 和扫描配置固定为 LF；扫描启动已复核通过。已有 checkout 若未转换，先将对应文件保存为 LF，再重新挂载/构建。不要降低扫描新鲜度或使用合成库规避启动失败。

## 验收命令

生成仅本机使用的短期证书（OpenSSL 可使用 Git 安装目录下的版本；不安装到系统信任库）：

```powershell
& '<Git安装目录>\usr\bin\openssl.exe' req -x509 -newkey rsa:2048 -nodes -keyout test-results/local-upload-key.pem -out test-results/local-upload-cert.pem -days 7 -subj '/CN=Local archive acceptance only' -addext 'subjectAltName=IP:127.0.0.1,DNS:localhost'
node scripts/local_upload_tls.cjs --allow-local-upload-tls
```

TLS 服务保持运行。微信开发者工具使用当前构建、自动化端口 9420；本地合成车型车辆已存在；后端签名 TTL 为 5 秒时，在另一个终端执行：

```powershell
node scripts/check_local_private_images.cjs --allow-local-image-test-writes
```

成功输出 `passed:26`。脚本只接受固定隔离容器、使用真实 wx.login；会临时停止并恢复扫描器以验证失败，最终撤销自己创建的会话。它保留本轮成功文件/档案以及明确的合成权限种子供复核；不打印 code、令牌、对象键或签名 URL。

恢复签名 TTL 为 120 后，H5 发布产物按本地 API 地址构建，启动现有服务：

```powershell
node scripts/local_business_harness.cjs --allow-local-test-writes
```

用 gstack `/browse` 在干净车主登录页运行 `apps/miniapp/test/local-private-image-browser-flow.js`，成功输出 `passed:11`。该流程操作真实原生 H5 图片 API；相机输入控件收到合成 PNG，预览桥接仅接受固定回环 HTTPS、固定桶/对象路径，并通过指定证书验证 TLS。它不改变小程序源码中的 HTTPS URL 校验，也不记录签名。

结束验收后停止回环测试服务；官方更新、扫描、存储和后端可保留供开发。真实微信相机/预览仍需可信 HTTPS 和实际设备，按[下一步规划](../progress/NEXT_STEPS.md)推进。
