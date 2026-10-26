# 新车登记行驶证候选接口

R2b 新增 POST `/api/vehicle/license-recognition`，配合小程序添加车辆的行驶证入口。旧 GET `/api/vehicle/ocr-license` 没有识别业务实现，应迁移本 POST。可生成契约见 [OpenAPI](openapi.json)，设计见 [R2b规格](../superpowers/specs/2026-10-26-license-recognition-design.md)。本接口不证明证件真伪、车辆归属或精确车型。

## 上传、同意与请求

选照片仅在本机进行。用户阅读并同意“上传到平台私有存储，用于本次新车登记的车牌/VIN识别”后，使用现有 POST `/api/file/upload`：当前 OWNER 会话、单个 `file` 图片字段、幂等键、私有存储与安全扫描。该接口不要求车辆 ID，不能套用需要既有车辆的 input-materials 或里程识别接口。识别照片限定 1..4MiB JPEG/PNG；通用上传接口可接受的其他格式不代表本识别支持。

照片按既有私有附件策略保留，本功能不承诺取消即删除或自动到期删除，也不会自动把证件照片关联到车辆或档案。后续负责人需明确运营保留期限、本人删除与存量清理政策后再开展正式证件验收。测试报告不得把“照片已上传”记为“证件已识别”。

识别请求要求当前有效 OWNER Bearer 会话、`Content-Type: application/json`，禁止任何 query，不需要幂等写键：

```json
{"file_id":123,"purpose":"NEW_VEHICLE","consent":true,"consent_version":"NEW_VEHICLE_LICENSE_OCR_V1"}
```

`file_id` 必须是本人当前有效、未删除、CLEAN 的 JPEG/PNG，大小为 1..4194304 字节。只接受以上四个字段；不接受 `vehicle_id`、URL、对象 key、额外字段、重复键或尾随 JSON。缺少同意、旧版同意或非新车用途均拒绝。请求不会读取其他车主或商家图片。

## 返回与人工确认

成功响应的 `data` 示例使用合成值：

```json
{
  "file_id":123,
  "purpose":"NEW_VEHICLE",
  "source":"LOCAL_OCR",
  "review_status":"CANDIDATE",
  "requires_confirmation":true,
  "fields":[
    {"key":"plate","status":"CANDIDATE","candidates":[{"value":"粤B12345","confidence":0.95}]},
    {"key":"vin","status":"CANDIDATE","candidates":[{"value":"LTEST223456789012","confidence":0.95}]}
  ]
}
```

固定先 `plate` 后 `vin`。每字段保留所有不同候选及 OCR 实际置信度，最多10个，超出预算报来源失败而不截断。无值为 NO_CANDIDATE；多值为 AMBIGUOUS，不自动选值；单值置信度低于0.9为 LOW_CONFIDENCE，否则为 CANDIDATE。整体 `review_status` 优先 AMBIGUOUS，再 LOW_CONFIDENCE；两字段均为高可信单值才 CANDIDATE，一字段为 PARTIAL，均无值为 NO_CANDIDATE。置信度不是证件准确率或真实性证明。

本地 OCR 仅从明确车牌/VIN标签的同行或紧邻下一行提取完整合法值，不修复 O/0、不删除内部空格/分隔符，不从 VIN 推算车型。分行标签取标签与值置信度的较低值，重复值取较低值。姓名、住址、原始 OCR 文本和图片位置不返回客户端、不进入普通日志。

页面展示全部候选及置信度；多值项留空由车主对照照片决定。用户可修正车牌/VIN，勾选逐项核对后带入原手动表单；手动修改不标为模型原始结果。预填只在内存、绑定当前账号、60秒单次消费，不带入照片附件或内部 `model_id`。品牌、车系、年款、配置和里程仍需人工选择，再点击原保存按钮。不会自动调用外部 VIN 来源。无结果、未配置或不愿上传时可直接手动录车。

## 权限、错误与资源预算

