# 单测试号三角色小程序

这是 S0 的 uni-app 3（Vue3）工程。一个微信小程序测试号用于预览车主、商家和技师三个角色入口；原始交付文档中的角色职责不变。PC 运营后台由其他团队负责。

## 从源码生成可导入的小程序

源码位于 `apps/miniapp/src`。微信开发者工具需要 uni-app 编译生成的 `app.json` 等文件；源码仓库根目录没有这些文件。C 盘或其他盘都可以构建，产物总是在**执行构建的那份仓库**的 `apps/miniapp/dist` 下。

在包含根 `package.json` 的仓库目录运行（项目 CI 使用 Node.js 22）：

```powershell
npm ci
npm run build:miniapp
Test-Path .\apps\miniapp\dist\build\mp-weixin\app.json
```

最后一行应返回 `True`；同目录还应有 `project.config.json`。在微信开发者工具中关闭之前导入的错误项目，选择“导入项目”，把**项目目录**设为这份仓库的 `apps/miniapp/dist/build/mp-weixin`，再点击“编译”。不要选仓库根目录、`apps` 或 `apps/miniapp/src`。若开发者工具显示“在项目根目录未找到 app.json”，先确认导入路径和上面的 `Test-Path` 结果；这条报错不代表 uni-app 源码编译失败。

需要热更新时，在仓库根目录持续运行 `npm run dev:miniapp`，改为导入 `apps/miniapp/dist/dev/mp-weixin`；终端停止后，先重新运行开发命令。测试号 AppID 已写入 `src/manifest.json`，编译后也会出现在产物的 `project.config.json`。它是公开项目标识，**AppSecret 不应放入此工程、前端环境变量、构建产物或 Git**。

身份核心、车主五 Tab、商家身份、页面状态及上传基础能力已整合至 `main`。在另一份仓库更新 `main` 后重新执行上述构建命令，即可生成对应小程序产物；编译产物不会提交到 Git。

