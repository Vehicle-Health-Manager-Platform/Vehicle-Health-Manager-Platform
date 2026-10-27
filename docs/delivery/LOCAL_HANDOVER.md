# 本地与测试环境交接入口

本阶段范围（2026-10-26）：正式微信认证、短信开通、生产环境部署由后续负责人办理。本地允许合成身份、LOCAL_TEST资金与明确标识的模拟服务。它们均不代表正式渠道验收。

交付对象是微信小程序（车主、商家、技师、受限运营入口）和共享Java后端。H5用于辅助验证，旧版四个Web应用只保留构建回归，不作为新PC产品承诺。

## 从哪里开始

- [本地启动与测试配置](LOCAL_SETUP.md)：先跑不依赖外部账号的测试，区分只读演示与真实数据库业务测试。
- [本轮测试报告](../testing/LOCAL_DELIVERY_2026_10_26.md)：本轮执行、历史依据、修复与未验证项。
- [外部接入交接清单](EXTERNAL_INTEGRATIONS.md)：资料、操作步骤、验收标准及责任角色。
- [已知问题与后续任务](LOCAL_BACKLOG.md)：外部等待、独立开发缺口和证据缺口分别记录。
- [环境模板及全部页面](MINIAPP_REFERENCE.md)：三份受控模板的变量清单；实际服务端配置还须核对源码中的环境变量。
- [网络配置](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)、[云托管准备](../../deploy/cloudrun/README.md)、[备份恢复](../operations/DATABASE_RECOVERY_RUNBOOK.md)、[性能容量](../operations/PERFORMANCE_CAPACITY_RUNBOOK.md)。

## 操作手册

- [车主](../user-guides/OWNER.md)：录车、档案、候选人工核对、预约、接车确认、取车核销、退款、评价、券与积分。
- [商家](../user-guides/MERCHANT.md)：目录报价、时段、订单、接车、防护、派工、核销、退款、员工与本店资料。
- [技师](../user-guides/TECHNICIAN.md)：绑定、本人任务、接单、施工资料与质检签字。
- [受限运营/管理员](../user-guides/OPERATOR.md)：按独立权限处理入驻、目录、内容、券池、政策、考核及资金记录。
- [运营账号配置](../operations/OPERATOR_ACCOUNTS.md)：管理员不能通过编辑客户端角色获得权限。

所有写操作遇到不确定结果，应保留原正文与原幂等键重试，先读实际状态再决定新操作。关闭页面、切换账号或订单后，旧响应不得覆盖新上下文；界面文案与本地缓存不能代替后端授权。

本轮新增[商家接车里程候选](../api/PICKUP_MILEAGE_RECOGNITION.md)：独立门店授权、仪表盘照片、明确同意、人工核对后带入原表单。接车单仍由原按钮提交，识别不写数据库。

## 接口交接

[接口索引](../api/README.md)按业务域解释字段及约束；机器契约为[OpenAPI](../api/openapi.json)，请求与写入协议见[写入完整性](../api/WRITE_INTEGRITY.md)。使用现有Postman集合和隔离运行器，不向正式环境导入测试身份或资金事实。

调用需检查HTTP状态及JSON业务码。访问凭证使用Bearer；写接口按契约携带UUID `Idempotency-Key`，需版本的接口同时提交当前revision/version。不要凭旧回执恢复已失效授权或已撤回公开内容。401要求重新登录；403表示当前角色无权；404也可能用于隐藏他人资源；409要求重读状态；422要求修正内容；503表示依赖不可用，不能显示为业务成功。

接口覆盖统计见[现有覆盖清单](../testing/API_COVERAGE.md)及[契约结论](../api/CONTRACT_RECONCILIATION.md)。历史证据覆盖不等于本轮复跑，也不等于真实微信、资金、设备通过。

## 部署交接顺序

