# A5.1 派工与接单规格交付记录

日期：2026-10-08。范围：A5.1 文档阶段，A5.2–A5.5 尚未实现或验收。用户授权按阶段整理文档并上传 GitHub。

## 基线与实际交付

- A4 [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35) 当前待合并；以其最终提交 `7778006` 为基线建立 `codex/a5-dispatch-spec`。
- [业务规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)：一单一技师、派工资格、本人接单、状态、权限、重复请求、锁顺序、历史处置、页面与 T01–T12 验收矩阵。
- [接口契约](../api/TECHNICIAN_DISPATCH.md)：候选列表、商家派工详情/首次派工、技师本人列表/详情/接单，共六个待实现操作。
- [中文计划](../superpowers/plans/2026-10-08-a5-dispatch.md)：A5.2 数据/后端、A5.3 界面、A5.4 安全竞争验证、A5.5 本机联调与阶段收口，逐项列出代码位置、迁移与验证。
- 更新决策记录、S0 草案、API 入口、当前进度、下一步和交付规则。防护阻断统一在 A6 报工；技师归属固定为员工 ID；A5.2 将同时停止商家通用 START_SERVICE。

## 代码核对发现

1. 派工表已存在且订单唯一，无需新建同义表；V012 仅计划补派工人/接单时间，A5.1 不执行迁移。
2. 技师 JWT subject 为员工 ID，绑定记录 ID 另列；当前组件局部 token 不能直接支撑跨页面工作台，A5.3 改为独立共享会话。
3. 现有员工码发放/回收与首次绑定的员工/JOIN 锁，需要在 A5.2 与派工统一商家→技师员工→绑定顺序，并做真实并发验证。
4. S0 草案与 Spec F15 防护前置不一致，按正式 Spec 修正文档；不将缺防护错误误用于 A5 派工。

## 验证与 GitHub

本机检查已完成：

- 文档自审：核对六个操作与状态/重复语义，T01–T12 覆盖业务、权限、竞争、回滚、页面与迁移，确认假设/待实现标注。
- 新增/修改的 26 个本地 Markdown 链接全部存在。
- 顺序执行 `generate_traceability.py`、`build_init_sql.py`、`generate_openapi.py`，保持 141 追踪项、39 表基线、85 操作，生成文件无内容差异。
- `git diff --check` 通过，本次仅变更文档；未新增代码、执行迁移或进行派工业务测试。

已上传 [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36)，规格提交 `c9187b9e85eae36139920836bb0b5e397fe94491` 的 [CI 37713027623](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37713027623) 六项通过：web、miniapp、backend、schema-and-ocr、schema-mysql、compose-smoke。

后端 **271 项**，失败/错误/跳过均 0；小程序 **120 项**，失败/跳过均 0，微信与 H5 构建成功。此为已有实现回归证据，不代表派工功能已交付。最终文档收口提交的检查结果以 PR 最新检查为准。

PR 以 `codex/s4-owner-pickup-confirm` 为基线，仅展示 A5.1 文档差异；A4 合并后调整到 `main` 并复核 CI。A5.1 为文档交付，A4 与本 PR 尚未合并。

## 下一步及未验收

执行 A5.2：V012、身份锁顺序、严格技师鉴权、六个接口、START_SERVICE 入口切换、HTTP/真实 MySQL 测试与 OpenAPI。每个完成子阶段继续整理文档并上传 GitHub。

真实相机、真机、本机测试证书下的直连图片预览、正式短信/收款/退款与公网环境继续独立待验收。争议处理/恢复后续设计，A5 不自动解除争议或更换派工技师。
