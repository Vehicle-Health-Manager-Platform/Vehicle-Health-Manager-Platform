# R1a 商家入驻本机端到端验收

日期：2026-10-10。基线 `main` **b389973**（当日把 #45–#79 共 35 支堆叠 PR 全部合并回 main 后的提交），收口分支 `codex/r1a-onboarding-e2e`，最终以该 PR head 为准。

本阶段在**真实后端容器 + 真实隔离 MySQL**上跑通「车主申请 → 运营审核 → 开店 → 配额满额 → 驳回重提」全链路，共 **92 项检查全部通过**，脚本可重复执行（连跑三次均 92/92，运行后合成数据零残留）。

## 已通过的证据

| 层次 | 结果与范围 |
| --- | --- |
| 入驻 HTTP 单测 | `MerchantOnboardingHttpTest` **6/6**（含新增的单层信封防漂移断言） |
| 入驻真实 MySQL 单测 | `MerchantOnboardingMySqlTest` **8/8**（Testcontainers MySQL 8） |
| 本机端到端 | `local_merchant_onboarding_e2e.cjs` **92/92**，真实容器 + 真实隔离库 + 真实私有上传/ClamAV 扫描 |
| 迁移 | V020 在本机隔离库**重复执行两次**成功（第二次全部命中 `SELECT 1` 空转），61 → **63 表** |
| 小程序 | 未改动页面代码；契约漂移修复见下 |

### 端到端覆盖矩阵

- **身份与权限**：匿名 `40100`；车主读运营接口 `40300`；只有 `can_review` 的运营读列表/详情/配额/资质一律 `40300`（不交叉授权）；运营读车主申请 `40300`。
- **严格正文与前置校验**：未知字段、未知品类、区域码位数、手机号、空/重复/超量资质文件、名称过短与首尾空白、缺幂等键、审核正文未知字段/批准带驳回码/驳回无码/未知驳回码/未知决定，全部 `40001`；不存在的申请 `40400`。
- **不安全资质**：不存在的文件、未完成扫描（`PENDING`）的文件、**他人真实上传的文件**，一律 `49002` 且不产生申请行。
- **fail-closed 配额**：配额表为空时批准返回 `49010`（缺失即 0，不降级为驳回）。
- **开店主链**：车主提交 → 运营待审列表（**固定摘要六字段**）→ 详情 → 资质受控访问（短时签名地址）→ 批准 → 核对 `merchant`（`merchant_type=3`、`region_code`、`status=1`、`qualification` 为文件 id 快照）、`staff_account`（`account='m{merchant_id}'`、`role='MERCHANT'`、`status='PENDING_ACTIVATION'`、**`password_hash IS NULL`**）、`merchant_application_review` 恰好一行。
- **幂等**：同键同载荷重放返回**原响应**（解析后深度比较）；不同键重复提交 `49001`；同幂等键换载荷 `40001`；批准同键重放原响应；已审 revision 再批 `49003`。
- **满额与配额下限**：配额 1 且已有 1 家有效门店时，同区同品类批准 `49010`；满额后可用固定码 `REGION_QUOTA_FULL` 驳回；配额调低到低于当前有效门店数 `49011`（两个区域各验一次）；同值重复设置成功且**不新增审计**。
- **驳回重提**：驳回后 `mine` 显示 `status=REJECTED` 与 `review_reason`；重提**复用同一行**、`revision=2`、清空驳回码；旧 revision 审核 `49003`；重提后批准成功；审核历史按 revision 倒序（`APPROVED` → `REJECTED`）。
- **会话失效**：撤销车主会话后请求 `40100`，运营会话不受影响。
- **审计最小化**：`merchant_application` 审计行的 `before_state`/`after_state` **不含手机号、不含对象键**，字段仅为 `status/revision/category/region_code/merchant_id` 白名单。

## 本次修复的真实缺陷

**写接口响应被双层包裹（`#78` 引入的契约漂移）**。`MerchantApplicationController.submit`、`MerchantOnboardingAdminController.moderate` 与 `setQuota` 把幂等写服务已经封装好的 `ApiResponse` 信封又交给 `ok()` 包了一层，实际响应变成 `{code,message,data:{code,message,data:{…}}}`。

