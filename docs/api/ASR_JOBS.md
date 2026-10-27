# 本人音频的持久 ASR 候选任务

R3 新增创建、查询及取消三项接口，使用既有 `input_material` 音频，不放宽通用图片或材料上传白名单。[OpenAPI](openapi.json)可从源码重新生成；[设计](../superpowers/specs/2026-10-27-asr-jobs-design.md)说明租约、权限和候选边界。默认关闭；实际模型、获授权语音效果和手机验收分别记录。

## 材料与用途

车主先在当前车辆的 AI 或档案页面录制、试听，再单独上传本人音频。现有 MP3 政策为 MPEG-2 Layer III、48kbps、16kHz、单声道，1..60秒、最多3MiB；需私有存储及 CLEAN 扫描。创建任务还需独立勾选本次转写同意，上传成功本身不代表已转写。

任务必须绑定当前本人车辆、材料及原用途 `AI` 或 `ARCHIVE`。请求不接受音频正文、URL或对象key；跨用户、跨车辆、跨用途及失效材料均不提供候选。任务不证明车辆诊断事实，也不会自动发送AI问题或保存档案。

## 公共请求

三个接口都要求当前有效 OWNER Bearer 会话，禁止任何 query，响应 `Cache-Control: no-store`。POST 需要 `Content-Type: application/json` 和 UUID `Idempotency-Key`；GET不需要幂等键。ID为正的JS安全整数，额外字段、重复键、尾随JSON均拒绝。

创建：`POST /api/recognition/asr/jobs`

```json
{"vehicle_id":1,"material_id":2,"purpose":"AI","consent":true,"consent_version":"ASR_TRANSCRIBE_V1"}
```

200的data仅为任务引用：

```json
{"job_id":3,"vehicle_id":1,"material_id":2,"purpose":"AI","task":"ASR"}
```

同账号、同键、同正文返回原引用，不重复排队或消耗新任务限额；同键不同正文400。回执不缓存转写文本。新任务每车主每UTC分钟最多5次、同时最多2个未过期QUEUED/RUNNING任务，换会话不重置账号限额。固定窗口边界可能产生突发，不构成全站供应商配额保证。

查询：`GET /api/recognition/asr/jobs/3`。data固定包含 job_id、vehicle_id、material_id、purpose、task、state、expires_at、error_code、result。状态为 QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/EXPIRED；只有 SUCCEEDED 可以带 result，其余为null。安全错误码为 UNAVAILABLE/SOURCE_CHANGED/AUTH_REVOKED/WORKER_LOST/INVALID_RESULT/EXPIRED/USER_CANCELLED/FEATURE_DISABLED 或null。

成功候选result示例为合成内容：

```json
{"version":1,"task":"ASR","source":"FASTER_WHISPER","status":"TRANSCRIBED","language":"zh","duration_ms":1008,"segments":[{"start_ms":0,"end_ms":900,"text":"合成测试文字","confidence":null}],"requires_confirmation":true,"audio_recognition_performed":true}
```

没有片段时为 EMPTY，segments为空，language可null；不虚构静音内容。最多50段，时间单调不重叠并受原音频时长限制；每段最多500 UTF16单元，以换行拼接总计最多1000单元，超限失败而不截断。文本无控制或孤立代理字符。实际 faster-whisper 适配保留未知可信度null，不将平均logprob换算成可信百分比。

取消：`POST /api/recognition/asr/jobs/3/cancel`，正文必须 `{}`，带独立UUID幂等键。200 data仅 `{ "job_id":3 }`。可取消排队、运行或已成功候选并清空文本；重复取消安全，过期状态以查询为准。新有效会话可以取消本人旧任务；取消不删除原音频，也不承诺已发送的推理立即停止。

## 权限、状态与错误

- 400：ID、正文、用途/同意或POST幂等键无效。
- 401/403：当前会话失效或角色不符；绑定凭证不具有业务权限。
- 404：非本人当前车辆或任务不可用，或创建时材料/用途不符。已建任务的材料变化会清空候选并以FAILED/SOURCE_CHANGED表示。
- 429：新任务分钟或活动任务限额。
- 503：功能关闭、数据库/任务服务未配置或不可用。

