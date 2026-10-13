# R4 完整收口契约

日期：2026-10-13。用户确认“先实现可配置来源规则，未配置显示未知”；没有生产阈值种子、旧资料确认回填或固定健康分。实现规格见[设计](../superpowers/specs/2026-10-13-r4-completion-design.md)，验证见[验收](../testing/LOCAL_R4_COMPLETION_ACCEPTANCE.md)。

## 权限与通用边界

全部接口使用现有 Bearer 身份、统一信封、`Cache-Control: no-store`。车主只操作本人当前有效车辆；规则读写仅 `ADMIN.can_catalog`。禁止额外/重复 query；只有星级报价接口接受下述分页/区域 query。所有 PUT 要求 UUID `Idempotency-Key`，同键同规范 body 可重试，异 body 拒绝；当前权限与必要来源复核仍执行。旧回执不会恢复已撤销确认或规则。版本变更与审计同事务，冲突 409 后重新读取，网络失败仅原 body/key 重试。

正整数 ID 不超过 9007199254740991。规则版本 0..9007199254740991，写 expected_version 上限减一；读数/确认/偏好 revision 0..2147483647，写 expected_revision 上限减一。错误 400 参数、401 会话、403 权限、404 当前归属/有效目录不可用、409 版本或来源冲突、503 数据库/审计不可用。响应不回传内部异常。

## 精确车型六指标规则

- `GET /api/admin/health-policies/{model}/{metric}` → `{model_id,metric,version,rule}`；不存在 version=0/rule=null，撤回保留版本但 rule=null。
- `PUT /api/admin/health-policies/{model}/{metric}` body 严格 `{expected_version,rule}`；rule=null 撤回，否则必须下列 14 字段齐全，禁止未知字段。
- metric 固定顺序：`OIL,BRAKE_PAD,TIRE,TRACTION_BATTERY,COOLANT,AUX_BATTERY`。按车型 ID 精确匹配，不依据动力类型猜测“不适用”。

rule 字段：`applicability,source_reference,source_checked_on,valid_until,source_confirmed,measurement_name,unit,method,provenance,max_age_days,weight,bands,action_project_id,action_score_below`。

`source_reference` 1..256 字、去边空白且无控制/格式/代理字符；来源核对日期不能晚于北京时间今天，有效期不能早于核对日，新发布不能已过期；所有日期严格 YYYY-MM-DD，年份至少1000。`source_confirmed` 必须 true，仅代表运营已核对其所填写来源声明，系统不替代手册/供应商真实性验证。

`applicability=NOT_APPLICABLE` 时，measurement_name/unit/method/provenance/action_project_id/action_score_below 均 null，max_age_days/weight 均0，bands=[]。

`APPLICABLE` 时名称1..80字、方法1..120字；provenance 为 OWNER_MEASURED 或 OWNER_TRANSCRIBED；max_age_days 1..3650，weight 1..100。单位按既有读数口径：机油 KM/PERCENT、刹车片 MM、轮胎 MM/KPA、动力电池 PERCENT/V、冷却液 CELSIUS、低压电瓶 V/PERCENT。

bands 为1..20段，每段严格 `{upper_bound,score}`，score 整数0..100；有限上界为十进制字符串（最多9位整数、3位小数），严格递增，CELSIUS 外不得负、PERCENT 不超过100。只有最后上界 null 表示正无穷。取第一段“读数 ≤ 上界”的分数，不要求高读数一定更健康。必做动作两字段同时 null 或同时填写：当前有效标准项目 ID，触发分数 action_score_below 1..100。没有任何内置技术阈值或养护周期。

## 当前健康面板、确认与趋势

`GET /api/vehicle/{vehicle}/health-dashboard` 返回严格七字段：`vehicle,today,overall,metrics,required_projects,reminders,preference`。vehicle 复用既有五字段白名单，不含车牌/VIN；today 为 Asia/Shanghai 日期。六项 metrics 顺序固定，每项九字段：`metric,status,rule_version,rule,observation,confirmation,score,trend,action_status`。observation 复用[读数](HEALTH_OBSERVATIONS.md)，原始本人来源不变成官方测量。

状态：MODEL_UNKNOWN/POLICY_NOT_SET/POLICY_INVALID/POLICY_EXPIRED/NOT_APPLICABLE/SOURCE_UNAVAILABLE/NOT_SET/EVIDENCE_INVALID/NOT_COMPARABLE/EVIDENCE_EXPIRED/CONFIRMATION_REQUIRED/REFERENCE_READY。除 REFERENCE_READY 外 score=null。NOT_COMPARABLE 要求名称、单位、方法和 provenance 四者与规则完全一致；age 超过 max_age_days 失效，等于边界仍可用。规则/读数变更、车型改变、归属改变、来源失效均令原确认不再有效。

