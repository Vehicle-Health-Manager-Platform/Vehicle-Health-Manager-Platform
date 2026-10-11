# R3c2 本人档案报告与私有导出：下一阶段计划

更新日期：2026-10-11。R3c1 接车扫码与独立油表照片已完成阶段实现并上传至 PR #111–#114，尚未合并；本阶段以 #114 为直接基线，范围只含车主微信小程序和必要后端。OCR/VIN 服务未开通，沿用手动确认/回退方向；不建 PC 后台。

## 用户目标与已确认方案

车主从本人车辆档案页按全部记录或日期区间导出中文 PDF。后端对当前 OWNER 会话和本人车辆授权，在单一只读 `REPEATABLE_READ` 快照中读取与现档案列表相同的记录来源，再于事务外生成 PDF 并直接返回；不保存报告、副本、对象或业务审计。普通 HTTPS 传输直接下载 PDF；微信云托管 `callContainer` 通过校验严格 JSON Base64 信封传递同一 PDF 字节。PDF 超 3 MiB 或档案超 500 条时明确失败并提示缩小区间。

数据仅含本人手动/拍照记录及 `content.source.user_id` 属于本人的已完成施工归档。历史语音记录不在现档案列表权限范围，不新增读取。未知日期在全部导出时标明；日期筛选时排除未知日期且列出未归类数量。车牌/VIN只用现有脱敏摘要；不导出姓名、手机号、码值、文件对象键、签字图或照片原件。不合成车辆评分、六项指标、证件或趋势。

中文字体采用 Noto Sans SC Regular TTF，包来源 `@expo-google-fonts/noto-sans-sc@0.4.4`，字体许可证 OFL-1.1 随资源保留；仅提取字体文件与许可，不引入 Expo 运行代码。PDFBox 嵌入使用字形。原始交付文档保持原样。

## 分阶段交付

1. **规格阶段**：核对字段与权限、日期缺失语义、500 条/3 MiB 限额、普通/云托管双传输、PDF 隐私及授权；更新本规格、详细实施计划、全局当前进度与缺口清单。自检后提交推送为依赖 #114 的规格 PR。不得把规格阶段 CI 当功能验收。
2. **后端阶段（实现完成，真库待 CI）**：新增 OWNER `GET /api/archive/report`。同事务校验有效会话、本人未删除车辆、过滤允许的档案源/日期并取 501 判限额；一次性返回 allow-list DTO，事务外用 PDFBox 与上述 OFL 字体生成 PDF。普通 HTTP 返回 `application/pdf`；云托管 `Accept: application/vnd.autocare.pdf-base64+json;version=1` 返回固定字段 Base64 信封。两者均 no-store、固定文件名；PDF 失败或超限无文件/表/审计副作用。中文 API 契约、OpenAPI 与执行记录已更新；本地单测/权限链通过，Testcontainers 本机无法连 Docker daemon，真 MySQL 用阶段 PR CI 验证。
3. **微信阶段（本地实现/测试构建完成，PR上传中）**：档案页增加日期区间选择与导出入口；普通 HTTPS 用带 OWNER Bearer 的原生 `downloadFile`，云托管继续使用 `request/callContainer` 并严格解码 Base64 到临时 PDF。验证状态码/MIME/文件名/大小/版本；页面隐藏、退出/换身份、切车或新任务时取消下载、作废迟到回调并删除临时文件；成功后用 `openDocument`。小程序330项通过，mp-weixin原生构建检查和H5兼容构建通过；Terser登记为 workspace 直接依赖。执行细节见[微信阶段记录](R3C2_ARCHIVE_REPORT_MINIAPP_EXECUTION.md)。
4. **验收收口（待实施）**：真 MySQL覆盖 A/B隔离、软删除、施工归属、日期边界/空日期、500/501、3 MiB上限；HTTP 覆盖 OWNER/角色、no-store、PDF 与 JSON 信封；PDF 解析和中文提取、字体许可/嵌入检查；微信生命周期和云托管假运行时测试。真实微信设备视觉/PDF 打开另列 R9。更新当前状态、三端缺口、执行/验收与下阶段 R3c3 计划，推送末阶段 PR，等待 CI 全绿；不自动合并。

## 错误与验收边界

`400` 参数无效，`401` 登录过期，`403` 角色不符，`404` 车辆不可用，`409` 条目数或 PDF 文件超过上限，`503` 数据/字体/PDF生成暂不可用。成功与错误均 `Cache-Control: no-store`；无部分 PDF。普通下载仅接受配置 HTTPS API 来源，云托管下载不得使用 `cloudrun.invalid` 哨兵。不得把 H5 构建冒称为微信真机验收。

规格以 [R3c2 设计](../superpowers/specs/2026-10-11-r3c2-archive-report-design.md) 为准。R3c3 后续单独规划分享授权摘要、有效期、撤销和访问审计；不把个人下载接口用作分享链接。正式短信/微信登录/真实资金/真机相机与公网证书保留 R9。
