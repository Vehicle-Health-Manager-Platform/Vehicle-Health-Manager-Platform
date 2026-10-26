# R2b 行驶证候选录车（2026-10-27）

分支codex/license-recognition，承接PR243/7f3b19c；前置PR243本轮核对8项CI成功、仍OPEN。设计和实施见[规格](../superpowers/specs/2026-10-26-license-recognition-design.md)、[计划](../superpowers/plans/2026-10-26-license-recognition.md)，接口与配置见[行驶证说明](../api/LICENSE_RECOGNITION.md)。本轮不合并或部署。

## 已实现

- 专用license场景：只读取明确标签的车牌/VIN，不猜O/0或繁简转换，低可信和多值保留，不返回姓名住址或OCR原文。复用受限Paddle子进程、8秒推理截止和单worker；原mileage场景继续回归。
- 当前OWNER新车用途POST网关：本人CLEAN JPEG/PNG、1..4MiB、严格正文、明确同意；读取前后会话/账号/文件事实复核，失败也复核。去标识化账号哈希计数每UTC分钟5次、实例并发2，不持有事务跨越推理，不写车辆/档案。
- 页面：选图仅在本机，同意后才上传/识别；未知上传结果同图同键重试，已上传文件可单独重试识别。全部候选及可信度可看，人工修改重置核对；多值不默认选。确认只带入车牌/VIN，仍需手动选车型和里程再保存。
- 隐藏/卸载、换身份/模式、撤回同意或取消处理会取消接收并丢弃迟到结果；支持时abort。预填仅内存、账号绑定、60秒单次，不传文件或内部车型ID。身份错误提供重新登录。
- 新功能默认关闭，配置/Compose/云模板、接口、四角色手册、启动/部署及外部验收说明已同步。

## 测试与修复

后端选定9套81项通过，0失败/错误/跳过：LicenseResultTest 6、LicenseOcrClientTest 6、JdbcLicenseRecognitionTest 15、LicenseHttpJourneyTest 4、MileageOcrClientTest 6、JdbcMileageRecognitionTest 12、JdbcVinRecognitionTest 11、VinHttpJourneyTest 4、JdbcUploadHttpTest 17。两个HTTP类各包含3条继承核心旅程；本轮未全跑所有后端。实际TCP HTTP、JWT、MySQL和生产上传逻辑已执行，扫描/存储/OCR上游为明确替身，不是生产私有存储或真实证件效果验收。

首轮修正两处测试夹具：Mockito重设副作用答案误再次撤销会话，改用doAnswer；伪造OWNER_BINDING的无效JWT本应401，改用正式AuthTokens.binding生成凭证测试403。没有放宽业务鉴权。私有OCR客户端补拒绝重复Content-Type和非identity编码，受影响里程客户端一并回归。

前端新增32项与既有录车/VIN合计66项通过。OCR最终77项：76通过、1项Linux限定跳过。最终统一9组回归全部通过，执行前后源码摘要一致：小程序1155/1155、Python539/539、OCR76通过/1平台跳过；微信/H5构建、普通微信无身份测试桥与原生共享样式门禁、OpenAPI/迁移挂载/交接索引/契约结论检查全部通过。

gstack browse最终390×844合成H5旅程20断言及登录失效4断言通过（共24项），含同意、同图同键重试、失败后复用、低可信/多值/空结果、取消迟到、人工修改/核对及手动选车型；截图已检查，无横向溢出。浏览器会话/选图/上传/来源均为明确替身，不是微信开发者工具或手机验收。

## 真实本地模型观察

复用已预置PaddleOCR 3.7.0、PaddlePaddle 3.3.1、Pillow 12.3.0与PP-OCRv5 mobile CPU模型，无新增下载；Windows受限ProcessRecognizer和私有HTTP实际运行5个合成场景。沪号牌图双字段正确；粤号牌图只得到VIN，返回PARTIAL；无标签与空白无候选，原里程控制123456正确；worker复用与关闭检查通过。

粤图失败原因是模型将简体“粤”高置信读为繁体“粵”（约0.983），严格白名单正确拒绝，新增回归防止自动修复。初次双字段精确断言失败证据保留；最终runtime_path_passed=true，但all_synthetic_fields_matched=false。**不能将运行路径成功或沪图正确表述为全部合成字段/真实证件效果通过。** 生产协议未返回私人原文；诊断行仅在本机合成材料缓存。

可复跑观察（需要已有离线模型、依赖与中文字体；不默认加载或下载）：

```powershell
& .cache/ocr-runtime/Scripts/python.exe services/ocr/license_isolated_check.py --models-root .cache/ocr-prepared --font C:/Windows/Fonts/msyh.ttc --report .cache/stage-handover/license-ocr-real-final-result.json
```

## 已知边界与后续

真实获授权行驶证、旋转/模糊等样本集、手机及本轮Linux真实模型仍未验；高置信错字说明不能直接使用模型置信度充当准确率。默认关闭保留，人工修正/手动入口可用。图片沿既有私有附件策略保留，本功能未提供自动到期或本人删除闭环，运营保留/删除政策须在正式证件接入前明确。取消不承诺撤销已到服务器的上传或删除对象。

契约356项/342受保护，317项选定历史四类证据加39具名结论；旧GET被新POST具名替代。完整最终版本全API验收仍false。国内精确车型来源、ASR异步任务、图文/PDF及向量检索继续按[路线](../superpowers/plans/2026-10-26-recognition-vector-roadmap.md)，正式微信/短信/生产发布后置。局部功能与资料完成不等于项目其余全部开发完成。

最终脱敏机器证据：[LICENSE_RECOGNITION_2026_10_27.json](../testing/LICENSE_RECOGNITION_2026_10_27.json)。原始日志、测试凭据与截图仅留.cache/stage-handover；统一9组日志位于.cache/local-delivery-69aedc44dc99，不上传。

上传：实现提交425d869已推送[草稿PR244](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/244)，base为codex/vin-owner-gateway（PR243）。未合并/部署；新head远程CI独立核对，排队/运行不计通过。
