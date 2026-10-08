# A6 防护与报工本机验收

日期：2026-10-08。后端代码来自 PR #42 `b383f5a`（最后后端修改 `6ee0b86`）；页面来自 PR #43 `9254e86`。脚本执行前比较运行容器 `/app/app.jar` 与本地编译 JAR 的 SHA-256，匹配后才准备夹具。

## 结果及证据口径

- 后端全量 CI **348/348**，小程序 **169/169**，微信/H5 构建通过；[A6.2 CI](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37797778774) 六项全绿。
- `scripts/local_service_work_e2e.cjs --allow-local-test-writes` 真实 HTTP 验收 **65/65 通过**，0 失败，最终进程退出码 0。不是 65 项新增 JUnit 测试，也不与 348 相加。
- 商家防护→本人完整报工→本人质检签字→PENDING_VERIFY，包括文件真实 multipart、MinIO 私有对象、官方 ClamAV 扫描和 MySQL 留痕；没有业务响应替身。
- gstack `/browse` 验证 H5 实际输入/上传/确认与 canvas 笔画→PNG 导出→上传→签字，[页面证据](../progress/A6_2_SERVICE_UI_EXECUTION.md)。H5 使用无界面合成图片，不是物理相机。
- 合成会话由本地 JWT 配置签发并写入 auth_session，真实 A4 确认、A5 派工与接单构成前置。不是实际微信登录或第二个真实用户。
- 签名 HTTPS 下载仅在 Node 中显式信任已有本机公钥证书，下载原字节一致；无签名返回 403。没有修改系统信任或关闭 TLS 校验，不代表 H5/微信接受本机自签证书直连预览。

## 65 项覆盖

| 领域 | 实际检查 |
| --- | --- |
| 环境 | 固定隔离容器/网络、仅回环 18080、A6 镜像、V014/54 表、JAR 一致、健康 |
| 前置 | 真实车主确认→派工→本人接单；缺接车 43001、未确认 43003、缺防护 43002、未完整报工 43005 |
| 角色与归属 | 匿名 401，商家/技师错误角色 403，另一技师/他店/非关联文件 404，文件非本人 422 |
| 证据 | 商家及技师真实 PNG 私有上传、CLEAN/类型/大小；必选项目、三组图片不重复、故障件/配件明确声明、整数分钟 |
| 不可变与幂等 | 三类原键重放响应相同，新键重复 40905，同键报工异体 400；缓存重放复核当前证据和绑定 |
| 施工门禁 | 失效防护/完工证据阻断，DISPUTED 阻断报工重放和签字 43007，已用施工照不能再当签名，商家通用 FINISH_SERVICE 43005 |
| 签字结果 | SIGNED/时间/就绪标记齐全，仅 PENDING_VERIFY；商家只读同一记录 |
| 私有图片 | 1–300 秒 HTTPS 签名、no-store、原字节下载、无签名 403、本人/本店关联授权 |
| 留痕 | 防护/报工唯一、五张证据关联、单次状态迁移和成功审计；缓存无身份字段；解绑/撤销后旧请求 401 |

负向检查对本轮真实文件元数据临时设置 PENDING/删除，对合成订单临时置 DISPUTED，随后恢复；这是明确故障注入，不是病毒检出或真实争议处理流程。确认值 3、OPEN 争议与订单状态不一致、真实竞争/回滚、历史拒绝另由 [A6.1 MySQL 测试](../progress/A6_1_SERVICE_BACKEND_EXECUTION.md)覆盖。H5 流程使用无故障件/无配件；HTTP 正向流程使用故障件照、配件和 45 分钟，两种口径分开。

## 固定本地环境

