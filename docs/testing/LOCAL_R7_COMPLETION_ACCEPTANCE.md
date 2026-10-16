# 整 R7 统一验收（2026-10-16）

基线R6 `956758d`，分支`codex/r7a-owner-evidence`。本文件记录整阶段最终源码的统一结果，不把R7各子阶段继承/重跑用例相加。

## 已通过

- 小程序全部664/664，0失败/跳过。基线648，收口新增3项精确登录回跳、9项施工恢复/身份顺序、4项配置恢复窗口/清理并发。旧未知请求改正文产生新键的测试改为断言拒绝改变、原正文原键重试。
- 最终微信和H5构建；微信全局共享样式与普通包不含原生测试身份桥检查；四个兼容Web构建。
- 真实MySQL V001–V048初始化、重复迁移和合成种子验证，104表；临时容器在脚本完成时清理。
- Compose现有两个配置解析成功。141追踪、44表基线生成、OpenAPI256操作和OCR Python编译成功；85路由/首路由、832引用、48有序唯一SQL挂载、47份变更文档本地链接及原基线不变通过；编译器AST确认26个变更Vue内24处重试绑定有效。
- 既有局域网帮助器21项、身份就绪检查6项（以模块方式运行）通过。它们不是正式身份或真机验收。

## 后端完整回归

上传前本地快照：排除`RealUploadAdaptersTest`的其余全部Maven测试已完成66类/499项，零失败/错误，仍继续运行；真实MySQL、HTTP、并发、恢复及既有兼容均包含。此快照不能称为全套后端通过。最终完整测试（含真实上传）以[PR153](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/153)当前head完整CI及正文记录为准。

命令：`mvn -B '-Dtest=*,!RealUploadAdaptersTest' test`，Java17/Maven官方容器及本机Docker套接字，`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`。不为网络失败跳过其他数据库组。

## 本地环境阻断及远程门禁

真实上传测试镜像构建在`FROM alpine:3.21`元数据认证失败：`https://auth.docker.io/token`连接超时；本机无该基础镜像缓存。真实上传适配类未运行，完整Compose实际烟测也依赖这两个镜像，因此本地只验证Compose配置，不冒称实际启动烟测通过。Dockerfile、镜像版本、摘要及校验脚本未放宽。

R7源码及其余门禁完成后，所有编辑代码/迁移/测试/文档同批上传，使用原有GitHub六项CI验证最终head；远程backend包含真实上传全类，compose-smoke验证实际容器启动。通过/失败及实际数量在最终PR正文记录。整R7已同批上传PR153，尚未合并或发布；远程结果以最终head为准，不提前写成功。

## 日志与边界

本机`.cache/r7-completion-{backend,miniapp,mp,h5,web,schema}.log`及`r7-upload-images.log`仅本机保留，不提交生成依赖/缓存日志。完整后端运行期间不并行启动写同一target的另一次Maven测试。

R7a/b/c局部失败定位与定向复验保留在原阶段文档；收口首次旧预期失败、检查脚本调用错误及修正见`findings.md`。正式供应商、短信/微信身份、真实资金、物理设备及生产发布仍R9，不能用本次自动化代替。
