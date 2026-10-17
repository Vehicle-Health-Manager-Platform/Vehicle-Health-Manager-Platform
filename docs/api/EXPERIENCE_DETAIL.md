# R8c1a 同款获批经验的受控详情

2026-10-17。原[经验授权与审核](EXPERIENCE_PUBLICATION.md)的单条读取能力；无新迁移、权限或公开用途，仍 V056/116 表。默认 `EXPERIENCE_PUBLICATION_ENABLED=false`。

GET `/api/community/experiences/{id}?vehicle_id=...`。仅 OWNER，id 必须为规范小写公开 UUID（非私有 card_id），vehicle_id 为安全正整数且必填。拒绝重复或其他 query，不接受 user_id、model_id、cursor、来源编号或角色选择器。全部 no-store。

`ApiResponse.data` 严格为 `{vehicle_id,experience}`，vehicle_id 绑定本次接收者的本人车辆；experience 复用原公开五字段：experience_id/title/summary/model_id/published_at。summary 仍只有 version/work_minutes/part_kinds/no_parts/recorded_month，不含照片、原施工文字、原作者/订单/档案/审核人员编号或私人标识。

## 统一有效性

列表和详情共用当前发布来源查询及可信施工校验，在一个只读 REPEATABLE_READ 事务中核验：

- 当前有效车主会话及接收车辆仍归本人，接收车型/车系/品牌有效。
- 原作者账号有效且仍拥有原车辆；原车辆车型与获批车型、接收车辆车型相同。
- 车型/车系/品牌均 status=1 且未删除；批准时同样检查当前有效车型。
- 非测试、当前公开授权版本/时间及未撤回状态、卡片发布状态和原审核修订匹配。
- 原可信施工、支付/核销/无异议、文件安全与冻结档案/摘要仍一致。

撤回、重新送审、换版审核、删除/转移、目录停用、源异常或跨车型时，旧 UUID 的新读取统一404；再次批准产生新的 UUID，不恢复旧入口。随机编号或复制入口本身不能授权。没有用不可核验的旧摘要兜底。

## 错误与兼容

40001 非法编号/选择器；40100 会话或账号失效；40300 非车主角色；40400 当前车辆或获批同款来源不可用；50300 数据库未配置/依赖不可读或发布开关关闭。无私有 SQL/源异常细节。详情开关关闭为503，原列表开关关闭仍空列表，保留原游标/五字段契约和方案证据形状。

只读不创建浏览量、审计、幂等、卡片、授权、积分或热度事件。前端另绑定当前车辆、车型和公开 UUID；切号/换车/车型变化/换引用/隐藏清空旧结果并丢弃迟到响应，刷新重新读取。列表确认会话或车辆不可用时同步清理详情；详情确认404时移除对应旧列表项。

应用内入口为 `/pages/community/experiences?experience_id={公开UUID}`，只含公开编号，无 token 或车辆/作者编号。当前页可复制入口；AI 方案获批摘要可导航进入并重新核验。登录后可按精确入口返回，再由接收者选择本人车辆；无匿名读取或官方分享渠道。见[操作](../operations/EXPERIENCE_DETAIL.md)。

R8c1b1增加[运营精选](CURATED_EXPERIENCES.md)和手动引用导入：接收者可在现有同款页粘贴规范应用内入口或公开UUID，仍调用本详情接口重验，不自动读取剪贴板或新增授权。
