# R8c1b1 有来源运营精选

2026-10-17。用户确认有来源运营精选，页面明确标为“运营精选”，不生成浏览热度。V057新增experience_curation及默认关闭can_community_curate，117表。全R8完成后统一上传。

## 独立运营接口

- GET `/api/admin/community/curation-sources?page=1&page_size=20`：当前获批、授权有效、非测试且可信的公开五字段来源及服务器SHA256。
- GET/POST `/api/admin/community/curations`：分页及新建草稿。
- GET/PUT `/api/admin/community/curations/{id}`：原依据详情及CAS草稿全量修订。
- PUT `/api/admin/community/curations/{id}/status`：CAS发布或停止。

仅当前有效OPERATOR及独立can_community_curate；审核/诊断/目录/经营等权限不能替代。全部no-store。列表只允许单值page/page_size、固定20；详情/写拒绝query。来源分页total为当前基础发布候选数，可信源过滤后本页可少于20，不当成有效经验总数。

创建精确字段：experience_id/source_hash/priority/source_note/scope_confirmed/valid_from/valid_to。公开UUID须规范小写，哈希64位小写；priority为1至1000000正整数，小值在前、同值新id在前；source_note为2至256字来源及排序说明，仅运营可见；scope_confirmed必须true。UTC规范整秒、截止大于生效，保存和发布时生效仍须未来。修订增加expected_revision。客户端不能自报来源快照。

响应增加id/source_snapshot/revision/status/rule_version（R8C_CURATED_V1）、created_at/published_at/stopped_at；snapshot为原五字段获批经验，不含私有来源。DRAFT可修订，发布冻结只可STOPPED，不能重开；同公开UUID最多一PUBLISHED，即使过期也须显式停止后才能替换。换审核UUID不会自动重绑旧精选。

UUID Idempotency-Key、通用回执和审计同事务；创建另保留永久主体/键/规范化请求哈希/原回执。通用24小时回执过期、来源撤回、窗口过期或展示关闭后，原创建键/正文仍返回原成功结果，但每次先核对当前权限；同键改正文拒绝。最新状态发布审计须未软删除且与冻结字段完全一致，后续停止事件阻止旧发布行回退。停止和已有依据详情不要求来源仍有效。

精选写锁序为运营账号→原作者账号→订单→经验卡片及当前来源→精选，兼容车主撤回的账号→订单顺序。缺失永久创建记录不额外加间隙锁，同账号由授权账号锁串行；数据库唯一发布约束防止双运营同时发布。

## 车主只读投影

GET `/api/community/hot?vehicle_id=...`，仅OWNER本人有效车辆，拒绝其他/重复选择器。此路径沿原文热门入口，响应明确 `selection_type=OPERATOR_CURATED`，不返回热度或浏览次数。

data精确为vehicle_id/model_id/selection_type/observed_at/state/items。model_id可null；state为CONFIGURED、UNKNOWN或UNAVAILABLE。单只读REPEATABLE_READ观察时点，取排序前20条同款、PUBLISHED且处于[valid_from,valid_to)的候选。逐条重验原发布最后有效审计、冻结哈希/快照和当前获批可信来源；失效候选隐藏，不以旧依据兜底，也不自动扫描更多候选补齐20。没有有效项UNKNOWN，原经验展示开关关闭UNAVAILABLE。

items仅含curation_id/revision/priority/valid_from/valid_to/experience；experience复用原公开五字段。没有运营来源说明、哈希、创建键、作者/订单/档案标识或图片。撤回、重审换UUID、源不可信、目录停用或停止/到期后新读取不展示。读取不写审计、积分、浏览、问答或分享事件。

错误：400输入/键/正文，401会话，403角色/独立权限，404当前来源或本人车辆/依据不可用，409来源/版本/未来窗口变化或唯一发布冲突，503无库/展示开关或依赖。来源503时实际配置页明确暂不可读，禁止新建/发布；仍可先读已有配置恢复原请求或停止，不伪装成正常空来源。

车主可手动粘贴规范应用内引用入口或公开UUID，再由[c1a详情](EXPERIENCE_DETAIL.md)按本人车辆重验。只解析本应用固定入口，不自动读剪贴板，不携带接收车辆或身份信息。见[操作](../operations/CURATED_EXPERIENCES.md)。
