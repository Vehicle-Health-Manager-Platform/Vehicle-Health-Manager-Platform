# 项目进度与下一步规划

本目录集中记录项目的当前状态、下一步工作和阶段完成情况。

- [当前进度](CURRENT_STATUS.md)：已完成事项、正在进行的阶段与尚未满足的里程碑条件。
- [下一步规划](NEXT_STEPS.md)：按依赖顺序排列的近期任务及完成判定。
- [阶段文档与 GitHub 交付规则](STAGE_DELIVERY.md)：每阶段同步文档、提交、推送、建立 PR 与验证 CI 的固定流程。
- [A4 执行记录](A4_OWNER_DECISION_EXECUTION.md)：车主接车单决定的实现、验证与交付状态。
- [A5.2 执行记录](A5_2_DISPATCH_BACKEND_EXECUTION.md)：派工与本人接单后端、V012 迁移的验证与交付状态。
- [A5.3 执行记录](A5_3_DISPATCH_UI_EXECUTION.md)：商家派工页、技师独立会话与「我的工单」/接单界面的验证与交付状态。
- [A5.4 执行记录](A5_4_DISPATCH_VERIFICATION_EXECUTION.md)：身份变更、竞争、持久化载荷与历史异常的安全验证计划与结果。
- [A5.5 执行记录](A5_5_DISPATCH_E2E_EXECUTION.md)：合成身份接真实后端容器与隔离 MySQL 的端到端联调、发现与未验收项。
- [A5.6 执行记录](A5_6_DISPUTE_RESOLUTION_EXECUTION.md)：争议处理记录、车主复核恢复、V013、投影无隐私与阶段 GitHub 验证结果。
- [A5 阶段总记录](A5_DISPATCH_EXECUTION.md)：派工与本人接单已实现能力、全阶段验证口径与后续。
- [本机派工与本人接单验收](../testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md)：真实后端/MySQL 的 37 项端到端检查与复现步骤。
- [本机争议处理与恢复验收](../testing/LOCAL_DISPUTE_ACCEPTANCE.md)：在派工闭环之上新增 32 项争议检查，端到端 69/69。
- [A5 下一阶段计划](../superpowers/plans/2026-10-08-a5-dispatch.md)：派工、本人工作台与技师接单的顺序及验收条件。
- [三端功能缺口清点](ROLE_GAP_ANALYSIS.md)：车主端、商家端、技师端与运营端还差什么，以及阶段 A–E 的实施顺序。
- [S0 逐步执行记录](S0_EXECUTION_LOG.md)：每步的完成证据、未完成范围与紧接着的工作。
- [开发与上线网络](../operations/MINIAPP_NETWORK_ENVIRONMENTS.md)：本机模拟器、手机调试、正式 HTTPS 与云托管方案的边界。
- [登录联调验收](../operations/AUTH_INTEGRATION_RUNBOOK.md)：真实微信、本机数据库、手机号、短信和真机的逐项核验。

每次完成一项任务或改变阶段时，更新上述文件的日期、状态和依据；阶段任务仍以[分阶段开发计划](../../汽车健康管家平台分阶段开发计划.md)为准，功能与验收要求以[Spec](../SPEC.md)和[交付文档](../reference/DELIVERY_V1.md)为准。
