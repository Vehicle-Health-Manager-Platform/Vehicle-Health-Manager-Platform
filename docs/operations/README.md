# 部署与运维文档

按交付文档 §10，记录环境变量、Compose/Nginx、监控告警、发布与回滚步骤。仓库已有 Compose 与 Nginx 配置并通过 CI 冒烟；这只验证临时容器能够启动，真实 HTTPS 环境、已有数据库 V002/V003 迁移、对象存储、告警以及发布/回滚演练尚未验收。当前外部条件见[依赖清单](../progress/S0_DEPENDENCIES.md)，M0 验收顺序见[下一步规划](../progress/NEXT_STEPS.md)。

真实登录环境可按[联调预检与验收清单](AUTH_INTEGRATION_RUNBOOK.md)检查入口和逐角色流程；清单中的未执行项仍须在测试部署中完成。
