# A4 车主接车单决定：执行与交付记录

日期：2026-10-08。分支：`codex/s4-owner-pickup-confirm`。依赖：A3 已合并的 PR #34。当前状态：阶段验证通过，已上传 [PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)，等待合并。

## 已实现范围

- 车主在本人七图接车单上确认，或填写 1–500 字原因提出异议；服务器复核有效身份、本人订单及接车状态。
- 确认保存决定时间和 `owner_confirmed_at`；异议保存完整原因，并将订单 `RECEIVED → DISPUTED`，阻断后续施工。
- 商家在订单与接车单查看异议提示及原因；通知形态是页面读取，没有短信或微信订阅推送。
- 幂等重放仍校验权限；审计、决定、状态及幂等响应同事务；并发确认与异议最多一项成功。
- V011 增加单据异议原因，扩展迁移审计原因容量为 500 字；Compose 新卷初始化和迁移检查均已接入。

接口与重试语义见[A4 契约](../api/PICKUP_OWNER_DECISION.md)。确认后仍需要 A5 派工；当前决定只接受一次，争议处理/恢复由后续独立规格定义。

## 验证证据

| 验证 | 结果与说明 |
| --- | --- |
| 小程序全量 Node 测试 | 120 项通过；新增车主决定请求、原因边界与正确幂等头的验证 |
| 后端编译及测试源码编译 | 已通过 |
| 接车 HTTP 测试 | 6 项通过；OWNER 权限、未知字段、缺幂等键、缓存头、40905 状态冲突码 |
| MySQL 接车/决定集成测试 | 12 项通过；决定、审计、回滚、越权、撤销会话重放及并发均通过；最终 500 字原因与审计完整保存另行补验通过，V011 重复应用通过 |
| 小程序与 H5 构建 | 两项通过；沙箱内曾将本机已安装的 terser 解析为缺失，沙箱外构建通过 |
| OpenAPI 与差异检查 | 生成 JSON 可解析，`git diff --check` 通过；PR CI 继续检查生成物一致性 |
| GitHub 六项 CI | 实现提交 `e0d4077` 全部通过；后端 271 项、小程序 120 项，无失败/跳过。运行：[37710468297](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/actions/runs/37710468297) |

本机 Docker Desktop 29 的 API 版本要求与 docker-java 默认版本不匹配，设置 `-Dapi.version=1.44` 后已连接真实 Docker/MySQL。验证容器使用测试生命周期清理数据库容器；本机环境诊断不改变生产配置。

## 迁移与验收

已有数据卷先按项目方式备份，再应用 `docs/sql/migrations/V011__pickup_owner_decision.sql`；迁移重复执行不清空数据。新 Compose 卷自动应用。

人工验收步骤：本人登录打开 `RECEIVED` 订单→核对七图及里程→确认→商家刷新单据看已确认；另一份订单填写异议→双方刷新看争议与原因→商家施工动作被拒绝。换车主/他店/技师访问不能越权。当前真实相机、真机和测试证书下直连预览仍未验收。

## GitHub 收口与下一步

代码、测试、V011、中文接口、生成 OpenAPI、进度文档、下一步计划及阶段交付规则已一起提交、推送并建立指向 `main` 的 PR #35。实现提交六项 CI 通过后补齐文档记录；文档提交仍由同一 PR 自动验证。执行[阶段交付规则](STAGE_DELIVERY.md)。下一业务阶段见[A5 派工与技师接单计划](../superpowers/plans/2026-10-08-a5-dispatch.md)。