- `vehicle-auth-local-mysql-1`，Compose project=`vehicle-auth-local`，既有独立测试卷；迁移前备份到被忽略的 `test-results/before-v014-a6.sql`。V014 连续两次应用，总计 54 表。
- `vehicle-auth-local-backend`，镜像 `vehicle-auth/backend:a6-service`，网络 `vehicle-auth-local_default`，仅 `127.0.0.1:18080`。保留旧镜像与配置；无状态替换没有删除 MySQL/对象卷。
- 已有 `vehicle-auth-local-minio` 与 `vehicle-auth-local-clamav`/updater，使用仓库固定版本；上传安全门禁保持启用。
- 真实签名原点 `https://127.0.0.1:9443`，已有本地 TLS 下载桥接；只复制已有公钥证书到当前工作树被忽略目录，不停止其他会话的下载服务。
- H5 dev 仅 `127.0.0.1:4326`；本地传输桥接仅 `127.0.0.1:18086`，允许该 H5 原点，原样转发 JSON/multipart 至 18080。合成身份入口及配置只存在被忽略 `.cache`，不进入产品或提交。

## 复现步骤

需要 Docker、Node 20+、已有本地业务基础（合成车型 9100601、项目 1）、真实私有图片适配器与扫描器、有效本机 TLS 下载服务和当前后端 JAR。脚本没有凭据参数，不输出密钥/令牌/对象路径/签名 URL。只可用于上述隔离环境；不要改成生产容器名。

1. 在仓库根目录构建当前后端。可使用本机 Maven/Java 17，或既有缓存 Docker Maven；不执行测试的 package 用于运行镜像，测试证据来自前述全量 CI。

```powershell
$workspacePath=(Get-Location).Path
docker run --rm --mount "type=bind,source=$workspacePath,target=/project" --mount "type=bind,source=$workspacePath/.cache/maven,target=/root/.m2" -w /project/backend maven:3.9-eclipse-temurin-17 mvn -q -DskipTests package
```

2. 已有库显式应用 `docs/sql/migrations/V014__service_work.sql`，应用前完成隔离库备份。Compose 初始化挂载只对新库生效；不删卷。A6 镜像基于 `eclipse-temurin:17-jre-alpine`，仅复制该 JAR 至 `/app/app.jar` 并运行 `java -jar /app/app.jar`。保留已有登录/数据库/上传环境配置，通过私有 env-file 传给后端，设置 8080 端口，绑定 `127.0.0.1:18080:8080`，网络如上。本次构建上下文只含 JAR/Dockerfile，没有配置或密钥。

3. 下载原点必须与后端 MINIO_PUBLIC_ENDPOINT 一致。已有 9443 服务则复用；没有服务时按[私有图片验收](LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)创建本机短期证书并启动 `node scripts/local_upload_tls.cjs --allow-local-upload-tls`。公钥证书保存在 `test-results/local-upload-cert.pem`，脚本只显式信任该证书；不得设 NODE_TLS_REJECT_UNAUTHORIZED=0。

4. 执行：

```powershell
node --check scripts/local_service_work_fixtures.cjs
node --check scripts/local_service_work_e2e.cjs
node scripts/local_service_work_e2e.cjs --allow-local-test-writes
```

预期最后输出 `passed: 65 / 65`，退出码 0。未提供写入标志会在接触 Docker 前拒绝（退出码 2）；镜像/数据库/网络不符，或 JAR 不一致，均拒绝准备夹具。前置不符时修复隔离环境，不删除真实数据或放宽安全门禁。

## 夹具与回收

专用订单 9206401–9206404，商家/商家员工 9206201/9206202，技师 9206231–9206233，绑定 9206331–9206333，车主/车辆 9206290，时段 9206301。重跑只清理这些明确 ID 对应订单记录、审计、缓存与合成身份；先校验预留 ID 与合成标记碰撞，不包含全表 DELETE 或对其他阶段争议审计的通配删除。

完成后撤销本轮所有合成会话，技师甲绑定处于撤销状态；成功订单与上传对象保留供人工核对。异常退出尽量撤销已创建的合成会话；若输出回收失败，按上述合成 ID 独立撤销，勿撤销真实账号。重复运行会生成新的私有测试对象，不自动清空桶。

## 尚待独立验收

真实相机/权限/取消、手机技师和商家页面、真实微信登录、手机可达且证书可信的 HTTPS、H5/微信直连图片预览。签名板已在 H5 鼠标交互通过，手机触摸仍需真机核对。A7 核销/退款/取消/评价/档案回写/经验卡片、第二次异议与超时自动处理不在本阶段。
