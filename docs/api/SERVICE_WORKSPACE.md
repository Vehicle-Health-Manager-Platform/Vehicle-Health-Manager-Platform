# R7b 工作台与本单档案协议

设计与权限：[R7b设计](../progress/R7B_WORKSPACE_DESIGN.md)。实际九项接口均no-store；除明确允许的分页/分类参数外拒绝未知、重复query。正整数ID上限9007199254740991，分页1至1000000，page_size固定20。

## 当前任务

- GET /api/merchant/workbench、GET /api/tech/workbench：可选page、page_size、bucket。返回counts固定八分类，tasks为items/total/page/page_size；每项仅order_id/order_status/bucket/project_name/starts_at。分类RECEIVE/CONFIRM/DISPUTE/ASSIGN/WAIT_ACCEPT/SERVICE/VERIFY/REVIEW_SOURCE。数量与列表在同一数据库读快照计算。
- GET /api/merchant/orders/{id}/service-context、GET /api/tech/orders/{id}/service-context：可选page/page_size。返回order_id/order_status、vehicle四车型标签、project白名单、arrival_mileage本单接车里程、archives分页。ACTIVE项有record；SOURCE_UNAVAILABLE项record为null，不回传已变化原文。

技师必须当前已接单；只用门店号/技师号作为客户端参数不能获得访问。无授权时archives为空，最小本单资料仍可读取。

## 车主逐条授权

- GET /api/order/{id}/archive-grants：本人本单、当前车辆，page/page_size。返回order_id/service_active/records分页；每条record/source_version/grant_revision/grant_state，状态NONE/ACTIVE/REVOKED/SOURCE_CHANGED。
- PUT /api/order/{id}/archive-grants/{archive}：无query，必填Idempotency-Key UUID。ACTIVE正文严格为status/expected_revision/source_version；REVOKED严格为status/expected_revision。初次revision为0，成功加1。返回ApiResponse包裹order_id/archive_id/grant_revision/grant_state。409表示版本或源变化，必须重读并重新确认；未知结果用同正文同键重试。
- GET /api/order/{id}/archive-grants/{archive}/files/{file}/preview：车主授权前独立预览，不创建授权。

record仅archive_id/archive_type/recorded_date/title/notes/mileage/fields/files；类型1/2/10。fields为该类型服务字段白名单，files每项file_id/available。不可用图片仍可展示不可用事实；不向客户端提供对象存储key或上传者身份。自由文本和照片由车主核对后主动授权，并非机器脱敏结果。

## 受控图片

- GET /api/merchant/orders/{id}/service-context/archives/{archive}/files/{file}/access
- GET /api/tech/orders/{id}/service-context/archives/{archive}/files/{file}/access

图片接口均无query，返回url（HTTPS）与expires_at（UTC Instant，可能有小数秒）。签名前后都重验本单授权、源和当前文件；已发URL残余期限最多300秒，撤销拒绝新签名。授权前车主预览也按当前归属与来源核对。

400严格参数/正文错误，401失效会话/绑定，403身份不符，404范围或来源不可用，409版本冲突，503数据库/私有存储不可用。拒绝时同样no-store。完整结构见[OpenAPI](openapi.json)，新增九项后总230操作。
