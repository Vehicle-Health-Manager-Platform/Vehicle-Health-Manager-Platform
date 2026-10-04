# S1 本人车辆与手动录入接口

## 身份与数据

五个接口只接受有效正式 user/OWNER 会话；服务复核用户和会话归属、有效期与撤销状态。local示例令牌、技师及绑定令牌被拒绝。使用 V001 的 vehicle/brand/series/model、V003 会话和既有幂等审计表，不新增迁移。

车型只查询已有有效品牌/车系/车型记录，未接通生产数据源。`docs/sql/seed_test.sql` 是合成测试数据，禁止用于生产。空库返回空列表，不创建占位车型。手动流程完成不代表四种录入、档案、驾驶舱或M1全部交付。

## 查询

| 方法和路径 | 参数 | data.list 每行 |
| --- | --- | --- |
| GET /api/vehicle/list | page、page_size | vehicle_id、model_id、model_name、current_mileage、plate_no_masked、vin_masked |
| GET /api/brand/list | page、page_size | id、name |
| GET /api/series/list | brand_id 必填，page、page_size | id、name |
| GET /api/model/list | series_id 必填，page、page_size | id、year、config_name |

page默认1、最大1000000；page_size默认20、最大100。统一data包含list、total、page、page_size。车辆只返回本人未删除记录、id降序；目录按id升序且父子链未删除。无匹配上级返回空列表。本人车辆的车型后来停用不会隐藏已登记车辆。成功响应 `Cache-Control: no-store`，车牌首2末1、VIN首3末4，中间星号；缺省为空字符串。

## 保存

`POST /api/vehicle/add`，Bearer和 `Idempotency-Key: UUID` 必填：

```json
{"add_type":4,"model_id":1,"current_mileage":32000,"plate_no":"","vin":""}
```

add_type=4为此次手动流程约定，model_id必填；里程缺省0且不超过2147483647。车牌仅普通/新能源大陆格式，VIN可选17位排除I/O/Q；可选串去首尾空格、大写、空串作为缺省。拒绝未知字段、字符串ID、小数或负里程，不接收客户端user_id。成功data为 vehicle_id、model_name、need_archive=true；最后一个字段表示后续需要录入档案，并未创建档案。

锁会话、用户后，复核有效车型链并插入；同车主非空车牌或VIN重复409，用户行锁避免不同幂等键并发重复。不同车主可分别登记同一标识，登记不作为所有权证明。未填写两个标识时不能自动识别重复车辆，客户端仍应同键重试。24小时内规范化正文相同且同键返回原响应；异正文400；过期键重新执行但仍检查车辆重复。重放复核车主会话，车型停用不改变已成功响应。

业务、幂等响应和成功审计原子提交；审计只记录vehicle_id/model_id/current_mileage，不含车牌/VIN原文。创建有敏感字段的车辆时，前端不持久化表单或签名URL。

400参数、401失效身份、403身份不符、404无效车型、409重复登记、503未配置/服务暂不可用。网络或503保持原正文与UUID重试；修改正文生成新键，401/403重新登录。此步未实现车型导入或更新、车辆修改、档案创建、OCR、VIN解码和车牌匹配。
