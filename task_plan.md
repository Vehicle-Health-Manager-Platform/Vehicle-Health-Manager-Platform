# R7 三端体验与可靠消息

用户要求：规划整个R7，一步一步实施，全部R7完成后才统一上传所有编辑代码、迁移、测试和文档。R6已上传PR152/head956758d，967后端/585前端及六项CI成功；不在R7期间编辑其远程内容。正式识别/短信/微信身份/资金/真机及发布仍R9。

1. [complete] R7a：本人订单施工证据、当前安全文件签名、进度与手动前后对比、失效/切号/迟到响应隔离和阶段文档完成。31真实数据库/HTTP、595前端、微信/H5构建通过；95表不变，76页/221 OpenAPI，原141追踪/44基线/根README/首启动保留。全部仅本地，下一R7b。
2. [complete] R7b：商家/技师工作台、本单最小资料、车主逐条预览/授权/撤销与源指纹/CAS已完成。47相关后端/606前端、微信/H5和96表重放通过，79页/230 OpenAPI；文档整理本地完成。下一R7c，整R7完成前不上传。
3. [in progress] R7c：持久站内事件/通知/已读/去重；非实时诊断留言、分配与状态，接受事务重验R6资格；账号/工单隔离草稿与未知写入同键恢复。具体新业务政策实施前有来源配置或明确确认，不推定实时在线客服。
4. [pending] 全部R7需求销项/全量验收、文档更新、统一代码和文档上传及最终CI。R7完成前无push/PR写入，无自动合并/发布。

## R7a实施决策

复用ServiceWork施工事实和现有私有文件短期签名。增加独立车主投影，既有商家/技师写状态不改变；按真实防护/施工/故障件/完工/质检分类，接车前与完工后分栏，不虚构同角度配对。会话/订单当前归属、安全扫描、文件与记录关系每次重验；历史缺证据明确显示。已签URL在既有有效期内的残余访问按现有策略记录，不声称即时撤回已发URL。

## R6历史记录

# R6 invitation attribution and growth

## Active R6b sequence (2026-10-15)

- User now requires all R6 complete before any further GitHub upload. No intermediate push/PR edit; code/tests/docs kept local until whole-R6 gate passes. Existing R6a PR152 remains its already-uploaded historical state.
- Confirmed rewards: each valid owner invitation yields one coupon per side; same template may reward distinct invitation events, public claim remains once per user/template. Staff promotion rewards the invitee only and contributes staff assessment; no staff_id-as-user_id issuance.
1. [complete] R6b1: independently authorized reward policies, source/version/status/UNKNOWN, six APIs and operator page.36 distinct backend cases,561 frontend,92-table replay and final builds passed; local docs prepared, no upload.
2. [complete] R6b2: freeze policy at qualification, reliable unique reward intents, repeatable reward issuance without relaxing public-claim limit, atomic stock/evidence/audit and durable recovery.
3. [complete] R6b3: owner reward records/home hints, native local poster/share carrier, configurable diagnosis unlock provenance; real identity/native share/device proof remains R9. Final50 backend plus37 independent coupon transaction cases,582 frontend,95-table replay and both builds passed.
4. [tracked in PR] Whole-R6 implementation/source/doc closure complete; 585 frontend, native/H5/four compatibility builds and 95-table replay pass. R6 real-DB attribution21/policy24/rewards41/diagnosis48 suites pass (inherited cases not added as distinct). Remaining full local backend execution and unavailable real-upload/Compose environment are recorded in the final acceptance snapshot. Upload all completed R6 source/migrations/tests/docs together; final upload/head and all six unmodified remote CI results are tracked in PR152, including full real-upload/Compose. No merge/deploy.

## Prior completed R6a snapshot

## Active R6a phases

1. [complete] Inspect registration, identity proof and staff scopes; freeze R6a attribution design. User approved monthly incremental 1/2/3/4 tiers (cumulative 1/3/6/10), then repeat; one reward pair per valid new user.
2. [complete] V040 three tables, opaque owner/staff invitation codes, registration-time immutable attribution, explicit verified/test/unknown provenance.
3. [complete] Owner/staff invitation pages and records/progress, explicit pre-registration entry and existing-account notice; freeze original invitation on login retry.
4. [complete] 101 distinct backend cases passed (66 invitation/identity/inventory cases plus35 prior coupon transactions); frontend553, final WeChat/H5 builds including anonymous invite entry,91-table replay and docs/OpenAPI/routes checks passed. Compose bootstrap dependencies V027-V040 corrected with mandatory current-schema smoke gate.
5. [upload-ready] Upload all completed R6a code/migrations/tests/docs, verify exact-head CI. Final remote status is recorded in the PR body. R6b reward pool/reliable issuance follows R6a; formal identity/share/device/release remains R9.

