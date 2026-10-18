# R9-A2b 受控辅助材料接口

## 开关与依赖

V062 在 V061 后运行，可重复执行，增加 input_material，当前131张表。Compose包含62个有序初始化挂载。默认 `INPUT_MATERIAL_UPLOAD_ENABLED=false`；准备正式车主会话、MySQL、已有私有MinIO适配和ClamAV扫描后才开启。签发还需可访问的 `MINIO_PUBLIC_ENDPOINT`；本机测试使用合成身份/设施替身，不表示正式环境接通。

## 上传

`POST /api/input-materials/{purpose}/{vehicleId}/upload`

- purpose严格为AI或ARCHIVE，vehicleId为本人的未删除车辆；商家、技师、演示身份拒绝。
- Authorization为正式Bearer；必传UUID `Idempotency-Key` 与 `X-Material-Kind: audio|pdf`。
- multipart只接受一个file，无额外文件/表单字段。微信临时文件名不稳定，服务器按显式kind选择策略并验证真实字节，不保存或回显原名称。
- MP3最多3MiB，MPEG-2 Layer III，16kHz、单声道、48kbps，完整帧1–60秒；允许≤64KiB的无扩展ID3v2.3/2.4标签。时长按576样本/帧计算，不信任客户端时长。本校验验证封装/帧头与长度，不进行语音解码或准确率认证。
- PDF最多10MiB、1–50页；限制对象/引用数量，拒绝加密、表单、注释、动作、脚本、嵌入文件；不渲染页面、不解压页面内容流。复杂或带链接注释的PDF需另存为静态版本。
- 原请求入口仍限制11MiB、默认并发4；当前本人上传10次/分钟、签发60次/分钟。前端每次最多保留3份辅助材料，服务器单份上传，不把该数量误当车辆终身配额。

成功为标准ApiResponse，data严格包含：`material_id, purpose, vehicle_id, content_type, size_bytes, duration_ms, pages, scan_status`。音频pages=0，PDF duration_ms=0；只有CLEAN成功。不返回file_id、文件名、对象键或URL。客户端重试本次原材料使用原key；用途/车辆独立隔离，不能拿同键覆盖内容。

## 访问

`GET /api/input-materials/{purpose}/{vehicleId}/{id}/access`：本人当前会话、车辆归属、原用途、CLEAN且未删除。对私有对象再次核对MIME/大小，签发前后复核归属，成功data为url和expires_at。60秒链接强制attachment及no-store，下载名固定material.mp3/pdf；链接是临时访问凭证，不写日志/持久存储。签发后的链接可能在剩余60秒有效期内继续可用。

材料与业务图片分别存储元数据，material_id不用于file_ids或原图片签发。当前UI只展示上传收据，本机试听/预览继续原路径；尚无材料管理/删除界面，移除本机引用不等于删除服务器材料。外部识别及删除/保留期限策略另行交付，未自动调用供应商。

## 失败与事务

400参数/字段无效；401会话失效；403身份无权；404车辆/材料不可用；409同键异内容或仍处理中；413过大；422格式或病毒检查未通过；429限流/并发；503开关/设施不可用。固定安全错误不回显文件或供应商细节。

验证/扫描/对象写入在数据库事务之外；预留、归属复核、收据与审计为短事务。对象写成功但元数据/审计失败进入原上传补偿队列；不确定提交先恢复成功收据，清理前再次确认没有成功元数据，不能删除已成功材料。
