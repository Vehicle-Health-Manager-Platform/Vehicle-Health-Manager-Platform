# R5b 真实中文嵌入与私有适配

承接PR250/6766042；PR249和PR250最新head均8项CI成功。用户要求规划后直接实施，本阶段不合并GitHub PR/生产部署，不办理外部微信/短信身份。范围为固定公开资源、真实受限推理/私有HTTP/Java适配及索引验证，车主公开检索仍KEYWORD；不默认注册自动索引或VECTOR入口。

## 已核实的官方依据与选择

2026-10-27经gstack browse读取[BAAI模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)及[资源API](https://huggingface.co/api/models/BAAI/bge-small-zh-v1.5?blobs=true)，revision=7999e1d3359715c523056ef9478215996d62a620，model.safetensors=95827648字节，公开LFS SHA256=354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026。模型卡标注MIT/512维、检索查询前缀和CLS后L2归一化，文档不加查询指令。模型卡排名不作为本项目效果证明。

官方仓库无ONNX，选择原始safetensors/固定CPU PyTorch和Transformers；不引入第三方转换权重或远程API。CPU依赖从官方PyTorch索引与PyPI精确解析后形成完整wheel/hash锁，运行时离线安装；不安装CUDA包，不执行pickle权重或trust_remote_code。仅公开模型资源下载，不发送用户资料。

## 资源与配置身份

锁定所需模型配置/tokenizer/vocab/README/安全权重的字节数和SHA256，小文件对照API Git blob ID，大文件对照LFS SHA256。准备工具先写自有临时目录、完整验证后原子发布，已有目录只校验，不覆盖未知资源；不在runtime自动下载。

EmbeddingProfile保留R5a四参数及原指纹，真实适配增加成对的encoder_policy/runtime_sha256。运行身份包含模型文件锁、完整依赖锁和推理源码摘要；明确查询前缀、CLS/FP32/L2、Unicode分块及精确tokenizer512策略，避免旧配置/模型/实现混用。实际已安装依赖版本也须核对。旧索引不隐式转换。

## 私有受限执行

私有POST /embed只接受固定version/profile_hash和1..4条文本，无URL/路径/附加字段/重复JSON，无query。文本有界且UTF16合法，body最多32KiB、响应128KiB；512维、有限非零单位向量、配置指纹及输入数量必须一致。精确tokenizer关闭truncation和padding，先计含特殊符号的token数，任一超过512整批拒绝，不截断成功；只在预算通过后加载原始模型。统一CPU float32/eval/no_grad/CLS/L2，文档不额外加前缀，查询由现有Java索引按配置加一次。

单槽、无等待推理队列，独立自有Python子进程，网络seccomp禁socket、父退出绑定/整组回收、整体硬截止；不继承服务token/用户凭据/JVM选项。生产构造仅Linux且有<=2GiB cgroup限制，非root/只读模型与rootfs/受限tmpfs/CPU2/PID128。默认EMBEDDING_SERVICE_ENABLED=false和EMBEDDING_MODEL_ENABLED=false，health只表示配置核验，不冒称完成真实推理。token32..512可见ASCII、严格HTTP头/长度/编码与3秒读取截止；返回安全错误码、不记录正文或向量。

Java客户端只固定/embed HTTPS或明确允许的embedding/localhost/127.0.0.1私有HTTP，不重试/跟随重定向；有总deadline及128KiB读取上限，严格重复/尾随JSON及精确结果结构。EmbeddingProvider接口不变，不默认注册业务bean。R5a总30秒/单线程直接交接继续约束整个文档，可能因总期限拒绝大文档，不把字符上限当吞吐承诺。

## 验证与交接

资源校验/篡改/缺失及无下载默认，协议/数值/token超限/错误头/忙/超时/父退出与恢复测试；真实容器使用自主编写中文车辆/无关文档，记录实际向量和相对排名，非授权领域准确率验收。Java私有HTTP及既有MySQL索引以明确真实模型/合成身份和许可逐项记录，任何替身不计模型通过。

更新默认回归入口与源码摘要覆盖新目录；完整文档列明模型/依赖来源、配置、运行/停止、预算/已知限制及未验证事项。R5c继续自动索引/运维状态/跨配置删除排程、车主VECTOR与KEYWORD明确降级、AI引用重验、授权领域评估及容量/真机。正式身份/短信/生产继续后置。