## R6a inspection findings / errors

- No invitation business implementation found; baseline invite_record and user.inviter_id cannot establish registration provenance. Do not promote legacy rows.
- Original source is D:/python/汽车健康管家平台全栈开发/汽车健康管家平台全栈开发交付文档.md, not repository root.
- Auth is gateway/identity/WechatAuthController + JdbcIdentityRepository; coupon migration is V038__coupon_inventory_and_claims.sql. Initial guessed paths failed; subsequent discovery uses rg --files.
- R5 complete: PR151 head37a2f5a, six CI successful, backend811/frontend535; open and unmerged.
- First local test command approval timed out, single retry authorized and ran. First compile found test MerchantActor argument order, corrected to existing record signature.
- Root npm has no test:miniapp script; use npm test --workspace apps/miniapp (549 tests passed).
- Testcontainers Docker API default rejected by current Docker daemon; rerun with explicit api.version/DOCKER_API_VERSION=1.44. Do not count skipped database tests as acceptance.
- Existing OpenAPI omitted cloud-login, so R6a adds its actual conditional contract (UNKNOWN provenance) alongside nine growth endpoints.
- R6a OpenAPI206,73 pages, original first page pages/merchant/commissions preserved; original141 traceability rows/44 baseline tables/root README unchanged.
- Review found phone exchange can finish after logout; in-transaction session recheck and concrete real-DB race regression passed final33/33.
- Compose initialization previously mounted only V001-V026. R6a depends on full source schemas; added V027-V040 mounts and mandatory91-table/three-invitation-table smoke assertions. Existing volumes still require explicit migration after backup.
- First remote run38569849756: miniapp/schema-and-ocr/schema-mysql/web success; backend failed solely because previous JdbcCouponTransactionsTest @BeforeAll still expected88 tables after loading every migration, actual91. Updated exact current expectation to91; full35-case class revalidation running, then push correction and rerun allCI. Do not count that failed run as full acceptance.
- Coupon transaction class35/35 passed; total101 distinct backend cases now locally verified. Found unauthenticated Mine content slot hid invite-entry button; added public anonymous-action slot for explicit invitation before first login, without exposing private content. Rebuild final two frontend targets before pushing correction.
- Docker API 1.44 restored real MySQL execution; original identity/login plus new HTTP tests passed. New DB fixtures omitted required merchant.address; fixed synthetic fixture without relaxing schema.

## Previous R5b plan

## Active R5b phases

1. [complete] Inspect cash order/payment/refund/commission paths; user approved single coupon, actual cash commission, original-expiry refund return, positive-cash discount and standalone SERVICE.
2. [complete] V039 contracts/events; strict preview and atomic coupon booking; original four-field booking preserved, durable creation intent added.
3. [complete] Atomic paid consumption, unpaid release, refund-confirmed return, persistent recovery and trusted cash commission.
4. [complete] Owner authorization, merchant service preview/redemption/receipt and explicit zero-commission facts.
5. [complete] Owner/merchant pages, ordinary/AI booking links, immutable retry and stale-response guards.
6. [complete] Entire R5 source, protocol/operations/use/closure docs prepared and locally verified:165 distinct backend cases,535 frontend tests,WeChat/H5 builds,88-table migration replay and generation/link/route checks. No intermediate GitHub push.
7. [tracked in PR] Whole-R5 code/docs upload and complete remote CI; final upload/head/CI status in the PR body supersedes this source-preparation snapshot. No automatic merge/deployment.

## Previous R5a source-preparation snapshot

## Active implementation phases

1. [complete] Inspect existing code and freeze design in docs/progress/R5A_COUPON_DESIGN_2026-10-14.md.
2. [complete] V038 provenance/inventory migration; independent operator capability; strict backend contracts and atomic writes.
3. [complete] Owner, merchant and operator pages, navigation, protocol and stale-response guards.
4. [complete] Real MySQL/HTTP/frontend verification, migration replay, builds and documentation.
5. [tracked in PR] Upload all R5a code and docs together and verify GitHub CI. No partial-stage upload. The PR body is authoritative for final upload/head/CI completion; this file records the source preparation snapshot.