1. 固定提交、依赖锁、镜像摘要和环境；先在隔离库跑测试，再备份目标库并演练恢复。不要在已有库重复执行初始化。
2. 核对V001基线及V002–V063的有序迁移记录；执行 `python scripts/verify_compose_migrations.py` 校验新库Compose挂载。已有卷不会自动执行新增迁移。迁移失败先核查部分DDL，不盲目重试全部SQL。
3. 通过私有环境注入配置，选择直连HTTPS或可信云托管。云通道开启前须关闭服务公网身份入口并校验可信身份头。小程序所有VITE变量均会进入客户端。
4. 配置维护中的私有存储、扫描、OCR内部网络；逐个启用业务开关并执行关联业务测试。不能用上传成功证明扫描或识别成功。
5. 外部服务负责人按[外部清单](EXTERNAL_INTEGRATIONS.md)完成接入与真实验收，再执行真机、完整接口、性能、安全和恢复门禁。
6. 记录发布版本、监控、负责人和回滚步骤后再提审发布。回滚应用前核对数据库向后兼容；不可直接反向删除业务数据。

[Compose说明](../../deploy/README.md)仅用于本地基础设施/历史网页回归，[云托管部署说明](../../deploy/cloudrun/README.md)用于所选云方向准备。两者存在不表示已经上线。

## 阶段验收的判断

以本轮报告中实际成功的项目为准。未通过项必须保留原因、复现方式和后续任务；不得将“资料齐全”“单测成功”写成“其余全部功能完成”。外部服务、未实现的独立功能和未执行的整体验收不应互相混淆。

R2a最新增量：[VIN车主网关/候选与手动录车说明](../api/VIN_OWNER_GATEWAY.md)，默认关闭；[测试和已知边界](../progress/RECOGNITION_R2A_VIN_GATEWAY.md)、[脱敏证据](../testing/VIN_OWNER_GATEWAY_2026_10_26.json)。后续行驶证、ASR、图文和向量按路线推进。

R2b最新增量：[行驶证车牌/VIN候选与手动录车](../api/LICENSE_RECOGNITION.md)，默认关闭；复用本人私有上传和受限Paddle，明确新车用途。真实证件效果、保留/本人删除政策、真机、国内车型及ASR/图文/向量另验。最终测试范围以本轮报告中实际记录为准。

R2b最终记录：[行驶证软件回归与真实模型限制](../progress/RECOGNITION_R2B_LICENSE.md)、[脱敏证据](../testing/LICENSE_RECOGNITION_2026_10_27.json)。既有私有图片保留策略不包含本功能自动到期/本人删除承诺，正式证件前须明确政策。

R3增量：[ASR持久任务/权限/页面与接入](../api/ASR_JOBS.md)，V063增加recognition_job（当前132表），默认关闭。统一运行器新增ASR第10组并纳入源码/requirements.in摘要。R3软件阶段的模型/资源待验已由R3b补充固定资源及Linux合成样本验证；授权语音/中文效果仍未验，见[离线准备](../../services/asr/README.md)。

R3软件闭环最终记录：[ASR异步任务回归/已知限制](../progress/RECOGNITION_R3_ASR.md)、[脱敏证据](../testing/ASR_JOBS_2026_10_27.json)。该记录为R3历史范围；R3b另有固定权重/完整Linux Python依赖锁及真实模型合成样本证据，真实用户效果仍未验。

R3b最新证据：[固定离线资源、Linux真实模型合成样本及静音修复](../progress/RECOGNITION_R3B_ASR_LINUX.md)。已取得并核验24个wheel与固定模型5文件，实际离线容器推理/超时恢复/网络禁止通过；原R3文档中的“尚无资源/未运行”是历史范围。中文/方言/噪声/真实用户准确率、Java真实ASR同版联调和手机仍待验，默认关闭，不代表生产接入完成。

R3c最新记录：[Java真实模型联调与进程/OOM故障](../progress/RECOGNITION_R3C_ASR_REAL_JOURNEY.md)。选定50项Java（含4项实际模型任务/3项继承核心旅程）、13项进程检查及实际OOM通过；身份/CLEAN/存储仍为夹具。原R3b的Java/进程待验已由本轮补充，中文/用户效果和真机另验。前置Compose CI旧表数断言已修正并通过独立真实MySQL门禁；完整本地Compose因Docker Hub连接超时未完成，不冒称通过。
