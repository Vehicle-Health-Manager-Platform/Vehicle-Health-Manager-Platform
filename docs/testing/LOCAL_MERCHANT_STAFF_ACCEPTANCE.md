# R1b 门店员工与门店资料本机端到端验收

日期：2026-10-11。收口分支 `codex/r1b-staff-store-e2e`（堆叠在 `codex/r1b-staff-store-pages` 之上），最终以该 PR head 为准。

本阶段在**真实后端容器 + 真实隔离 MySQL**上跑通「店长建店员/建技师 → 停用即时踢下线 → 员工码签发/轮换/撤销并真实绑定 → 门店资料读写与审计」全链路，脚本 `scripts/local_merchant_staff_e2e.cjs` 共 **136 项检查全部通过**，可重复执行（运行结束后合成数据零残留）。**员工码这一段是真实闭环**：码由店长经 HTTP 签发，再交给真实的 `POST /api/auth/technician/bind`，因此「轮换后旧码失效」「撤销后不可再绑定」是对真实查询路径的断言，不是读一下列。

## 已通过的证据

| 层次 | 结果与范围 |
| --- | --- |
| 员工维护 HTTP 单测 | `MerchantStaffHttpTest` **6/6**（严格正文在 HTTP 边界、单层信封防漂移） |
| 员工维护真实 MySQL 单测 | `MerchantStaffMySqlTest` **8/8**（Testcontainers MySQL 8） |
| 相关回归 | 20 个测试类 **200/200** |
| 本机端到端 | `local_merchant_staff_e2e.cjs` **136/136**，真实容器 + 真实隔离库 |
| 迁移 | V021 在本机隔离库**重复执行两次**成功（第二次全部空转），仍 **63 表**；扩列 `display_name`/`created_by` 逐列核对 |
| 小程序 | 离线 **273/273**；`build:miniapp`（mp-weixin）与 `build:h5` 均通过 |

### 端到端覆盖矩阵

