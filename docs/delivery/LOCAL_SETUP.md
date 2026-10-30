# 本地启动、配置与测试

当前1/3/4/5项完善（2026-10-30）：默认回归为12组，库版本65迁移/134表。ASR、图文/PDF、真实中文嵌入、自动索引及公开VECTOR均已具备显式测试配置，仍默认关闭。分别参照[ASR](../api/ASR_JOBS.md)、[图文](../api/DOCUMENT_JOBS.md)、[公开检索](../api/KNOWLEDGE_VECTOR_RETRIEVAL.md)与[实际结果](../progress/RECOGNITION_R5C2_PUBLIC_VECTOR.md)。下方ASR段落中的63迁移/10组为R3时的阶段记录，不是当前启动上限。正式认证缺失时，业务验收继续使用明确标识的隔离合成会话，不在正常包中增加伪造身份入口。

## 环境

2026-10-30实测：当前覆盖率运行器已解决Windows中文工程路径的JaCoCo采集问题，agent使用每次独占ASCII临时目标，完成后复制回backend/target，完整2641项已直接生成本轮数据。无需移动整个工程。系统TEMP仍需ASCII可写目录，统一回归包装使用系统独占临时目录并清理。旧误解码和会话恢复结果仅保留历史，不以旧target充当新测。详见[本轮报告](../testing/SCOPE_1345_ACCEPTANCE_2026_10_30.md)。

使用Node.js 22、npm锁文件、Python 3.11+、Java17、Maven3.9和Docker Linux Engine。Java/Maven可配置到当前终端PATH，不需更改全局设置。首次安装依赖/镜像需要网络；测试HTTP需要本机回环访问权限。

在仓库根目录执行：

```powershell
npm ci
python -m venv .cache/qa-runtime
.cache/qa-runtime/Scripts/python.exe -m pip install -r services/ocr/requirements.txt
.cache/qa-runtime/Scripts/python.exe -m pip install -r scripts/requirements-export-qa.txt
```

OCR普通协议测试依赖Pillow；真实模型准备另见[OCR运行说明](../../services/ocr/README.md)。不安装真实模型也可运行明确使用替身的协议/异常测试。Windows不执行Linux内核专属检查，跳过必须记录。

## 不需要微信/短信账号的验证

```powershell
.cache/qa-runtime/Scripts/python.exe scripts/run_local_delivery.py
```

运行小程序、Python工具和OCR回归、微信/H5构建、OpenAPI、迁移挂载、交接索引与契约结论检查。每次生成独立 `.cache/local-delivery-<随机值>/report.json` 和日志。任一命令失败或运行期间源码变化均非零退出，继续执行后续独立检查以保留完整问题清单；不会读取旧summary充当新执行。

需要真实MySQL/HTTP集成时，确认 `java -version`、`mvn -version`、`docker version` 正常后执行：

```powershell
.cache/qa-runtime/Scripts/python.exe scripts/run_local_delivery.py --backend
```

后端使用全新Testcontainers数据库，创建合成角色、CLEAN文件元数据与LOCAL_TEST资金；三条核心业务旅程走真实TCP HTTP和正式业务Bean。数据不写已有业务库。禁止同时运行多个共用 `backend/target` 的Maven任务。`--backend` 不开启任何生产身份桥。

`--backend`通过宿主Java/Maven执行完整 `-Pcore-coverage clean verify`，再审计JUnit、声明范围的行/方法覆盖率和核心旅程；失败、跳过、缺失或配置/迁移变化均拒绝通过。三个需固定真实模型资源的opt-in套件由profile明确排除，须各自独立运行，不计为已验证真实服务。首次测试镜像构建需要网络及Docker权限。

