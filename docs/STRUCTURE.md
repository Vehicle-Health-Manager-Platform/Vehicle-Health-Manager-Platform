# 工程目录说明

当前目录最初按四端网页方案建立；2026-10-02 起以恢复的[原始交付文档](reference/DELIVERY_V1.md)和[MVP Spec](SPEC.md)为目标基线。`apps/miniapp` 是单测试号三角色 uni-app 工程；现有车主、商家、技师 Vue3/Vite 工程仅为过渡网页原型。运营 PC 后台交由其他团队负责，本团队只维护其共用接口契约。`docs/sql/init.sql` 已按原始文档重生并通过 CI 空库验证。

| 路径 | 用途 | 来源 |
| --- | --- | --- |
| `apps/miniapp/` | 单测试号三角色小程序工程；车主、商家、技师页面模块分开，尚非完整业务交付 | 用户确认的测试方案、§1.4、§6.2 |
| `apps/owner/` | 过渡期 Vue3/Vite 网页原型，尚不是目标车主微信小程序；C01–C25 路由可作为功能清单参考 | §3.1、§6.1–6.2 |
| `apps/merchant/` | 过渡期网页原型，尚不是目标商家微信小程序 | §1.4、§3.1 |
| `apps/technician/` | 过渡期网页原型，尚不是目标技师微信小程序 | §1.4、§3.1 |
| `apps/admin/` | 既有运营 PC 后台网页骨架；后续业务页面由其他团队负责 | §1.4、§3.1、§6.1 |
| `backend/src/main/java/com/autocare/platform/` | Java 17 / Spring Boot 3.x 模块化单体，子包与 §6.5 一致 | §6.1、§6.5 |
| `backend/src/main/resources/`、`backend/src/test/` | 后端配置与测试位置 | §6、§9 |
| `services/ocr/` | 自建 PaddleOCR 服务的位置；实现行驶证和仪表盘里程识别 | §6.1 |
| `docs/sql/` | 39 张表的初始化 SQL、V001 迁移、字段字典及合成测试种子；恢复微信身份字段后的版本已通过 CI 空库验证 | §7、§12.2 |
| `docs/api/`、`docs/ui/` | OpenAPI/Swagger 与 UI 原型、设计规范 | §4–5、§8、§12.2 |
| `docs/operations/`、`docs/testing/`、`docs/user-guides/` | 部署回滚、测试报告和三端使用手册 | §9–10、§12.2 |
| `docs/progress/` | 当前项目进度、下一步规划和阶段完成记录 | 本项目工程约定 |
| `deploy/` | Compose、Nginx、监控配置的预留位置 | §10 |
| `tests/e2e/`、`tests/load/`、`tests/security/` | 跨端、性能与安全测试的预留位置 | §9 |
| `scripts/scaffold.ps1` | 以现有结构清单补齐缺失的空目录和页面占位文件；不会覆盖已实现文件 | 本项目工程约定 |

后端的 `gateway/common/user/vehicle/merchant/order/coupon/point/service/ai/check/community/admin/job` 是 Java 包目录，**不是 14 个独立可部署服务**。商家、技师和运营端路由路径在交付文档中未明确，当前文件名仅供工程组织，S0 需写入各端路由契约。车主端路由以交付文档 C01–C25 为准。

交付文档 §3.1 的商家 M30–M45、技师 T50–T58、运营 O60–O78 是范围标签，但分别只列出 15、10、13 个具体页面名称，与编号跨度不一致。本骨架只为**明确命名的页面**创建文件，不推测缺失页面。

数据库章节仅给出部分建表 SQL，且完整表数量与列举实体不一致，见 Spec G01–G02；`docs/sql/init.sql` 的新增实体字段属于 S0 工程契约，正式部署前仍须完成版本化迁移和环境验证。
