# R5b 私有中文嵌入服务

真实模型推理适配，默认关闭，无公开业务路由、自动索引任务或下载行为。用户/运营页面继续使用现有知识资料流程；车主检索和AI引用仍为KEYWORD。不能将下述合成文本检查称为汽车领域准确率验收。

## 固定资源与本地复现

Python准备工具仅使用标准库。运行模型要求Linux x86_64、CPython3.11、Docker及已准备的ASR守卫镜像。Dockerfile从固定Python基础镜像安装24个带哈希的CPU wheel，从`autocare-asr-runtime:acceptance`复制libgomp/libseccomp两个库，不复制ASR Python包。先按[ASR指南](../asr/README.md)构建该镜像；正式交付镜像仍须固定守卫镜像/操作系统库供应链并复验。验收报告记录实际镜像和库摘要。

在仓库根目录执行：

```powershell
$env:PYTHONUTF8='1'
python services/embedding/prepare_assets.py --kind model --output .cache/embedding-model --download-public-assets
python services/embedding/prepare_assets.py --kind wheels --output .cache/embedding-wheels --download-public-assets
python services/embedding/prepare_assets.py --kind model --output .cache/embedding-model
python services/embedding/prepare_assets.py --kind wheels --output .cache/embedding-wheels
python scripts/run_linux_embedding_acceptance.py --allow-isolated-linux-embedding --models-root .cache/embedding-model --wheelhouse .cache/embedding-wheels
```

下载选项必须显式提供，仅下载公开制品；默认只核验本地文件。已有目录不会覆盖，缺失、额外文件、符号链接、字节数/摘要错误均拒绝。大文件采用有界分段重试，完整摘要通过后才发布自有暂存目录。准备完成后运行时不访问网络、不解析main/latest、不加载pickle或远程代码。

可追加`--java-maven <mvn可执行文件> --maven-repository <完整本地m2仓库>`执行真实Java/MySQL联调；先配置JAVA_HOME=JDK17、Docker/Testcontainers及离线Maven依赖。Windows Docker29可设置`JAVA_TOOL_OPTIONS=-Dapi.version=1.44 -Dfile.encoding=UTF-8`。两个选项必须同时提供，Maven使用离线模式。

运行器创建带唯一标签的自有容器，2GiB内存及同额swap上限、CPU2、PID128、只读rootfs和模型、256MiB tmpfs、非root、无网络和宿主端口，结束核验所有权后清理。Java联调通过仅绑定127.0.0.1的测试转发桥和Docker stdin访问容器内部真实HTTP；桥不产生模型向量。MySQL由Testcontainers隔离创建。日志和报告保存在`.cache/linux-embedding-<run>/`；不要上传私有日志。中断后先按标签确认所属运行，再清理对应容器。

## 模型与许可

模型为[BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，MIT，精确revision `7999e1d3359715c523056ef9478215996d62a620`；11个文件的来源、SHA256和字节数见[模型锁](models.lock.json)。原始safetensors权重95,827,648字节，SHA256 `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026`。不得用仓库中的旧pickle权重替代。

[完整Linux wheel锁](runtime-wheels-linux.json)和[安装锁](requirements-runtime-linux.lock.txt)固定PyTorch2.8.0+cpu、Transformers4.52.4、tokenizers0.21.4、safetensors0.5.3等24个依赖。准备阶段已按官方PyTorch/PyPI摘要核验；许可合规交接还须保存各依赖许可证与NOTICE，MIT模型许可不替代知识资料的嵌入授权。

固定BERT/FP32/eval/CLS/L2、512维。查询由Java索引加一次`为这个句子生成表示以用于检索相关文章：`，文档不加。精确tokenizer包含CLS/SEP，不超过512 token；关闭自动截断。任一文本超限整批失败，不返回部分结果。Unicode分块上限不等于token上限。

## 配置及管理员操作

[独立服务模板](.env.example)仅供该进程读取，不自动注入主后端。`EMBEDDING_SERVICE_ENABLED`、`EMBEDDING_MODEL_ENABLED`均默认false；显式启用时必须设置`EMBEDDING_MODEL_ROOT`、`EMBEDDING_SERVICE_TOKEN`，端口`EMBEDDING_PORT`默认8093。token需32..512可见ASCII；本地验收使用明确合成凭据，运行环境须自行生成并保密。

服务必须置于内部网络，禁止公网端口；模型以只读卷挂载。推理子进程内核seccomp禁socket，限CPU时间、文件大小、20秒整体截止，父退出/超时整组回收，一次一批且不排队。Java客户端总截止25秒，现有索引对整份文档总截止30秒，长文档可能超时。失败不自动重试、不留下部分块。

运行器是推荐测试启动/停止方式。需要独立测试服务时，按运行器同等资源参数启动镜像，仅连接自有内部网络，设置上述显式开关和凭据；停止对应自有容器即可。健康检查`GET /health`只表明配置核验，必须另行调用实际/embed验收。R5b没有业务Spring bean或调度器，管理员不能通过设置环境变量自动启动索引；Java构造器和内部索引只供明确测试接线。

## 私有接口

`POST /embed`，Bearer认证，Content-Type application/json；不允许query、重复认证/长度头、压缩、分块上传、重复JSON键、尾随JSON或其他字段。请求≤32KiB，文本1..4条，每条≤1024 UTF16单位、无孤立代理/格式控制符，仅允许换行控制符。

```json
{"version":1,"profile_hash":"<当前配置SHA256>","texts":["合成资料文本"]}
```

成功返回精确三字段`version:1`、相同`profile_hash`、`vectors`矩阵，每行512有限单位值。响应≤128KiB。配置指纹包含模型、revision、维度、查询前缀、归一化/分块/encoder策略及模型/依赖/推理代码摘要。Java旧四参数profile保留旧JSON/指纹，新runtime字段成对出现，不能混用新旧索引。

错误为安全JSON：401 unauthorized；404 not_found；411 length_required；415 json_required；429 busy；413 LIMIT_EXCEEDED；422 INVALID_REQUEST/INVALID_RESULT；503 UNAVAILABLE或embedding_not_configured。错误不回显文本、路径、凭据或底层异常。Java将预算错误保留为LIMIT_EXCEEDED、非法结果映射INVALID_VECTOR、其余不可用，不重试或跟随重定向。

## 测试与后续

`python -m unittest discover -s services/embedding -p 'test_*.py'`是协议/资源/测试子进程/HTTP夹具回归，不运行模型。真实模型验收、Java/MySQL测试及已知限制见[本轮报告](../../docs/progress/RECOGNITION_R5B_REAL_EMBEDDING.md)。主业务接口、数据库迁移和前端交互本轮不新增；正式微信/SMS、生产、真机、领域质量/容量以及R5c自动索引/状态/公开VECTOR/降级/AI引用复核继续待办。
