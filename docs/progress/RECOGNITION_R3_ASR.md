# R3 ASR持久异步任务（2026-10-27）

分支codex/asr-durable-jobs，承接PR244/a611312；本阶段不合并或部署。设计见[规格](../superpowers/specs/2026-10-27-asr-jobs-design.md)、[计划](../superpowers/plans/2026-10-27-asr-jobs.md)，接口与操作见[ASR任务](../api/ASR_JOBS.md)，资源准备见[服务说明](../../services/asr/README.md)。

## 软件实现

- V063新增recognition_job（当前132表、63迁移），保存任务作用域、原创建会话标识/期限、来源元数据hash、租约、状态和短期候选；不存JWT/音频/对象key。实际读取字节后单独记录内容SHA-256，不把元数据hash当成内容hash。
- OWNER创建/查询/取消三接口，严格JSON、幂等、每账号分钟5次且最多2个活动任务；只读本人同车辆同用途CLEAN MP3，不放宽现有16kHz mono/48kbps/1..60秒/3MiB上传政策。
- 短事务SKIP LOCKED领取、单实例后台线程、租约/CAS完成，原会话撤销/源变化/取消/到期/旧租约不交付；丢失worker失败不自动重推。候选自创建后10分钟失效清空，元数据和原音频保留政策另记。
- 私有Provider有90秒期限、64KiB与严格JSON/响应头；离线faster-whisper small CPU/int8适配，固定本地manifest和实际包版本，禁止模型简称自动下载。解码/推理在受限子进程，生产要求Linux无网络及cgroup内存限制；默认关闭，不返回模拟生产转写。
- 小程序已上传音频独立同意、创建/轮询/取消、未知创建原键重试。未知取消保留恢复句柄；未知创建的恢复需要重新同意后确认原创建再请求取消，不承诺隐藏即已取消服务端任务。
- 候选展示时间片段与未知可信度，可修改并重新核对；带入前再读取当前授权、TTL、源片段签名与原表单快照，避免旧文字覆盖新输入。只带入AI问题/档案备注，仍需原发送/保存按钮；不自动产生诊断事实。

## 测试、修复与当前证据

最终统一10组全部通过，执行前后源码摘要一致：小程序1189/1189（新增ASR34项）、Python541/541、OCR76通过/1平台跳过、ASR26/26、微信/H5构建、普通微信无身份测试桥与原生共享样式、OpenAPI/迁移挂载/交接索引/契约结论检查均通过。

后端选定12套159项最终分项通过：JdbcAsrJobs27、AsrHttpJourney5、AsrResult5、AsrHttpClient6、JdbcLicense15、LicenseHttp4、JdbcVin11、VinHttp4、JdbcUpload17、JdbcMileage12、JdbcCommunityQuestions16、JdbcCouponTransactions37。原矩阵158项有一处CHECK异常翻译断言失败，修正后26项回归通过；随后fresh GET租约实际修复又单独回归JdbcAsr27+HTTP5共32项通过。不是同一Maven的最终全量运行，也未全跑所有后端。真实TCP/JWT/MySQL、原档案显式保存及132表/重复旧迁移已验证，ASR/存储/音频为明确替身。

修复记录：

- 已知创建成功后移除了创建回执，导致取消网络未知时没有恢复句柄；补保留已知任务的取消句柄，并增加只重试取消、不重建任务的回归。
- 候选到期时原本只在带入前查询阻止，页面缓存仍可能留存；新增本地到期定时清空候选/缓存副本，已明确带入的原表单文字不删除。
- 模型子进程剥离服务凭据/HF令牌，增加实际子进程环境回归；修复扁平/app部署目录固定parents索引导入失败，并增加浅目录导入回归。
- 查询立即将过期RUNNING租约标为FAILED/WORKER_LOST，不等待定时清理；旧租约仍不能完成或交付。
- 取消后旧created引用会让按钮显示“查询”却创建新任务；清除引用并要求显式清空/重新同意后再准备。
- 模型清单起初要求preprocessor_config.json；官方small目录实际需要vocabulary.txt，已核查并修正，权重校验改为流式hash。
- 私有服务配置/manifest通过不等于模型可转写，健康状态在实际成功推理前仅CONFIG_VERIFIED，测试引擎永远TEST_ONLY；补超时清理锁释放、早期HTTP错误排空及重复头检查。
- 后端首轮夹具复用了refresh_hash触发唯一约束，同键不同正文用未授权材料本应先404；改独立会话hash和本人合法第二材料，不放宽鉴权。MySQL CHECK错误经Spring译为UncategorizedSQLException，测试改精确验证SQL错误3819。
- 首轮小程序4项本机回环测试受沙箱EACCES阻止，获准重跑后全过。H5脚本补等待Vue/uni控件更新与独立测试文档上下文，不把脚本时序失败算业务通过。

gstack browse 390×844合成H5完整旅程17断言通过：录音规格、选录音不上传、独立上传与转写同意、未知创建原键复用、排队/运行、片段/未知可信度、人工修改清核对、原问题变化拒绝覆盖、取消迟到丢弃、带入前新查询、仅带入而未发送。会话、录音、上传和任务来源均为替身；截图已检查，不是实际麦克风、真实ASR或微信真机验收。

## 配置与真实模型边界

未安装新ASR依赖、下载权重或运行真实转写；真实中文、方言、噪声、静音、CPU时延/内存及手机效果均未验。requirements.in只固定faster-whisper1.2.1直接需求，不是完整transitive/wheel hash锁；Dockerfile是待验证构建入口，不是已构建/验收镜像。构建需仓库根context、operator准备的asr_wheels命名上下文及全量requirements.lock，Linux运行必须实际验证资源与网络隔离。

官方依据使用gstack读取[faster-whisper官方实现](https://github.com/SYSTRAN/faster-whisper)、[1.2.1发行](https://pypi.org/project/faster-whisper/1.2.1/)和[small模型文件目录](https://huggingface.co/Systran/faster-whisper-small/tree/main)。具体模型revision须资源准备负责人记录实际40位版本与文件摘要，不伪造已取得某个版本。

当前辅助音频上传仍沿既有可直达HTTP(S)材料接口，微信callContainer模式不承载multipart；JSON任务接口可以走现有云通道。云身份、短信、生产资源/发布继续后置，不将本地替身路径宣称为云端真机闭环。

## 其余待办

完整最终版本后端/全API、Linux实际构建运行、权重/依赖资源、获授权语音效果集、手机及原材料保留/本人删除政策仍待验。下一步先准备ASR完整离线依赖/模型并做Linux实际运行与授权样本验证，随后R4图文/PDF复用持久任务授权/TTL约束，R5向量需独立embedding许可/版本/撤回检查；本轮不等于其余所有功能开发完成。

脱敏机器证据：[ASR_JOBS_2026_10_27.json](../testing/ASR_JOBS_2026_10_27.json)。本机原日志/截图/测试凭据仅在.cache/stage-handover；最终10组日志在.cache/local-delivery-620ed3d2858c，均不上传。前置PR244最终8项CI成功、仍OPEN，本轮未合并部署。
