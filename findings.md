# 2026-10-14 findings

## R5b final findings

User approved one coupon/issuing store/project, cash-paid commission with existing waiver, full-refund original-expiry return, >=0.01 cash DISCOUNT and standalone noncash SERVICE. V039 adds four tables and permanent creation intent: generic24-hour receipts cannot recreate closed coupon orders. Payment/close/refund convert coupons atomically; persistent recovery rejects contradictory paid/closed facts and defers bad rows. Service code stores digest only, previews actual project, uses current merchant/staff, unique noncash receipt/0 commission, no fake payment or work archive. R5 is a+b; invitation/points rewards are laterR6/R8. Local165 distinct backend/535 frontend cases, builds and88-table replay passed; upload only after wholeR5 closure.

R4 is merged and final main CI is green. Do not repeat historical health/ratings/AI gaps as unimplemented. Coupon/invite/points/assessment/notification base tables exist, but current business Java contains no corresponding complete services. ReservationInput.Booking accepts exactly quote/version/vehicle/slot; no coupon flow. CommissionLedger leaves coupon orders WAIT_COUPON, not implemented coupon exemption. Mine page explicitly postpones welfare/points/community entries; no welfare/index.vue exists. Owner order detail exposes status/pickup/refund/redemption, not full construction evidence or before/after comparison.

Supplied document text equals tracked DELIVERY_V1 after newline/BOM normalization; raw SHA differs only encoding/newlines.63 actual registered pages, no welfare/coupon/invite/point/assessment/notification routes. Existing private order review accepts up to3 photos; only public photo projection is missing. Professional knowledge retrieval/vector/source corpus not present in inspected business code; current AI uses limited SQL context/catalog, so evidence cannot imply complete external RAG. Original cash-discount coupon commission behavior unspecified; plan separates explicit no-fee coupon redemption from cash-order commission policy to freeze beforeR5b.
# R5a implementation findings — 2026-10-14

Existing coupon/user_coupon rows had no trusted issuance source. V038 adds four evidence tables and independent can_coupon (default0); no legacy promotion, policy seed or implicit grant. Published rights frozen, single issuing merchant/project, lifetime one claim per template. Merchant/owner/operator mutations follow current auth, ordered source locks, canonical WriteIntegrityService, atomic audit and receipt. Network ambiguity preserves exact payload/UUID; successful write followed by failed GET closes merchant draft to prevent accidental recreation. UI selects/displays Beijing validity; UTC only on wire. 62 distinct backend targeted tests and 514 frontend tests passed; no R5b trading behavior added.
# R6a findings (2026-10-14)

## R6b 2026-10-15

- Completed local R6b1 configuration, global shop/project lock ordering for multi-coupon publication and generated active-scope uniqueness. Reward issuance remains R6b2; public claim UNIQUE(coupon_id,user_id) unchanged.
- Docker engine initially stopped; desktop CLI started local engine29.6.2, real MySQL tests completed, no skipped database acceptance. Final evidence36 distinct backend/561 frontend/92 tables/74 pages/212 actual/draft OpenAPI operations.

- R6a PR152 exacthead1b2161e, remote six CI successful, backend842/frontend553. User now forbids intermediate upload until all R6 complete.
- R5 coupon_issue has UNIQUE(coupon_id,user_id), so repeat reward issuance requires a separately constrained issuance origin in R6b2; do not silently remove public-claim uniqueness in R6b1.
- User approved owner two-sided reward per valid invite and staff invitee-only reward. Reward config references current published, operator-selected coupon sources; no production seed or fabricated staff beneficiary.

- Remote first full run isolated stale coupon @BeforeAll total-schema assertion; updated88 to91 and35/35 passed. Public Mine invitation action now renders outside private signed-in content gate, preserving first-registration attribution entry. Local total101 backend cases,553 frontend, final builds passed.

- Implemented V040 (91 total tables), nine growth routes, opaque registration record UUIDs and private source-scoped reads; strict login/phone payloads and source flags cannot be client supplied.
- Official evidence gate defaults false, cloud source UNKNOWN; server official endpoint plus same-app verified phone required for formal qualification. Source/phone/month/audit facts atomic, earlier unknown/test evidence never promoted.
- R6b staff rewards need real owner beneficiary provenance; never treat staff_id as user_id. No automatic human-diagnosis unlock without configured source/rule.
- Final focused session gate33/33 passed;66 distinct backend cases in total,553 frontend. Compose bootstrap missing V027-V039 dependencies was identified and corrected alongside V040; remote smoke verifies91 actual tables.

