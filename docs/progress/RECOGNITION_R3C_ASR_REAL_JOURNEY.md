# R3c Java真实ASR任务联调与进程故障（2026-10-27）

承接PR246/864ac3e，分支codex/asr-real-journey；不合并、部署或启用生产开关。[设计](../superpowers/specs/2026-10-27-asr-real-journey-design.md)、[计划](../superpowers/plans/2026-10-27-asr-real-journey.md)、[运行/配置与接入说明](../../services/asr/README.md)、[脱敏机器证据](../testing/ASR_REAL_JOURNEY_2026_10_27.json)。

## 已完成与实际范围

新增显式开启才运行的Java真实模型旅程和隔离入口。实际Spring/JWT/MySQL、生产调度worker及Java HTTP客户端调用固定Linux faster-whisper模型：幂等创建/查询/权限、AI用途候选不自动写档案、ARCHIVE用途候选经原接口显式幂等保存、数据库JSON备注与候选文字一致、实际推理期间取消/原会话撤销后不交付迟到结果。内容SHA-256与实际读取的MP3一致。

身份、CLEAN材料元数据和私有对象存储为明确夹具，英文来自本机Microsoft Zira合成语音。没有实际微信身份、上传/扫描、中文/方言/噪声或用户语音准确率验收。原档案API写入不是本轮浏览器/真机人工核对操作的渲染验收。默认不开启真实套件；单独验证未启用时7项全部跳过、构建成功，没有暗中启动模型或数据库。

模型容器只连接本轮内部网络，不发布端口，沿用2GiB/CPU2/PID128/只读资源与非root等限制。独立64MiB、非root、只读Nginx测试转发器接内部和专用桥接网络，只绑定127.0.0.1随机端口，目的地固定为已验证模型IP；不放宽产品URL校验，不代表正式部署路由完成。token及私有配置、原XML和日志只留在忽略缓存。

## 最终测试与故障结果

- 选定5套Java共50项通过，无失败、错误或跳过：RealAsrModelJourney7（4项实际模型旅程+3项继承核心旅程）、JdbcAsrJobs27、AsrHttpJourney5、AsrResult5、AsrHttpClient6。不是全后端或359项API最终验收。
- 实际进程13项检查通过：核对PID/命令/启动时间、pidfd绑定worker后SIGKILL、安全失败、回收及下一次实际模型恢复；杀死本轮supervisor后内核SIGKILL、subreaper收养并回收孤儿，无残留worker。
- 独立无网络256MiB容器实际OOM：memory.events中oom_kill由0增至1，服务返回安全503、主进程存活且健康状态回到CONFIG_VERIFIED。不是用一般退出或替身异常冒充OOM，也不代表生产容量/压力验收。
- 统一10组检查全部通过，执行前后源码一致：小程序1189、Python553、ASR40、OCR76通过/1平台跳过，微信/H5构建及普通微信身份桥/原生样式门禁、OpenAPI、迁移挂载、交接和契约一致性通过。新增6项运行入口及4项PID/过滤器保护测试，另补shell/PowerShell脚本纳入源码摘要的回归。

最终真实旅程和10组报告使用同一源码摘要。JUnit原件归档后，普通开关测试产生的7项跳过报告单独记录，不能覆盖或冒充实际50项通过。容器、内部/桥接网络和SQL夹具均已清理。

## 定位与修复

1. Docker internal网络没有实际宿主端口映射；保留模型隔离，改为独立本机回环转发器。NGINX只读根的默认fastcgi等缓存路径需要全部改为受限/tmp；Docker cp也拒绝只读根，改为非root exec通过有界stdin写固定tmpfs夹具，未放宽根文件系统权限。
2. 新增档案断言误把JSON的notes当独立数据库列，首轮50项中1项错误；按实际content JSON校验后，最终50项全过，没有改业务存储结构。
3. 故障脚本硬编码Seccomp_filters至少2，无法区分docker exec继承过滤器与worker新安装过滤器，可能过早杀supervisor。改为实际worker过滤器数大于当前父进程，并用pidfd防止PID复用；最终实际父退出SIGKILL/回收通过。Windows单元测试显式模拟Linux信号常量，不宣称Windows执行了Linux信号。
4. PR245/246最终7项CI成功（含后端），但compose-smoke失败：旧脚本仍期望V001–V062的131表，实际已是132。修正为V063/132并增加任务授权、来源hash、租约、候选TTL等7字段检查；源码摘要同时补纳入shell/PowerShell脚本，避免门禁脚本变化遗漏。

## Compose限制与剩余待办

本地完整Compose尝试在未缓存基础镜像拉取阶段遇到Docker Hub auth.docker.io连接超时，未完成整个栈。没有把网络失败当产品回归通过。另用已缓存MySQL、无网络的新库实际执行全部63迁移与修正脚本中的原样5组SQL：业务字段15、表数132、识别字段7、邀请表3、奖励表4均通过且清理完成。这仅验证数据库门禁，不能替代完整Compose HTTP/网页/权限验收；新head远程CI需独立跟踪。

仍未验：完整当前后端/全API、真实私有存储和扫描接入、中文/方言/噪声/真实用户效果、iOS/Android、云上传、真实身份/短信/支付和生产性能/发布。真实样本必须具备授权和人工对照，不能由本轮英文合成样本推断效果。原音频保留/本人删除政策仍独立待办。

下一阶段R4先实现有限预算的图片/原生文字PDF提取和来源位置，扫描页才用受限OCR；复用任务授权/撤销/租约/TTL及人工确认。R5再处理独立embedding许可、固定版本/维度和撤回校验，不把KEYWORD或替身向量当语义检索验收。
