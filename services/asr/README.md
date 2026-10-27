# 受限离线 ASR 服务

持久任务接口见[ASR任务](../../docs/api/ASR_JOBS.md)，默认开关保持关闭。R3b已在本地Linux x86_64容器实际加载固定small模型，验证英文合成语音、纯数字静音、超时恢复和网络禁止；这不代表中文、方言、噪声、手机或真实用户准确率验收。[运行报告](../../docs/progress/RECOGNITION_R3B_ASR_LINUX.md)分别列出证据和剩余事项。

## 固定资源与准备

- models.lock.json固定Systran/faster-whisper-small的实际40位revision、官方文件URL/字节数/SHA-256及公开MIT声明依据；权重不进入Git。
- runtime-wheels-linux.json记录官方PyPI的24个Linux x86_64/CPython3.11 wheel来源与摘要。requirements-runtime-linux.lock.txt是对应完整Python运行闭包，包含setuptools；requirements.in只表达直接需求。
- 固定Python基础镜像摘要；apt安装的OS包版本另记于实际容器报告。没有固定Debian快照源，不能保证未来重建产生字节完全相同的镜像，也不能将这些Linux轮子用于Windows/ARM。

在仓库根执行以下命令。带--download-public-assets才联网，默认只校验；已有目录不完整或存在多余文件会失败，不能自动覆盖。下载使用临时目录、字节预算、分段限次重试与最终hash，全部校验后才发布目录。

```sh
python services/asr/prepare_assets.py --kind wheels --output .cache/asr-linux/wheels --download-public-assets
python services/asr/prepare_assets.py --kind model --output .cache/asr-linux/models --download-public-assets
python services/asr/prepare_assets.py --kind wheels --output .cache/asr-linux/wheels
python services/asr/prepare_assets.py --kind model --output .cache/asr-linux/models
```

wheel目录仅包含声明的wheel及自动生成的requirements.lock；模型目录仅包含声明的5个文件。禁止符号链接、额外文件或改写版本。下载需要可用官方网络，失败不代表资源可用；不自动改镜像源或降低TLS/hash校验。

## 镜像与真实运行验收

构建从仓库根执行，命名上下文必须为上述已核验的wheel目录；pip安装阶段--no-index --require-hashes，不会请求时下载依赖。

```sh
docker build -f services/asr/Dockerfile --build-context asr_wheels=.cache/asr-linux/wheels -t autocare-asr-runtime:acceptance .
```

准备本机英文合成WAV。Windows有Microsoft Zira Desktop时可在PowerShell中运行以下命令；只生成无个人资料的固定测试句，不录制用户声音：

```powershell
Add-Type -AssemblyName System.Speech
$asrSynth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$asrSynth.SelectVoice('Microsoft Zira Desktop')
$asrSynth.SetOutputToWaveFile((Join-Path (Resolve-Path .cache/asr-linux).Path 'synthetic-english.wav'))
$asrSynth.Speak('The engine makes a noise. Please check the brakes.')
$asrSynth.Dispose()
```

该声音不可用时，负责人准备同一句英文的合法本地合成WAV并记录合成来源；不能代换真实个人录音后上传报告。验收入口会重新核验资源并构建镜像，仅启动本轮标记的隔离容器：

```sh
python scripts/run_linux_asr_acceptance.py --allow-isolated-linux-asr --models-root .cache/asr-linux/models --wheelhouse .cache/asr-linux/wheels --synthetic-english-wav .cache/asr-linux/synthetic-english.wav
```

容器无网络/无宿主端口、非root、只读rootfs/资源挂载、2GiB内存及swap上限、CPU2、PID128、16MiB共享内存、256MiB临时目录、cap-drop及no-new-privileges。HTTP检查只用容器回环；模型子进程另用实际seccomp禁止socket。运行检查实际英文预期词、数字静音空结果、错误凭据/JSON、健康状态、真实子进程超时及清理、429/恢复和关闭。源码前后摘要必须一致；退出时只清理精确匹配本轮标识的容器。日志和独立report.json保存在忽略的.cache/linux-asr-*，失败返回非零。

这不是生产部署工具。R3b入口只覆盖Python真实模型运行；跨容器Java任务及进程故障使用下述R3c独立入口。真实授权声音效果集仍单独验收；资源限制配置与内核禁止证据不能替代效果评估。

## Java真实模型联调与故障检查

`scripts/run_real_asr_journey.py`沿现有Spring/JWT/MySQL测试运行真实调度worker、Java HTTP客户端和固定Linux模型。身份、CLEAN元数据、私有对象存储仍为测试夹具；没有真实上传/扫描或微信登录。新增Java套件默认跳过，必须显式入口才启用，普通CI不会下载模型或偷偷调用服务。

