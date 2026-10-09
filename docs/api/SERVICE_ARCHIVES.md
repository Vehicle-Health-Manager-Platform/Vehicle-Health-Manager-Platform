# A7.2b 施工档案自动回写接口与发布

## 触发与恢复

新本人评价在原评价事务内写 `service_archive_job`，不改变评价响应。归档成功不是评价成功的前置条件；任务插入失败则评价事务整体回滚，可同键重试。历史评价、评价重放不建新任务。后台独立事务写档案、私有文件关联、系统摘要审计及 DONE；失败均回滚，保留任务并每分钟重试。错误记录为脱敏分类，不存数据库报错或施工正文。

来源缺失时评价仍可成功，任务保持 PENDING；来源修复后重试。已冻结快照与当前可信报工不一致时拒绝生成，需人工核查来源，不自动覆盖快照。缺车辆/报工/质检签字/核销/付款事件、跨车主归属、争议与支付异常均不可生成。没有历史扫描补写和公开卡片。

## 本人查询与私有图片

既有 `GET /api/archive/list?vehicle_id=…` 返回手动/拍照档案及 `input_type=4` 施工档案；分页、会话、当前车辆归属校验保持。自动档案包含原字段（mileage=null）以及：

- `source`：order_id、report_id、review_id、redemption_id；不返回员工或车主身份。
- `test_mode`：测试核销来源，页面显示“测试施工记录，未真实扣款”。
- `parts_used`、`no_parts`、`work_minutes`：实际报工数据，工时单位分钟。
- `submitted_at`、`signed_at`、`redeemed_at`：UTC ISO 时间，档案日期为签字 UTC 日期。
- `file_ids`：仅施工过程/故障件/完工私有照片；不包括手写签字。

`GET /api/archive/{archiveId}/files/{fileId}/access` 要求正式车主 Bearer 会话，无查询参数。本人档案、当前车辆归属、原订单、完成任务、施工照片关系、文件所有者与 CLEAN 安全状态都满足才签发短时 URL，`Cache-Control:no-store`。仍不允许在普通 `/api/file/{id}/access` 读取员工文件。响应 `data={url,expires_at}`；400 参数无效、401 会话失效、403 角色不符、404 档案或图片不可用、503 存储或数据库不可用。

测试施工记录可供本人列表和首页查看，但不参与 AI 真实养护历史。原手动/拍照接口仍拒绝 input_type=4。

## 发布与排查

1. 备份数据库，执行 V017 两次检查任务表与三个唯一索引；无历史改写。
2. 先部署后端与支持 input_type=4 的小程序；默认 SERVICE_ARCHIVE_ENABLED=false。
3. 显式设置 SERVICE_ARCHIVE_ENABLED=true 并重建后端容器；SERVICE_ARCHIVE_INTERVAL_MS 默认为 60000。
4. 查询 PENDING、attempts、last_error、next_attempt_at；SOURCE_UNAVAILABLE 需核查来源，DATABASE_UNAVAILABLE 需核查事务/数据库。保留评价、核销和任务，不重复提交业务来恢复。
5. 关闭消费仅停止任务处理，不删除数据；恢复开启自动重试。旧客户端上线前不得开启消费。

RabbitMQ 运输层尚未接入当前消费者；真实相机、真机、测试证书直连预览及正式收款独立验收。
