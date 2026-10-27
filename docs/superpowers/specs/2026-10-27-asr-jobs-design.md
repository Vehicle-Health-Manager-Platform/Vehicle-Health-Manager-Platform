# R3 ASR 持久异步转写

用户已授权规划后实施。本轮完成任务/本地适配/前端人工核对软件闭环，真实模型与效果单独验。采用MySQL任务与受限本地worker，复用OWNER、input_material及原AI/档案表单；同步60秒音频推理会超过云请求期限，纯云方案需账号并改变外发边界，故选持久异步任务与可替换Provider。

## 公共接口（JSON、no-store、禁止query）

- POST /api/recognition/asr/jobs，OWNER+UUID Idempotency-Key，严格正文 `{vehicle_id,material_id,purpose:"AI"|"ARCHIVE",consent:true,consent_version:"ASR_TRANSCRIBE_V1"}`。成功data仅 `{job_id,vehicle_id,material_id,purpose,task:"ASR"}`，不缓存候选文本。默认ASR_ENABLED=false时503且无任务/外部调用。
- GET /api/recognition/asr/jobs/{id}，当前OWNER，data固定 `{job_id,vehicle_id,material_id,purpose,task:"ASR",state,expires_at,error_code,result}`。state为QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/EXPIRED。未成功result=null；SUCCEEDED时严格ASR结果对象。error_code仅null或UNAVAILABLE/SOURCE_CHANGED/AUTH_REVOKED/WORKER_LOST/INVALID_RESULT/EXPIRED/USER_CANCELLED/FEATURE_DISABLED。
- POST /api/recognition/asr/jobs/{id}/cancel，当前OWNER+UUID Idempotency-Key，严格正文{}，data仅{job_id}。成功候选也可取消并清空，重复取消安全；不把删除音频混同取消转写。

创建前、任务执行前后、GET返回前重验当前账号/原会话、当前本人车辆及同用途CLEAN音频；越权/车辆转移404，会话当前失效401，角色403。原创建会话撤销后不再交付旧任务候选；新会话可取消自己的任务。仅接受现有audio/mpeg、16kHz mono、1..60秒/<=3MiB，客户端不传URL、对象key或音频正文。本人每UTC分钟5次新任务、最多2个未过期活动任务；幂等重放不重复排队或消耗限额。

## 数据与调度

新增V063 recognition_job表（一个新表，迁移按现有顺序挂载），保存owner/vehicle/material/purpose、创建会话标识、元数据source_hash、状态/lease、expires_at和有限result；不保存JWT、原音频、对象key或原文到普通审计。source_hash覆盖材料ID、归属、用途、不可变对象key、类型/大小/时长/创建时间，不将元数据hash冒充内容hash。执行读字节后可另外记录实际内容hash。

创建和取消沿WriteIntegrityService幂等写，只在回执/审计记任务ID/作用域/状态，不放转写文本。结果候选TTL为创建后10分钟；到期即查询不可见并清空，定期清理过期正文。元数据墓碑的运营保留另记，不宣称已删除原音频。

短数据库事务领取QUEUED任务（SKIP LOCKED/CAS租约）。单实例一个后台执行线程，不在Spring调度线程内做长I/O；不持有事务跨HTTP/模型。120秒或更充足有界租约覆盖90秒来源期限；过期RUNNING转FAILED/WORKER_LOST，不自动重放已发送推理。完成时用租约与状态CAS、重验原会话/材料source_hash/当前车辆及TTL，取消/撤销/过期/旧租约均不落候选。后台不可用只记录安全错误码。

## Provider 与私有服务协议

Java包com.autocare.platform.order：AsrProvider接口 `JsonNode transcribe(byte[] audio,int durationMs)`；AsrHttpClient受限私有HTTP/HTTPS `/transcribe`，90秒总期限、64KiB响应、无重定向/应用级重试，服务token仅后端；仅显式允许http://asr或127.0.0.1/localhost。AsrResult.validate(result,durationMs)供任务完成前验证。

请求固定 `{version:1,task:"ASR",audio_base64,duration_ms}`；严格JSON、头部和3MiB音频/有限请求体。结果固定 `{version:1,task:"ASR",source:"FASTER_WHISPER",status:"TRANSCRIBED"|"EMPTY",language,duration_ms,segments,requires_confirmation:true,audio_recognition_performed:true}`。duration_ms绑定原材料。TRANSCRIBED非空segments，EMPTY空；language是2..3位小写代码或EMPTY时null。最多50段；每段严格 `{start_ms,end_ms,text,confidence}`，时间整数、单调不重叠、0<=start<end<=duration_ms；文本非空去首尾空白、无控制/孤立代理字符，每段<=500 UTF16单元，所有段以换行拼接<=1000单元。confidence仅null或真实0..1数；faster-whisper的平均logprob不换算成可信百分比，实际适配返回null。

Python服务services/asr：默认不加载模型。启用必须本地固定模型文件/manifest与依赖版本匹配；不得用模型简称隐式下载。试点faster-whisper multilingual small、CPU/int8，具体包版本/模型revision仅根据官方核查或实际固定manifest声明，不编造已下载/已验。解码/推理在有硬截止、资源限制和无网络的子进程；并发1、无无界请求队列，输出有字数/帧上限。复用现有OCR的process_limits/network_guard基础（Docker显式COPY、测试导入路径），不放宽OCR协议。若本环境没准备ASR权重，真实ASR验收保持false，替身只测试协议/任务流程。

## 前端

在LocalInputMaterials已有上传成功的音频旁增加AsrTranscription组件，复用其materialScope。明确本次转写用途同意后创建任务，自动有界轮询、显示排队/运行/失败/取消/到期。创建网络未知时同正文同键重试，重建须明确新任务。隐藏/换身份/车辆/用途/材料移除或撤回同意时停止轮询并丢弃迟到结果；知道任务ID时发送best-effort取消，未知创建结果留幂等句柄以便显式确认取消，不能承诺隐藏即服务端已取消。

转写展示片段/时间与未知可信度，用户可修正，编辑清核对。核对后仅emit到原AI问题或档案备注，仍需原发送/保存按钮；签名绑定当前原表单，原文已变化时阻止静默覆盖。AI发送或档案保存期间禁用转写带入。为空/超长/超时明确手动回退，不自动摘要诊断或发消息。试听与转写效果分别验证。

## 验收与交接

真实MySQL+应用HTTP/JWT：幂等/重放/限流/跨用户/跨用途/撤销/车辆转移/材料变化、取消与完成竞争、TTL、旧租约、中断恢复、无车辆档案/AI写入。Python进程/私有HTTP使用明确合成音频和Provider，验证坏MP3、超限/协议、关闭/超时/错误/资源清理；真实模型若未加载不冒称通过。前端轮询/取消/未知创建/迟到/人工确认与原表单冲突；全量小程序/Python/OCR/新增ASR回归、MP/H5、契约/迁移/交接索引，浏览器检查。默认关闭，交接包含模型准备、依赖/许可/摘要、重启/清理和已知未验。
