# 受限离线 ASR 服务准备

本服务为持久ASR任务的私有能力适配，默认关闭。现阶段没有安装/加载真实ASR权重，没有完成授权中文语音、方言/噪声、手机或Linux生产运行验收；合成Provider、协议/进程测试只能验证软件边界。[公共任务接口及操作](../../docs/api/ASR_JOBS.md)与[实施设计](../../docs/superpowers/specs/2026-10-27-asr-jobs-design.md)分别说明权限和人工确认。

## 依赖与离线资源

试点为 `Systran/faster-whisper-small` 多语模型、CPU/int8。源码要求 faster-whisper 1.2.1，本目录 `requirements.in` 只列直接需求，**不是完整依赖或wheel/hash锁**。不要将一次 `pip install -r requirements.in` 当作可复现部署证据。

正式准备负责人需要：

1. 在独立Linux构建环境审查许可和支持平台，固定Python及全部transitive包/轮子版本、下载来源、SHA-256与镜像摘要；离线wheelhouse须包含实际所需PyAV/CTranslate2等原生依赖，不能请求时联网补包。
2. 从已确认来源取得上述模型的精确40位revision，记录模型/Tokenizer及所有文件摘要和许可依据。不要凭模型简称或本项目代码声明推断已取得某个revision。
3. 本地模型目录至少包含 model.bin、config.json、tokenizer.json、vocabulary.txt，以及实际快照的其他文件；禁止符号链接或目录外文件。运行时比对完整文件清单/摘要和包版本。
4. 在隔离资源环境验证实际模型加载、MP3解码、推理时间/内存、无网络、超时/崩溃和进程清理，再接获授权样本及业务网关。

`prepare_manifest.py` 只记录**已经下载的目录与已经安装、审查过的运行时**，不负责下载，不证明revision来源。示例变量必须由负责人填写真实核验值，不可把占位值或随机hash用于验收：

```sh
python services/asr/prepare_manifest.py \
  --root "$ASR_MODEL_ROOT" \
  --revision "$ASR_MODEL_REVISION" \
  --license-reference "$ASR_LICENSE_REFERENCE" \
  --accept-license \
  --output "$ASR_MODEL_MANIFEST"
```

manifest内必须有 repository、revision、license_reference、license_accepted、packages和files。代码同时校验 faster-whisper、ctranslate2、av、tokenizers、onnxruntime、huggingface-hub、numpy等实际包版本；记录完整运行时仍须维护独立的全依赖锁，不靠此manifest替代供应链审查。不得设置虚假的许可同意或用“清单生成成功”表述模型效果通过。

## 启动与隔离

服务端变量：`ASR_SERVICE_ENABLED=false`、`ASR_MODEL_ROOT`、`ASR_MODEL_MANIFEST`、`ASR_SERVICE_TOKEN`（32..512个可见ASCII字符）、`ASR_PORT=8091`。模型启用与Java的 `ASR_ENABLED` 为独立开关；Java还需私有 `ASR_URL` 和相同token。

启用前必须完成Linux隔离、内存/CPU/文件上限与无网络保护，按源码的guard要求构建镜像。本目录Dockerfile是待验证的构建入口，未交付经过验证的Linux镜像/overlay或完整transitive锁；不要直接把服务暴露公网。服务内部只接受严格JSON、有限base64字节与既有1..60秒/<=3MiB MP3，不接受URL、路径或任意远程模型名称；推理只在受限子进程内执行。未准备模型时保持关闭，返回不可用而不是合成文本。

运行命令为 `python services/asr/src/asr_server.py`，需在上述隔离与已核验本地资源环境中执行；这条命令本身不提供隔离保证。可信度保持null，不将平均logprob伪装为准确率。超过片段/字数/时间预算明确失败，不静默截断。

## 回归与验收

软件回归：`python -m unittest discover -s services/asr -p 'test_*.py'`；统一本地入口 `python scripts/run_local_delivery.py` 包含ASR第10组及源码摘要。这些测试的Provider/音频夹具须明确标识，不把返回的source标签视作真实模型已运行。

真实验收至少包含：授权普通话/方言/噪声/静音/不完整音频、合法时长边界、坏格式/超限、段时间/文本准确性、人工修正、真实受限子进程超时/资源耗尽/退出、关闭与重启，以及Java任务的取消/撤销/材料变化/TTL与原表单保存。记录版本、模型revision/文件摘要、样本授权、分项结果及失败；不以单个样本或协议测试代替本领域效果。原音频保留与删除政策仍按input_material单独管理。

构建入口需从仓库根使用 `-f services/asr/Dockerfile`，并提供命名 `asr_wheels` 构建上下文；该目录必须有负责人准备的完整 `requirements.lock` 及匹配wheel/hash。当前没有这些已验资源，不应直接构建/启用并声称通过。健康状态中CONFIG_VERIFIED仅表示配置/清单已核对，真实受限子进程成功推理后才READY；测试引擎为TEST_ONLY。