完整套件的私有上传适配器要求宿主回环端点；嵌套Docker Maven不能替代该执行环境。`run_core_coverage.py --docker --full`会明确拒绝运行。离线且已有完整依赖/上传测试镜像时，可单独执行 `scripts/run_core_coverage.py --full --offline --prebuilt-upload-images --maven-repository <本地Maven缓存目录>`；缺失缓存仍应失败。Windows须将Java17及Maven3.9加入当前进程PATH；Docker客户端兼容选项可在该测试终端配置 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44 -Duser.timezone=UTC`。

本机Docker Engine/宿主Java组合可在当前终端显式设置 `$env:TESTCONTAINERS_HOST_OVERRIDE='127.0.0.1'`，避免遗留的容器内host.docker.internal设置影响端点校验。此配置不适用于远程Docker主机，也不应通过放宽生产上传URL规则来适配测试。

Maven完整套件还包含真实私有文件、扫描、事务回滚、并发和角色隔离测试，可能耗时数十分钟；需Docker可用且依赖/镜像可取得。全量完成后可以独立校验本次核心旅程：

完整适配器套件在较慢机器可能超过两小时。统一入口对完整后端等待专用运行器结束，不再用普通检查的90分钟外层超时只杀包装进程；其他默认组仍有5400秒限制。手动中断后须先核对本次Maven/Testcontainers进程与资源，再启动共用target的下一轮。

```powershell
python scripts/run_isolated_journeys.py --check-only
```

`--check-only`仅核对已经生成的JUnit证据，不代表再次发起业务请求。完整旅程的步骤和模拟边界见[核心旅程](../testing/ISOLATED_CORE_JOURNEYS.md)。测试结束Testcontainers应清理自建资源；异常中断后只按本次标签核查/清理，不能删除其他项目容器或卷。

## 查看页面

```powershell
npm run preview:miniapp
```

微信开发者工具导入 `.cache/local-preview/mp-weixin`；页面和原生标题明确标注“演示”，无网络、只读，写入会被拒绝。适合熟悉三角色界面，不能证明表单真实提交或业务闭环。[只读演示说明](../operations/LOCAL_ROLE_PREVIEW.md)列出已有样本及限制。

正常小程序：`npm run build:miniapp`，导入 `apps/miniapp/dist/build/mp-weixin`。H5辅助调试：`npm run dev:h5 --workspace @autocare/miniapp`。正常包不含演示或测试身份桥；缺正式身份时正常登录仍应清楚报错。

## 持续运行本地后端

根目录 `.env.example` 是模板，不会自动被裸Java进程加载。复制到Git忽略的私有文件，替换占位密码，由终端环境或Compose `--env-file` 注入。不要把模板当成可用凭据。

只需本地数据库时：

```powershell
docker compose --env-file .env -f deploy/compose/compose.yml up -d mysql
```

首次新卷将应用基线与全部挂载迁移，Compose绑定本机3307，容器内部为3306。宿主Java须设置 `MYSQL_HOST=127.0.0.1`、`MYSQL_PORT=3307`、相同数据库名/账号/密码与至少32字节随机 `JWT_SECRET`。Java进程启动：

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.timezone=UTC'
mvn -f backend/pom.xml spring-boot:run
```

服务端持久化时间按UTC运行，业务月份/自然日按显式Asia/Shanghai规则计算。上面的设置只影响当前终端，不改系统时区。Maven测试已在Surefire中固定UTC，使Windows与Linux的日期边界测试一致。

检查 `GET http://127.0.0.1:8080/actuator/health`；匿名业务接口应401。此时没有测试种子或真实登录账号，不能直接操作全部业务。自动业务回归使用上文隔离测试，不要求人工SQL激活商家或在普通包自签身份。

如只需演示开发接口，可设置 `SPRING_PROFILES_ACTIVE=local`，使用[后端说明](../../backend/README.md)中的 `/api/dev/token`；该凭证仅用于对应开发示例，不能等同完整业务会话。LOCAL_TEST支付须显式启用专用profile、开关及独立密钥，生产配置仍关闭。

## 配置与依赖

