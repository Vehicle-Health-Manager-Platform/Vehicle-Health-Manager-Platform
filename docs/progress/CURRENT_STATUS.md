# 当前进度

> 更新日期：2026-09-30

| 项目 | 状态 | 证据或说明 |
| --- | --- | --- |
| 交付文档与分阶段开发计划 | 已入库 | 仓库根目录的两份 Markdown 文档 |
| MVP Spec | 已入库 | `docs/SPEC.md`，覆盖 F01–F20、C01–C25、43 个核心 API |
| 工程目录骨架 | 已建立 | 四端 63 个页面占位文件、14 个后端包目录及 OCR/部署/测试目录 |
| S0 契约与可运行工程 | 进行中 | 业务契约、39 表 SQL、四端网页、Java 后端样例、OCR 服务骨架、OpenAPI 草案和 Compose 已入库；运行验证仍待完成 |
| M0 骨架就绪里程碑 | 未通过 | CI 五项作业已全绿，四端网页与后端经 Compose 联合启动；上传、幂等、限流、审计及完整基础设施验收仍待实现 |
| S0-1 追踪基线 | 进行中 | 已建立 20 项功能、63 个具名页面、43 个 API、15 项验收的逐项清单；计划用例待实现。用户确认外部资源均待办，负责人和到位日未定 |
| S0-2 来源差异 | 决策已记录 | `docs/DECISIONS.md` 已逐项记录 G01–G07；G01–G03 的 SQL/OpenAPI 实施仍属后续 S0 步骤 |
| S0-3 业务契约 | 草案已记录 | `docs/api/S0_BUSINESS_CONTRACT.md` 包含订单/支付/退款状态图、预约、券、佣金、入驻、派工与通知；正式 OpenAPI 和第三方沙箱验证仍未完成 |
| S0-4 数据模型 | MySQL 8 CI 验证通过 | 39 张表的初始化 SQL、V001 迁移和合成测试种子已生成；CI 在空库执行初始化、重复迁移和两次种子导入并核对表数及种子行，见 `scripts/verify_mysql_schema.sh` |
| S0-5 工程骨架 | 构建与基础测试通过 | Vue3/Vite 四端及 63 个路由占位已就绪；`npm run build` 四端通过，gstack browse 打开四端均为 HTTP 200；Java 17 后端的 Maven 测试和 OCR 语法检查在 CI 通过 |
| S0-6 基础设施 | CI 冒烟通过 | Compose 已配置 MySQL、Redis、RabbitMQ、MinIO、Milvus、后端、OCR、Nginx 与 Prometheus；CI 启动前七项并验证四端网页入口返回 HTML、受保护 API 返回 401。Milvus、Prometheus 与本机 Docker 尚未运行验收 |
| S0-7 横向能力 | 权限样例测试通过 | JWT 保护的本地车辆资源样例、40300 越权校验和 61 操作 OpenAPI 草案已编写；`VehicleAccessTest` 在 CI 通过，上传/幂等/限流/审计仍待实现 |
| S0-8 UI 基础 | 浏览器检查通过 | 共享色值、响应式导航、五类列表/详情状态和四态表单已实现；四端构建通过，gstack browse 已查看车主端 390px 预览与控制台 |

当前没有已交付的业务功能。`docs/sql/init.sql` 已在 MySQL 8 CI 空库执行并验证重复迁移、重复种子导入；仍需在部署环境验证备份、恢复和版本化升级。GitHub CI 五项作业在提交 `cf33314` 的[运行记录](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/36660024333)均为成功。