`PUT /api/vehicle/{vehicle}/health/{metric}/confirmation` body 严格 `{expected_revision,confirmed,observation_revision,policy_version}`，返回 `{vehicle_id,metric,revision}`。true 需本人核对当前原始来源、规则用途和版本，服务端锁定并验证当前匹配和有效期；false 可撤回，包括原车型停用后。保存后客户端重读当前面板，不用旧回执填充当前分数。

overall 严格 `{status,score,weighted_sum,weight_total}`。只有六项均明确：来源确认的不适用，或当前有效且本人确认的适用读数，且至少一项适用，才 `REFERENCE_READY`：sum=Σ(分数×权重)，weight=Σ(适用权重)，score=HALF_UP(sum/weight) 整数。不适用不参与分母。任一未知或全部不适用则 UNKNOWN，三个数值均 null；不以部分平均冒充整车分。前端复算分段/权重/总分并严格校验来源证据。

trend 严格 `{status,points,delta,truncated}`；从最近50条不可变历史和当前点获取连续同口径有效来源，跳过被修正项，同日保留最新点；遇清空、失效来源、过期、读数日期晚于当前、名称/单位/方法/provenance变化即停止，不跨断点连线。最多51点，实际日期递增；point 为 `{revision,archive_id,measured_on,value,unit}`。至少两点 COMPARABLE_UNVERIFIED，delta=末值−首值；否则 INSUFFICIENT/null 或 NOT_AVAILABLE/空点。原始值不是历史健康分，增减不推断“健康改善”。原始履历仍可独立查阅。

## 有来源的技术必做与预约

required_projects 最多6个去重项目，每项 `{project_id,project_name,category,tier,evidence}`，tier=MUST。仅当前 REFERENCE_READY 且 score 严格小于动作分界、项目在售、精确项目/车型适用性当前明确 APPLICABLE 才触发。适用性未知、撤回、不适用或项目停用都不产生必做。

evidence 十字段：`metric,policy_version,observation_revision,confirmation_revision,action_rule_version,value,unit,measured_on,source_reference,project_source_reference`。action_status 明示 NONE/EVIDENCE_UNKNOWN/NOT_TRIGGERED/PROJECT_UNAVAILABLE/APPLICABILITY_UNKNOWN/CONFLICTING_APPLICABILITY/TRIGGERED。与模型 REQUESTED/RECOMMENDED/OPTIONAL 分开呈现，本地私有健康证据不发送给 DeepSeek。点击沿用[方案预约](PLAN_BOOKING.md)当前报价/时段及提交复核；这些是建议，不替代诊断，预约创建仍按既有事务校验价格/名额。

## 五类提醒与提前偏好

`PUT /api/vehicle/{vehicle}/reminder-preference` body：`expected_revision,maintenance_days,maintenance_km,insurance_days,inspection_days,maintenance_project_id`。前三类天数0..365，保养里程0..100000，项目 null 或当前有效 ID；兼容旧五字段未传项目的 body（清空项目选择）。返回六字段偏好，expected_revision 替为 revision。默认全部提前范围0、项目null，不自动推定15/30天。

reminders 五组顺序：FAULT/MAINTENANCE/INSURANCE/INSPECTION/VIOLATION；组严格 `{kind,status,items,limited}`。每条十字段：`kind,severity,title,archive_id,source_revision,source_reference,remaining_days,remaining_km,preference_revision,action`。严重级按 OWNER_URGENT/DUE/PENDING/UPCOMING 排序，保留本人的来源和版本。

保养/保险/年检使用已有[主动来源目标](ARCHIVE_REMINDERS.md)，不是根据证件推断法定期限；剩余日期或保养里程≤0 为 DUE，正值≤本人提前范围为 UPCOMING；缺里程不假设达到。保养选定项目有效且无明确不适用时 action PROJECT，否则 SERVICES；保险/年检 TARGET 回来源目标。故障/违章 action ARCHIVE 回本人记录。

故障与违章仅看最新200条本人结构化记录，超过范围 limited=true；按故障码（大写）/案号先去重，再查来源有效性与本人完整确认，最新删除/改类/归属变更/未确认记录不得复活更旧警报。ACTIVE/PENDING 生成待关注；RESOLVED/HANDLED 不警报，未知状态 UNCONFIRMED。无本人记录 NOT_SET，不代表官方无故障/无违章。组状态还可 UNAVAILABLE/INACTIVE/UNKNOWN/PLANNED/DUE/UPCOMING/ATTENTION/CLEAR。

