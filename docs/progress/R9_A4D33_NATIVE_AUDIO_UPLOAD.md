# A4d33 微信录音状态、试听与音频上传

2026-10-24，基线69b121c，分支codex/r9-a4d33-native-audio-upload，依赖尚未合并的PR223。范围为微信小程序与隔离验收，不新增PC页面或生产识别适配。

## 方案和已实施内容

复用现有LocalInputMaterials的录音租约、取消、试听、受控上传及扫描收据。既有原生执行器新增互斥`--audio`模式；Python/Java只允许pdf/image/audio枚举，省略参数仍为PDF，默认非原生CI不等待开发者工具。代理只绑定宿主127.0.0.1，只放行本轮身份、当前模式测试输入和固定上传路径，音频要求X-Material-Kind=audio；不模拟上传/扫描/存储回执，不新增容器监听或端口发布。

测试输入是仓库自生成440Hz单音MP3，48,600字节、按真实帧解析8,100ms，无人声或个人资料。生成命令（首次生成；FFmpeg需含libmp3lame）：

```powershell
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=440:duration=8:sample_rate=16000' -ac 1 -ar 16000 -c:a libmp3lame -b:a 48k -write_xing 0 -id3v2_version 0 -map_metadata -1 backend/src/test/resources/fixtures/native-tone.mp3
```

该二进制已纳入backend/src来源摘要。FFmpeg完整解码和逐帧策略检查通过；Java仍调用产品现有InputMaterialValidator派生时长，不放宽格式白名单，不盲信工具录音事件时长。

仅隔离构建插件装载native-audio-fixture：录音start/stop是明确合成事件，严格限制临时路径、大小/时长及编码参数；播放器保留真实createInnerAudioContext，仅观察真实onPlay/onEnded/onError，不伪造播放成功。辅助只能在固定隔离输出、回环18080、无云托管且devtools平台安装，正常微信包必须无测试身份桥。恢复时清监听、停毁活跃播放器并还原原函数；安装RPC回执即便丢失也尝试恢复。

## 验收与复现

按[上一阶段构建说明](R9_A4D31_NATIVE_MATERIAL_UPLOAD.md)生成隔离微信包，构建后重开该项目，启用RPC9420。Docker与回环18080可用后在仓库根目录运行：

```powershell
node scripts/native-material-upload-e2e.cjs --allow-isolated-upload --audio
```

脚本通过真实downloadFile生成微信管理的临时MP3，先走页面录制/取消不留材料，再录制/停止加入临时材料，点击试听并等待真实onPlay回调后停止销毁。录音与试听阶段要求零上传；仅点击“上传本份材料”后才真实uploadFile。页面核对CLEAN/尚未识别提示、移除和新页重入；Java核对独立车主991、车辆991、ARCHIVE、本人仅一份CLEAN audio/mpeg、正确大小/时长/零页数、签名下载逐字节相同，无图片或档案写入。

短期合成令牌、curl配置与原生日志只在忽略的.cache目录；最终恢复系统API、身份、录音/播放器覆盖及引用，关闭代理，删除握手/curl私有配置，清理initializer、ClamAV、MinIO、MySQL和网络。执行期间不要修改来源受保护文件或并跑共享backend/target的其他后端测试。只上传脱敏报告。

## 边界与当前检查

麦克风录音和权限没有执行，录音事件为工具辅助；原生播放回调不等于人工听感、手机扬声器或静音策略验收。微信管理临时缓存，本轮仅验证页面引用释放。扫描使用测试注入合成签名库，正式身份/生产病毒库/ASR及识别另验，不保存档案或调用AI。

小程序1047项及材料/上传Python20项、覆盖Python47项通过。桥恢复回执在集成复核时已修正为先restore再取status，并新增回归。实际原生旅程、普通构建、交接索引和GitHub交付结果在收口后追加；本节不提前宣称实际播放/上传通过。覆盖仍317/352、36套来源，全API仍false。

首轮c7662296原生前四项通过，含实际微信onPlay；随后点击停止试听的RPC回执先于页面停止处理完成，立即读取仍见一个活跃播放器，整轮失败且零上传。finally已记录两次录音停止、零录音监听、播放器销毁、零播放错误、音频辅助/身份恢复；该轮不计成功。修正执行器为只点击一次后有界等待实际activePlayers=0，不重复点击或伪造播放回调，再独立重跑完整旅程。

## 音频实际结果

9c52760e4a794a33b91fbd4cd3f874f7整轮通过：原生九项、实际页面上传一次、既有HTTP80条/四接口及一项Java完整旅程通过，来源摘要稳定。真实微信onPlay一次、零自然结束/播放错误，停止后播放器销毁，录音监听归零；录音事件仍为工具辅助。本人991/车辆991/ARCHIVE仅一份CLEAN audio/mpeg，48600字节/8100ms/零页数，签名下载逐字节一致，无档案或图片写入。系统API、身份、音频辅助及页面引用恢复，私有握手/curl配置和五类隔离资源清理成功。[脱敏报告](../testing/reports/R9_A4D33_NATIVE_AUDIO_UPLOAD_2026_10_24.json)。普通微信构建成功，普通包无身份桥及音频辅助。

GitHub核查曾因自动审批服务额度不足未执行；收到继续实施后正常审批已恢复，默认PDF兼容复验已启动。该失败不是安全性拒绝，没有绕过审批。阶段上传和最终CI以收口记录为准。

收口时已联网确认PR223最终69b121c八项CI全部成功，仍未合并。相关Python检查合计75项（20材料/上传、47覆盖、8交接），交接摘要已重生成并核对通过。

默认PDF兼容5f4d84a6整轮通过：原生五项、页面真实上传一次、HTTP80及一项Java完整旅程，归属/大小/扫描、签名下载、无档案/图片、来源和五资源清理通过。[兼容报告](../testing/reports/R9_A4D33_NATIVE_PDF_REGRESSION_2026_10_24.json)。两轮各一项Java旅程，不宣称同轮55项。代码复核的RPC清理建议已修正；敏感扫描仅对固定本机测试输入URL精确例外，零发现。

## GitHub交付

f5eeb41已整批上传[草稿PR224](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/224)，base=codex/r9-a4d32-native-image-upload，依赖PR223。最终head远程CI另核，不提前宣称全部通过；未合并/部署。
