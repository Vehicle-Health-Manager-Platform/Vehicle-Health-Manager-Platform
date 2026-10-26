# 接车检查与商家图片契约（A3）

2026-10-08。依据：[已确认规格](../superpowers/specs/2026-10-07-pickup-inspection-design.md)。

商家完成七图检查单后，真实提交 PAID→RECEIVED。车主确认和异议见[A4 契约](PICKUP_OWNER_DECISION.md)；派工、施工、核销仍由后续步骤提供；不能把接车成功视为施工完成或真实收款。

## 入口与身份

| 方法/路径 | 身份与作用 |
| --- | --- |
| GET /api/order/{id} | 本人 OWNER；PAID 详情返回 appointment_code，六位字符串，保留前导零 |
| POST /api/merchant/files/upload | 有效 MERCHANT/STAFF；单个 multipart file、UUID Idempotency-Key |
| GET /api/merchant/files/{id}/access | 上传者本人 MERCHANT/STAFF，CLEAN 文件短时签名 |
| GET /api/check/pickup/context?order_id={id} | 本店 MERCHANT/STAFF；可接车订单及最新里程基线，不返回码 |
| POST /api/check/pickup/submit | 本店 MERCHANT/STAFF；完整单据、预约验码与状态迁移 |
| GET /api/check/pickup/{order} | 本店 MERCHANT/STAFF 或关联本人 OWNER |
| GET /api/check/pickup/{order}/files/{file}/access | 同上；仅单据实际关联且 CLEAN 的图片 |

TECHNICIAN、其他店铺、非关联车主不能接车或读单。身份有效性、店铺/用户状态在真库复核；读取响应 Cache-Control=no-store。

## 提交字段

```json
{
  "order_id": 123,
  "appointment_code": "012345",
  "photos": {"FRONT": 1, "REAR": 2, "LEFT": 3, "RIGHT": 4, "ROOF": 5, "DASHBOARD": 6, "INTERIOR": 7, "FUEL": 8},
  "mileage": 1200,
  "fuel_level": "HALF",
  "damage_status": "PRESENT",
  "damages": [{"photo_slot": "LEFT", "x": 0.5, "y": 0.3, "note": "划痕"}],
  "arrival_reason": "提前到店"
}
```

例中的码和 ID 均为合成示例。服务端拒绝未知字段及目标状态字段。七个原槽位必须完整，允许追加唯一命名 FUEL 油表槽（严格 7/8 图），微信新表单要求八图、ID 互不重复、属于当前提交员工且 CLEAN/未删除，文件不能复用到第二份接车单。单张 ≤10MiB，JPEG/PNG/WebP，扫描和存储在接车事务外独立完成。

- mileage：0–9999999 整数，来源固定 MANUAL。
- 里程基线：最新有整数里程的本人车辆档案（recorded_at、id 降序，方式 1/3），无档案则车辆 current_mileage。接车事务锁车辆后重读并保存快照；低于基线时 mileage_reason 必填 1–200 字，说明后允许提交。
- fuel_level：EMPTY、QUARTER、HALF、THREE_QUARTERS、FULL，必须明确选择。
- damage_status：NONE（damages 为空）或 PRESENT（1–20 条照片位置与说明）；坐标 0–1，说明 1–200 字。
- arrival_reason：提交时实到时间与预约开始绝对差超过 2 小时必填 1–200 字；说明后允许提前/延后接车。实到时间来自服务端。
- 不自动覆盖车辆里程、不新建车主档案、不发送短信或订阅消息。本人可刷新订单查看接车单。

## 事务与重试

预约码复用 order.verify_code，由服务端安全随机生成；新订单生成、旧 PAID 订单本人详情安全补码，商家投影和审计不返回/保留明文码。先鉴权及验码，失败以独立事务计数，按员工与订单的固定 10 分钟窗口最多 5 次失败，随后 429/Retry-After。

接车写入带 UUID Idempotency-Key。验码通过后同键同正文返回原响应，同键不同正文返回 400；错误码校验先于幂等匹配。新键不能修改已提交单据。成功响应含 pickup_check_id、order_id、status、owner_confirm、实到时间、里程比较、实际七或八槽 file_id 与损伤信息，不含预约码、手机号、完整车牌/VIN、对象键或长期图片链接。

接车单、图片关联、check_in_completed_at、RECEIVED、order_status_transition、audit_log、幂等成功响应同事务；任一步失败整体回滚。成功重复请求不会新增检查单和两类成功审计。接车后仍占预约名额、owner_confirm=0；START_SERVICE 继续返回 43003。