- 数据库：`MYSQL_HOST`、`MYSQL_PORT`（宿主3307/容器3306）、`MYSQL_DATABASE`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_POOL_SIZE`。连接池调整依据压测，不能以加大连接数代替定位慢查询。
- 身份：`JWT_SECRET`、`WECHAT_APP_ID`、`WECHAT_APP_SECRET`；云通道另需服务端 `WECHAT_CLOUD_RUN_ENABLED`。未配置时拒绝正式认证，不生成假openid。
- 前端：`apps/miniapp/.env.example` 对应 `VITE_API_BASE_URL` 或成对的云环境/服务名。VITE值公开，绝不放服务端密钥。
- 私有附件：MinIO应用级凭据、私有桶、签名URL期限及ClamAV地址/超时；根账号不能当应用账号。真实供应商选择及维护风险见[部署说明](../../deploy/README.md)。
- OCR：内部服务token、固定模型目录/摘要、推理运行锁、受限容器；默认关闭。不得将内部token发给客户端。
- AI：`AI_PROVIDER`、`DEEPSEEK_API_KEY`、地址/模型；缺少时503降级。ASR/图文提取及向量检索使用各自独立开关、私有模型与用途许可，不能仅配置文本AI密钥就开启；领域质量及真实上游另验。
- 异步业务：归档、通知、经验卡、入驻、员工、支付/退款恢复、积分、邀请、公开内容等开关，见[模板变量索引](MINIAPP_REFERENCE.md)及对应运维手册；默认关闭不等于故障，开启前须迁移、配置和局部验收。
- Redis、RabbitMQ为Compose基础设施；不能仅因容器健康认定所有业务消费链路通过。Milvus存在部署位置不代表已实现向量检索。
- H5辅助产物使用uni-app完整API包；本轮浏览观察到框架自动加载阿里云验证SDK及设备token脚本。该加载不是微信/短信认证成功，也不是当前业务主动启用人脸验证的证明；若后续交付H5，须专项审查并裁剪未使用的第三方加载，不能以关闭树摇造成的完整包行为代替用户用途同意。小程序普通包和H5分别验收。

配置的最终依据是 `.env.example`、`apps/miniapp/.env.example`、`deploy/cloudrun/env.example` 和 `backend/src/main` 的实际属性定义；三模板索引不宣称覆盖所有运行时参数。

## 常见错误

本机 `EACCES`/`WinError 5` 先核对回环网络和临时目录权限，可将TEMP/TMP指向仓库内独立目录；不得改成跳过测试或一律返回成功。Docker不可用时数据库测试应失败而非计为通过。UTC/北京时间边界失败须记录JVM、连接和数据库时区，再定位转换，禁止直接改预期时间。

页面503时检查实际依赖、开关、会话及服务端请求ID；不要把界面“提交成功”当作数据库、短信送达或资金到账证据。

## 本地已有镜像的测试覆盖

Docker Hub暂不可达时，可显式叠加 `deploy/compose/cached-test.yml`，使用本机已有的Maven/MinIO/OCR测试镜像。先从当前源码构建JAR、记录摘要并复制到独立位置，在私有环境文件设置绝对路径 `LOCAL_TEST_BACKEND_JAR`，随后使用独立项目名和 `up -d --no-build --pull never`。该覆盖不会下载镜像，缺少本机镜像应失败；不证明生产Dockerfile构建或正式OCR模型通过。

OCR覆盖运行当前源码的默认关闭协议入口；真实模型仍按其独立手册显式配置和验收。生产Compose不要带此文件或ci.override。测试结束先核验项目标签和资源归属，再仅清理本次项目，不能删除已有业务库卷。本轮Nginx健康探测已固定IPv4回环，与默认IPv4监听一致，避免某些镜像将localhost解析至IPv6而误报不健康。

VIN可选查询：默认关闭即可完整手动录车。本地需测试真实NHTSA时按[VIN配置与接入验收](../api/VIN_OWNER_GATEWAY.md)设置后端两个变量并重启；固定HTTPS出口，明确外发用途和有限地区覆盖。合成测试使用测试类中的VinDecoder替身，不发送到真实服务。

行驶证可选识别：按[接口与配置](../api/LICENSE_RECOGNITION.md)准备私有上传/扫描和受限Paddle，再设置后端LICENSE_OCR四变量；默认关闭仍可手动录车。新车照片使用现有/api/file/upload，不要求尚未创建的车辆ID。可选OCR overlay的行驶证开关独立默认false、token复用该overlay的MILEAGE_OCR_SERVICE_TOKEN，启用前确认两端一致；不把上传或合成模型测试当真实证件验收。

## ASR任务可选配置（R3）

先按顺序执行V063和全部前置迁移，现为63项/132表。后端ASR_ENABLED默认false，不影响本机试听和手动填写；启用前需独立私有ASR服务、同用途CLEAN音频及经核验本地模型。ASR_URL/ASR_SERVICE_TOKEN仅后端配置，VITE中不可放服务凭据。[接口、参数和状态](../api/ASR_JOBS.md)及[离线模型准备](../../services/asr/README.md)区分软件回归与真实效果；requirements.in只列直接需求；R3b另交付完整Linux wheel/hash锁和本地镜像合成样本验收，未验生产overlay或真实中文效果。

`python scripts/run_local_delivery.py` 默认现为10组，新增ASR协议/进程回归；完整后端仍需显式--backend。合成Provider不等于真实转写，创建未知与取消失败按页面恢复入口核对，不删除原音频。

## 2026-10-30 中文路径与新模块

Windows中文工作目录的JaCoCo采集已改用每次独占的ASCII临时数据路径，完成后复制回backend/target。无需再将整个工程搬到ASCII目录；使用当前 `run_core_coverage.py`，保留原始run/XML/exec并检查 sources_unchanged。历史恢复报告不覆盖新执行结果。

新增车型规则和合作入口详见 [VIN配置](../operations/VIN_LOCAL_SOURCE.md) / [汽车生活配置](../operations/CAR_LIFE_SERVICES.md)。普通构建不含身份测试桥；隔离UI验收运行器必须显式 `--allow-synthetic-ui`，页面显示“合成身份联调”，不得用于正式登录。
