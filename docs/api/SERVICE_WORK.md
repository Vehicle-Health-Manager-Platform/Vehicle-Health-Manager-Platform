# A6 防护与报工接口

更新：2026-10-08，A6.1 后端阶段。页面与真实 HTTP 链路联调分别安排在 A6.2/A6.3，不将后端测试记为真机验收。

## 通用约定

统一 `{code,message,data,request_id}`，UTC 时间，所有响应 `Cache-Control: no-store`。仅正式有效 MERCHANT 或 TECHNICIAN 会话；技师须有当前 WECHAT_APP_ID 下有效员工绑定。正文是封闭字段集，不接受角色、商家、技师、目标状态或 URL。无查询参数；ID 为 1–9007199254740991 的整数，不接受字符串、浮点数。

写入带 UUID `Idempotency-Key`，24 小时按主体/方法/路径/键/正文去重。同键异体 40001；同键同体在权限与证据仍有效时重放原成功回执。新键重复提交已存在记录返回 40905，不覆盖历史。保护提交重放仍要求订单 RECEIVED/IN_SERVICE；报工/签字重放允许对应已签字的 PENDING_VERIFY，并复核现有证据。进入核销/取消等其他状态后不能继续重放旧施工写入。

身份、商家、工号绑定、订单归属先于缓存复核。施工写入检查有效接车单、检查时间、车主确认 1 或 3、确认时间及 owner_confirmed_at；DISPUTED 或存在 OPEN 争议均阻断。读取只能本店商家或本人派工技师；跨店、其他技师统一 404，不能通过读取获知他人工单状态。

## 1. 图片上传

`POST /api/merchant/files/upload` 商家，`POST /api/tech/files/upload` 技师。一个 multipart `file` 字段、UUID 幂等键，JPEG/PNG 最大 10 MiB，沿用病毒扫描、私有存储、上传并发及频率限制。返回 `{file_id,content_type,size_bytes}`。技师接口新增；数据库鉴权在上传前、成功落库和成功缓存重放时均重新检查。

`GET /api/tech/files/{id}/access` 仅本人原始上传；他人上传但已关联本人施工的防护照走第 5 节订单证据访问。

提交施工证据只能用本人上传、未删除、CLEAN、有效大小/类型的文件。不能复用接车单照片或任何施工证据文件。照片实体是否包含防护项/施工部位，以及签名真伪，仍需实际操作与人工验收，服务器不做视觉识别断言。

## 2. 商家提交防护

`POST /api/check/protection/upload`，MERCHANT。

```json
{"order_id":1,"items":["SEAT_COVER","STEERING_COVER"],"photo_file_id":101}
```

订单必须 RECEIVED 或 IN_SERVICE。items 去重，2–4 项；SEAT_COVER（座椅套）、STEERING_COVER（方向盘套）必选；FLOOR_MAT（一次性脚垫）、FENDER_COVER（翼子板布）可选。一张照片应同时拍到座椅套与方向盘套。防护提交不改变订单状态，成功后不可修改。

## 3. 本人提交完整报工

`POST /api/tech/report/submit`，TECHNICIAN。

```json
{
  "order_id":1,
  "process_photos":[102],"fault_part_photos":[103],"finish_photos":[104],
  "no_fault_parts":false,
  "repair_plan":"更换故障件并复测","fault_analysis":"按实际检查结果填写",
  "parts_used":[{"name":"火花塞","model":"实际型号","brand":"实际品牌","quantity":4}],
  "no_parts":false,"work_hours":45
}
```

当前本人派工必须 ACCEPTED 且有 accepted_at，订单 IN_SERVICE，防护记录与关联图片有效。process_photos、finish_photos 各 1–9 张；fault_part_photos 在有故障件时 1–9 张，没有故障件须 no_fault_parts=true 且数组为空，并在故障分析中说明无故障件。三组文件 ID 整体不能重复。

repair_plan、fault_analysis 去除首尾空白后 1–2000 字。parts_used 最多 20 条，name/model/brand 各 1–100 字，quantity 为 1–999 的整数；没有配件须 no_parts=true 且列表为空，有配件须 false 且至少一条。work_hours 是 1–1440 的整数分钟。

