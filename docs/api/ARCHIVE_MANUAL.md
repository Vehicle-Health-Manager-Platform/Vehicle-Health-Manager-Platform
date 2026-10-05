# 本人车辆档案手动与拍照录入

要求正式 `user/OWNER` Bearer 会话及已应用 V005 的数据库。`POST /api/archive/add` 的 `Idempotency-Key` 为 UUID；24 小时内同键同规范正文重放原响应，正文不同返回 400。每次重放仍复核会话和车辆归属。

```json
{
  "vehicle_id": 123,
  "input_type": 1,
  "archive_type": 1,
  "recorded_date": "2026-10-04",
  "mileage": 32000,
  "title": "更换机油",
  "notes": "下次保养参考手册周期",
  "file_ids": [45, 46]
}
```

`input_type=1` 为拍照录入，必须有 1–5 个图片 ID；省略或设为 `3` 为手动录入，可无图片。方式 `2`（语音）尚未开放。拍照方式在规范正文中包含来源，手动方式沿用旧规范正文，保证旧请求键在部署后可同键重试；拍照与手动使用同一键会被判为不同正文。`archive_type` 为 1 保养、2 维修、3 保险、4 事故、5 改装、6 违章、7 年检。里程、备注可省略；标题必填，最多 80 字符，备注最多 1000 字符；图片最多五张且不能重复。图片先经 `POST /api/file/upload` 获得 `file_id`，保存时服务端在同一数据库事务中验证文件属于当前车主、未删除且 `CLEAN`，再创建档案与有序关联。仅上传图片不会创建档案。成功 `data` 为 `archive_id,vehicle_id`。

`GET /api/archive/list?vehicle_id=123&page=1&page_size=20` 先验证车辆属于当前车主，再分页返回拍照与手动记录，按发生日期和档案 ID 倒序返回 `{list,total,page,page_size}`；`total` 使用相同的方式过滤条件。每条含 `archive_id,vehicle_id,archive_type,input_type,recorded_date,mileage,title,notes,file_ids,created_at`。图片仅返回稳定文件 ID；预览时调用 `GET /api/file/{id}/access` 获取短时 URL，不把 URL 存入档案。首页摘要使用第一页的 `total` 与首条记录。

参数错误返回 400；失效会话 401；角色错误 403；车辆或图片不可用 404；数据库或事务不可用 503。跨车主图片与不存在图片使用相同的 404 文案，避免泄露他人资源。业务、幂等响应与成功审计一起提交，审计记录录入方式但不含标题、备注、对象键或签名 URL。详情见[手动设计](../superpowers/specs/2026-10-04-archive-manual-design.md)与[拍照设计](../superpowers/specs/2026-10-05-archive-photo-design.md)。

V005 增加 `vehicle_archive_file`，已有数据库应先应用 V001–V004，再执行 V005；新建 Compose 卷自动按顺序执行。未进行真实微信、私有 MinIO/ClamAV、HTTPS 合法域名或真机联调前，不将其计作真实环境验收。
