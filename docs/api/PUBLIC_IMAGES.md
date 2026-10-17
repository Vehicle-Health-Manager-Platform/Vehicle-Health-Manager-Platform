# 独立脱敏图片公开

R8c2，V060；后续V061后全库130表。`PUBLIC_IMAGES_ENABLED=false`默认关闭，并依赖有效的经验公开配置和私有文件存储。所有接口要求当前登录并no-store；原始私有图片不签名给审核人或同款接收者。

## 原图与独立副本

车主选择当前本人车辆档案中本人上传的CLEAN原图，再经原私有上传接口上传已遮挡/裁剪隐私的独立副本，两个文件ID必须不同。仅JPEG/PNG副本；服务器有限读取10MiB、边长4096、600万像素并重新编码PNG，结果不超过2MiB，去除EXIF及尾随元数据。系统不声称自动识别人脸/车牌等视觉隐私，须本人检查和人工审核。

预览body严格`{experience_id,archive_id,source_file_id,copy_file_id}`，父经验必须为当前本人已获批来源；提交另加`preview_hash,consent_version:"R8C_IMAGE_V1",consent:true`。预览完整返回`{preview_hash,png_base64,width,height,model_id}`。生成快照与源指纹绑定，在短事务内重新锁定复核；读取对象和图像处理在事务外。

## 路由

- GET `/api/owner/community/image-sources?experience_id=UUID&page=1&page_size=20`：当前本人原图候选，行仅archive_id/source_file_id。
- POST `/api/owner/community/images/preview`：服务器重编码预览，不写业务状态，无幂等头。
- GET/POST `/api/owner/community/images`：本人申请分页/提交。
- POST `/api/owner/community/images/{id}/withdraw`：严格`{expected_revision}`。
- GET `/api/owner/community/images/{id}/preview`：本人已提交快照，来源失效时仍可核对；损坏快照拒绝读取。
- GET `/api/admin/community/images`：can_review待审分页，不含原图、档案或对象键。
- GET `/api/admin/community/images/{id}/preview`：can_review只读取主动提交的当前有效待审副本；父来源失效拒绝新读取。
- POST `/api/admin/community/images/{id}/moderate`：严格`{expected_revision,decision,reason_code}`；APPROVE理由null，REJECT理由PRIVACY/ABUSE/OFF_TOPIC/UNVERIFIABLE。
- GET `/api/community/images?vehicle_id=ID&experience_id=UUID`：本人同款、父经验当前有效时列出最多20张获批图片。行仅public_id/width/height/published_at。
- GET `/api/community/images/{publicId}?vehicle_id=ID`：重新核验当前本人车型、父内容、源指纹和最新原事件/审计，返回独立PNG字节。

三个图片读取端点支持`format=base64`，返回统一信封data`{png_base64}`，用于云托管JSON和微信/H5；不发可绕过当前授权的永久URL。默认二进制image/png、nosniff和no-store。未知/重复/额外query拒绝。

## 状态与恢复

提交、审核和撤回要求UUID Idempotency-Key；状态PENDING/APPROVED/REJECTED/WITHDRAWN及CAS版本。创建有永久原键/原body回执，来源失效或开关关闭后仍可恢复；本人列表、快照与撤回保留。其他操作采用23小时客户端安全窗口。每父经验最多20条PENDING/APPROVED申请，同原图仅一条有效申请；重新授权产生新的公开UUID，撤回后旧UUID永不复活。

原图关联、扫描、档案内容、车辆归属/车型、父公开来源变更会使冻结指纹失效；需新的独立预览和授权，不能用旧确认补造来源。快照PNG、车型索引、最新事件和审计被修改/软删或旧行回退时拒绝展示。撤回拒绝后续请求，已复制、截图或下载的内容无法远程收回。

实际页面`/pages/community/public-images`按owner/review/public分入口，绑定账号、角色及当前车辆。上传与申请分别保存原请求；存储失败阻止发送；人工审核须图片渲染成功并核对hash后才允许批准。切号、隐藏或撤权清空副本及迟到结果。
