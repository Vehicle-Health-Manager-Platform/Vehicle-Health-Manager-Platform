# A6.3 真实 HTTP 联调执行记录

日期：2026-10-08。分支 `codex/a6-service-e2e`，直接依赖 `codex/a6-service-ui`（PR #43 `9254e86`）。

## 交付

新增显式启用的 `local_service_work_fixtures.cjs` 与 `local_service_work_e2e.cjs`，使用真实后端/MySQL/MinIO/ClamAV，合成身份前置通过真实 A4 确认与 A5 派工/接单。数据库备份后重复应用 V014；后端只替换本地无状态容器，端口收紧到回环，保留旧配置与存储卷。运行 JAR 校验一致。

最终 **65/65 通过，退出码 0**；脚本语法检查、缺写入标志拒绝及 diff 空白检查通过。覆盖门禁、权限、证据归属/安全/复用、显式声明、幂等与不可变、争议/失效证据阻断、私有 HTTPS 原字节、双审计及撤销后拒绝。复现环境、命令、故障注入口径、夹具范围与待验收项见[本机验收](../testing/LOCAL_SERVICE_WORK_ACCEPTANCE.md)。

首轮脚本把通用操作路径写成 transition、撤销字段写成 revoked_at，已按现有 `/actions` 和绑定 status/unbound_at 修正；下载结果最初取 Node response.status 而非 statusCode，修正后 HTTP 200 原字节与无签名 403 均通过。这些是验收脚本错误，没有放宽服务端约束。新增仅回环网络/JAR 校验和异常会话回收后，最终脚本再次 65/65 通过。

## GitHub

A6.2 [PR #43](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/43) `9254e86` 的 [CI 37797778774](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37797778774) 六项全绿，后端 348、小程序 169，微信/H5 构建通过。A6.3 本阶段提交后推送并创建以 codex/a6-service-ui 为 base 的 PR；最新 CI 结果随后写入阶段总记录。

未执行合并或修改已有 base。按 #37→#38→#39→#40→#41→#42→#43→本 PR，在前序合入 main 后逐支把当前 base 改回 main、复核差异/CI。退款/取消归 A7；第二次异议与超时不在范围；真机/真实登录/自签证书直连预览仍单列。