- 三处证据一致指向服务端错误：OpenAPI（`scripts/generate_openapi.py`）声明的是**单层**信封；小程序服务层 `merchant-onboarding.js` 与 `experience-publication.js` 都按单层 `data.data` 解析；既有 A7 控制器（`ExperienceCardsController`）对写接口是**直接透传**信封。
- 后果：真实环境里车主提交、运营批准/驳回、配额设置会被小程序判为「协议错误」——页面在单测和 MockMvc 下全绿（`$.code` 无论包几层都是 0），**只有真实端到端才能暴露**。
- 修复：三个写接口改为透传幂等信封（新增 `wrote()`，GET 仍走 `ok()`）；并新增 `MerchantOnboardingHttpTest.writeScopesReturnSingleEnvelope`，断言 `$.data.application` 存在且 `$.data.data`/`$.data.code` **不存在**，防止再次退化。

**测试可用性修复**：`local_service_work_fixtures.cjs` 原以「合成 id 必须完全空闲」为前提，失败一次就会留下合成行导致下次无法重跑；改为「归属校验 + 重置本脚本自己的合成状态」，并给 `verifyIsolation` 增加 `r1a-onboarding` 阶段（镜像、V020、63 表断言）。另加默认关闭的 `LOCAL_E2E_VERBOSE=1`，让被脱敏的失败能按需打印底层 stderr。

## 本机隔离与合成边界

仅 `vehicle-auth-local` 命名 Docker 项目、固定回环 `127.0.0.1:18080`、独立网络、**精确镜像 `vehicle-auth/backend:r1a-onboarding`**（由当前分支构建的 JAR 打成，写入前核对容器内 `/app/app.jar` 与工作树 JAR 的 SHA256 一致）及 V020/63 表。迁移前备份（`before-v020.sql`），V020 重复执行两次；`MERCHANT_ONBOARDING_ENABLED` 只在隔离栈开启，默认配置仍为 false。

合成身份：车主 `9207701/9207702/9207703`（`synthetic-r1a-owner-*`），区域 `440106`/`440105`，运营账号由镜像内置无 HTTP 的 `operator-admin` CLI 创建（`OPERATOR_CAN_ONBOARD` 显式传入），随机密码与合成 OTP 不输出。

**资质文件是真实上传**：经 `POST /api/file/upload`（multipart 单 `file` 字段）写入真实 MinIO 对象并由真实 ClamAV 扫描为 `CLEAN`，因此「资质受控访问」得以真实签名（`sign()` 会 `stat` 对象并复核大小/MIME，纯数据库夹具无法通过）。上传字节固定为 `.cache/a6-photo.png` 这一张合成 PNG，**不是任何真实申请人材料**；唯一直接造库的文件是故意 `PENDING` 的那条，用于验证「未完成扫描」拒绝。运行结束后删除全部合成 `file_object` 行（连跑三次残留均为 0）；隔离桶中会留下少量合成对象，属已知孤儿对象，本机回环 MinIO 内、不参与任何验收结论。

车主会话使用既有本机合成 JWT 桥（写入 `auth_session`），**不代表 wx.login**；运营登录走真实 HTTP（密码 + 合成一次性 OTP，OTP 只由测试脚本写 bcrypt 哈希）。没有真实微信登录、真实短信、真实收款、真机或开发者工具模拟器页面流程。

## 复核命令与清理

先在受控隔离栈备份并应用 V020、安装当前 JAR、显式开启入驻开关并授予 `can_onboard`：

```powershell
docker build -t vehicle-auth/backend:r1a-onboarding backend
node scripts/local_merchant_onboarding_e2e.cjs --allow-local-test-writes
```

脚本会先核对命名栈、精确镜像、V020/63 表、运行 JAR 与开关，再重置自己的合成状态；失败时用 `LOCAL_E2E_VERBOSE=1` 查看被脱敏的底层错误。正常结束会撤销全部合成会话、禁用并删除合成运营账号、删除合成申请/门店/账号/文件/配额/审计/幂等/短信行。凭据、私有备份与会话文件均不上传。

> 本机若出现 `child_process.spawnSync` 对任意可执行文件返回 `EBUSY`（异步 `spawn` 正常），可先用 `--require` 加载一个把 `spawnSync` 建立在 Worker + 共享内存上的本地垫片；该垫片不是仓库产物，不影响正常环境。

## 尚未验收

正式短信与门店账号激活（R9）；真实微信登录、真机与开发者工具模拟器页面流程；真实商家/技师/运营人员操作；生产发布。默认入驻开关关闭，在 V020 与新小程序客户端升级前不得开启。R1b（店员/技师维护、员工码受控签发与撤销、门店资料与标准项目治理）尚未开始。
