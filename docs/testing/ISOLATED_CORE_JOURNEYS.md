# 隔离核心业务 HTTP 端到端

## 复跑

在项目根目录运行（需要 Docker Engine；首次需要 Maven/MySQL/Testcontainers 镜像和依赖）：

```powershell
python scripts/run_isolated_journeys.py --docker
```

本机已有 Java 17 和 Maven 时去掉 `--docker`。Docker 入口使用 Maven17 镜像、专属 Maven 缓存卷与 Docker socket；Testcontainers 创建随机 MySQL 端口和全新数据库，完成后回收容器。只对该新数据库执行全部迁移及合成数据准备，无已有库地址选项，也不访问历史固定容器。请勿同时运行多个共用 backend/target 的 Maven 任务。

CI 默认全量后端测试包含 `IsolatedCoreJourneysTest`；之后运行 `python scripts/run_isolated_journeys.py --check-only` 检查本次 JUnit 证据。该选项只校验已有报告，不代表新执行一次。结果进入 `backend/target/isolated-journeys-run.json`，与 JUnit XML 一同上传既有 `core-coverage-and-tests` artifact（保留14天）。本地运行先移除旧旅程报告并 `clean test`；命令失败、测试缺失、失败、跳过或运行期间源码/迁移变化均判失败。

## 三条旅程与断言

- `ownerVehicleArchiveAndInquiryPreparation`：真实 JWT/会话校验、建车/重复建车请求/同键变更、列表脱敏与他人列表、档案保存与重复提交、跨用户档案/AI上下文拒绝、AI 未配置503、会话撤销401。
- `reservationThroughPickupServiceRedemptionAndArchive`：真实发布时段、读取报价、预约及重复提交、订单隔离；正式支付不可用503；LOCAL_TEST 创建与签名回调/重放；接车与重复提交、确认前禁止派工、车主确认、派工、防护、技师接单、角色拒绝、报工/重放、签字、核销/重放、实际调度任务归档、车主读取。校验关键状态、唯一报告/支付事件/测试核销、异步任务DONE和跨用户不可见。
- `merchantApplicationReviewAndPendingActivationBoundary`：不属于申请人的资质拒绝、申请与重放、运营权限、无配额拒绝、运营配置配额后原键重试、审核开店与重放、单份审核记录、待激活无密码、登录和伪造待激活账号令牌均拒绝。

## 严格边界

本阶段是后端 HTTP + 真实 MySQL 业务集成验证。身份与已扫描文件元数据由测试夹具创建，不等于微信/SMS、真实文件上传或扫描验证；不替换 JWT 解码器或业务服务。所有支付均 LOCAL_TEST，无真实扣款。AI key 显式未配置，不请求供应商。

**商家正式激活尚未实现，仍留 B1**：审核创建 PENDING_ACTIVATION 账号；本测试不会用 SQL 激活后假称完成。小程序界面点击、真机、真实渠道仍需分别验收。三条旅程不等于全部352项API已具备Postman/Newman验收；已有HTTP输入/错误/权限测试继续在全量CI运行。A4c性能/恢复另行执行。

## 配置

后端新增 `MYSQL_PORT`（默认3306）。Testcontainers通过动态属性注入随机映射端口，生产保留既有默认。其他正式渠道与功能开关不因运行测试而开启到部署配置。
