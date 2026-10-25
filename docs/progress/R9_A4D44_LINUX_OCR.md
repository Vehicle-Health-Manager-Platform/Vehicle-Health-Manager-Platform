# A4d44 Linux CPU推理镜像与出口约束

2026-10-25启动，2026-10-26交付，基线782daa5 / PR234全部8项CI成功。分支codex/r9-a4d44-linux-ocr，范围仅微信小程序共享OCR基础设施，默认网关关闭，未合并部署。

## 实现与实际结果

独立Dockerfile.runtime固定官方Python3.11.17/bookworm基础摘要，实际Linux禁网解析71包并逐一核对PyPI官方wheel字节数/SHA256，生成Linux专用锁和文件清单；Windows锁不复用。可选择公开镜像仅加速传输，最后按官方摘要验证，离线上下文安装并pip check。入口先校验依赖及模型再监听，非root运行，模型仅只读挂载，不烘焙凭据或推理时下载权重。

显式Compose覆盖：OCR只接internal网络、后端双网络接线，三项私有token/模型/wheel配置缺失均拒绝解析，普通配置仍关闭OCR和网关。子进程在初始化Paddle之前加载libseccomp网络拒绝规则，已有线程同步、未来线程继承，规则失败拒绝启动；默认/Windows未请求策略时兼容。内核拒绝socket/connect与发送接收/io_uring，不把internal网络当完整宿主沙箱。

最终真实Linux受限容器两次生成里程图均CANDIDATE/123456且须确认，401与无效JSON422通过，同一worker及启动身份复用；模型worker过滤器数3、HTTP父进程2，额外规则实际存在。Docker inspect与cgroup实读2 CPU、1536MiB、swap0、128 PID、64MiB临时空间、16MiB共享内存；非root10001、无新增权限、全部capabilities移除、只读根/模型、无宿主端口通过。外部控制服务正向可达，OCR父服务跨网请求失败，内核策略测试覆盖TCP/UDP/Unix及已有线程拒绝、管道/文件可用。

收口将全部验收容器绑定已核对的不可变镜像ID，避免其他构建改变同名标签后影响来源；按最终源码完整复验通过。正式镜像与wheel缓存保留供后续联调，运行容器和网络全部移除。

60项Linux OCR回归全部通过、0失败/错误/跳过；Windows运行同60项，59通过、Linux内核1项明确跳过。新增运行器审计7项通过，覆盖资源类型/权限/模型/网络旁路、精确worker参数、容器/网络查询类型与自有标签。Compose实际合并、默认关闭和三配置缺失拒绝均通过。来源稳定，容器/网络与私有env全部清理；版本与完整脱敏结果见[报告](../testing/reports/R9_A4D44_LINUX_OCR_2026_10_25.json)。

## 失败轮次与修正

1. 历史镜像拉取阻塞本次已解除，官方基础镜像拉取成功。容器内pip约20KB/s，宿主整套跨平台解析因OpenCV候选冲突失败，未放宽固定版本。转为实际Linux解析候选、下载官方元数据对应文件，再在network=none/ignore-installed的Linux解析最终锁。初始dry-run将基础镜像预装setuptools/packaging跳过，补齐固定版本后离线闭包成功。
2. urllib经本机代理出现TLS EOF，直连官方API200；显式直连与TUNA范围传输后全部官方摘要通过。gstack部分浏览器启动失败，Docker/libseccomp正文重试成功；TUNA帮助正文未取得，不冒称已核对。一次临时报告生成误用Windows GBK，尚未发布锁；改UTF8后生成成功。wheel测试首轮受限临时目录ACL拒绝，正常授权环境重跑7项通过。
3. 首轮构建APT官方HTTP入口502，不计镜像通过；改同一官方HTTPS、保留签名校验后构建成功。次轮模型已就绪，但进程探针按文本包含误把自身算worker；另因泛型inspect把同名network当container误报清理失败。实际资源已移除，改为精确argv及类型化查询，新增回归后整轮重跑通过，失败轮次不计总体成功。

## 边界与下一步

本阶段验收实际Linux CPU镜像/内核/网络及合成图，不是JVM/微信全链路、真实仪表准确率、手机权限或生产运行。没有进行耗尽RAM/PID、500并发或发布版安全扫描；OS包非快照锁，实际版本有记录。保留317/353历史API证据、36套/36结论、全API验收false。

下一阶段将Linux推理容器接入共享网关及微信旅程，继续补证；真实样本/手机及发布运维另验。完成文档与扫描后上传依赖PR234草稿PR。

新增内容扫描11项均已逐处核对：内存字节数/2 CPU对应NanoCpus被误作电话、OpenCV固定版本被误作IP、固定localhost健康探针源码片段被误作私有URL。仅精确允许上述四个匹配值后复扫，不允许实际凭据、个人地址或组织内部接口例外。