- **隔离与前置**：命名栈 `vehicle-auth-local`、回环 `127.0.0.1:18080`、**精确镜像 `vehicle-auth/backend:r1b-staff`**；容器内 `/app/app.jar` 的工作树 SHA256 一致（不一致直接拒绝写入夹具）；`MERCHANT_STAFF_ENABLED=true`；**V021 两列 + 63 表**逐项核对（V021 只扩列不加表，只数表数挡不住"库停在 V020"）。
- **匿名与角色边界**：匿名读员工列表/门店资料 `401`/`40100`；技师令牌读员工列表、读门店资料、新增员工一律 `403`/`40300`；**店员读本店订单成功**（履约执行面与店长同级）。
- **严格正文与前置校验**（全部 `400`/`40001`）：未知字段、角色为店长、未知角色、姓名过短、姓名首空白、密码无数字、密码过短、密码含非 ASCII、店员缺手机号、手机号非大陆、手机号非文本、字段过少；缺幂等键、非 UUID 幂等键、多余 query、列表未知 query、列表未知角色筛选、`page_size` 超限、停用正文非空对象、停用缺幂等键、资料读带 query、资料写字段数不为五、资料写未知字段、越界经度、坐标为文本。
- **新增员工**：响应与列表行**九字段同形**（`staff_id/account/role/display_name/status/phone_masked/employee_code_issued/wechat_bound/created_at`）；账号按规则生成 `s{merchant_id}-1`、`t{merchant_id}-1` 且初始 `ACTIVE`；手机号脱敏为 `138****8804` 且响应里无明文；新员工 `employee_code_issued=false`、`wechat_bound=false`；同键同载荷重放返回**原响应**（含 `request_id`，解析后深度比较）；同键不同载荷 `40001`；`password_hash` 为 BCrypt（`$2a$`/`$2b$`）；**明文密码不落幂等记录与审计**；列表恰好 4 名下属且不含店长自己、键集合与创建响应一致；按角色筛选 `total=2` 且只出店员；跨店列表不串（他店店长只看到本店 1 名技师）。
- **停用即时阻断原会话**：停用前确认存在有效会话；停用响应回传 `sessions_revoked` 与库中未撤销会话数**一致**；停用后**原访问令牌立即 `401`/`40100`**；**刷新令牌也不可用**（`401`/`40100`）；库中该员工已无未撤销会话；重复停用幂等成功且**不重复撤销**；跨店停用按不存在 `404`/`40400`；停用不存在的员工 `404`；**店长账号不可经本接口停用**（`404`）。
- **技师停用连带撤销微信绑定**：停用后 `staff_wechat_identity.status='REVOKED'`、原令牌 `401`、员工行保留（停用不改历史）；启用只回 `staff_id`+`status`，**不恢复已撤销的绑定**；启用同键重放原响应；店员启用他人 `403`；员工已停用后签发员工码 `404`。
- **员工码真实闭环**：店员签发 `403`、跨店签发 `404`；签发响应为 `employee_code`/`single_use`/`staff_id` 三字段且 `single_use=true`，码形如 32 位 base64url；库中**只存 SHA256**；**明文码不进审计、不进幂等记录**；用该码走真实 `POST /api/auth/technician/bind` 成功并写入 `ACTIVE` 身份行；列表随之显示 `employee_code_issued=true`；**重新签发产生新码**、库中哈希换新、**旧码绑定 `403`/`40300`、旧绑定令牌 `401`**、该技师无 `ACTIVE` 绑定残留；新码可再次绑定；`DELETE` 撤销响应形状为 `employee_code_revoked`/`staff_id`、哈希清空为 `NULL`、**撤销后码不可绑定**、上一轮绑定失效、重复撤销幂等成功。
- **门店资料**：店员可读且为**十字段白名单**、`can_edit=false`；响应不含 `qualification`/`grade`/`commission_rate`/`region_protected`/`is_deleted`；店长读 `can_edit=true`；**店员写 `403`/`40300`**；跨店读到的是本店资料；店长写响应为五字段白名单、坐标规范化（`113.2644`/`23.1291`，DECIMAL 不补零）；同键同载荷重放原响应；**坐标尾随零（`113.26440000`）不产生新意图**（复用同一幂等记录）；**重放不产生新审计**；同键不同载荷 `40001`；坐标置空可存可读（读写均为 `null`）；**资料审计两次真实写各留一条、每条状态只含白名单五字段**（共 4 组键集合），不含运营字段与资质；未改动的 `status`/`merchant_type`/`region_code` 保持原值。
- **零残留**：运行后逐表核对 `staff_account`/`merchant`/`staff_wechat_identity`/`auth_session`/`idempotency_record`/`audit_log` 全部为 0。

## 收口过程中定位并修复的真实缺陷

### 1. 店员根本登录不上（`wechat-auth.js`，小程序）

`requestMerchantLogin` 只接受 `role === 'merchant'`。R1b 起店员复用门店登录入口、服务端按员工行真实角色签发 `staff`，于是**店员的合法登录响应会被客户端判成协议错误**——等于店员永远进不去门店端，页面做得再对也没用。

- 修复：抽出 `STORE_ROLES = ['merchant','staff']` 与 `validStoreSession`，登录入口同时接受店长与店员，其余角色与"有角色无令牌"一律拒绝。
- 防漂移：新增离线测试「门店登录入口同时接受店长与店员角色，其余角色与缺字段一律拒绝」，并**先在旧代码上跑一遍确认它会失败**（否则这条测试等于没测）。
- 这一条只有真实链路才暴露：MockMvc 与离线单测都不经过"客户端如何判定登录响应"。

### 2. 门店资料读白名单与写白名单被合并成一个列表（`merchant-profile.js`，小程序）

初版把只读字段 `region_code` 与真正"绝不该出现"的运营字段放进同一个 `NOT_EDITABLE_KEYS`，而 `region_code` **本来就该出现在读响应里**——结果**每一个合法读响应都被判成协议错误**。

- 修复：拆成 `READ_ONLY_KEYS`（读得到、不可写）与 `NEVER_READ_KEYS`（读响应里绝不该出现），并补一条"两个白名单恰好是契约字段且不掺运营侧字段"的断言测试。

### 3. 门店端登录文案残留"商家"（小程序）