- R6 next after uploaded R5. R6a must reject existing/self/duplicate attribution and freeze owner or staff source at first registration; R6b uses R5 coupon issuance with recoverable reward events.
- User approved each Beijing calendar month incremental 1/2/3/4 targets, cumulative 1/3/6/10, repeated after 10; each qualified new user produces one reward pair rather than tier payouts.
- Current createOwnerOrRead inserts or reads owner by unique openid but records neither new-registration evidence nor invitation context. Legacy inviter_id/invite_record are not reliable proof.
- Actual formal WeChat identity and native share remain R9. Test identities must be explicit and excluded from formal progress/rewards; never infer verification from client flags or timestamps.


- R6b2 V042 public_user_id keeps PUBLIC unique per template/user; INVITE uses unique reward_item_id and batch/role. Qualification snapshots published policy inside original phone transaction; legacy/no-policy event remains UNKNOWN_POLICY. GET is read-only and other-party coupon IDs are null.
- R6b2 fixed published quotas have no top-up API: WAIT_STOCK persists without a partial pair; no automatic replacement/expiry extension. Original issuer/account can recover through durable retries. Formal reward scheduler defaults off.

- R6b2 lock-expiry test failed because its fixed observation budget included worker startup/pre-lock reads; failure snapshots showed both actual LOCK WAIT and not-yet-at-lock states. Added an observed JdbcTemplate checkpoint and reused monitoring connection, keeping the actual database wait assertion. R6b3 accepted monthly eligibility excludes currently disabled invitees and never exposes an operational message endpoint.

- Final review: qualification month and qualified_at previously used separate clock reads and could disagree across Beijing month boundary; one snapshot plus moving-clock DB regression added. Native preview could hide/unmount and unlink its image; use in-page preview, retire in-use album files until completion. Operator forms retained stale CAS revision after confirmed writes; reset on confirmed receipt, including retry and refresh failure.
- Local whole-R6 integration environment: only Maven/MySQL/ClamAV images available after interruption; Docker Hub auth resolution/connect fails for alpine:3.21. Explicit local exclusion of RealUploadAdaptersTest and failed Compose startup are documented; repository six-job CI remains unmodified and mandatory on final uploaded head.

# R7 start

- User authorized full R7 planning and stepwise implementation; no upload until all R7 complete. Start R7a using already reviewed next-plan. ServiceWork currently accepts only merchant/technician scope; existing report/evidence relationships and short-lived signing can support an independently projected owner read.

- R7a first checks exposed numeric JDBC wrapper comparison in safe-file owner/is_deleted checks (replaced Object equality with Number value checks) and frontend predicate returning null for absent signed report (now explicit boolean). HTTP3 and existing work HTTP7 passed; first DB20 had17 pass/1 failure/2 errors, frontend593 had592 pass/1 failure. Failure runs retained; revalidate after fixes.

- R7a private URL boundary: existing storage signer returns fractional Java Instant and maximum300 seconds. Client must accept fractional UTC expiry, reauthorize each open, and isolate preview-request generation from page generation so stale expiry callbacks cannot clear a newer photo. Signed URLs already delivered remain usable until original expiry; no instant revocation claim. Protection lacks frozen uploader field, so require unique relation plus current original-shop merchant/staff uploader; report photos match historical technician_id.

# R7b start

- Existing merchant/technician lists are separate order screens, not aggregated workbenches. TechnicianAssignments.own rechecks current assignment; accepted-only is needed for private archives. Current schema has no order-scoped private archive consent, so user explicitly approved per-record grants/revocation with no default sharing. Archive types1/2/10 are maintenance/repair/fault; structured detail is separate owner/version table. New V044 grant table will default empty; source fingerprint prevents changed records from inheriting prior consent.

## R7b本地收口