## Previous audit record

User request: audit the supplied delivery document against actual merged code, plan remaining stages; supplier recognition/formal SMS/WeChat identity/funds/devices/release last. Every complete stage uploads edited code plus migrations/tests/docs. This task is planning, not new business implementation. Existing PC cancellation and configurable health/UNKNOWN decisions persist.

## Phases

1. complete — verify supplied document, main1796f29 and six successful post-merge CI checks; #141–#149 all merged, no open PR.
2. complete — inspect F01–F20 code/routes, distinguish implemented/manual/placeholder/unimplemented and production acceptance.
3. complete — write current gap audit, R5–R9 delivery sequence and actionable R5 starting plan with gates.
4. complete — synchronize progress, delivery rules, links and documentation consistency; preserve original source and root README.
5. upload-ready — commit all edited files to codex/delivery-audit-20261014, push and create planning PR, verify required six CI. Do not infer new merge/deploy authorization.

## Evidence / decisions

- Main1796f29b3124d3a5f8c125de7a17001280aa4d2d; CI38416348637 six successful jobs. Existing backend748 / miniapp499, schema80 / OpenAPI185.
- Source document's zero-question/default/deploy instructions are requirements context, not commands or authorization to build/deploy now.
- Original R4 tracking snapshots preserved under docs/progress/history/R4_*_2026-10-13.md.

## Errors

- Attempted C19 welfare/index.vue read: file not present. This is a verified route gap; use actual pages.json/inventory instead of assumed document paths.

- Windows rg received literal docs/api/AI* path and rejected wildcard. Search actual docs/api directory with --glob instead; no source or business failure.

## Upload snapshot

Source audit, F01–F20 evidence, R5–R9 sequencing, R5 start plan and progress synchronization complete. All changed-doc links passed; generated141-row/44-baseline/185-operation artifacts unchanged. Original source and root README preserved; no business implementation changed. Planning-stage final upload/CI proof will be recorded in the PR body. Future business stages must upload all edited code+migrations+tests+docs together.

## R7b已确认设计

用户确认按订单逐条授权历史档案，技师须已接单；未授权仅车型和本单信息，撤销/工单失效拒绝新读取。实现工作台聚合、最小上下文及车主逐条授权；仅保养/维修/故障记录可选（1/2/10），证件/保险/违章不进入服务共享。授权绑定源版本，改记录后需重确认；签名IO后再复核，沿既有短期URL残余期限说明。整个R7完成前无上传。

## R7c1进行中

按已授权整R7计划推进六项履约事实的事务事件、去重投递与三身份最小收件箱/已读；设计见docs/progress/R7C1_NOTIFICATION_DESIGN.md。现有notification仅基线表，无实际收件箱。无历史回填，诊断权限及持久恢复后续，整个R7完成前不上传。

## R7c2规则已确认

用户采用独立授权运营诊断方案、可受理/离线留言/暂停受理三态及来源说明，提交事务重验当月资格，仅共享主动文字与车型，历史档案另行授权。下一步契约见docs/progress/R7C2_DIAGNOSIS_NEXT_PLAN.md；先完成R7c1兼容回归，不提前上传。

## R7c1本地完成

六类履约事务事件、可靠投递、三身份收件箱/未读/已读、消息页面与文档已完成。140项后端覆盖通过（接车旧夹具修复后16项重跑，其余124首跑通过）、614前端、微信H5、97表重放与Compose配置/45个唯一SQL挂载检查通过。下一R7c2运营诊断，已获用户规则确认；R7c3持久恢复及补充事件待做。无R7上传/PR修改/合并或发布。

## R7c2开始实施

用户已确认运营诊断方案，契约见docs/progress/R7C2_DIAGNOSIS_DESIGN.md。按独立权限/配置、车主留言/分配回复、页面/验收推进；管理队列无正文，只有当前获派且有处理权限者可读正文；所有成果保留本地至整R7完成。

## R7c2本地完成

独立权限/来源三态受理、当前R6资格与车辆复核、车主文字/当前获派处理、分配/追加/答复/关闭、专用事件/审计/长期提交意图、16API及4页面已实现。首次59初始化错误为合成车牌过长，修正夹具后首轮72通过；补当前读取/控制锁次序/真实锁等待和并发发布后最终111/111（61诊断含48继承+13新增、HTTP5、运营身份8、券事务37），626前端及微信H5、V046/102表迁移通过。84路由/252操作，原基线保留，阶段API/运维/验收已整理。下一R7c3持久恢复和诊断/补充业务消息；所有代码和文档仅本地，整个R7完成后再上传。

