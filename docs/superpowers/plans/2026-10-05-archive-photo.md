# S1 本人车辆档案拍照录入实施计划

> **供执行者使用：** 按任务逐项实施；如使用 Superpowers，可选 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`。步骤用 `- [ ]` 跟踪。

**目标：** 车主拍照后保存来源明确的车辆档案，并在档案列表与首页摘要中查看。

**架构：** 扩展现有档案契约，拍照方式为 `input_type=1`，省略该字段仍为手动方式 `3`。复用现有表单、私有图片上传、本人归属检查、幂等写入与分页查询；拍照模式只改变图片选择来源及必填图片规则。

**技术栈：** Java 17、Spring Boot、JdbcTemplate、MySQL；uni-app 3、Vue 3、Node 测试运行器。

**设计规格：** `docs/superpowers/specs/2026-10-05-archive-photo-design.md`

## 全局约束

- 只允许创建方式 1（拍照）和 3（手动）；省略时取 3。方式 1 须关联 1–5 张图片，方式 3 可关联 0–5 张。
- 保持车主会话、车辆归属、图片 CLEAN 状态、事务、幂等与成功审计保证；不新增数据库迁移。
- 不根据照片推断档案内容，不宣称支持 OCR 或语音；不持久化签名 URL 或敏感表单数据。

---

### 任务 1：档案接口与数据库读写

**文件：** 修改 `backend/src/main/java/com/autocare/platform/vehicle/ArchiveInput.java`、`ArchiveService.java`；测试 `backend/src/test/java/com/autocare/platform/JdbcArchiveTest.java`。

**接口约定：** `ArchiveInput` 增加 `int inputType` 并解析可选的 `input_type`；`canonical()` 只为拍照方式加入该字段，保持旧手动请求的重试正文不变。`ArchiveService.list()` 返回方式 1 和 3 的记录及 `input_type`。

- [ ] 先加入解析和 MySQL 失败用例：省略方式取 3；方式 1 无图、方式 2 返回 400；方式 1 关联一张本人 CLEAN 图片后写入 `input_type=1`，列表与审计可见，同键更改方式返回 400。断言混合方式的分页与总数、外部或未扫描图片被拒绝、事务回滚。
- [ ] 在具备 Java 和 Docker 的 `backend` 目录运行 `mvn -B -Dtest=JdbcArchiveTest test`，确认新增用例在修改代码前失败。
- [ ] 严格解析字段：缺省取 3，只接受 1 或 3，方式 1 且图片列表为空时拒绝；只为拍照方式将 `input_type` 加入幂等规范正文，两种方式均写入 SQL 与安全审计。列表和总数均使用 `input_type IN (1,3)`，列表每行返回 `input_type`。
- [ ] 重新运行后端测试并提交；若本机无 Java 或 Docker，记录原因并交由 CI 实际执行。

### 任务 2：小程序档案契约与相机选择

**文件：** 修改 `apps/miniapp/src/services/archives.js`、`archive-flow.js`、`private-images.js`；测试 `apps/miniapp/test/archives.test.js`、`archive-flow.test.js`、`images.test.js`。

**接口约定：** `state.inputType` 默认 3；`archiveBody(fields)` 只在方式 1 时发出 `input_type`，并拒绝无图拍照；`imageApi.choose(token, source='mixed')` 在 `source='camera'` 时请求 `['camera']`，否则保持 `['album','camera']`。

- [ ] 先加入失败用例，覆盖拍照请求体、手动兼容、拒绝方式 2、仅相机 `sourceType`、取消或权限失败，以及带 `input_type` 的混合档案列表响应。
- [ ] 在 `apps/miniapp` 运行 `node --test --test-isolation=none test/archives.test.js test/archive-flow.test.js test/images.test.js`，确认新增断言先失败。
- [ ] 实现上述接口，继续使用 `ArchiveError` 和 `ImageError` 的安全文案；取消拍照返回 `null`，不替换已上传的文件 ID。
- [ ] 重新运行定向测试，通过后提交客户端契约变更。

### 任务 3：档案页与首页

**文件：** 修改 `apps/miniapp/src/pages/archive/index.vue`、`record-add.vue`、`home/index.vue`；新建 `apps/miniapp/src/services/archive-entry-mode.js` 和 `apps/miniapp/test/archive-entry-mode.test.js`；修改 `apps/miniapp/test/home-summary.test.js`。

**接口约定：** `archiveEntryUrl(vehicleId, mode)` 构造两种入口；`archiveInputType(query)` 仅在精确匹配 `mode=photo` 时返回 1，否则返回 3；`archiveInputTypeName(value)` 返回来源文案。拍照入口使用 `record-add?vehicle_id=N&mode=photo`，手动入口不传 `mode`。

- [ ] 先测试两种精确路由和来源文案，并加入最近记录为 `input_type=1` 的首页摘要样本。拍照图片必填和相机专用来源已由任务 2 的客户端测试覆盖。
- [ ] 运行定向测试并确认新增断言先失败。
- [ ] 档案页为当前车辆提供手动和拍照入口；录入页解析 `mode=photo`，表单重置后保留模式，拍照时只调用相机选择器，显示拍照提示，无已上传图片时禁用保存。档案列表和首页最近记录展示来源；保持现有迟到请求隔离、预览和上传重试键行为。
- [ ] 运行小程序完整 Node 测试，以及 `npm --prefix apps/miniapp run build:mp-weixin` 和 `npm --prefix apps/miniapp run build:h5`；通过后提交页面变更。

### 任务 4：契约文档与最终验证

**文件：** 修改 `docs/api/ARCHIVE_MANUAL.md`、`scripts/generate_openapi.py`、生成文件 `docs/api/openapi.json`、`README.md`、`docs/progress/CURRENT_STATUS.md`、`NEXT_STEPS.md`、`S0_EXECUTION_LOG.md`。

**接口约定：** 写清请求中的 `input_type`、混合列表响应、旧请求省略字段的默认行为、拍照图片必填，以及实际验证证据。

- [ ] 更新书面契约和 `scripts/generate_openapi.py`，运行 `python scripts/generate_openapi.py`；再执行 `git diff --check` 并重复运行生成器，确认生成结果稳定。
- [ ] 在进度与执行记录中分别记录本机通过、CI 实际验证，以及真实相机和私有环境仍需联调的边界。
- [ ] 检查差异是否越界；推送独立分支，使用正文文件创建指向 `main` 的 PR，核对六项 CI 后报告 PR 与证据。未经用户另行要求不合并。
