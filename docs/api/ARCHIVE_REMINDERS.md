# R4a1 本人档案目标与提醒

全部接口有效正式OWNER JWT，检查会话、账号、当前车辆归属和来源；no-store。手动/附图档案(input_type 1/3)、类型保养1/保险3/年检7可用。原始档案与R3c3分享白名单不变；默认无目标，不从备注、测试施工或法定规则推断。

| 接口 | 参数/请求 | data |
|---|---|---|
| GET /api/archive/{id}/reminder | 本人有效来源ID | `vehicle_id,archive_id,archive_type,recorded_date,current_mileage,today,setting` |
| PUT /api/archive/{id}/reminder | UUID Idempotency-Key，见下 | `vehicle_id,archive_id,archive_type,revision,active` |
| GET /api/vehicle/reminders?vehicle_id=... | 本人车辆 | `vehicle_id,today,current_mileage,list`，list固定按1/3/7三类 |

启用/修改：`{"expected_revision":0,"active":true,"due_date":"2026-12-01","due_mileage":12000,"replace_source":false}`。保养至少一个目标；保险/年检仅日期。日期严格1000–9999年有效日期；里程0–2147483647。允许过去目标，立即进入到达状态。replace_source仅在明确替换当前类目来源时置true；版本不一致始终409。首次版本0，成功递增1。

停用：`{"expected_revision":1,"active":false}`，不能携带目标字段或true替换标记；须是当前来源。保留目标/来源与版本，可从有效来源重新启用。删除来源后提醒不可用，需选择新的本人同类记录设置并明确替换；不会继续按失效来源提示。

setting/列表每条：`archive_type,revision,active,archive_id,recorded_date,due_date,due_mileage,status,triggered_by`。未设置revision0，目标和来源null；无效来源不返回旧ID/目标。状态：NOT_SET未设置、INACTIVE停用、UNAVAILABLE来源失效、UNKNOWN里程目标无法核对且没有已到日期目标、PLANNED未到目标、DUE目标已到。triggered_by为DATE/MILEAGE数组，仅有效启用且到达时包含原因。Asia/Shanghai日期当天即到；登记里程达到即到；任一满足即DUE。没有提前提醒或自动延期/完成。

所有目标均为OWNER_ENTERED个人设置，日期不是证件真伪、官方查询或法定截止证明。来源显示本人档案类别及记录日期。查阅源档案仍走原有本人档案权限，不扩权门店/技师或分享接收者。

400输入错误；401失效会话；403非正式车主；404来源/车辆不可用；409版本变化/未确认替换/停用目标来源不匹配；503数据库/写入或审计失败。资源锁、版本检查、目标写入、审计及幂等回执同事务；重放仍校验权限/来源；旧版本在缓存命中时可复用原回执，但不绕过授权。审计只存来源ID、版本与启停，不保存档案自由文本。
