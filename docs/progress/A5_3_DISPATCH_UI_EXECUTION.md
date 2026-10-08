# A5.3 商家派工与技师工作台界面执行记录

日期：2026-10-08。状态：A5.3 界面阶段本机验证通过并上传阶段分支，尚未合并；A5.2 后端（PR #37）同样未合并。A5.4 安全与竞争验证、A5.5 本机联调与收口后续交付。

## 基线与变更

基于 A5.2 最终提交 `9176aa2` 建立 `codex/a5-dispatch-ui`。A5.2 已上传 [PR #37](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/37)，前序 [PR #36](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/36)（A5.1）、[PR #35](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/35)（A4）同样未合并，阶段 PR 以直接前序分支为基线，不自动合并或部署。

本阶段只改小程序前端，无数据库迁移、无后端改动。

- 新增 `apps/miniapp/src/services/technician-session.js`：技师会话独立于商家/车主，跨页面有效；只保存 access token，不落盘、不进 URL、不写日志。
- 改造 `apps/miniapp/src/services/role-identity.js`：技师登录/绑定写入共享会话，退出与「重新登录」清理它。此前技师 token 是组件局部 `ref`，换页即丢，工单页只能报「请先登录」。
- 新增 `apps/miniapp/src/services/technician-dispatch.js`：六个接口客户端，全部经 `apiRuntime` 调用，兼容直连与云托管；严格校验最小投影、拒绝越界字段、翻译服务端错误码。
- 新增 `apps/miniapp/src/pages/merchant/dispatch.vue`：分页选本店候选技师、二次确认、已有派工只读展示。`pages/merchant/order-detail.vue` 增加派工入口与「查看派工结果」。
- 新增 `apps/miniapp/src/pages/technician/orders.vue` 与 `order-detail.vue`，并在 `pages.json` 注册；`RoleLanding.vue` 增加技师「我的工单」入口。
- 新增 `apps/miniapp/test/technician-dispatch.test.js`（19 项）并扩展 `test/identity.test.js`（1 项）。

## 页面行为约定

- **一单一位技师**：已有派工时商家页面只读展示技师标签、派工状态与时间，不提供改派或撤回；服务端也不允许客户端覆盖既有派工。
- **不默认选中**：候选为空显示「暂无已绑定微信的有效技师，请联系管理员维护技师账号」；读取失败只提示重试，不展示假候选，也不预选首位。
- **二次确认**：选择技师后按钮显示「派工给「某某」」，点击弹模态框，内容含订单号与员工标签；提交期间禁用选人与提交，成功后刷新订单与派工状态；冲突（`40905`）后清空选择并重新加载。
- **接单是唯一施工入口**：商家通用 `START_SERVICE` 已在 A5.2 停用（`43004`），技师工单详情只有在服务端投影 `can_accept=true` 时才显示「接单并开始施工」；已接单、订单非 `RECEIVED`、或身份失效时都给出具体原因，不留可点击的按钮。首版没有报工入口，A6 接入。
- **幂等与代次**：写操作复用 `createReservationWriteFlow` 的代次与幂等机制——断网重试沿用原键，改选技师后换新键，提交期间重复点击不再发请求，成功后不再重复提交；401 清会话并引导重新登录，冲突刷新当前资源。
- **失效处理**：`onShow` 刷新，`onHide`/`onUnload` 使在飞请求失效；账号切换清空页面数据与选择。模态回调经 `confirmStillHolds` 校验身份、页面可见性与目标，任一变化即作废旧确认，避免用新账号提交旧工单或派成用户已改掉的人。
- **最小投影**：技师工单只含工单号、订单状态、派工状态与时间、项目/预约快照与 `can_accept`；客户端显式拒绝 `technician_id`、`merchant_id`、`user_id`、`vehicle_id`、车牌、VIN、核销码、付款摘要等字段，越界即报协议错误，防止投影回退后悄悄泄露。

## 实际验证

本机命令：

- `npm test --workspace @autocare/miniapp`：**140 项通过**（A5.2 基线 120 项 + 新增 20 项），失败/跳过均 0。
- `npm run build:mp-weixin --workspace @autocare/miniapp`、`npm run build:h5 --workspace @autocare/miniapp`：两端构建通过；产物 `app.json` 已包含 `pages/technician/orders`、`pages/technician/order-detail`、`pages/merchant/dispatch`。
- `python scripts/generate_traceability.py`、`build_init_sql.py`、`generate_openapi.py`：重新生成后无额外差异（89 个操作不变，本阶段不动后端契约）。
- `git diff --check` 通过。

新增测试覆盖：候选与工单分页/筛选协议、越界字段与最小投影、`can_accept` 与状态组合的一致性、派工与接单的成功响应断言、非法输入不发出请求、服务端错误码（`40001`/`40100`/`40300`/`40400`/`40905`/`43001`/`43003`/`43004`/`50300`）翻译、未登录不请求、传输失败分类与未配置服务、同键重试与换键、重复点击抑制、切账号与离开页面丢弃迟到响应、确认回调守卫、技师会话跨页共享与退出清理。测试数据均合成，未使用真实微信身份。

**未验收**：真实技师微信登录、真机页面联调、与真实后端的端到端派工→接单链路属 A5.5；完整竞争与安全证据属 A5.4。本阶段证据只证明前端逻辑、协议校验与构建通过，不等于真实登录或页面联调通过。

## 下一步

1. A5.4：按规格 T01–T11 补完整竞争与安全证据（两人派工、并发接单、解绑/停用竞争、故障回滚、缓存权限、历史异常）。
2. A5.5：新建 `docs/testing/LOCAL_TECHNICIAN_DISPATCH_ACCEPTANCE.md`，用合成车主/商家/两名技师身份接真实后端与 MySQL 完成接车→确认→派工→本人查询→接单，并验证另一技师与他店拒绝。
3. 之后先设计争议处理与恢复条件，再推进 A6 防护与报工。

真实相机、真机、测试证书下的直连图片、正式微信/短信/收款/退款与公网环境仍各自待验收。
