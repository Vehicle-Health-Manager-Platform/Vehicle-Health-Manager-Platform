# R4a2 本人结构化档案设计

用户已要求规划下一步并开始实施，承接已交付 R4a1 的下一步方案；微信小程序及必要后端，无 PC。

## 字段盘点与方案

现有档案已有类别、记录日期、里程、标题、自由备注和照片；施工档案有冻结来源订单与配件/工时。本阶段补手动/附图保养(1)、保险(3)、年检(7)的本人结构化资料，不修改施工快照。

| 类别 | 新字段（均可选，未填不推断） |
| --- | --- |
| 保养 | provider_name 服务单位，service_items 项目列表，performed_mileage 本次保养里程 |
| 保险 | insurer_name 保险公司，policy_number 保单号，coverage_type COMPULSORY/COMMERCIAL/COMBINED/OTHER，coverage_start/end 保障起止日 |
| 年检 | inspection_station 检验机构，inspection_result PASSED/FAILED/UNKNOWN（本人填写结果），valid_until 资料所载有效日期 |

字符串不含控制字符，去首尾空白；单位/公司最多100字符，保单号64，项目最多20条、每条80字符且不重复；里程0–2147483647整数，日期严格1000–9999有效日期，保障起止均填写时先后有序。不填字段直接省略，不接受 null/未知字段或跨类型字段。{}明确清空且保留版本。资料不是官方核验，不自动更新车辆里程或R4a1目标。

选择独立本人扩展表而非改写旧content：旧档案/报告与分享白名单维持兼容，私有字段有独立版本；统一自由JSON会缺少类型校验，直接重写旧记录会破坏施工快照。没有自由备注历史回填。

## 契约、权限与生命周期

V029 archive_structured_detail 唯一(user_id,archive_id)，记录 vehicle_id、archive_type、fields_json、revision、updated_at。本人数据按账号隔离，车辆转移新车主不继承旧车主扩展资料。所有GET/PUT验证有效正式OWNER会话、正常账号、当前车辆归属和有效手动/附图来源。

GET/PUT /api/archive/{id}/structured。GET返回 vehicle_id/archive_id/archive_type/recorded_date/revision/fields/status，status为NOT_SET或OWNER_ENTERED_UNVERIFIED。PUT {expected_revision,fields}全量替换，同事务资源锁/版本/UUID幂等/审计；回执只含来源ID/版本/状态，不含私有字段，审计仅字段名和版本。重放依旧复核授权。资料清空不删除原始档案或图片。

微信独立来源页按类别展示表单、本人未核验提示、保存/修正/清空确认；档案卡片入口，登录返回。日期可清除，保险类型/检验结果不默认选已通过，保养项目按换行编辑。失败原载荷复用内存UUID，改载荷换键，409刷新复核；隐藏、换账号清理且忽略迟到结果，不持久化私有表单。

## 分段与验收

规格提交上传→后端/迁移/中文API/真实MySQL与HTTP→微信/协议和生命周期/原生构建→验收/README/三端缺口与下一步。依赖#127；逐支合入main后再调整下一支base。本次不自动合并或部署。

验证字段和跨类型、日期顺序、本人权限、车辆转移、源删除、版本竞争、同键重试、清空、审计失败回滚及旧content不变。原生完整系统实点/真机留独立验收。驾驶证/违章官方信息、OCR/VIN识别、六指标/分数、动力电池/电瓶测量仍欠；下一步单独冻结可信指标来源/单位/时间和评分规则，不从本人项目名称推导健康百分比。
