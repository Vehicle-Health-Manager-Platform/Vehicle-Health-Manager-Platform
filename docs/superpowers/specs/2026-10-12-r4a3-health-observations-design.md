# R4a3 本人可追溯读数设计

承接用户“规划下一步并开始实施”；README等待用户另行要求，本阶段所有README均不改动。

## 盘点与范围

旧档案有文字/照片和施工快照，R4a2有本人项目与证件字段，目前没有可信健康测量或评分算法。采用独立本人当前读数表；不从旧项目文字回填。直接把项目名映射为分数缺少依据，供应商接入需外部条件，本阶段先完成可独立录入/抄录的来源与原始读数。

六类固定顺序：OIL机油、BRAKE_PAD刹车片、TIRE轮胎、TRACTION_BATTERY动力电池、COOLANT冷却液、AUX_BATTERY低压电瓶。动力电池与低压电瓶分开；适用车型未核验，不因未填或燃油车信息不足而自动判正常/不适用。

来源限定本人当前车辆未删除的手动/附图保养或维修记录（类型1/2、input_type1/3）。每辆车每类一份当前读数；明确确认后换来源。可修正/清空。没有历史趋势、来源有效期、安全阈值或分数算法，本轮不把百分比读数称为健康百分比。

## 字段与数据状态

measurement非空时严格六字段：measurement_name名称≤80、value十进制字符串（最多9位整数/3位小数）、unit、measured_on（1000–9999有效日期，不晚于Asia/Shanghai今天）、method测量方法/资料位置≤120、provenance OWNER_MEASURED本人测量或OWNER_TRANSCRIBED本人抄录。字符串无首尾空白/控制字符。

| 类别 | 允许单位（记录读数，不生成诊断） |
| --- | --- |
| OIL | KM/PERCENT |
| BRAKE_PAD | MM |
| TIRE | MM/KPA |
| TRACTION_BATTERY | PERCENT/V |
| COOLANT | CELSIUS |
| AUX_BATTERY | V/PERCENT |

CELSIUS可负，其余非负；PERCENT仅0–100。这里只校验数值/单位格式，不定义合格阈值。名称和方法必须明确实际测的是什么，例如电量与容量状态不能互换。读数为本人提供、未核验；单位规则不代表设备/工况或车型适用证明。

未录入NOT_SET；非空且来源有效RECORDED_UNVERIFIED；源删除/变更SOURCE_UNAVAILABLE，旧ID/日期/读数隐藏。清空传measurement:{}，保留版本但不删除原档案；空配置换来源无需确认，非空替换必须replace_source:true。不自动变更车辆里程、个人目标、结构化资料、PDF或分享白名单。

## 接口与权限

GET /api/archive/{id}/health-observation?metric=... 来源context；PUT同路径 {metric,expected_revision,measurement,replace_source}；GET /api/vehicle/health-observations?vehicle_id=... 固定六类列表。context包含来源类别/日期与本类当前读数，列表无私有全文/图片。

V030 vehicle_health_observation 唯一(user_id,vehicle_id,metric)，存当前source archive/type、measurement_json和revision。账号隔离，新车主不继承前车主个人读数。有效正式OWNER会话/正常账号、当前车辆和有效来源每次读写/幂等重放复核。资源锁/版本/UUID幂等/最小审计同事务；回执不含读数/方法，只含来源ID/指标/版本/状态，审计无测量值和自由文字。

微信档案/首页入口→六类当前列表→本人保养/维修来源卡片选择指标→录入页。无自动日期/通过/来源默认值；换来源与清空确认、旧数据可复核、冲突刷新、同载荷内存键重试、隐藏/切账号清理及登录返回。普通HTTPS/云托管复用已有层。

## 验收与交付

规格上传→后端/迁移/API/输入+真实MySQL+HTTP→微信/协议生命周期/构建→验收/进度和下一步独立PR。依赖#130，前序合入main后逐支改base检查，不自动合并部署。

验证六序/单位/数值/日期/来源、越权/车辆转移、源删除、修正清空、替换确认、版本竞争、幂等及审计失败回滚；旧content/资料/目标不变。原生系统实点/真机独立待验。完整证件/官方数据、健康指标趋势/算法、订阅/提前、正式身份/资金及供应商仍欠。后续先补历史与可信工单测量来源，再冻结可复算评分，规则不足可推进R4b商家发现。