完整报工一次提交，不保存半成品；服务器状态为 SUBMITTED（technician_report.status=0），订单仍 IN_SERVICE、service_report_ready_at 仍为空。不可修改既有报工。A6.2 页面保存未提交输入，不引入服务器草稿或改报工流程。

## 4. 本人质检签字

`POST /api/tech/sign`，TECHNICIAN。

```json
{"order_id":1,"signature_file_id":105}
```

签名为本人新上传的 CLEAN PNG 图，不能复用施工照。重新核对全部防护、完整报工、三类图片与声明、本人派工、车主确认和未解决争议。一个事务写：签名关系、status=1、signed_at、order.service_report_ready_at、IN_SERVICE→PENDING_VERIFY、ORDER_SERVICE_FINISH 迁移与成功审计、成功缓存。任一步失败全部回滚，原键重试；并发只允许一份签字与一次迁移。

商家 `/api/merchant/orders/{id}/actions` 的 FINISH_SERVICE 固定返回 43005，allowed_actions 不再包含它，内部通用履约服务同样拒绝。核销与 COMPLETED 留待 A7；签字成功不代表车主核销完成。

## 5. 施工记录与私有图片读取

| 路径 | 角色与归属 |
| --- | --- |
| `GET /api/merchant/orders/{id}/work` | 本店 MERCHANT |
| `GET /api/tech/orders/{id}/work` | 当前本人派工 TECHNICIAN |
| `GET /api/merchant/orders/{id}/work/files/{file}/access` | 本店、有效关联证据 |
| `GET /api/tech/orders/{id}/work/files/{file}/access` | 本人派工、有效关联证据 |

施工记录 data 为 `{order_id,order_status,protection,report}`：

- protection 无记录为 null，有记录为 `{items,photo_file_id,uploaded_at}`。
- report 无本阶段有效提交元数据为 null，有记录为 `{report_id,process_photos,fault_part_photos,finish_photos,no_fault_parts,repair_plan,fault_analysis,parts_used,no_parts,work_hours,status,submitted_at,signed_at,signature_file_id}`；status=SUBMITTED/SIGNED，未签字的最后两项为 null。
- 私有图片访问返回 `{url,expires_at}`，签名 1–300 秒，由已有对象存储适配器核对对象类型与大小后生成。只在此响应返回临时 URL，禁止持久化或日志记录 URL。
- 不返回员工、绑定、车主、车辆、付款、预约/核销码等身份字段。用户自己输入的方案文字与照片内容仍是订单证据，页面必须按文本安全展示。

## 错误与数据迁移

| 状态/代码 | 说明 |
| --- | --- |
| 400 / 40001 | 字段/范围/互斥条件/幂等键错误，同键异体 |
| 401 / 40100 | 过期/撤销会话、停用员工或商家、失效工号绑定 |
| 403 / 40300 | 角色或 AppID 不匹配 |
| 404 / 40400 | 非本店/非本人、不存在或非有效关联图片 |
| 409 / 40905 | 状态变化、已经提交不可改、异常历史记录 |
| 409 / 43001、43003、43004 | 缺接车、车主确认、本人接单 |
| 409 / 43002 | 缺防护或防护图片失效 |
| 409 / 43005 | 缺完整报工或报工图片失效、通用完工入口停用 |
| 409 / 43007 | 未解决争议阻断 |
| 422 / 42200 | 图片非本人安全文件、类型不符或已用于其他证据 |
| 503 / 50300 | 数据库/对象存储未配置、事务或外部适配器失败 |

V014 新增 service_evidence_file（file_id 唯一）与 service_report_submission（order_id、report_id 各唯一），总计 54 表。复用 repair_protection 与 technician_report；原 photo_url 不写入，报工 photos JSON 存文件 ID。无外键，重复迁移只保留历史；没有关联文件或提交元数据的历史防护/报工不得被当成就绪证据，写入拒绝并交人工处理。不自动回填旧报工或签字。

已有数据库须显式执行 V014；Compose 初始化挂载只对新数据库生效。不得删除生产数据卷来应用迁移。
