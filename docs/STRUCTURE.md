# 工程目录说明

本目录骨架依据[全栈开发交付文档](../汽车健康管家平台全栈开发交付文档.md)第 3、6、10、12 章和[MVP Spec](SPEC.md)建立。当前是**文件与页面占位阶段**：尚未安装依赖、生成可运行工程、实现功能或编写完整 SQL。不要将占位页面或 `docs/sql/init.sql` 视作已交付功能。

| 路径 | 用途 | 来源 |
| --- | --- | --- |
| `apps/owner/` | 车主 uni-app 3 / Vue3 小程序，另编译 H5；`src/pages/` 按 C01–C25 建立精确路由位置 | §3.1、§6.1–6.2 |
| `apps/merchant/` | 商家 uni-app 小程序；页面文件按 §3.1 的页面清单创建，具体路由待 S0 契约固定 | §1.4、§3.1 |
| `apps/technician/` | 技师 uni-app 小程序；页面文件按 §3.1 清单创建，具体路由待 S0 契约固定 | §1.4、§3.1 |
| `apps/admin/` | Vue3 / Vite / Element Plus 运营 PC 后台；页面文件按 §3.1 清单创建 | §1.4、§3.1、§6.1 |
| `backend/src/main/java/com/autocare/platform/` | Java 17 / Spring Boot 3.x 模块化单体，子包与 §6.5 一致 | §6.1、§6.5 |
| `backend/src/main/resources/`、`backend/src/test/` | 后端配置与测试位置 | §6、§9 |
| `services/ocr/` | 自建 PaddleOCR 服务的位置；实现行驶证和仪表盘里程识别 | §6.1 |
| `docs/sql/` | 初始化 SQL 与后续数据库迁移；当前 `init.sql` 只是不可执行占位 | §7、§12.2 |
| `docs/api/`、`docs/ui/` | OpenAPI/Swagger 与 UI 原型、设计规范 | §4–5、§8、§12.2 |
| `docs/operations/`、`docs/testing/`、`docs/user-guides/` | 部署回滚、测试报告和三端使用手册 | §9–10、§12.2 |
| `deploy/` | Compose、Nginx、监控配置的预留位置 | §10 |
| `tests/e2e/`、`tests/load/`、`tests/security/` | 跨端、性能与安全测试的预留位置 | §9 |
| `scripts/scaffold.ps1` | 以现有结构清单补齐缺失的空目录和页面占位文件；不会覆盖已实现文件 | 本项目工程约定 |

后端的 `gateway/common/user/vehicle/merchant/order/coupon/point/service/ai/check/community/admin/job` 是 Java 包目录，**不是 14 个独立可部署服务**。商家、技师和运营端路由路径在交付文档中未明确，当前文件名仅供工程组织，S0 需写入各端路由契约。车主端路由以交付文档 C01–C25 为准。

交付文档 §3.1 的商家 M30–M45、技师 T50–T58、运营 O60–O78 是范围标签，但分别只列出 15、10、13 个具体页面名称，与编号跨度不一致。本骨架只为**明确命名的页面**创建文件，不推测缺失页面。

数据库章节仅给出部分建表 SQL，且完整表数量与列举实体不一致，见 Spec G01–G02；因此 `docs/sql/init.sql` 不含虚构建表语句，须在 S0 完成后才能用于部署。
