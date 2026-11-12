# 2026-11-06 现状盘点与剩余执行计划

本文件只做两件事：核对"现在到底到哪一步"，把剩余工作按**能否在本地闭环**分堆并给出顺序。不发起新的业务、性能、真机或供应商验收，因此不放宽任何既有结论。

## 一、本次核对到的事实

| 项 | 值 | 核对方式 |
| --- | --- | --- |
| 主分支 | `main` = `9aec3f7` | `git log -1` |
| 工作树 | 干净，无未提交改动 | `git status --short` 空 |
| 未合并分支 | 0（本地与远端均无） | `git branch -a --no-merged main` 空 |
| open PR | 0 | 近三轮（#260/#261/#262/#263/#264）均已 8 项 CI 全绿后合并 |
| 本地功能闭环 | 有效本地接口缺口 0 | [本轮验收](../testing/SCOPE_1345_ACCEPTANCE_2026_10_30.md) |
| 全 API 验收 | `false` | [覆盖索引](../testing/API_COVERAGE.md) |
| 安全门禁 | `false` | [安全发现](../testing/SECURITY_FINDINGS_2026_10_30.md) |
| 生产就绪 | 否（外部接入与真机未验） | [外部接入清单](../delivery/EXTERNAL_INTEGRATIONS.md) |

已闭环的质量基线（10-30 冻结回归，可复用不重跑）：后端 255 套 2641 项 0 失败/错误/跳过；九组声明范围行覆盖 96.27% / 方法 94.01%；默认 12 组 1246 小程序 + 623 Python；Linux 500 并发稳态 205.573 / 161.523 / 382.508ms、0 请求错误、订单/占位/幂等各 4020 一致。

**结论：功能开发阶段结束。** 剩下的不是"还没写的功能"，而是三类：口径不一致、质量门禁未达标、依赖外部资源的正式接入与真机。

## 二、本轮新发现：两处对外口径互相打架

这两处都不是代码 bug，但都会让"现在完成到多少"出现两个答案，必须先收口，否则后面每份报告都要重复解释。

### 2.1 API 覆盖存在两套数字：317/45 与 327/35

- 生成物口径：[API_COVERAGE.md](../testing/API_COVERAGE.md) 与 [CONTRACT_RECONCILIATION.md](../api/CONTRACT_RECONCILIATION.md) 都写 **317 项四类证据 / 45 项结论**，缺口 45 里含 10 项 `implemented-local`。
- 本轮执行口径：10-30 报告写 **327/362 四类去重、缺口 35**，并明确"有效本地软件接口缺口 0"。
- **差额正好是 10 项**，且恰好就是那 10 项 `implemented-local` 识别接口（接车里程识别 1、ASR 任务 3、图文任务 3、行驶证候选 1、里程网关 1、VIN 解码 1）。核对：45 = 17 superseded + 1 internal-only + 10 implemented-local + 11 blocked-external + 2 not-implemented + 1 no-success-path + 3 dev-only；去掉那 10 项即 35。
- 根因已定位：`scripts/summarize_api_coverage.py` 的 `POSTMAN` 只登记 29 套、`JOURNEYS` 只登记 7 条（upload / service_archive / material_workbench / pickup_work_files / merchant_onboarding_files / community_images / invitation_rewards），**R6b 的识别套件整批没有登记**，脚本内 `grep recognition` 为空。`CONTRACT_VERDICTS.json` 同理仍是 45 条、停在 R4b 的 `acbd7e7`。
- 注意 `--check` 只保证"文件与脚本输出一致"，查不出"脚本漏了一整批套件"。CI 因此是绿的，缺口是口径不是红灯。

### 2.2 性能门限在原文里有两处互斥要求