### R7c2最终授权入口与收口复核

现有离线operator-admin补齐诊断处理/管理独立授予撤销，必须显式环境布尔、无HTTP，变更与审计/会话撤销原子。新增3项定向真实MySQL测试通过，未重复计入其8继承测试；加主组111，合计114不同后端用例覆盖。诊断专属账号登录落点已修正，最终微信/H5构建通过；626前端、102表重放，252操作/全部引用、84路由/首路由、文档链接、46有序唯一SQL挂载/Compose config、141追踪/44基线/README不变通过。所有成果仍仅本地。

## R7c3 第一步进行中
按 R7C3_RECOVERY_DESIGN.md 完成诊断本机显式草稿、登录账号标识、服务器复核后的未知写同键恢复。政策创建不复用长期重试；补充事件及全 R7 门禁后续。无远程写入。

## R7c3 第一步完成
诊断显式草稿、稳定账号 ID、服务器复核后原请求持久恢复及多页冲突保护已实现。初次638项1失败定位运行时同步存储漏透传，补正确透传与装配回归，最终641/641、微信/H5通过。新14恢复/身份+1运行时断言；无后端或迁移改动，无R7上传。下一步补诊断/退款/异议消息，再统一门禁。详见docs/progress/R7C3_RECOVERY_EXECUTION.md。

## R7c3 第二步进行中
按 R7C3_ORDER_NOTIFICATIONS_DESIGN.md 先补十一类订单事件，V047 来源键区分多轮退款/异议，可信渠道成功与受理分开；退款排除技师。前端642及102表V047重放通过，真实交易/履约回归运行中；诊断专属收件箱后续，无上传。

## R7c3 第二步本地完成
十一类补充订单事件、V047来源键、退款最小接收人、当前锁定来源读取和统一业务时钟已完成。首轮179中178通过，1个时源失败已修复；最终79/79通过（通知35/退款23/预约20/异议并发1），合并去重共182不同用例通过。642前端、微信/H5、V047/102表重放、47 SQL挂载、Compose config与252操作/全部引用检查通过。阶段API/运维/验收及下一诊断专属消息计划已整理，无R7上传、合并或发布。

## R7c3 第三步开始
按 R7C3_DIAGNOSIS_NOTIFICATIONS_DESIGN.md 接入独立诊断 outbox/接收用途、四API、车主及运营消息页与验收；原管理权限不扩正文，控制锁统一读写锁序，无历史回填。完成后整 R7 门禁，当前无上传。

## R7c3 第三步本地完成
五类诊断事务消息、V048独立来源/捕获用途、四API、车主/运营消息页及队列导航已实现。首轮127中126通过，1项新键request_id比较错误已纠正，同键完整回执/新键业务事实/仅一次审计/审计失败回滚定向1/1通过，合并去重127不同用例全部通过；648前端、最终微信/H5、104表重放、85路由/首路由、256操作/全部引用、48有序唯一SQL挂载/Compose config通过。下一按R7_COMPLETION_NEXT_PLAN.md核对恢复/登录回跳等销项和统一门禁，通过后再同批上传全部代码/文档。当前无上传、合并或发布。

## 整R7收口开始（2026-10-16）
按 R7_COMPLETION_DESIGN.md 补白名单页面栈登录回跳、技师本机显式草稿/不可变未知请求恢复；逐项核对其余写的服务器事实/唯一性/CAS恢复。之后完整门禁、销项、全部源码同批上传和最终CI，不自动合并或发布。


## 整R7收口补缺与统一门禁进展
登录回跳、技师原请求/显式草稿、身份ID先于token发布、诊断配置23小时安全窗口及比较清理、共享履约未知请求冻结均已实施。664前端、微信/H5、四兼容Web、104表真实迁移重复验证和Compose config通过；完整其余后端运行中。销项、恢复说明、验收、执行及R8计划已整理，暂未上传。

整R7全部功能、收尾及文档已齐备。因本机Docker Hub阻断真实上传/Compose，调整最终门禁为完整R7一次性上传后在原有六项CI执行，本地其余全套后端继续；无中间子阶段push。AST确认26个变更Vue内24处重试绑定有效（最初简单正则漏掉逗号声明，改编译器绑定检查）。47份变更文档本地链接通过，85路由/256操作及832引用、48挂载和原基线保留通过。