复用上述模型、wheel和英文WAV，并准备Java17/Maven/Docker及已缓存的Maven依赖。PowerShell示例沿项目本机工具位置（当前入口按Windows宿主和本地Maven缓存路径实现，其他宿主需适配工具位置）：

```powershell
$env:JAVA_HOME=(Resolve-Path '.cache/host-tools/jdk/jdk-17.0.20.1+1').Path
$env:PATH=$env:JAVA_HOME+'/bin;'+$env:PATH
$env:JAVA_TOOL_OPTIONS='-Dapi.version=1.44 -Dfile.encoding=UTF-8'
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_HOST_OVERRIDE='127.0.0.1'
python scripts/run_real_asr_journey.py --allow-isolated-real-asr --models-root .cache/asr-linux/models --wheelhouse .cache/asr-linux/wheels --synthetic-english-wav .cache/asr-linux/synthetic-english.wav
```

入口使用本地缓存的nginx:1.27-alpine作为测试转发器。模型容器只连内部网络、不发布端口，转发器只接本轮内部/桥接两个网络并绑定127.0.0.1随机端口；转发目的地固定为已检查的模型IP，关闭请求重试与访问日志。JVM通过该转发器访问真实私有服务，不代表正式部署路由已验收。

Java检查选定ASR套件及真实旅程：幂等创建/查询/权限、真实候选不自动保存、原档案接口显式幂等写入且数据库JSON文字一致、取消和原会话撤销后迟到结果不交付。取消不承诺推理立即终止。英文真实模型结果只验证预期词，不能替代中文/方言/噪声准确率。

随后只在本轮容器中故障注入：核对PID/命令/启动时间，用pidfd绑定实际模型worker后SIGKILL并验证失败/恢复；杀死本轮supervisor检查内核父退出SIGKILL及subreaper回收；独立256MiB、无网络容器通过实际cgroup OOM计数、安全503和主进程存活判断内存不足。精确归属不符、跳过/缺失JUnit结果、清理不全或源码变化均不计通过。私有配置、token、原日志和JUnit XML留在忽略的.cache/real-asr-*，公共证据仅包含摘要与脱敏结论。

## 私有服务接入与回滚

服务变量：ASR_SERVICE_ENABLED=false、ASR_MODEL_ROOT、ASR_MODEL_MANIFEST、ASR_SERVICE_TOKEN（32..512个可见ASCII字符）、ASR_PORT=8091。Java另需ASR_ENABLED、固定私有ASR_URL及相同token；独立开关默认关闭，禁止公网暴露。

prepare_manifest.py只记录已经取得的固定本地文件及实际安装包版本，不下载资源或证明许可来源。先在目标Linux镜像内核对许可并生成清单，输出在模型目录外，再把模型和清单分别只读挂载；不要在已锁定模型目录加入额外文件。

```sh
python services/asr/prepare_manifest.py --root "$ASR_MODEL_ROOT" --revision "$ASR_MODEL_REVISION" --license-reference "$ASR_LICENSE_REFERENCE" --accept-license --output "$ASR_MODEL_MANIFEST"
```

在目标镜像实际安装环境执行，不能用Windows包版本生成Linux清单。服务启动只核验清单，状态为CONFIG_VERIFIED；受限子进程成功处理音频后才READY。测试引擎永远TEST_ONLY。请求只接固定JSON和现有1..60秒/3MiB MP3，不接URL/路径/模型简称；可信度保持null。只对完全零能量数字静音直接返回EMPTY，不使用幅度阈值删除轻声；其他静音/背景噪声幻觉风险仍需评估，候选必须人工核对。

回滚先关闭Java及私有服务开关，核对活动任务、租约与TTL清理，再回退应用；不逆向删schema、档案或音频。原材料的保留和本人删除政策独立处理。

## 回归与后续验收

python -m unittest discover -s services/asr -p 'test_*.py'验证资源/协议/替身边界；python -m unittest discover -s scripts -p 'test_linux_asr_acceptance.py'验证验收入口的权限与资源约束。统一python scripts/run_local_delivery.py包括这两组和既有构建检查，不自动下载模型或执行真实容器验收。

授权普通话/方言/噪声/静音/长短音频、坏编码/超限、时间片段/准确性、CPU时延与内存压力、父进程退出、重启、Java任务取消/撤销/材料变化/TTL及原表单保存、iOS/Android与云上传仍须分别记录版本/授权/结果。公开MIT模型声明不等于语音样本授权。正式微信、短信、支付与生产发布继续后置。