## 完整手动档案与私有证件

原始档案允许 archive_type 1..10，既有1..7兼容，新增8行驶证、9驾驶证、10故障。结构化接口不变，类型1/2/3/6/7/8/9/10可写；严格 `{expected_revision,fields}`，fields={}清空，其余为类型字段和通用 `owner_confirmed,source_reference,source_checked_on` 白名单。部分未确认资料可保存；本人确认须完整必填，变更页面会撤销旧确认。确认不改变 OWNER_ENTERED_UNVERIFIED，也不代表官方核验。

- 1保养：provider_name/service_items/performed_mileage/service_date/oil_spec/parts_used/next_due_date/next_due_mileage；确认必填前四项。
- 2维修：provider_name/repair_items/performed_mileage/repair_date/problem_description/parts_used/result_notes；确认必填 provider_name/repair_items/repair_date。
- 3保险：insurer_name/policy_number/coverage_type/coverage_start/coverage_end/policy_holder/insured_name/insured_vehicle_plate/insured_vin/coverage_amount；确认必填前五项。
- 6违章：case_number/occurred_on/location/description/penalty_amount/points/violation_status/handled_on/authority；确认必填案号/发生日期/地点/描述/状态，HANDLED另需handled_on。
- 7年检：inspection_station/inspection_result/valid_until/inspection_date/certificate_number/remarks；确认必填机构/结果/检验日期/有效期。
- 8行驶证：owner_name/address/plate_number/vehicle_type/brand_model/vin/engine_number/use_character/register_date/issue_date/issuing_authority/record_number/gross_mass_kg/unladen_mass_kg/approved_passengers/inspection_valid_until；确认必填车主/车牌/车辆类型/品牌型号/VIN/注册及发证日期/发证机关。
- 9驾驶证：holder_name/license_number/nationality/address/birth_date/first_issue_date/permitted_classes/valid_from/valid_until/validity_type/issuing_authority；确认必填姓名/证号/出生及初领日期/准驾车型/有效起始/有效类型/发证机关，FINITE须有效结束，LONG_TERM不得填结束日。
- 10故障：fault_code/fault_description/observed_on/fault_status/cleared_on/attention_level；确认必填前四项及attention_level，RESOLVED须cleared_on。

上述确认还须source_reference与source_checked_on，核对日不晚于今天，历史事件日期不晚于今天，所有日期对顺序逐项校验（例如发生≤处理、出生≤初领≤有效起始、检验≤有效期）。枚举与数值格式详见生成[OpenAPI](openapi.json)。金额固定两位小数字符串、质量最多9位整数3位小数、列表1..20个去重条目；原档案标题/里程/目标不被结构化字段自动覆盖。

本人列表/私人PDF包含新类型，分享摘要强制仅1..7，连未知日期计数也不暴露新证件存在；服务端摘要投影双重过滤。AI自动上下文也仅1..7，8/9/10原始标题、备注和结构化证件字段均不进入公开分享或自动AI上下文。识别和官方查询保持 R9。

## 星级报价与服务搜索

`GET /api/service/project/{id}/rating-quotes?page=1&page_size=20&region_code=六位区域码`。page1..1000000，page_size必须20，区域可省略。返回 `{items,total,page,page_size}`；行含当前七字段报价和rating_summary `{count,rating_sum,average_rating}`。沿用[公开星级](PUBLIC_RATINGS.md)独立授权及实时真实非测试履约过滤，按有效样本优先、两位HALF_UP平均分降序、样本数降序、当前价格升序、报价ID稳定排序；SQL用足够精度避免重复舍入，显示值与排序相同。无样本average_rating=null。星级排序不冒充距离排序。

`POST /api/service/project-search` 是只读、无幂等键、无query，严格 `{query,category,page}`，query1..80字，category0..6（0全部），page1..1000000；字面搜索有效目录名称/服务内容，SQL转义 `%/_/!`。返回20条分页，项目字段 `id,project_name,category,base_price_low,base_price_high`，不查私有档案，不持久化问题/审计或调用模型。

症状1..300字通过15分钟、账号绑定、一次性内存交接到AI建议页，不放URL/本地缓存；切账号/隐藏清除，过期不可消费。带入后仍须主动确认第三方问题用途才发给模型，不把目录搜索当作诊断。

## 数据库升级

[V037](../sql/migrations/V037__health_policies_and_confirmations.sql)新增 model_health_policy、health_evidence_confirmation、vehicle_reminder_preference 三表，总表80。完整重放两次通过；无生产规则种子、无原资料确认回填。归档完整字段继续用已有JSON版本表。原初始化44表基线和原141项追踪不重写历史需求。
