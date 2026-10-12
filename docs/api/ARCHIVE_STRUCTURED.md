# R4a2 本人结构化档案

有效正式 OWNER JWT、活动账号/会话、当前本人车辆、未删除手动或附图来源（input_type 1/3，archive_type 1/3/7）。所有响应 no-store。每条来源每个账号独立一份，新车主不继承前车主扩展资料。

## 接口

- GET `/api/archive/{id}/structured`：data 为 `vehicle_id,archive_id,archive_type,recorded_date,revision,fields,status`。
- PUT 同路径：UUID `Idempotency-Key`，正文严格 `{"expected_revision":0,"fields":{"provider_name":"本人填写服务单位","service_items":["机油"],"performed_mileage":12000}}`，data 仅 `vehicle_id,archive_id,archive_type,revision,status`，不含私有字段。

全量替换 fields，遗漏旧字段即移除；`fields:{}` 清空扩展资料并递增版本，不删除原档案/图片/个人目标。首次revision0，写入递增；409刷新复核。读空资料status NOT_SET（清空后revision保留），非空 OWNER_ENTERED_UNVERIFIED。不包含官方核验来源。

| 类型 | fields 允许字段 |
| --- | --- |
| 1保养 | provider_name 字符串≤100；service_items 唯一字符串数组1–20条，每条≤80；performed_mileage 整数0–2147483647 |
| 3保险 | insurer_name≤100，policy_number≤64；coverage_type枚举COMPULSORY/COMMERCIAL/COMBINED/OTHER；coverage_start/end日期 |
| 7年检 | inspection_station≤100；inspection_result枚举PASSED/FAILED/UNKNOWN；valid_until日期 |

字段均可省略；不接受null、空文本、控制字符、跨类字段/未知字段。客户端去首尾空白，服务器要求无首尾空白；字符串长度按Unicode码点。日期严格1000–9999年的yyyy-mm-dd有效日期，保障起止均填写时start≤end。

## 权限与兼容

资源锁与版本、写入、审计、回执同事务。幂等同键同体复用回执，变更内容换键；缓存命中仍校验会话/归属/来源。审计只包含版本与字段名，回执不带保单号/单位/项目等私有值。不存在公开查询或门店/技师新增读取权限。

V029 archive_structured_detail 唯一(user_id,archive_id)，初始化42表、完整迁移69表。原content/PDF投影/分享白名单、登记里程及R4a1目标不自动更新。无历史备注解析或回填。is_deleted为统一表结构保留字段，本阶段通过{}清空，不暴露软删除接口。

400输入/幂等键问题；401会话失效；403非正式车主；404本人车辆/来源不可用；409旧版本或来源变更；503数据库/审计失败。日期仅本人抄录，不推断周期、法定期限或健康分数。
