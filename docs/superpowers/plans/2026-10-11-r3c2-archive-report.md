# R3c2 中文实施计划

依赖：R3c1 收口 PR #114；分支直接以 #114 的 head 为基线，保持堆叠关系，不在本轮合并。四阶段逐个提交、推送并建 PR。

## 阶段一：规格与数据边界

- 固定当前 `ArchiveService.list` 已允许的 `input_type IN (1,3)` 和 `input_type=4 AND JSON_EXTRACT(content,'$.source.user_id')=owner` 范围；不扩大历史语音/技师/商家权限。
- 固定日期区间（首尾含）、未知日期在全量/筛选时行为、同一 `REPEATABLE_READ` 快照、501 探测、3 MiB PDF 上限、脱敏字段白名单、无报告持久化。
- 固定普通 HTTPS `application/pdf` 下载与云托管 JSON Base64 双路径；云路径用版本化媒体类型，不能把 `cloudrun.invalid` 哨兵当可下载 origin。
- 固定 Noto Sans SC Regular TTF：`@expo-google-fonts/noto-sans-sc@0.4.4` 的 `400Regular/NotoSansSC_400Regular.ttf`（10,559,284 字节），只复制字体和 `LICENSE_FONT`，按 OFL-1.1 随仓库发布，不引入 Expo 运行包。
- 完成设计规格自检及进度/三端缺口/下一步文档更新，推送规格 PR；该阶段仅验证文档/契约自洽，不声称功能实现。

## 阶段二：后端私有报告

- 添加 PDFBox 3.x、固定无用户控制的 ASCII 文件名 `vehicle-archive-report.pdf` 和 no-store 响应。
- 通过 OWNER `Jwt` 取得 `VehicleOwner`，校验当前有效会话、用户状态、本人未删除车辆；参数只接受 `vehicle_id` 与可选完整 `from`、`to` 日期对，拒绝重复/未知参数。
- 在只读 `REPEATABLE_READ` 事务内加载脱敏车辆摘要及最多501条 allow-list 档案（手动/拍照、本人已完成施工归档），同步获取符合范围的未知日期数量及每条附件数。事务返回 DTO 后才生成 PDF，不读取施工进行中/争议记录，不暴露来源 ID 或任何私有对象键。
- 默认全部导出含 `recorded_at IS NULL` 并标注；筛选时排除它们且列明未分类数量。超过500条立即返回409；PDF生成后大于3 MiB返回409，不返回局部字节。
- 普通 HTTPS 无自定义 Accept 时返回 PDF；云端协商 `Accept: application/vnd.autocare.pdf-base64+json;version=1` 时返回固定 JSON 外层 `code/message/data`，`data` 严格包含版本、固定文件名、MIME、字节数和 Base64。错误仍为 API JSON。两种成功路径语义同一 PDF，全部响应 no-store。
- PDFBox 按 A4 分页、纯文本安全绘制并嵌入 Noto Sans SC 子集；仅渲染实际提供字段。同步增加 PDF 可解析/中文提取/字体嵌入测试、MockMvc OWNER/错误/响应头测试、真实 MySQL A/B归属/软删除/NULL 日期/边界测试。更新 `ARCHIVE_MANUAL.md` 和 OpenAPI 生成源。

## 阶段三：微信小程序下载与打开

- 档案页添加“导出档案”及可选日期范围，校验真实日历日期、from/to 配对和顺序；生成中防重复提交，错误文案对应400/401/404/409/503。
- 在 `api-runtime` 显式透传 `downloadFile`、`openDocument`，普通 HTTPS 模式附 OWNER Bearer 和 `Accept: application/pdf`；校验任务状态、MIME、固定文件名/受限 Content-Disposition、临时路径与1字节至3 MiB大小。
- 云托管模式通过现有 `request/callContainer` 取 JSON 信封；严格校验字段/版本/MIME/长度/固定文件名/Base64 合法性及解码后 PDF `%PDF-` 魔数，再用原生文件系统写入 userDataPath 的随机临时文件。H5 构建保留能力检测，不伪装成已验原生打开。
- 下载期间 `onHide/onUnload`、注销/会话主体变化、车辆切换和新导出都递增代次并取消任务、清理临时文件；迟到回调不得打开文件。调用 `openDocument` 期间保留它所需的路径，文档查看器返回小程序后或页面销毁时再删除；系统调用失败可重试且不保留个人报告副本。
- 增加离线测试：普通与云托管传输、无 token/错误响应、错误 MIME/Base64/长度/魔数、超限文件、请求取消及所有生命周期变化。通过 mp-weixin/H5 构建后推送页面阶段 PR。

## 阶段四：验收与文档收口

- 后端真 MySQL：A/B车主、OWNER会话过期/撤销、停用/软删车辆、施工 `source.user_id`、施工未完成/争议、日期首尾、未知日期、无过滤、0/500/501条、3 MiB上下界。
- HTTP：Bearer、OWNER与非OWNER、固定404、no-store、普通 PDF MIME/Disposition/魔数/可解析和 JSON Base64 可反解成相同 PDF 字节；每种失败都没有部分正文、表写入、审计或文件对象副作用。
- PDF：提取中文标题/档案备注、脱敏摘要、来源与施工测试标记；确认 embedded font 为 Noto Sans SC 子集、许可文件在仓库、记录数量与数据一致。
- 微信：运行全部离线套件及 mp-weixin/H5 build；若环境支持，真实 HTTP 下载；用开发者工具/真机打开 PDF 的视觉检查列作 R9 独立验收，不把模拟任务/构建当通过。
- 更新 `CURRENT_STATUS.md`、`NEXT_STEPS.md`、`ROLE_GAP_ANALYSIS.md`、三端业务规划、API契约、阶段执行/本机验收记录与 R3c3 计划。只写实际观察结果。推送收口 PR 并等待六项 CI 全绿；不自动合并。