- 原文 §9.3：核心读接口 P95 < 300ms / P99 < 800ms，**写接口 P95 < 500ms**。
- 原文 §12.1：核心接口 **P95 < 300ms**（不分读写）。
- 当前实测：读 205.573 / 161.523 达标，写 382.508ms **满足 9.3、不满足 12.1 的字面**。
- 这是标准解释问题，不是技术问题。按项目既有约定（不得通过减负载或换门禁结案），需要验收人给出裁决并落成决议文档，再决定是否值得继续压写入 300ms 以内。

## 三、剩余未完成清单（按能否本地闭环分堆）

### A 类：本地可闭环（不依赖任何外部账号/设备）

| 编号 | 事项 | 现状 | 完成判据 |
| --- | --- | --- | --- |
| A1 | 统一 API 覆盖口径 | **已完成：327 项历史四类证据 / 35 项明确结论** | 识别套件登记进 `JOURNEYS`，移除已获历史证据的 10 项结论并重生成索引；当前版本识别重跑仍待 Docker 环境 |
| A2 | Python 依赖 4 项 HIGH | **已完成（2026-11-06）**：Transformers 5.22.1；四项告警从最终扫描消失；Linux wheel/安装锁固定官方 SHA | 安全扫描、嵌入测试、真实模型离线/隔离及 worker 恢复证据见[安全修复复验](../testing/SECURITY_REMEDIATION_2026_11_06.md) |
| A3 | Java 2 项 CRITICAL | **已完成（2026-11-06）**：Spring Boot 4.0.9 / Spring MVC 7.0.10；漏洞门禁扫描无发现 | 迁移、全量 2,642 项后端回归及构建记录见[安全修复复验](../testing/SECURITY_REMEDIATION_2026_11_06.md)；生产安全接受仍为 false |
| A4 | 当前源码性能复验与必要优化 | 最近留存结果 464.547ms，来自 2026-10-30、安全框架迁移前的源码；2026-11-12 诊断确认 Docker Desktop 应用报告已运行，但 Linux Engine 管道缺失、`com.docker.service` 停止且当前执行权限无法启动，复测未启动 | 由有权管理本机环境的人员恢复 Docker Desktop Linux Engine 与服务；确认 `docker version` 有 Server 且 `docker info` 为 Linux 后，按当前源码重跑同一 500 并发与数据一致性门禁；若任一核心接口 P95 ≥300ms，再剖析并优化，直到严格低于 300ms |
| A5 | 性能门限口径 | **已按既有发布政策收口**：§9.3 用于读写分项压测门禁，§12.1 的统一 P95 `<300ms` 仍为最终发布门禁 | 未批准范围调整前不降低严格发布门槛；决议见[性能门限裁决记录](../operations/PERFORMANCE_THRESHOLD_DECISION_2026_11_12.md)，并由覆盖索引与容量手册互相引用 |

A 类中 A1、A2、A3、A5 已有代码/证据或政策结案记录。当前本地实施项为 A4：针对安全迁移后的当前源码重跑性能基线，只有严于最终门限时才开展性能优化。

### B 类：需外部资源，本地只能做"可插拔骨架 + 交接清单"，不得验收

短信生产适配器（商家/运营验证码、手机号绑定）；真实微信支付与退款回调（`POST /api/payments|refunds/callback/{channel}` 目前仅 `LOCAL_TEST`）；微信上游 code2session、官方邀请小程序码、订阅消息与真实新客归因；真实国内车型规则数据；生产部署（HTTPS/合法域名/云托管入口/告警投递/备份演练）。共对应 11 项 `blocked-external` + 2 项 `not-implemented`。

**边界**：骨架可以写、可以用假 provider 做单测，但按项目约定不能写成"已接入"，验收证据必须等真实上游。

### C 类：需真实设备或授权样本

iOS/Android 三端完整旅程（车主录车→预约→接车确认→取车核销→档案评价、商家接车→派工→核销、技师接单→拍照报工→签字），相机/麦克风/扫码/私有预览/日期 picker/弱网/后台恢复/身份切换；授权样本效果（真实证件、真实仪表、中文语音与方言、领域语料召回）。

