# 汽车健康管家平台

本仓库处于 S0 基础搭建阶段。2026-10-02 起按用户提供的原始交付文档 v1.0 开发：车主微信小程序为主、H5 兜底，商家和技师使用微信小程序，运营使用 PC 网页。业务功能尚未实现。

## 当前过渡原型

现有 `apps/owner`、`apps/merchant`、`apps/technician` 是此前网页方案留下的 Vue3/Vite 原型，**不是微信小程序工程**。`apps/admin` 是交给其他团队的运营 PC 网页骨架。需要 Node.js 20.19+ 和 npm；执行 `npm ci` 后运行 `npm run dev:miniapp` 或 `npm run build:miniapp` 开发/编译目标小程序。`npm run build:legacy-web` 验证旧网页原型；`npm run build` 同时验证旧网页与新小程序。小程序工程详情见 [apps/miniapp/README.md](apps/miniapp/README.md)，后端步骤见 [backend/README.md](backend/README.md)。

`apps/miniapp` 是正在搭建的单测试号三角色 uni-app 工程；其目标是先完成开发者工具编译与角色入口预览，真实微信授权登录及业务接口仍未实现。车主 H5 兜底、正式小程序主体/AppID 与支付资质仍待处理。此前网页构建、截图和 Nginx 冒烟结果不能算作小程序验收。PC 运营后台交由其他团队负责，本团队聚焦小程序与共用接口契约。

## 项目文档

- [全栈开发交付文档](汽车健康管家平台全栈开发交付文档.md)：产品范围、页面、技术架构、数据库示例、接口、测试及验收要求。
- [MVP 规格说明](docs/SPEC.md)：按交付文档逐项整理功能、流程、权限、技术约束和验收标准，并标出原文缺口。
- [分阶段开发计划](汽车健康管家平台分阶段开发计划.md)：S0–S6 的开发步骤、阶段成果、验收条件与依赖事项。
- [工程目录说明](docs/STRUCTURE.md)：目录与交付文档模块、页面的对应关系及当前状态。
- [项目进度与下一步](docs/progress/README.md)：当前状态、近期任务和阶段推进记录。

后续开发以恢复的原始交付文档、[2026-10-02 决策](docs/DECISIONS.md)和[单测试号设计](docs/superpowers/specs/2026-10-02-single-test-miniapp-design.md)为准，逐步补齐小程序页面、微信身份映射、真实接口、测试与提审流程。
