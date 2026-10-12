# R4a4 本人读数历史接口

## 写入扩展

PUT `/api/archive/{id}/health-observation` 保留 R4a3 六字段读数、expected_revision、replace_source 与 UUID Idempotency-Key。可选 operation：RECORD 新测量、CORRECT 修正当前同来源非空版本、CLEAR 清空当前。省略时按 measurement 是否为空推导 CLEAR/RECORD，兼容旧客户端与已保存的幂等指纹。CORRECT 必须同来源且 replace_source=false；不允许修正任意旧版本、失效来源或已清空值。空值仅允许 CLEAR；连续清空拒绝409。

当前值、追加版本、审计和幂等回执同事务。修正保留旧值并指向 supersedes_revision；回执保持原五字段。通用审计仅版本/来源/是否有值，无私人测量内容。清空不删除历史。历史删除不是本阶段接口。

## 分页

GET `/api/vehicle/health-observation-history?vehicle_id=101&metric=AUX_BATTERY&limit=20`。

可选 before_revision 为1..2147483647；limit默认20，1..50。登录正式 OWNER、会话有效、账号启用、车辆当前本人所有。响应 no-store。

data 字段：vehicle_id、metric、today（上海业务日期）、list、has_more、next_before_revision（无下一页时null）。版本倒序而非测量日期倒序；下一页使用 next_before_revision，刷新重新从第一页取。新增写入不会混入已经获取的旧页；历史修正状态可能改变，刷新获取最新状态。

每行：metric、revision、archive_id、recorded_date、measurement、status（继承 R4a3 脱敏契约），以及 operation（RECORD/CORRECT/CLEAR/MIGRATED_SNAPSHOT）、supersedes_revision（仅CORRECT非空）、saved_at（UTC ISO秒）、corrected（原版本被后续修正）。corrected不隐藏原值，只明确该版本已被修正。CLEAR没有读数；迁移空快照同样没有读数。

每次读逐行校验档案仍属该车、类型正确、input_type=1/3、未删除；失效行 SOURCE_UNAVAILABLE，来源ID/日期=null、measurement={}。转移后原车主404，新车主只返回本人历史，不继承私人记录。尚未提供永久删除历史能力，界面明确清空后保留履历。

只展示未核验原值与来源。名称/单位/方法/本人来源/原档案不同不可直接比较；分页表格不生成趋势、健康分或诊断。MIGRATED_SNAPSHOT只说明迁移时当前状态，不推断已有版本之前的历史。

400 参数错误；401 会话失效；403 非正式OWNER；404 车辆/来源不可用；409 版本或修正条件冲突；503 数据库/审计失败。弱网同内容重试同UUID，改内容/操作后换UUID，409刷新复核后再写。
