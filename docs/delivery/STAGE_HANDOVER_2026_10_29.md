# 本地阶段交接（2026-10-29）

本阶段交付对象为微信小程序的车主、商家、技师及受限运营入口，以及共享Java后端。H5为辅助验证，旧四端Web只保留回归。本阶段不办理正式微信身份、短信、生产发布和真实支付退款渠道；合成身份、LOCAL_TEST资金、模拟AI或识别上游必须分别注明。

本地主要流程和交接资料已齐备，完整后端2627项及默认回归通过；500并发P95仍未达标，作为明确的容量开放事项，不能将本阶段交接写成所有性能验收或生产就绪。Windows完整覆盖率复跑使用纯ASCII工作路径；本次覆盖率是从同次全量原始数据严格选取当前会话恢复，详见测试报告。

## 交接资料

- [项目说明](../../README.md)、[本地启动和故障处理](LOCAL_SETUP.md)：环境、依赖安装、普通构建、只读演示、真实数据库自动业务测试。
- [实际环境变量读取点及第三方依赖](CONFIGURATION_REFERENCE.md)、[三份模板与全部页面](MINIAPP_REFERENCE.md)、[网络环境](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)。服务端凭据不能进入VITE变量。
- [车主手册](../user-guides/OWNER.md)、[商家手册](../user-guides/MERCHANT.md)、[技师手册](../user-guides/TECHNICIAN.md)、[管理员手册](../user-guides/OPERATOR.md)、[管理员账号配置](../operations/OPERATOR_ACCOUNTS.md)。
- [接口索引](../api/README.md)、[OpenAPI](../api/openapi.json)、[写入完整性](../api/WRITE_INTEGRITY.md)、[当前版HTTP测试入口](../testing/CURRENT_API_ACCEPTANCE.md)。
- [当前部署/升级/观察与回退指南](DEPLOYMENT_RUNBOOK.md)、[本地Compose部署](../../deploy/README.md)、[云托管步骤](../../deploy/cloudrun/README.md)、[备份恢复](../operations/DATABASE_RECOVERY_RUNBOOK.md)、[性能容量](../operations/PERFORMANCE_CAPACITY_RUNBOOK.md)。部署资料存在不代表生产已上线。
- [微信认证、短信、支付等待办：所需资料、接入步骤和验收标准](EXTERNAL_INTEGRATIONS.md)。
- [本轮测试报告](../testing/STAGE_ACCEPTANCE_2026_10_29.md)、[已知问题与后续清单](LOCAL_BACKLOG.md)。本轮结果优先于早期阶段描述；历史证据不改写为本轮新执行。

## 可独立运行的业务范围

已有软件覆盖手动录车、车辆档案和健康来源、服务目录和商家报价、预约及LOCAL_TEST支付、接车/异议、派工/施工/签字/核销、归档/评价、取消退款/佣金、券/积分/邀请、站内通知、私有问答/诊断、经营/政策/目录/内容审核。识别候选、ASR、图片/PDF、许可知识与向量查询有独立测试配置；用户核对后才走原保存或发送流程。

功能代码存在、默认开关关闭、软件测试通过和真实供应商通过是四种不同状态。具体通过范围和未验项以本轮报告为准；不以历史API覆盖数字推算项目完成百分比。

## 复现顺序

1. 按本地指南使用同一个虚拟环境安装OCR协议及PDF导出测试依赖，执行 `scripts/run_local_delivery.py`。默认12组覆盖小程序单测、Python/识别协议、微信/H5构建、契约/迁移/交接一致性。
2. 需要完整后端时执行 `scripts/run_local_delivery.py --backend`（宿主Java17/Maven3.9，Windows须使用纯ASCII绝对项目路径）。完整core profile拒绝失败、错误、跳过或缺失；独立真实模型opt-in套件不因此自动通过。完整上传适配器要求宿主回环地址，嵌套Docker完整执行不受支持；离线缓存与中文路径采集问题见本地指南。
3. 执行 `scripts/run_business_api_acceptance.py --recognition run` 新建隔离库验证HTTP。默认29业务及七文件/奖励旅程；一次可能持续数小时。不要与任何共享 `backend/target` 的Maven任务并行。独立副本并行须先核验源码一致，并使用各自target和输出目录。
4. 执行 `scripts/run_database_recovery.py` 验证当前全部迁移与跨实例恢复。仅在其他负载结束后运行 `scripts/run_performance_baseline.py --gates all`；不得以仅错误预算通过代替延迟通过。
5. 正常小程序构建不含测试身份桥。只读演示可熟悉页面，但不证明真实提交；真实HTTP测试使用明确合成身份和独立库，不能向已有业务库导入这些夹具。

## 故障与验收记录

所有写入需保存原请求正文和幂等键；网络未知先读取真实状态，不另造请求。401重新登录，403检查权限，404可能隐藏他人资源，409重新读取版本/状态，422修正内容，503记录依赖不可用；页面缓存或成功提示不能代替数据库事实。

测试保留原始失败和复跑记录，汇总仅引用脱敏证据。`.cache`中可能包含合成token、私有请求或SQL转储，不能整目录上传。清理只针对本次拥有且标签/ID匹配的资源，不清理其他项目容器或已有卷。

正式证件/声音领域效果、iOS/Android硬件、保留/删除政策审批、生产账号和外部服务由相应负责人补齐；软件缺口与证据缺口继续在待办中独立登记，不能全部归为外部原因。
