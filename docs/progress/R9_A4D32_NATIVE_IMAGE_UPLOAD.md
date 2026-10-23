# A4d32 微信档案图片原生上传

2026-10-23实施，2026-10-24收口，基线3bff98e，分支codex/r9-a4d32-native-image-upload，依赖尚未合并的PR222；开始时该PR八项CI全部成功。本阶段推进车主档案图片实际上传联调，不开发PC端。

## 范围与实现

现有record-add页面已具备图片选取、uploadFile、移除和保存后归档。本轮为上一阶段隔离原生执行器增加显式`--image`模式，默认仍为PDF；Python只允许有效运行ID加pdf/image两种模式，常规CI仍执行原55项Java测试且不等待开发者工具。

JVM生成有效2×2合成PNG，使用本轮独立车主/车辆991和短期会话，通过忽略的私有握手交给原生执行器。宿主127.0.0.1代理只允许同一身份获取该模式测试输入及访问固定`/api/file/upload`路径；PDF路径在图片模式拒绝，图片模式拒绝材料类型头及非法请求键。原始multipart经docker exec/curl进入真实HTTP接口，扫描、MinIO写入和MySQL事实核对均真实，不增加容器监听或端口发布。

图片先由微信downloadFile生成真实临时路径，chooseMedia/chooseImage返回由工具辅助；随后点击页面实际选择上传按钮，检查成功提示、1/5数量、移除和新页面重入0/5。Java核对本人仅一份CLEAN PNG、大小正确、签名下载逐字节一致，没有新增档案或辅助材料。页面移除只解除待归档引用，不代表删除服务器文件。

## 复现与验收边界

按[A4d31隔离构建流程](R9_A4D31_NATIVE_MATERIAL_UPLOAD.md)构建回环18080微信包，构建后仅重开该隔离项目并开启RPC9420。Docker引擎可用且18080空闲后，在仓库根目录运行：

```powershell
node scripts/native-material-upload-e2e.cjs --allow-isolated-upload --image
```

省略`--image`复验既有PDF模式。执行期间不要修改受来源摘要保护的文件或同时运行共享backend/target的其他后端测试。私有日志、令牌及握手只在忽略的.cache目录；公开报告仅布尔值、计数、请求名和来源摘要。完成后恢复系统API、清空内存身份、释放页面引用并关闭回环代理，删除握手/curl私有配置，清理原有initializer、ClamAV、MinIO、MySQL和网络。

微信管理临时下载缓存，本轮不宣称物理删除；手机选图/相机/权限/取消、原生预览、正式身份、生产病毒库及识别仍需独立验收。不会保存档案或调用AI。覆盖保持317/352、36套来源，全API仍false。

## 本地检查

小程序1040项和材料/上传Python20项回归通过；覆盖相关Python47项、交接索引Python8项通过，共75项相关Python检查。普通微信构建与无测试身份桥门禁成功，页面/配置索引检查通过。更新剩余验收清单后交接摘要过期，已重生成并核对通过。初次Python命令在根目录因模块路径不符失败，改在scripts目录执行后通过；新增测试一次插入位置错误，已修正并复验20项全部通过，这些失败不计成功证据。

本地单元/构建与下列实际原生旅程分别记账，不将辅助选择器结果登记为手机验收。

## 图片实际结果

ff8e3efd68c84b69b9bca0e3f985ec8e整轮通过：原生五项、实际页面PNG上传一次、既有HTTP80条/四接口完整旅程，Java该旅程一项通过。本人991仅一份大小匹配的CLEAN PNG，签名下载逐字节一致，没有新增档案或辅助材料；源码摘要稳定。系统API/身份恢复、页面引用释放、回环代理关闭、私有握手/curl配置删除，initializer、ClamAV、MinIO、MySQL及网络五项清理均通过。[脱敏报告](../testing/reports/R9_A4D32_NATIVE_IMAGE_UPLOAD_2026_10_23.json)。

图片输入为合成PNG，扫描使用测试注入合成签名库；不代替物理选图、生产病毒库覆盖或正式身份验收。

## PDF兼容复验

同一实现的默认PDF模式9774a96903a74ccf8dbbd21a8060370a独立通过：原生五项、实际页面PDF上传一次、既有HTTP80条/四接口及Java该旅程一项通过。本人/本车/ARCHIVE/CLEAN一页PDF与签名下载一致，没有跨类型图片或档案；身份/API/引用/代理、握手和五类隔离资源清理均成功，源码摘要稳定。[脱敏报告](../testing/reports/R9_A4D32_NATIVE_PDF_REGRESSION_2026_10_23.json)。两轮均各跑一项完整Java旅程，不将它们表述为同轮55项Java通过。

代码只读复核未发现本阶段新增的可确认问题。提交前gstack敏感扫描的一项MEDIUM是固定本机测试输入URL，核实并设置精确例外后零发现；未对令牌或其他地址设例外。微信/后端生产功能和契约无新增变更，历史覆盖仍317/352，全API仍false。

下一阶段建议复用本隔离平台推进录音/试听与受控音频上传联调；开发者工具结果、手机麦克风录制和正式ASR应分开验收。

## GitHub交付（2026-10-24）

完整实现、测试与两份脱敏报告已随f49f870整批上传[草稿PR223](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/223)，base=codex/r9-a4d31-native-material-upload，依赖PR222。创建后已核实OPEN/draft及提交一致；远程CI正在运行，最终head状态另行核查，不宣称全部成功。未合并或部署。
