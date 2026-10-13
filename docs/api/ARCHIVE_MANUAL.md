# 本人车辆档案手动与拍照录入

> 2026-10-13：本人档案类型扩为1..10，新8行驶证/9驾驶证/10故障可进入本人PDF，分享/自动AI仍限定1..7；见[R4完整契约](R4_COMPLETION.md)。

2026-10-09 A7.2b：列表已兼容 input_type=4 的施工自动档案；手动/拍照写入仍仅接受1/3。施工记录含来源、实际配件/工时/时间及test_mode，员工施工照片使用档案专用授权。下面为手动/拍照规则，自动档案与发布恢复见 [施工档案](SERVICE_ARCHIVES.md)。

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

## 导出本人档案报告

`GET /api/archive/report?vehicle_id=123` 只允许有效 `user/OWNER` 会话导出本人未删除车辆。可选 `from=YYYY-MM-DD&to=YYYY-MM-DD` 必须成对、使用真实日期且 `from <= to`；首尾均含。拒绝重复或未知参数。服务端在只读 `REPEATABLE_READ` 快照中读取与本人列表相同的手动/拍照记录和 `source.user_id` 属于当前车主的完工施工归档，不读取历史语音、其他车主或施工中数据。未筛选时日期为空的记录仍包含并标明；使用日期区间时日期为空的记录排除，报告列出未归类数量。

默认返回 `application/pdf` 和固定 `Content-Disposition: attachment; filename="vehicle-archive-report.pdf"`。微信云托管 `callContainer` 使用 `Accept: application/vnd.autocare.pdf-base64+json;version=1`，返回标准 `ApiResponse`；`data` 只有 `version=1,file_name,content_type,content_length,content_base64`。两种传输封装对应同一 PDF 字节，所有成功响应均 `Cache-Control: no-store`。PDF 使用仓库内 Noto Sans SC 字体子集，来源/再分发许可见 `backend/src/main/resources/fonts/NotoSansSC-OFL.txt`；报告按请求生成后直接返回，不保存副本或文件对象。

超过 500 条、生成的 PDF 超过 3 MiB 返回 409，提示缩小日期范围，不截断或返回部分文件。参数错误400、会话失效401、非车主403、本人车辆不可用404、数据库/字体/PDF生成故障503；所有错误均为 no-store JSON。报告仅包含脱敏车牌/VIN、车辆登记里程、档案日期/类型/来源/标题/备注/记录里程/附件数量，以及施工归档的测试标记/时长/配件/关键时间；不含姓名、手机号、码值、对象键、签名图、照片原件、健康评分或推算指标。

参数错误返回 400；失效会话 401；角色错误 403；车辆或图片不可用 404；数据库或事务不可用 503。跨车主图片与不存在图片使用相同的 404 文案，避免泄露他人资源。业务、幂等响应与成功审计一起提交，审计记录录入方式但不含标题、备注、对象键或签名 URL。详情见[手动设计](../superpowers/specs/2026-10-04-archive-manual-design.md)与[拍照设计](../superpowers/specs/2026-10-05-archive-photo-design.md)。

V005 增加 `vehicle_archive_file`，已有数据库应先应用 V001–V004，再执行 V005；新建 Compose 卷自动按顺序执行。未进行真实微信、私有 MinIO/ClamAV、HTTPS 合法域名或真机联调前，不将其计作真实环境验收。
