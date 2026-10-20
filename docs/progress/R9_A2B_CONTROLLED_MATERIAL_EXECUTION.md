# R9-A2b 执行记录

2026-10-18。基线d68d7da，前序PR156/157仍未合并。按用户要求规划后实施，代码/迁移/测试/契约/使用与验收文档同阶段上传，不自动合并或部署。

## 实施内容

- 独立MP3/PDF服务器策略，不扩充原图片白名单；静态PDF对象遍历和活动内容限制，MP3逐帧时长。
- V062独立材料表；既有上传请求机制支持用途/本人车辆范围，扫描CLEAN后原子写材料/收据/审计，异常写入可补偿。
- 车主专用入口复用认证、请求体前授权、容量/限流/并发；私有访问60秒强制附件、no-store，签发前后复核车辆与会话。
- 共享材料组件单份显式上传、原键重试、上下文变化与离页隔离；文本AI和档案正文不自动携带材料。
- 开关默认关闭，Compose和示例配置同步；352 OpenAPI操作、131表及62初始化挂载。

## 执行发现

1. PDFBox 3没有旧版getObjects方法，已根据缓存JAR的实际API改用受限xref池遍历。
2. 本机Docker Java客户端默认API不兼容，首次数据库测试未启动；使用既有JAVA_TOOL_OPTIONS=-Dapi.version=1.44后恢复。
3. 新测试初始漏V010上传归属列，并缺MySQL测试触发器配置；已补完整前置并重跑。生产迁移按V001–V062顺序，不修改旧迁移。
4. 微信普通构建受开发工具目录锁影响，关闭指定项目后重建通过。

## 最终验证与上传

前端899/899、普通微信与H5构建通过，普通产物无演示令牌/钩子；独立微信模拟器13检查/24请求通过并返回三端入口。后端相关套件64/64通过，最终格式规则及新增PDF入库定向复验7/7通过（6项重复、1项新增，合计65个不同测试）。完整V001–V062重放/重复迁移/合成种子通过，62挂载及生成文件一致性检查通过。代码/迁移/测试/文档已上传[PR158](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/158)，代码提交 `69e248b`，base为 `codex/r9-a2a-local-input-materials`。远程全量CI尚未结束，保持草稿，最终结果以PR当前head为准，不提前声称六项通过。见[验收](../testing/CONTROLLED_INPUT_MATERIALS_ACCEPTANCE.md)、[接口](../api/CONTROLLED_INPUT_MATERIALS.md)、[使用](../user-guides/CONTROLLED_INPUT_MATERIALS.md)。

后端命令：`mvn -o -B -Dtest=InputMaterialValidatorTest,JdbcInputMaterialTest,JdbcUploadHttpTest,PrivateUploadTest,PrivateFileAccessTest,UploadHttpTransportTest test`，再以 `InputMaterialValidatorTest,JdbcInputMaterialTest#pdfPersistsServerDerivedPageCountWithoutImageMetadata` 复验最终规则。Java17/Maven缓存容器执行，未跳过Docker数据库测试。真实MinIO新增附件响应头测试编译通过但本机未执行，留当前head全量CI验证。前序PR156现已六项CI通过，PR157后端仍运行。

## 下一步

A3识别候选核对：先落实手动候选/修正/确认入库和可替换识别契约；真实ASR/OCR仍B2后置。辅助材料的保留/删除管理、正式设施及真机必须单列，不将上传完成视作语音识别完成。