- 400：图片 ID、新车用途或明确同意无效；query、额外/重复键、尾随 JSON 均拒绝。
- 401：JWT/数据库会话过期、撤销，或账号停用/删除。处理前、私有读取后与供应商返回后复核；供应商失败也复核。
- 403：非 OWNER 角色或仅有绑定凭证。
- 404：非本人、已删、非 CLEAN、类型或大小不符的图片。
- 409：处理期间文件可读取元数据变化；需要重新选择/上传。权限或安全状态已失效时按401/404拒绝。
- 429：每车主每 UTC 分钟最多5次准入尝试（失败也计入，换会话不重置），或实例同时已有2次；不排队。固定窗口边界可能突发。
- 503：开关关闭、数据库/私有存储/来源不可用、超时或协议无效；不透传原文、对象 key、URL或内部凭据。

响应为 `Cache-Control: no-store`。不持有数据库事务跨越存储/模型调用；业务操作只写限流哈希/时间/计数，不写车辆、档案或业务审计事件。每次准入仅清理当前账号一天前的行驶证计数，不改其他业务 scope。

私有来源 HTTP 客户端总期限10秒、响应64KiB，无应用级重试或重定向；受限worker默认8秒推理截止、一个任务且无排队，沿用进程/网络限制。客户端上传与识别是两个阶段，不能把10秒来源期限说成全流程总耗时。取消/撤回同意、换图、换账号、切模式或隐藏卸载时取消接收并丢弃迟到结果；运行时支持时 abort，已经在服务端准入的上传/识别可能继续。

## 本地配置及部署接入

后端根模板、Compose、云托管模板提供四个变量；正常包默认关闭，无测试模型或身份后门：

```dotenv
LICENSE_OCR_ENABLED=false
LICENSE_OCR_URL=
LICENSE_OCR_SERVICE_TOKEN=
LICENSE_OCR_ALLOW_PRIVATE_HTTP=false
```

启用前依次准备当前有序数据库迁移、私有对象存储及 CLEAN 扫描、固定版本 Paddle 模型/离线依赖、受限 OCR 服务、后端配置和小程序现有 API 原点/云通道。URL只能由后端配置，固定 `/recognize` 路径，不含 query/片段/用户信息；优先私有 HTTPS，只有显式允许时才接受 `http://ocr`、`http://127.0.0.1` 或 `http://localhost`。服务凭据为32..512个可见 ASCII 字符，只放后端和 OCR 服务，不进 VITE、普通日志或仓库。

基础 Compose 的开关/URL/凭据均来自私有 env 文件。可选 `deploy/compose/ocr-runtime.yml` 复用已核验离线模型和私有网络：行驶证开关仍默认 false，显式 `LICENSE_OCR_ENABLED=true` 才打开；该 overlay 将行驶证 URL 固定为内部 `http://ocr:8090/recognize`，凭据复用 `MILEAGE_OCR_SERVICE_TOKEN`，与 OCR 服务匹配。overlay 同时启用已有里程能力，不能当作只开行驶证的部署。基础配置使用独立 LICENSE 凭据时必须与所选 OCR 服务真实 token 一致。

关闭 `LICENSE_OCR_ENABLED` 并重启后端即可回到503与手动录车。共享 OCR 模型不应因行驶证失败而被误删；回滚代码时核对原里程场景仍可用。本阶段没有新增数据库迁移，不改变原图片上传白名单。

## 正式接入所需资料与验收

负责人准备经授权的行驶证照片及车牌/VIN对照、用途同意说明、图片保留/本人删除/清理政策、私有存储与扫描策略、固定模型/依赖摘要和运维资源。分别测清晰/模糊、部分标签、多值、低置信、无值、旋转、坏图、超限、超时及人工修正；按本人/跨账号、撤销/停用、扫描状态变化、迟到、限流和关闭降级验证，再核对最终车辆保存和无证件自动关联。

合成文本、测试 Provider、合成图真实模型、获授权真实证件、微信开发者工具与 iOS/Android 真机分别记录。局部测试不能替代真实证件效果、国内精确车型来源、真机或正式微信/短信/生产验收。ASR、通用图文/PDF及向量检索按[后续路线](../superpowers/plans/2026-10-26-recognition-vector-roadmap.md)继续。

本轮[测试/已知问题报告](../progress/RECOGNITION_R2B_LICENSE.md)与[机器证据](../testing/LICENSE_RECOGNITION_2026_10_27.json)分别记录替身回归、合成图真模型与尚未验收的真实证件；粤号牌合成图出现高置信错字，严格拒绝并返回PARTIAL，不能概括为所有合成字段通过。