用户确认逐单逐条授权，已实现工作台、车型/本单资料最小投影及档案源指纹/CAS/撤销；自动归档来源和签名IO前后复核。最终47后端、606前端、微信H5、96表重放通过，79页/230操作；原141追踪/44基线/根README和首路由保留。文档见docs/progress/R7B_WORKSPACE_EXECUTION.md。下一R7c，整个R7完成前不上传。

## R7c1进行中

按已授权整R7计划推进六项履约事实的事务事件、去重投递与三身份最小收件箱/已读；设计见docs/progress/R7C1_NOTIFICATION_DESIGN.md。现有notification仅基线表，无实际收件箱。无历史回填，诊断权限及持久恢复后续，整个R7完成前不上传。

## R7c2规则已确认

用户采用独立授权运营诊断方案、可受理/离线留言/暂停受理三态及来源说明，提交事务重验当月资格，仅共享主动文字与车型，历史档案另行授权。下一步契约见docs/progress/R7C2_DIAGNOSIS_NEXT_PLAN.md；先完成R7c1兼容回归，不提前上传。

### R7c1兼容夹具修复

最终相关回归中JdbcPickupInspectionTest仅加载到V013，事件埋点需要的V012派工/V045事件表均未加载，导致1失败/12错误。已定位缺表SQL异常，将该夹具改为完整排序迁移并保留V010/V011/V013重复执行，后续仅重跑受影响接车组；生产代码不跳过事件写入。支付35、核销17、券事务37已通过。

## R7c1最终本地验收

相关140项后端覆盖通过：通知31/HTTP4/支付35/接车16/核销17/券事务37。扩展首跑接车缺V012/V045表导致1失败12错误，改完整迁移保留原重复检查后16/16重跑BUILD SUCCESS（2026-10-15T12:14:13Z）；其余124首跑通过。614前端、微信H5、97表重放、236 OpenAPI/80路由及引用链接通过。Compose V045挂载复制时目标重名已在差异复核修正，init+V002至V045共45唯一有序目标验证通过，config --quiet通过，未宣称整栈Compose运行。R7c2规则已获确认且计划已写，尚待实现；所有R7代码/文档留本地。

## R7c2开始实施

用户已确认运营诊断方案，契约见docs/progress/R7C2_DIAGNOSIS_DESIGN.md。按独立权限/配置、车主留言/分配回复、页面/验收推进；管理队列无正文，只有当前获派且有处理权限者可读正文；所有成果保留本地至整R7完成。

## R7c2本地完成

独立权限/来源三态受理、当前R6资格与车辆复核、车主文字/当前获派处理、分配/追加/答复/关闭、专用事件/审计/长期提交意图、16API及4页面已实现。首次59初始化错误为合成车牌过长，修正夹具后首轮72通过；补当前读取/控制锁次序/真实锁等待和并发发布后最终111/111（61诊断含48继承+13新增、HTTP5、运营身份8、券事务37），626前端及微信H5、V046/102表迁移通过。84路由/252操作，原基线保留，阶段API/运维/验收已整理。下一R7c3持久恢复和诊断/补充业务消息；所有代码和文档仅本地，整个R7完成后再上传。

### R7c2最终授权入口与收口复核

现有离线operator-admin补齐诊断处理/管理独立授予撤销，必须显式环境布尔、无HTTP，变更与审计/会话撤销原子。新增3项定向真实MySQL测试通过，未重复计入其8继承测试；加主组111，合计114不同后端用例覆盖。诊断专属账号登录落点已修正，最终微信/H5构建通过；626前端、102表重放，252操作/全部引用、84路由/首路由、文档链接、46有序唯一SQL挂载/Compose config、141追踪/44基线/README不变通过。所有成果仍仅本地。

R7c3 检查：诊断流程原 pending 只在闭包内，隐藏保留但页面重建/重启丢失；车主和运营登录响应均有服务器账号 ID，但内存会话未保留。手机号绑定 setOwnerSession 调用无 ID，需保留同令牌已有 ID。

## R7c3 第一步完成
诊断显式草稿、稳定账号 ID、服务器复核后原请求持久恢复及多页冲突保护已实现。初次638项1失败定位运行时同步存储漏透传，补正确透传与装配回归，最终641/641、微信/H5通过。新14恢复/身份+1运行时断言；无后端或迁移改动，无R7上传。下一步补诊断/退款/异议消息，再统一门禁。详见docs/progress/R7C3_RECOVERY_EXECUTION.md。

