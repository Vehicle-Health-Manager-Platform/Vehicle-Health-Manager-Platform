# A4d42 OCR硬截止与进程隔离

2026-10-24启动，2026-10-25收口，基线a462f9e / PR232八项CI均成功。分支codex/r9-a4d42-ocr-worker，仅微信小程序共享OCR基础设施。

## 实现

生产enabled入口由单个常驻子进程承担原始JSON、图片解码与真实模型推理；二进制输入帧6MiB、输出16KiB，独立看门狗从管道写入至结果读取默认8秒，启动30秒。超时/崩溃终止受管进程组，后台仅重建一次，不重放请求；重建失败不可用，等待运维重启。CPU线程固定2，不因异常返回空成功。

HTTP单推理无队列，最多4个处理连接和4个监听积压，头/体读取总3秒，慢速分段不重置；推理忙429、重建/异常503、无效图片422，满连接在TCP层拒绝。健康区分READY/BUSY/RECOVERING/UNAVAILABLE，启用模型的Compose检查仅READY/BUSY就绪。默认关闭仍501；历史直接注入引擎仅保留明确协议夹具，不能证明生产隔离路径。

Windows实际Job Object设置并查询组/进程内存1536MiB、活动进程8、父退出关闭句柄杀全组；Linux父死亡SIGKILL、独立进程组，WNOWAIT健康检测保留PID身份至清理，幂等回收防止重复发信号。额外覆盖主worker已退出但后代持有输入管道：看门狗仍终止受管组，不让父进程写入卡死。

Compose OCR配置2 CPU、1536MiB、128 PID、只读根、64MiB临时空间、去除capabilities等。实际模型和Windows内核限制已验证；Linux容器运行与耗尽内存压力并未验证，资源配置不能替代运行验收。

## 验证

最终Windows和Ubuntu各42项OCR回归通过、无失败/跳过，其中新增16项实际进程/HTTP生命周期测试。Ubuntu为Python3.14.4/Pillow12.3.0，源码按摘要核对后复制至隔离临时目录运行；Windows真实模型继续使用固定Python3.11环境。故障引擎是明确替身，覆盖挂起/崩溃/异常/启动截止/失败重建/无队列/帧上限/父退出/慢头/连接上限/关闭/身份和回收；不把替身结果当识别效果或Linux容器/真实模型运行验收。

初次GitHub Linux CI在41项中有4项恢复检查失败；独立标准库复现确认父进程仍在时，创建线程退出即触发worker的父死亡SIGKILL，与[Linux手册](https://man7.org/linux/man-pages/man2/PR_SET_PDEATHSIG.2const.html)一致。改为持续存活的单个监督线程统一创建和恢复worker，异常仍只尝试重建一次；新增“调用线程退出后继续服务及连续两次恢复”的回归在修复前Linux失败、修复后通过。WSL跨盘首次解码约350毫秒触及测试350毫秒截止，最终同一源码在Linux临时文件系统完成全量42项；未放宽产品8秒截止。

真实PaddleOCR 3.7.0 / CPU Paddle 3.3.1 / Pillow 12.3.0在隔离Job中，私有HTTP连续两次识别生成里程图均为CANDIDATE，唯一123456公里且保留本人确认；同一预热进程复用，错误JSON422不重载，完整关闭。未重跑A4d41微信/JVM旅程，旧观测注入路径不代表新worker验收。见[脱敏报告](../testing/reports/R9_A4D42_OCR_WORKER_2026_10_25.json)。

首轮连接容量测试预期HTTP503，但Windows未解析连接直接关闭会重置；按连接层限流语义改为TCP拒绝并实测，推理忙仍429。Compose首次核对因规范化内存为数字字符串导致校验断言失败，改为数值化核对通过；实际健康表达式5种状态使用明确HTTP返回替身验证，不冒称容器运行。

通过gstack浏览[微软Job Object文档](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)核对嵌套、最后句柄关闭和整组终止；首次浏览器启动超时，重开200。Docker再构建仍在python:3.11-slim授权元数据阶段网络超时，未生成或启动产品镜像，未换源隐藏失败。

交接索引、覆盖和结论对账通过；353项契约、317历史完整证据、36套/36结论，全API仍false。模板未增加客户端密钥；不记录图片原文、私有凭据或模型异常详情。

## 交付和下一步

实现提交ff0209b已上传[草稿PR233](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/233)，依赖PR232；最终head CI另核，未合并部署。下一阶段让隔离worker进入共享网关/微信验收夹具，随后补授权仪表/手机、Linux推理镜像与容器实测及出口隔离。

脱敏扫描将本机健康检查的源码URL片段、1536MiB对应字节数误判为内部地址/电话；核对实际配置和内核查询值后仅精确允许两个匹配值，复扫0发现，无组织地址、个人资料或凭据例外。
