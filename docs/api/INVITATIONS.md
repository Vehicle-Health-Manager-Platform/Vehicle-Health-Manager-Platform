# R6a 邀请归因与资格协议

版本 `R6A_INVITE_V1`，迁移 V040。九个增长接口已实现；登录携码与手机号资格联动已实现，双向奖励到账属于下一 R6b，正式渠道/原生分享属于 R9。

## 来源与资格

邀请码为服务端生成的 32 位小写随机十六进制值，与员工登录绑定码分离。车主、当前有效店长/店员各有一个稳定码。GET 不创建，POST 同一人不同键或通用幂等回执到期后仍返回原码，不生成重复推广源。审计只记资源与来源类型，不写推广码。

`POST /api/auth/wx-login` 严格正文 `{code,role,invite_code?}`；原 `{code,role}` 兼容。invite_code 仅 role=owner，必须是 32 位小写十六进制，不接受 null。重复字段、尾随 JSON、verified/new_user/test_mode/user_id 等字段拒绝。只有服务端真正首次 INSERT 的 openid 记录归因；已有账号重新登录不会新增或修改注册账本。无效/失效邀请码不阻断正常注册，自己的注册记录给出 NO_CODE/INVALID_CODE/SELF/SOURCE_UNAVAILABLE；不能事后补填。

云托管显式启用时 `POST /api/auth/cloud-login` 为 `{role,invite_code?}`，来源暂为 UNKNOWN。其默认关闭与可信网关要求维持，R6a 不将网关头当已验收正式邀请证据。

携码成功登录额外返回 invitation_result：EXISTING_ACCOUNT/ATTRIBUTED/INVALID_CODE/SELF/SOURCE_UNAVAILABLE。已有账号直接返回 EXISTING_ACCOUNT，客户端提示“该账号已注册，本次不增加邀请人数”；不泄露其他用户身份。未携码的原登录响应保持兼容。

`POST /api/auth/phone/bind` 严格 `{code}`，服务端换码取得手机号后，同事务绑定和确认资格。新注册来源 VERIFIED_WECHAT、同 app、唯一首次手机号证据与当前有效推广源同时满足才进入 QUALIFIED；手机号摘要历史永久保留，旧手机号解绑/账号删除后不恢复新客资格。已有历史账号不会补建注册证据。手机号只存既有用户字段，邀请历史仅存 SHA256 摘要，读接口不返回手机号/摘要/openid/被邀请人 ID。资格事件与审计原子；审计失败整体回滚。

`INVITE_VERIFIED_IDENTITY_ENABLED=false` 为默认。正式来源必须由开关和官方换码端点共同确认；手机号证据同样要求官方端点。未知/测试注册之后不因开关开启或绑定手机号升级为正式新客。生产启用须待 R9 获取实际证据，不将合成测试当正式新客。

## 已实现增长接口

- `GET /api/invite/qrcode`：本人推广码 `{code:null|InviteCode}`；名称兼容原核心路径，返回推广码与落地路径，不是微信官方小程序码图片。
- `POST /api/invite/code`：严格空正文 `{}`，必需 UUID Idempotency-Key；返回 `{code:InviteCode}`。
- `GET /api/invite/records?page=1&page_size=20`：本人作为推广源的分页记录，固定每页 20，page 1..1000000。
- `GET /api/invite/progress`：本人作为推广源的北京时间当前月统计。
- `GET /api/invite/registration`：自己的首次注册记录；legacy 为 `{registration:null}`。
- 当前店长/店员对应 `GET/POST /api/merchant/invite/code`、`GET /api/merchant/invite/records`、`GET /api/merchant/invite/progress`，只访问本人且冻结门店相同的来源；不能传 user_id/staff_id/merchant_id，技师角色不能使用员工推广端点。

所有读取/写入均 no-store，当前会话/账号/门店复核。空查询端点拒绝未知或重复参数；列表只允许 page/page_size。400 参数/正文/UUID错误，401 失效，403 角色不匹配，409 原码门店已改变，503 数据库/事务不可用。

## 输出与月度计算

InviteCode：value/source_type/created_at/landing_path/rule_version。OWNER 或 STAFF，落地路径严格 `/pages/welfare/invite-entry?code=<value>`；不得改为外部链接。

InviteRecord：record_id（不透明 UUID）、source_type、identity_source、attribution_result、qualification_status、qualification_reason、registered_at、qualified_at、reward_month、rule_version。初始来源与 attribution_result 不变；资格确认失败另写 qualification_reason，不覆盖归因。UNKNOWN/TEST/PENDING/REJECTED 的 qualified_at/reward_month 为 null。QUALIFIED 有唯一资格发生时间及北京时间月份。

InviteProgress：qualified_count（本月）、completed_cycles、tier、tier_count、tier_target、next_threshold、month、timezone=Asia/Shanghai、verified_identity_enabled、rule_version；pending_total/unknown_total/test_total/rejected_total 为该源全部历史数量，页面明确标注。

设本月人数 n、轮内 m=n%10：m=0/1..2/3..5/6..9 分别第 1/2/3/4 档，档内完成 m−0/1/3/6，目标为 1/2/3/4；下一累计节点 n−m+1/3/6/10，完成轮数 n/10 向下取整。按资格确认时间计月，注册发生在月末但下月绑定则计下月；每月重新计算，历史记录仍可分页读取。之后停用不倒改已确认事件，但停用来源不能产生新资格。

## 客户端

车主 `/pages/welfare/invite`、落地 `/pages/welfare/invite-entry`、店员 `/pages/merchant/invite`。显式采用邀请码后保留在内存，首次登录尝试冻结，重试沿用原码；登录成功/退出清理，刷新应用不恢复旧上下文。已登录账号不能输入码补归因。未知写入保留原 key；隐藏/账号切换清除私有显示和迟到响应，读失败不保留旧进度。无奖励到账或官方分享成功提示。

设计见[R6a设计](../progress/R6A_INVITATION_DESIGN_2026-10-14.md)，升级见[运维](../operations/INVITATIONS_RUNBOOK.md)，验证见[本地验收](../testing/LOCAL_R6A_INVITATIONS_ACCEPTANCE.md)。