R7c3 第二步发现：原(kind,order_id)唯一会吞掉重复退款申请和多轮异议处理。V047 新增 source_key（旧事件ONCE），退款用ID/revision、异议用追加记录、渠道结果用ID/结果去重。所有埋点位于真实业务事务，不扫描状态回填。读取脚本时猜测了不存在的V042文件名和JdbcRefundsTest，已改用实际JdbcOrderRefundsTest及文件清单，未改业务阈值。

R7c3 第二步数据库新测试发现：已生成9条事件但固定业务时钟下只投递2条；next_attempt_at原由MySQL默认墙钟生成，与恢复扫描db.now不是同一时源。改为入队显式使用业务db.now（不放宽9条断言），避免跨主机时钟偏移造成延迟。同时来源捕获用当前锁定读取，避免先前RR读视图取旧接收人，新增真实双连接回归。最终定向重跑覆盖这些修复及新补关单/旧结构升级测试。

诊断已有五类 case_event，但无投递接收人。现有 notification 支持 VARCHAR24 recipient_type，能复用 user/operator_account；用独立来源/用途表区分当时处理/管理接收人，同账号去重。读写统一 control 锁避免运营已读锁账号后等待病例、病例写反向等待账号形成死锁。

R7c3 诊断消息首轮127项126通过、1失败：新已读测试将不同幂等键的新请求 request_id 也要求相同，业务 data/read_at 实际一致。修正为同键比较完整回执、新键仅比较业务data，仍严格验证首次审计一次及审计失败回滚，定向重跑该方法。无生产权限或事务逻辑放宽。前端648、微信/H5、V048/104表重放已过。额外给车主角色页直接入口，已有答复不依赖邀请资格接口成功才能进入。

## R7c3 第三步本地完成
五类诊断事务消息、V048独立来源/捕获用途、四API、车主/运营消息页及队列导航已实现。首轮127中126通过，1项新键request_id比较错误已纠正，同键完整回执/新键业务事实/仅一次审计/审计失败回滚定向1/1通过，合并去重127不同用例全部通过；648前端、最终微信/H5、104表重放、85路由/首路由、256操作/全部引用、48有序唯一SQL挂载/Compose config通过。下一按R7_COMPLETION_NEXT_PLAN.md核对恢复/登录回跳等销项和统一门禁，通过后再同批上传全部代码/文档。当前无上传、合并或发布。

整R7本地上传镜像再次构建失败：auth.docker.io连接超时，原Dockerfile/校验未放宽。启动除RealUploadAdaptersTest外全部后端，其余验证继续；真实上传/完整Compose需可达镜像或最终远程CI验证，不冒称本地全套通过。


## 整R7收口补缺与统一门禁进展
登录回跳、技师原请求/显式草稿、身份ID先于token发布、诊断配置23小时安全窗口及比较清理、共享履约未知请求冻结均已实施。664前端、微信/H5、四兼容Web、104表真实迁移重复验证和Compose config通过；完整其余后端运行中。销项、恢复说明、验收、执行及R8计划已整理，暂未上传。
过程记录：旧共享写测试曾期望未知后改正文换新键，首轮两项失败，已按防重复要求修正生产行为及原键断言；UI批量插入遇额外root属性断言，改精确匹配后构建通过。静态挂载检查误把init.sql当V001漏报，按真实001-init+V002–V048校验；身份帮助器直接运行不能导入scripts包，改python -m unittest scripts.test_check_auth_readiness，6项通过。PowerShell通配符路径及猜测文件名读取失败，改rg实际文件后读取，无业务修改。真实上传基础镜像认证超时未放宽镜像/校验；全后端只排除RealUploadAdaptersTest。

整R7全部功能、收尾及文档已齐备。因本机Docker Hub阻断真实上传/Compose，调整最终门禁为完整R7一次性上传后在原有六项CI执行，本地其余全套后端继续；无中间子阶段push。AST确认26个变更Vue内24处重试绑定有效（最初简单正则漏掉逗号声明，改编译器绑定检查）。47份变更文档本地链接通过，85路由/256操作及832引用、48挂载和原基线保留通过。
