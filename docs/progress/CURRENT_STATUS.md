# 当前进度

> 更新日期：2026-09-30

| 项目 | 状态 | 证据或说明 |
| --- | --- | --- |
| 交付文档与分阶段开发计划 | 已入库 | 仓库根目录的两份 Markdown 文档 |
| MVP Spec | 已入库 | `docs/SPEC.md`，覆盖 F01–F20、C01–C25、43 个核心 API |
| 工程目录骨架 | 已建立 | 四端 63 个页面占位文件、14 个后端包目录及 OCR/部署/测试目录 |
| S0 契约与可运行工程 | 进行中 | 业务契约、39 表 SQL、四端网页、Java 后端样例、OCR 服务骨架、OpenAPI 草案和 Compose 已入库；运行验证仍待完成 |
| M0 骨架就绪里程碑 | 未通过 | CI 尚未全绿，后端与基础设施尚未完成运行验证；上传、幂等、限流和审计仍待实现 |
| S0-1 追踪基线 | 进行中 | 已建立 20 项功能、63 个具名页面、43 个 API、15 项验收的逐项清单；计划用例待实现，外部资源多数待核实 |
| S0-2 来源差异 | 决策已记录 | `docs/DECISIONS.md` 已逐项记录 G01–G07；G01–G03 的 SQL/OpenAPI 实施仍属后续 S0 步骤 |
| S0-3 业务契约 | 草案已记录 | `docs/api/S0_BUSINESS_CONTRACT.md` 包含订单/支付/退款状态图、预约、券、佣金、入驻、派工与通知；正式 OpenAPI 和第三方沙箱验证仍未完成 |
| S0-4 数据模型 | 静态校验通过 | 39 张表的初始化 SQL、V001 迁移和合成测试种子已生成；`sqlglot` 可解析，空库执行及重复迁移尚待可访问 MySQL 测试库 |
| S0-5 工程骨架 | 前端运行验证通过 | Vue3/Vite 四端及 63 个路由占位已就绪；`npm run build` 四端通过，gstack browse 打开四端均为 HTTP 200；Java 17 后端与 OCR 服务已建工程，后端构建待 CI 验证 |
| S0-6 基础设施 | Compose 配置通过 | MySQL、Redis、RabbitMQ、MinIO、Milvus、后端、OCR、Nginx 与 Prometheus 已写入 Compose；`docker compose config --quiet` 通过；本机 Docker Engine 未运行，容器健康和空库执行未验证 |
| S0-7 横向能力 | 样例待构建验证 | JWT 保护的本地车辆资源样例、40300 越权校验和 61 操作 OpenAPI 草案已编写；本机无 Maven/JDK 17，需 CI 验证，上传/幂等/限流/审计仍待实现 |
| S0-8 UI 基础 | 浏览器检查通过 | 共享色值、响应式导航、五类列表/详情状态和四态表单已实现；四端构建通过，gstack browse 已查看车主端 390px 预览与控制台 |

当前没有已交付的业务功能。`docs/sql/init.sql` 已生成 39 张表，但尚未在空库上执行及验证重复运行，不宜用于正式部署。GitHub CI 的 `schema-and-ocr` 作业曾失败；已发现 CSV 换行符跨平台不一致，修复后仍需以 CI 结果确认。