R1b 引入店员后，"商家端/商家账号/商家登录成功"会造成"店员是不是走另一个入口"的误解。全仓 grep 后把登录相关文案统一为**门店**（`门店端`/`门店账号`/`门店登录成功`/`正在验证门店身份…`/`前往门店登录`），历史执行记录与规格里的原文保留不改。

### 4. 规格与实现的字段数漂移（文档）

规格原写"创建响应只含 `staff_id/account/role/display_name/status/phone_masked` 六个字段"，实现返回的是列表行的**九个**字段。规格的实质约束（不返回密码、明文不落库）未被突破，且客户端因此只需一套行校验器、新建行可直接并入列表——故**以实现为准修正规格文字**，并在真实容器按九字段断言（`创建响应九字段`、`创建与列表行逐字段同形`）。

### 5. 验收脚本自身的 SQL 括号不配平（工具）

`residue()` 用一个大 `CONCAT` 拼 6 个子查询，漏掉了 `CONCAT` 的收尾右括号，MySQL 报 `ERROR 1064 ... near '' at line 7`——**症状与"stdin 最后一行被截断"完全一样**，一度把排查方向带偏到 shim / `docker exec -i` 上（实际用文件重定向复现同样失败，证明与传输无关）。改为**逐表各发一条 `COUNT` 再拼接**，既消除括号手工配平的风险，也让失败信息能直接指向是哪张表有残留。

## 本机隔离与合成边界

仅 `vehicle-auth-local` 命名 Docker 项目、固定回环 `127.0.0.1:18080`、独立网络、**精确镜像 `vehicle-auth/backend:r1b-staff`** 及 V021/63 表。迁移前备份在本机 `test-results/`（受版本控制忽略）。`MERCHANT_STAFF_ENABLED` 只在隔离栈显式开启，**默认配置仍为 false**。

合成身份：门店 `9208101`/`9208102`、店长 `9208111`/`9208113`、店员 `9208112`、技师 `9208121`、他店技师 `9208122`、微信绑定行 `9208131`，openid 前缀 `synthetic-r1b-*`。会话使用既有本机合成 JWT 桥（用本机 `JWT_SECRET` 铸造并写入 `auth_session`），**不代表 wx.login**；员工码那一段除了"码由店长签发"外是真实 HTTP + 真实绑定查询。

**未覆盖**：正式短信、真实微信登录、真实技师微信绑定（R9）、真机与开发者工具模拟器页面流程、生产发布。默认 `MERCHANT_STAFF_ENABLED=false`，在 V021 与新小程序客户端升级前不得开启。

## 复核命令与清理

```powershell
docker build -t vehicle-auth/backend:r1b-staff backend
# 重建容器时显式开启开关（docker restart 不重读环境变量，必须 docker run 重建）
node scripts/local_merchant_staff_e2e.cjs --allow-local-test-writes
```

脚本会先核对命名栈、精确镜像、V021/63 表、运行 JAR 的 SHA256 与开关，再重置自己的合成状态；失败时用 `LOCAL_E2E_VERBOSE=1` 查看被脱敏的底层 stderr。正常结束会撤销全部合成会话并删除合成门店/员工/绑定/幂等/审计行，最后逐表断言零残留。

> 本机若出现 `child_process.spawnSync` 对任意可执行文件返回 `EBUSY`（异步 `spawn` 正常），可先用 `--require` 加载一个把 `spawnSync` 建立在 Worker + 共享内存上的本地垫片；该垫片不是仓库产物，不影响正常环境。

## 尚未验收

- 正式短信与门店/员工账号激活（R9）；真实微信登录、真实技师员工码绑定与真机页面流程。
- 开发者工具模拟器上的门店端页面交互（员工列表/新增/启停/员工码、门店资料读写）——本轮只验证了后端契约与离线状态机。
- 生产发布与灰度：`MERCHANT_STAFF_ENABLED` 的开启顺序见 [STORE_ACCOUNTS.md](../operations/STORE_ACCOUNTS.md)。
- R1c（标准项目/品牌/车型治理）尚未开始，另起一套堆叠 PR。
