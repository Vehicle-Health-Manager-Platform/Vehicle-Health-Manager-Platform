# R4a3 本人可追溯当前读数

正式有效OWNER JWT、正常账号、当前本人车辆，来源为未删除手动/附图保养或维修档案（type1/2、input_type1/3）。所有响应no-store；账号隔离，车辆转移后新车主不继承前车主个人读数。

## 接口

| 操作 | 输入 | data |
| --- | --- | --- |
| GET /api/vehicle/health-observations | vehicle_id查询参数 | vehicle_id,today,list（固定六类顺序） |
| GET /api/archive/{id}/health-observation | metric查询参数 | vehicle_id,archive_id,archive_type,recorded_date,metric,today,observation |
| PUT 同来源路径 | UUID Idempotency-Key，见下 | vehicle_id,archive_id,metric,revision,status（不含私有读数） |

固定metric顺序OIL/BRAKE_PAD/TIRE/TRACTION_BATTERY/COOLANT/AUX_BATTERY。list每条和observation严格为metric,revision,archive_id,recorded_date,measurement,status。未设置NOT_SET（首次revision0，清空后保留revision），有效非空RECORDED_UNVERIFIED，来源失效SOURCE_UNAVAILABLE；未设置/失效时旧来源ID/日期为null、measurement:{}，不算健康或正常。

PUT示例：`{"metric":"AUX_BATTERY","expected_revision":0,"replace_source":false,"measurement":{"measurement_name":"资料所载电压","value":"12.50","unit":"V","measured_on":"2026-10-12","method":"本人抄录检修单读数栏","provenance":"OWNER_TRANSCRIBED"}}`。

全量替换当前读数。清空传measurement:{}，replace_source只能省略/false且须当前来源；不删除原档案/照片。每车每类一个当前配置，非空配置换来源须replace_source:true，旧版本始终409。清空后新来源不需替换确认。首次revision0，成功递增1。

measurement非空严格六字段：名称≤80字符、方法/资料位置≤120、value十进制字符串（最多9整数位/3小数位，不接受科学计数/前导零）、unit、实际日期measured_on、provenance OWNER_MEASURED/OWNER_TRANSCRIBED。禁止空文本/null/额外字段/控制字符和首尾空白；客户端去空白，服务器严格拒绝。日期严格1000–9999有效yyyy-mm-dd，不晚于服务器Asia/Shanghai业务日；没有默认日期。

单位白名单：OIL KM/PERCENT；BRAKE_PAD MM；TIRE MM/KPA；TRACTION_BATTERY PERCENT/V；COOLANT CELSIUS；AUX_BATTERY V/PERCENT。除CELSIUS允许负数外均非负；PERCENT仅0–100，仍是原资料读数不是平台健康分。来源/名称/方法未核验，单位与格式校验不代表诊断阈值、测量工况、数据有效期或车型适用证明。

## 权限与一致性

读取/写入/幂等重放复核现时会话、账号、车辆归属和有效来源。资源锁、版本、写入、最小审计与回执同事务；同键同体复用，改内容换键。审计仅来源ID、版本和是否非空，不含测量值、方法等文字；写回执无measurement。source删除/类型变化时列表隐藏旧值，重新选择有效来源才能更新；失效来源无法继续写入。

V030新增vehicle_health_observation，唯一(user_id,vehicle_id,metric)，init/baseline43表、完整链70表，OpenAPI154操作。既有环境追加V030，无旧文字/图片解析回填。没有历史趋势，原content、R4a2资料、登记里程、R4a1目标、PDF/分享白名单不变；不新增门店/技师或分享接收者权限。

400输入/键无效；401会话失效；403非正式车主；404有效本人来源/车辆不可用；409版本/未确认换来源/清空来源不匹配；503数据库/审计失败。此接口只记录本人原始读数，不提供评分或健康诊断。