## 四、建议顺序

1. A1 已统一 API 覆盖统计；A2/A3 已完成依赖修复、扫描与回归，不再重复升级。
2. A5 已按既有路线图原则收口：§9.3 保留为分项压测规则，§12.1 更严格的 300ms 是最终发布门槛；没有范围调整批准时不降低门槛。
3. **下一步：A4**。在当前 Spring Boot 4 源码上用原 500 并发与一致性门禁复测；若写 P95 未严格低于 300ms，则继续性能剖析和优化。
4. **阶段四（真机）**：C1 需要设备在场，可与阶段二并行排期。
5. **并行**：B 类按 [外部接入清单](../delivery/EXTERNAL_INTEGRATIONS.md) 交接，本地只准备骨架与验收脚本。

## 五、每阶段必须留的证据

按既有约定，不接受任何一项被简化：

- 四件套齐全：规格 `docs/superpowers/specs/`、契约 `docs/api/`、运维 `docs/operations/`、复现 `docs/testing/`，并在 `docs/progress/README.md` 登记。
- PR 的四个状态分开写：已提交分支 / 最终 head CI / 是否合并 / 是否部署。当前只有前两项可谈。
- 未真实联调的不得写"已完成"；本地合成数据、`LOCAL_TEST` 渠道、自签 JWT 都不算验收证据。
- 「已达标」必须给出实测数字与门限原文，不能只写"通过"。

## 六、入口

- 当前位置：[当前状态](CURRENT_STATUS.md) / [进度与路线图](STATUS_AND_ROADMAP_2026_10_26.md) / [已知问题与后续任务](../delivery/LOCAL_BACKLOG.md)
- 剩余验收条件：[剩余验收与交接](../delivery/REMAINING_ACCEPTANCE.md)
- 覆盖与结论：[API 覆盖索引](../testing/API_COVERAGE.md) / [契约逐项结论](../api/CONTRACT_RECONCILIATION.md)
- 质量门禁：[容量手册](../operations/PERFORMANCE_CAPACITY_RUNBOOK.md) / [安全发现](../testing/SECURITY_FINDINGS_2026_10_30.md)
- 原始需求：[交付文档](../../汽车健康管家平台全栈开发交付文档.md)

## 2026-11-12 A1 收口记录

- 覆盖生成器现在登记 7 个识别测试套件，校验后对应 10 个唯一契约操作。`VIN` 与 `local-vin` 是同一路由的两组证据，不重复计接口。
- 覆盖索引与契约逐项结论已统一为 **327 项历史四类证据 + 35 项结论**。10 项 `implemented-local` 已有匹配的历史四类 HTTP 证据，因此不再作为“无证据结论”重复列出。
- 历史识别快照来自 2026-10-30 的 `AUDIT_EXISTING`。它绑定当时源码并标记本地 provider fixture；该快照不等于当前代码重跑，不代表真实模型或供应商验收。全 API 与生产验收仍为 false。
- 当前版本重跑尝试因 Docker Desktop Linux Engine 管道不可用而在 Maven 启动前失败；没有新的识别 HTTP 执行或当前数据库清理结论。详情见 [A1测试记录](../testing/API_COVERAGE_RECONCILIATION_2026_11_12.md)。
- A2/A3 已由[安全修复复验](../testing/SECURITY_REMEDIATION_2026_11_06.md)完成，不应再按本计划最初的旧版本信息重复升级。
- A5 已按[既有路线图](STATUS_AND_ROADMAP_2026_10_26.md)采用“§9.3 分项压测 + §12.1 严格发布门槛”；[性能门限裁决记录](../operations/PERFORMANCE_THRESHOLD_DECISION_2026_11_12.md)留存原文、依据与最新留存数据。
- 下一项为 A4：当前源码基线尚未复测；若严格发布门槛不达标，再根据当前源码证据做性能优化。