通用 `/api/merchant/orders/{id}/actions` 的 RECEIVE 已停止对外开放，返回 43001；PAID 的 allowed_actions=[]，页面改走接车检查入口。

## 文件权限与迁移

商家上传使用 owner_type=staff_account，上传幂等/限流作用域含主体类型，车主同 ID 不串用。既有车主上传与档案最多五图协议兼容。预览链接沿用现有最多 300 秒；过期重新签发。已签发链接到期前仍是有效访问能力，撤销身份阻止新签发。

V010 扩展上传作用域和 pickup_check，新增 pickup_check_file 的单据/槽位及全局文件唯一索引，49→50 表。新 Compose 数据卷自动安装，旧数据卷须备份后补迁移。迁移重复执行保留既有数据。

统一错误：400 字段/必要原因；401 失效会话；403 角色不符；404 资源不属于本人/本店；409 状态冲突；422 错码/非本人安全或已关联文件；429 验码/上传限流；503 扫描、存储或写入不可用。数据库/私有存储细节不向客户端暴露。

验证与本机辅助边界见[验收记录](../testing/LOCAL_PICKUP_ACCEPTANCE.md)。

## R3c1 微信扫码与油表（2026-10-11）

车主 PAID 订单二维码严格为 `VHMP:PICKUP:1:<安全正整数订单ID>`，不含预约码、车牌、电话或URL。商家/员工扫码仅定位：先调用本店 context，通过后打开接车表单；仍手填预约码验码，不因扫描改变状态。取消扫码无错误提示，其他格式和外部URL拒绝，身份变化/页面隐藏废弃迟到回调。

FUEL 与 DASHBOARD 必须不同文件，沿用 CLEAN、上传员工本人、未删除、全局不可重复关联及同事务写入/审计/幂等。FUEL 不作为损伤标注槽。旧七图响应不会补造油表照片，客户端明确显示历史未提供。已有 `pickup_check_file.photo_slot VARCHAR(16)` 可存 FUEL，无新增表或迁移（仍66表）。图片签发仍受订单归属授权。

原生扫码/二维码视觉与真机留在R9；离线独立解码只证明生成内容可解码。二维码不是授权凭证。

## R7a 取车对比（2026-10-22）

`POST /api/check/delivery/compare` 把接车基线与完工证据放在一起，回答"这辆车能不能交付"。**只读**：不写库、不改订单状态，交付本身仍由核销完成（核销带资金与免佣语义），避免第二条写路径与核销争夺订单状态。因此该接口**不需要** `Idempotency-Key`（契约已同步移除该参数）。

正文严格为 `{"order_id":<正整数>}`，不接受任何 query。身份为本单车主或本店 `MERCHANT/STAFF`；技师、他人车主、他店一律 404，未知角色 403。

返回 `data`：

| 字段 | 说明 |
| --- | --- |
| `order_id` / `order_status` | 订单与当前状态 |
| `result` | `READY`（证据齐备）或 `INCOMPLETE`（有缺口，见 `missing`） |
| `deliverable` | `result=READY` 且订单处于 `PENDING_VERIFY`；已完成订单可回看但不再可交付 |
| `baseline` | `pickup_check_id`、`mileage`、`mileage_delta`（相对接车基线）、`fuel_level`、`damage_status`、`damage_count`、`photo_count`、`photo_slots`（排序后的槽位名）、`owner_confirm`、`confirm_at` |
| `work.protection` | `submitted`、`item_count`、`photo` |
| `work.report` | `submitted`、`status`（`MISSING/SUBMITTED/SIGNED`）、`process_photos`、`fault_photos`、`finish_photos`、`signature_photos`、`no_fault_parts`、`no_parts`、`signed_at` |
| `missing` | 缺口编码：`OWNER_CONFIRM`、`PROTECTION_PHOTO`、`REPORT`、`PROCESS`、`FINISH`、`FAULT`、`SIGNATURE` |

`FAULT` 仅在既无故障件照片、也未声明 `no_fault_parts=true` 时出现。没有接车单时返回 `409` `43001`（没有基线无法对比），不返回空对比。响应不含车主/员工/技师 id、对象键或文件列表。

车主端页面已接入：`pages/order/delivery-compare.vue`（入口在订单详情，仅 `PENDING_VERIFY`/`COMPLETED` 显示），文案与缺口语义集中在 `services/delivery-compare-display.js`。契约与四类证据见[验收](../testing/DELIVERY_COMPARE_ACCEPTANCE.md)，页面设计见[取车对比页规格](../superpowers/specs/2026-10-22-delivery-compare-page-design.md)。
