# 图文提取内部协议（R4a）

这是受限内核的本机进程协议，**没有新增公开HTTP接口**。不能直接从小程序调用或凭材料ID跳过权限；R4b还需实现任务/当前身份与材料绑定、撤销/TTL和页面人工核对。公开API清单仍为359项，不把本轮库函数算成已接入业务接口。

[准备/运行/预算/回滚边界](../../services/documents/README.md)、[设计](../superpowers/specs/2026-10-27-document-extraction-core-design.md)、[测试与交接报告](../progress/RECOGNITION_R4A_DOCUMENT_CORE.md)。

## 请求

只允许version整数1、content_type和document_base64；媒体为application/pdf、image/jpeg或image/png。禁止URL/路径/附加字段、重复键、布尔version、非法base64或超预算正文。二进制在受限子进程验证和处理，不把JSON格式正确当作文件安全。

默认DOCUMENT_CORE_ENABLED不启用。显式开启且Linux/cgroup限制满足后，ProcessDocument才使用固定child命令；_command仅供明确标识的进程单元测试，生产构造不走替身。

## 候选

固定顶层键：version、task=DOCUMENT、source=PDFBOX_PADDLE、content_type、content_sha256、page_count、pages、status、requires_confirmation、image_recognition_performed、all_pages_processed。

每页固定键：page（连续1起）、method（NATIVE_TEXT/OCR/EMPTY）、text、raster_size、blocks。每块只有text、confidence、source；整个文档受4页/80块/4096 UTF16限制，每块最多500单元。无法满足预算整次失败，不返回部分结果。

- 原生来源为kind=TEXT_OFFSET及start/end：对应本页规范化text的UTF16区间，必须与块文字逐一一致且覆盖所有非空行，confidence=null、raster_size=null。
- OCR来源为kind=RASTER_POLYGON及4个实际点：相对PDF渲染/EXIF规范化后栅格的左上像素坐标，X向右、Y向下；raster_size为宽/高。多边形有限、凸、非退化且在栅格内；分数为模型实际有限0..1值，不能伪造百分比。
- EMPTY页无文本/块、无栅格；OCR执行后无值仍保留method=OCR和真实尺寸。status仅为EXTRACTED或EMPTY，image_recognition_performed必须与实际页方法一致。

content_sha256绑定本次原始文件字节，父进程再次核对媒体类型与hash，不能用旧来源结果完成本次提取。all_pages_processed=true指所有页已处理，不保证OCR正确、文字层与视觉一致或可直接形成维修事实。requires_confirmation始终true。

## 错误与边界

INVALID_DOCUMENT：坏/不支持媒体、加密/活动内容或静态策略拒绝；LIMIT_EXCEEDED：页数/文字/块数/栅格/正文超预算；INVALID_RESULT：来源/多边形/分数或结果结构无效；UNAVAILABLE：关闭、模型/运行资源、截止或进程故障。错误不含原文、路径或供应商诊断。

Java固定CLI只有stdin字节和受控工作目录，不接用户路径/参数。输出仅有限计划JSON及固定page-N.png；复制Java产物只允许提取器和校验所需类及固定PDFBox/Jackson依赖。原PDF不加载OCR，视觉页才加载已核验的Paddle模型。

取消、原会话撤销、车辆转移、材料状态变化和TTL的公共业务语义尚未接到该库；必须由R4b复用既有持久任务约束。当前结果不写AI/档案，不生成日期/金额/里程业务字段。缺少授权、来源不符、保留/本人删除政策和真实效果验收不能靠合成数据证明。
