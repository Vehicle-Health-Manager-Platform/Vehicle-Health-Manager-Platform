# R4a实施计划

1. [x] 核对上传/PDFBox/Paddle、现有安全预算，明确本轮内核与后续任务接入边界。
2. [x] 实现严格页面/来源协议、PDFBox受限CLI原生提取与栅格计划、Paddle文档多边形适配。
3. [x] 实现受限内核运行与明确标识合成材料的本地实际PDF/OCR验收，修复问题。
4. [x] 冻结版本完成Java/Python及相关上传/OCR/构建/交接回归，记录限制和R4b接入步骤。
5. [x] 上传独立草稿PR，核对远端head，清理本轮资源，不合并部署。

R4b复用持久任务的会话/材料重验、撤销/TTL及页面人工核对；R5向量仍需独立embedding许可与版本。

最终21项真实内核/18项Java与11组回归全部通过、同一源码摘要。修复NIO/ImageIO与seccomp兼容、镜像源码来源、Windows测试有界重试以及前置覆盖率配置；没有公开任务/页面，R4b仍待接。报告见RECOGNITION_R4A_DOCUMENT_CORE.md。

实现f8c43b3已推送codex/document-extraction-core，草稿[PR248](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/248)依赖PR247。新head CI独立跟踪，未结束不计通过；不合并部署。