任务执行前后及候选返回前重新检查原创建会话、当前账号/车辆、同用途CLEAN材料及source_hash；原会话撤销、来源变化、取消、过期或旧租约结果都不能交付候选。当前请求会话失效直接401；车辆已转移不提供旧车辆内容。推理失败只留下安全错误码，不透传供应商诊断、URL或原文。

候选自创建起10分钟过期。查询到期后立即不可见并清空；后台批量清理过期正文。任务元数据墓碑和原音频的保留/本人删除政策另行管理，不能把候选TTL宣称为音频已删除。

## 页面操作与未知创建

上传成功音频旁选择“同意本份音频转写”，创建任务后查看排队/运行状态，完成后对照试听检查片段、修正文字并勾选核对。带入前再次读取当前授权并检查原表单：原文字已变化时阻止静默覆盖。AI问题或档案备注带入后，仍需原按钮发送/保存；忙碌/已保存页面不能带入。

隐藏、换账号/车辆/用途、移除材料或撤回同意会停止轮询、清空本页候选并丢弃迟到响应。知道任务ID时仅作best-effort取消；网络失败不能写为取消成功。

创建结果未知时保留账号/作用域绑定的原正文及原幂等键，页面显示未确认取消请求。恢复入口需要用户**重新同意原音频转写**后，同正文同键确认创建，再立即请求取消；若原创建没到达，这一步可能创建新任务后取消。恢复不会把旧文字带入表单。已知任务只需重试取消。该恢复句柄保存在本机内存，不是跨启动持久会话或服务端取消保证。

## 配置与运维

后端模板 `.env.example`、Compose及云托管提供：

```dotenv
ASR_ENABLED=false
ASR_URL=
ASR_SERVICE_TOKEN=
ASR_ALLOW_PRIVATE_HTTP=false
ASR_JOB_INTERVAL_MS=1000
ASR_CLEANUP_INTERVAL_MS=60000
```

启用前执行V063及其全部前置迁移（共63项，当前132表）、准备私有存储/扫描、独立ASR服务及经核验离线模型。URL只由后端设置，固定 `/transcribe` 路径、无query/用户信息/片段；优先私有HTTPS，只有显式允许时接受 http://asr、127.0.0.1或localhost。token为32..512个可见ASCII字符，仅放Java后端与私有服务，不进VITE、日志或仓库。

后端私有调用90秒总期限/64KiB响应、不重定向/不作应用级重试。任务使用单实例一个后台执行线程，短事务领取/完成，不占Spring调度线程等待长I/O，不持事务跨存储/HTTP。租约失效的RUNNING任务标记WORKER_LOST，不自动重放；用户确认后另建新任务。停用后端开关并重启可以关闭新任务，现有候选按当前授权/关闭状态处理。

Python私有服务独立使用 `ASR_SERVICE_ENABLED=false`、`ASR_MODEL_ROOT`、`ASR_MODEL_MANIFEST`、`ASR_SERVICE_TOKEN`、`ASR_PORT=8091`。必须Linux隔离/资源上限与无网络保护，本阶段没有提供已验证的生产镜像或启用overlay；不能把Windows替身进程测试说成Linux模型安全验收。已有基础Compose只转发后端参数，不会隐式下载或启动ASR模型。

离线模型与完整依赖准备、manifest声明和验收边界见[ASR服务准备](../../services/asr/README.md)。`requirements.in` 仅列 faster-whisper 1.2.1 直接需求，不是完整transitive wheel/hash锁。真实模型、依赖完整锁和Linux镜像尚未准备时保持关闭。

本轮[测试与问题记录](../progress/RECOGNITION_R3_ASR.md)、[脱敏机器证据](../testing/ASR_JOBS_2026_10_27.json)区分合成协议/真实本机HTTP数据库及尚未验收的真实ASR。候选内存缓存到期也会清除，用户已核对带入的原表单文字不受任务TTL自动删除。

R3b最新证据：[固定离线资源、Linux真实模型合成样本及静音修复](../progress/RECOGNITION_R3B_ASR_LINUX.md)。已取得并核验24个wheel与固定模型5文件，实际离线容器推理/超时恢复/网络禁止通过；原R3文档中的“尚无资源/未运行”是历史范围。中文/方言/噪声/真实用户准确率、Java真实ASR同版联调和手机仍待验，默认关闭，不代表生产接入完成。