联调后端时，将 `.env.example` 复制为本目录 `.env.local` 并设置 `VITE_API_BASE_URL`。常规真机使用合法 HTTPS 地址，本机开发者工具可使用回环 HTTP 和临时域名校验例外。**真机（测试号）联调**用仓库根目录的 `node scripts/miniapp_lan_helper.cjs --apply --build` 把地址写成电脑的局域网地址并重建产物；用完执行 `--restore` 回到回环地址。细节见[真机真实微信登录清单](../../docs/operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。没有服务端地址时，登录按钮会提示不可用；后端已实现 `/api/auth/wx-login`、技师绑定、车主手机号绑定、刷新与退出。技师待绑定状态可输入商家发放的员工码；车主可通过微信手机号按钮绑定，但主体能力与额度须另行核对。商家已有账号密码与短信码表单，生产发送器未接入，验证码请求当前返回 503。本机私有配置及真实微信 code 联调已通过，真机、手机号与短信继续按[验收清单](../../docs/operations/AUTH_INTEGRATION_RUNBOOK.md)记录。

微信云托管（免配通讯域名）模式：改为配置 `VITE_WECHAT_CLOUD_ENV_ID` 与 `VITE_WECHAT_CLOUD_SERVICE`，所有后端请求改走 `wx.cloud.callContainer`，登录由网关注入的 `X-WX-OPENID` 完成，不再调用 `wx.login`，也无需在小程序后台配置服务器域名。两个变量同时非空才生效，否则完全沿用上一段的 `uni.request` 通道。要求基础库 ≥2.23.0。部署见 [`deploy/cloudrun`](../../deploy/cloudrun/README.md)，验收见[云托管登录清单](../../docs/operations/CLOUDRUN_LOGIN_RUNBOOK.md)。

`src/pages/index` 是测试入口，`src/pages/owner`、`merchant`、`technician` 为三个角色模块；车主另有首页、服务、AI、档案、我的五个原生 Tab。入口切换不代表登录或授权。微信登录的调用边界在 `src/services/wechat-auth.js`，请求通道在 `src/services/api-runtime.js`（云托管走 `callContainer`，否则走 `uni.request`）；商家使用账号密码加短信码。首页、服务、档案与 AI Tab 已接真实接口，商家和技师工作流仍待真实接口接入。

正式采用三个独立 AppID 时，应先建立 `(app_id, openid)` 身份映射，再分别配置、构建与真机验证三个目标小程序。测试号构建不等于支付、提审或正式发布通过。

## 页面状态与离线验证

三角色身份入口统一展示加载、成功、断网、超时、401/403、429 和服务不可用状态。发送验证码实际成功后才提示已发送；失败退出保留会话以便重试，身份被拒绝时可主动重新登录。车主会话仅保存在内存，五 Tab 共享该会话；业务列表未接入时显示待接入，不能当作真实空数据。

在仓库根目录执行 `npm test --workspace @autocare/miniapp` 运行认证请求与状态测试（使用离线运行时替身，不需要 AppSecret、数据库或短信服务商）。此测试已加入小程序 CI。

H5 手工验证：设置仅用于本次本机开发服务的 `VITE_API_BASE_URL=http://127.0.0.1:4317`，运行 `npm run dev:h5 --workspace @autocare/miniapp -- --host 127.0.0.1 --port 4317`。用 gstack browse 新建 `http://127.0.0.1:4317/#/` 标签页后，在仓库根目录执行 `browse eval apps/miniapp/test/browser-flow.js`。脚本暂时替换该浏览器页面的 uni 登录和请求响应，结束时恢复；测试文件不被应用导入。页面测试不能代表真实微信或短信联调。

微信模拟器路由验证：用已安装开发者工具的 `cli.bat auto --project <当前仓库的 apps/miniapp/dist/build/mp-weixin 绝对路径> --port 11927 --auto-port 9420 --trust-project` 打开编译产物，再从仓库根目录运行 `node apps/miniapp/test/simulator-smoke.cjs`。它检查测试入口、三角色和五 Tab 的实际页面路径，并将当前车主页截图保存至被 Git 忽略的 `test-results/wechat-owner.png`。此流程不调用真实登录或发短信。

## 档案图片操作

PR #13 接入档案 Tab 的选图、上传、原图同键重试及本人短时预览，依赖 PR #12 的 [HTTP 图片接口](../../docs/api/UPLOAD_HTTP.md)，需正式车主会话、V004 和真实上传适配器。一次一张 JPG/PNG/WebP，最大 10 MiB；服务器再次校验。上传成功不等于档案创建；关闭小程序或重新登录清除页面信息，不表示删除服务器已上传对象。

同次选图固定 UUID，503/断网/超时后使用原图片重试，换图换键。每次预览重新获取 HTTPS 签名，不持久化链接；签名过期可重试。401/403 引导重新登录；切换账号清空图片，页面隐藏中止操作并忽略迟到结果。内存状态不提供跨重启恢复。

新增图片离线测试与身份测试合计 27 项。H5 页面复现：在上述仅本机开发服务配置下，用 gstack browse 同一会话打开本机首页并执行 `browse eval apps/miniapp/test/image-browser-flow.js`，13 项交互检查使用 uni 响应替身，结束恢复原方法。脚本仅存在测试目录，不被生产页面导入。微信模拟器目前验证九条路由加载，未完成真实选图、微信授权或私有图片上传；真机验收须另行记录。

## 本人车辆与手动录入（S1）

PR #14 在档案Tab接入本人车辆列表和 `/pages/vehicle/manual`。四级选择真实数据库中的有效车型，车型为空禁用保存；里程必为非负整数，车牌与VIN可选并由服务端再次校验。使用[车辆接口](../../docs/api/VEHICLE_MANUAL.md)，仍需正式车主登录、数据库与可访问的后端。生产车型来源尚未确定；禁止以测试种子充当生产数据。

保存失败沿用原UUID及正文，修改正文换键；账号切换清除表单和旧列表，页面隐藏忽略迟到结果。列表只有脱敏车牌/VIN，支持分页及失败重试。此步没有档案创建、车辆修改、车牌匹配、OCR或VIN解码。

当前39项Node测试通过；`vehicle-browser-flow.js` 用实际H5选择器与测试uni响应验证11项交互。测试须从新加载页面开始（例如本机URL添加唯一查询串），避免内存身份残留；结束恢复uni方法。微信模拟器脚本新增手动页，共10条路由，仍只是路由加载验证。编译产物重新构建并从当前源码仓库的dist目录导入。

## 当前真实登录与后续验收

2026-10-05 已在当前工作树构建产物中用真实微信 code 完成车主按钮登录、技师绑定、刷新与退出。2026-10-07 起，本机后端改为 `0.0.0.0:18080` 发布、产物指向电脑局域网地址，使**测试号真机预览**也能走真实微信登录；开发者工具临时跳过域名校验仍可继续本地开发。手机侧仍需按网络条件验收（校园网可能有客户端隔离，建议用电脑移动热点）；常规真机与发布需符合微信 HTTPS/通讯域名要求，详见[网络说明](../../docs/operations/MINIAPP_NETWORK_ENVIRONMENTS.md)与[真机登录清单](../../docs/operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。

当前包含手动档案、首页摘要和拍照入口：手动默认 `input_type=3`，拍照为 `1` 且至少一张上传成功图片，最多五张；服务端复核本人车辆及 CLEAN 图片。真实相机、私有上传和有数据多车业务尚未验收。[下一步计划](../../docs/progress/NEXT_STEPS.md)从本地业务联调开始，[登录验收表](../../docs/operations/AUTH_INTEGRATION_RUNBOOK.md)继续记录未覆盖项。

2026-10-06：本机官方库扫描、MinIO 和真实后端的 H5 图片交互 11 项通过，详见[复现说明](../../docs/testing/LOCAL_PRIVATE_IMAGE_ACCEPTANCE.md)。使用合成 PNG 填充原生文件输入、真实上传与签名字节预览；不代表物理相机或微信真机已验收。

标准服务 Tab 已接分类/分页列表、参考价与详情，具备失败重试和身份切换隔离；本机 H5/真实会话验证见[复现说明](../../docs/testing/LOCAL_SERVICE_CATALOG_ACCEPTANCE.md)。商家报价与预约另行接入。

2026-10-07：车主端 AI 管家已接入 DeepSeek，代码在 `src/services/ai-chat.js`、`src/services/ai-chat-flow.js` 与 `src/pages/ai/index.vue`，复用同一传输层，请求超时 60s。选择爱车后后端注入本人车辆与最近 5 条档案上下文（车牌/VIN 不进入上下文），未选车按通用知识回答；未配置 `DEEPSEEK_API_KEY` 时接口返回 `50301`，页面显示降级提示并支持原样重试，换车后自动开新对话。真实密钥已配置并通过本机端到端 19 项验证（选车后回答实际复述了档案内容，追问车牌未泄露）；真机提问待验收。启用步骤、安全边界与 A0–A8 状态见 [AI 管家接入清单](../../docs/operations/AI_CHAT_RUNBOOK.md)，接口契约见 [AI 管家接口](../../docs/api/AI_CHAT.md)，离线测试见 `test/ai-chat.test.js`。
