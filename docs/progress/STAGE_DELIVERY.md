# 阶段文档与 GitHub 交付规则

更新日期：2026-10-08。用户要求：每完成一个阶段，整理对应文档并上传 GitHub。适用于 A4 起的后续阶段。

## 一个阶段的交付顺序

1. 明确范围、前置条件、权限、状态变化和验收条件，记录已确认规则与尚未确定的业务选择。
2. 实现本阶段代码、数据库迁移和界面，更新中文接口契约及生成的 OpenAPI。
3. 完成相关测试、构建与本机联调；发现问题就在当前阶段修复。真机、正式支付等外部验收单独记录结果与依赖。
4. 写阶段执行记录，列出变更、迁移方法、验证命令与结果、未验收项、下一阶段。
5. 更新 `CURRENT_STATUS.md`、`NEXT_STEPS.md` 和缺口清点；检查生成文档一致性与提交差异。
6. 将代码、规格/计划、迁移、接口说明、测试与执行记录一起提交到 `codex/` 阶段分支，推送到本项目 GitHub 仓库，并创建指向 `main` 的 PR。
7. 检查 PR 的全部 CI。修复失败后再次推送；记录最终提交和 CI 结果。尚未通过验证的阶段使用草稿 PR，标明阻塞项。

仓库：[Vehicle-Health-Manager-Platform](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform)。上传阶段分支和建立 PR 已由用户授权；合并、部署沿用当次会话的授权，不把“上传仓库”推断为自动部署。

## 文档职责

| 文档 | 内容 |
| --- | --- |
| `docs/progress/CURRENT_STATUS.md` | 当前阶段、完成证据、GitHub PR 与待验收项 |
| `docs/progress/NEXT_STEPS.md` | 当前待办、下一阶段顺序、前置与完成条件 |
| `docs/progress/ROLE_GAP_ANALYSIS.md` | 三端及运营端缺口与阶段映射 |
| `docs/superpowers/specs/`、`plans/` | 业务规格和实施计划；草案必须标明 |
| `docs/api/`、`openapi.json` | 中文请求/响应、权限、错误码、幂等与事务契约 |
| `docs/sql/migrations/` | 可重复执行的迁移及已有数据处理方式 |
| `docs/progress/*_EXECUTION.md`、`docs/testing/` | 实际执行证据、验收步骤与未验收项 |
| `docs/progress/history/` | 已被新规划替代的历史记录 |

## 完成口径

“已编码”表示工作树中已有实现；“已上传”表示提交在 GitHub 分支且已有 PR；“阶段验证通过”表示约定测试与 CI 通过；“已合并”以 GitHub 实际状态为准。真实相机、真机、正式收款及公网环境分别验收，不能用代码或 CI 状态代替。

每次阶段收口向用户给出 PR 链接、最终提交、验证结果、尚未验收的事实以及下一步。密钥、会话令牌、本机覆盖配置、依赖目录、构建产物与私有图片不得进入提交。
