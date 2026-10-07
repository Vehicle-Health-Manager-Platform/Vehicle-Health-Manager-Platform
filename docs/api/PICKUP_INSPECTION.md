# 接车检查与商家图片契约（A3）

2026-10-08。依据：[已确认规格](../superpowers/specs/2026-10-07-pickup-inspection-design.md)。

商家完成七图检查单后，真实提交 PAID→RECEIVED。车主确认、派工、施工、核销仍由后续步骤提供；不能把接车成功视为施工完成或真实收款。

## 入口与身份

| 方法/路径 | 身份与作用 |
| --- | --- |
| GET /api/order/{id} | 本人 OWNER；PAID 详情返回 appointment_code，六位字符串，保留前导零 |
| POST /api/merchant/files/upload | 有效 MERCHANT；单个 multipart file、UUID Idempotency-Key |
| GET /api/merchant/files/{id}/access | 上传者本人 MERCHANT，CLEAN 文件短时签名 |
| GET /api/check/pickup/context?order_id={id} | 本店 MERCHANT；可接车订单及最新里程基线，不返回码 |
| POST /api/check/pickup/submit | 本店 MERCHANT；完整单据、预约验码与状态迁移 |
| GET /api/check/pickup/{order} | 本店 MERCHANT 或关联本人 OWNER |
| GET /api/check/pickup/{order}/files/{file}/access | 同上；仅单据实际关联且 CLEAN 的图片 |

TECHNICIAN、其他店铺、非关联车主不能接车或读单。身份有效性、店铺/用户状态在真库复核；读取响应 Cache-Control=no-store。

## 提交字段

```json
{
  "order_id": 123,
  "appointment_code": "012345",
  "photos": {"FRONT": 1, "REAR": 2, "LEFT": 3, "RIGHT": 4, "ROOF": 5, "DASHBOARD": 6, "INTERIOR": 7},
  "mileage": 1200,
  "fuel_level": "HALF",
  "damage_status": "PRESENT",
  "damages": [{"photo_slot": "LEFT", "x": 0.5, "y": 0.3, "note": "划痕"}],
  "arrival_reason": "提前到店"
}
```

例中的码和 ID 均为合成示例。服务端拒绝未知字段及目标状态字段。七个槽位必须完整、ID 互不重复、属于当前提交员工且 CLEAN/未删除，文件不能复用到第二份接车单。单张 ≤10MiB，JPEG/PNG/WebP，扫描和存储在接车事务外独立完成。

- mileage：0–9999999 整数，来源固定 MANUAL。
- 里程基线：最新有整数里程的本人车辆档案（recorded_at、id 降序，方式 1/3），无档案则车辆 current_mileage。接车事务锁车辆后重读并保存快照；低于基线时 mileage_reason 必填 1–200 字，说明后允许提交。
- fuel_level：EMPTY、QUARTER、HALF、THREE_QUARTERS、FULL，必须明确选择。
- damage_status：NONE（damages 为空）或 PRESENT（1–20 条照片位置与说明）；坐标 0–1，说明 1–200 字。
- arrival_reason：提交时实到时间与预约开始绝对差超过 2 小时必填 1–200 字；说明后允许提前/延后接车。实到时间来自服务端。
- 不自动覆盖车辆里程、不新建车主档案、不发送短信或订阅消息。本人可刷新订单查看接车单。

## 事务与重试

预约码复用 order.verify_code，由服务端安全随机生成；新订单生成、旧 PAID 订单本人详情安全补码，商家投影和审计不返回/保留明文码。先鉴权及验码，失败以独立事务计数，按员工与订单的固定 10 分钟窗口最多 5 次失败，随后 429/Retry-After。

接车写入带 UUID Idempotency-Key。验码通过后同键同正文返回原响应，同键不同正文返回 400；错误码校验先于幂等匹配。新键不能修改已提交单据。成功响应含 pickup_check_id、order_id、status、owner_confirm、实到时间、里程比较、七槽 file_id 与损伤信息，不含预约码、手机号、完整车牌/VIN、对象键或长期图片链接。

接车单、图片关联、check_in_completed_at、RECEIVED、order_status_transition、audit_log、幂等成功响应同事务；任一步失败整体回滚。成功重复请求不会新增检查单和两类成功审计。接车后仍占预约名额、owner_confirm=0；START_SERVICE 继续返回 43003。

通用 `/api/merchant/orders/{id}/actions` 的 RECEIVE 已停止对外开放，返回 43001；PAID 的 allowed_actions=[]，页面改走接车检查入口。

## 文件权限与迁移

商家上传使用 owner_type=staff_account，上传幂等/限流作用域含主体类型，车主同 ID 不串用。既有车主上传与档案最多五图协议兼容。预览链接沿用现有最多 300 秒；过期重新签发。已签发链接到期前仍是有效访问能力，撤销身份阻止新签发。

V010 扩展上传作用域和 pickup_check，新增 pickup_check_file 的单据/槽位及全局文件唯一索引，49→50 表。新 Compose 数据卷自动安装，旧数据卷须备份后补迁移。迁移重复执行保留既有数据。

统一错误：400 字段/必要原因；401 失效会话；403 角色不符；404 资源不属于本人/本店；409 状态冲突；422 错码/非本人安全或已关联文件；429 验码/上传限流；503 扫描、存储或写入不可用。数据库/私有存储细节不向客户端暴露。

验证与本机辅助边界见[验收记录](../testing/LOCAL_PICKUP_ACCEPTANCE.md)。
