# 商家派工与技师接单接口契约

更新：2026-10-08，A5.2。六个后端接口已编码，权限/幂等/竞争/回滚验证进行中；商家派工和技师工作台页面归 A5.3。业务与权限依据见[A5 规格](../superpowers/specs/2026-10-08-a5-dispatch-design.md)。商家通用 `START_SERVICE` 已停止对外开放（43004），`allowed_actions` 不再投影它；本人接单是新增施工开始入口。

## 通用规则

返回 `{code,message,data,request_id}`；读取分页沿用订单接口的 `items/total/page/page_size`，默认 20、最大 100，页码 1–1000000。时间为 ISO8601 UTC。所有响应 `Cache-Control: no-store`。

写请求必带 UUID `Idempotency-Key`，24 小时内按调用者、方法、路径、键与规范化正文去重。同键异体 `40001`。正文严格验证，不接受额外字段、浮点/字符串 ID、目标订单状态或商家 ID。查询不接受重复或未知参数。身份与归属在成功缓存重放时仍复核。

`technician_id` 永远是员工 `staff_account.id`。AppID 来自服务端配置，商家 ID 来自有效会话。候选必须是本店有效 TECHNICIAN 员工、商家有效且在当前 AppID 下有有效微信绑定，无需存在在线会话。

## 1. 商家候选技师

`GET /api/merchant/technicians?page=1&page_size=20`，仅 MERCHANT。

`data`：`{items:[{technician_id:12,label:"tech-demo"}],total:1,page:1,page_size:20}`。

按员工 ID 升序分页；`label` 取员工账号标签。不返回手机号、员工码/哈希、openid、绑定 ID、会话状态；无候选返回空列表。管理员维护与绑定入口沿用已有身份流程，本阶段不新增人员管理 API。

## 2. 商家查询派工

`GET /api/merchant/orders/{id}/assignment`，仅 MERCHANT，本店有效订单。

无派工返回 `data:{assignment:null}`。有派工返回 `data:{assignment:{assignment_id,order_id,technician_id,technician_label,status,assigned_at,accepted_at}}`，`status` 为 `ASSIGNED/ACCEPTED`，`accepted_at` 待接时为 null。查不到订单或跨店均 404；异常历史记录明确冲突，不把它显示为“未派工”。

## 3. 商家首次派工

`POST /api/merchant/orders/{id}/assign`，仅 MERCHANT。

严格正文：`{"technician_id":12}`。

`data:{assignment_id,order_id,technician_id,assignment_status:"ASSIGNED",order_status:"RECEIVED",assigned_at,accepted_at:null,changed:true}`。

订单 `RECEIVED`，有效接车单和接车时间、`owner_confirm=1`、确认时间及 `owner_confirmed_at` 均齐全才能派工。缺防护不是派工失败原因；A6 报工再校验防护。派工、时间、幂等成功响应与成功审计同事务。

新键重复给同一技师时仅在完整待接记录与订单前置仍有效时返回原派工 `changed=false`；已有派工换技师、已接单后再派、历史记录不一致均 40905。不可选的员工（不存在/跨店/停用/未绑定等）统一 40400。服务端不允许客户端覆盖既有派工。

## 4. 技师本人工单列表

`GET /api/tech/orders?assignment_status=ASSIGNED&page=1&page_size=20`，仅 TECHNICIAN。

`assignment_status` 可省略（全部）或为 `ASSIGNED/ACCEPTED`；不接受 `technician_id/merchant_id` 等身份筛选。

`data:{items:[工单投影],total,page,page_size}`。按派工 `created_at DESC,id DESC` 排序。只包含员工本人、同店、未删除派工关联的未删除订单；`ACCEPTED` 工单允许历史订单状态，因此不等价于“仅施工中”。

工单投影：`assignment_id,order_id,order_no,order_status,assignment_status,assigned_at,accepted_at,project_snapshot,appointment_snapshot,can_accept`。项目与预约快照沿用既有订单安全字段；不返回车主、车辆、付款流水、预约/核销码和私有图片。`can_accept` 由服务端结合本人归属、状态与接车/确认证据计算，写入仍独立复核。

## 5. 技师本人工单详情

`GET /api/tech/orders/{id}`，路径 ID 为订单 ID，返回相同工单投影。非本人、跨店、不存在订单或派工统一 40400。前端通过列表返回的 `order_id` 打开详情。

## 6. 技师本人接单

`POST /api/tech/orders/{id}/accept`，仅 TECHNICIAN，路径 ID 为订单 ID。

严格正文为 `{}`，必须带幂等键，不接受代填员工、商家、状态、时间。

`data:{assignment_id,order_id,technician_id,assignment_status:"ACCEPTED",order_status:"IN_SERVICE",assigned_at,accepted_at,changed:true}`。

再次锁定并检查当前身份、本人派工、订单 `RECEIVED` 与接车确认。一次事务写派工状态/接单时间、订单 `IN_SERVICE`、迁移审计、成功审计和幂等响应；失败全回滚。新键重复本人接单只在完整 `ACCEPTED/IN_SERVICE` 状态返回 `changed=false`；其他状态为 40905。同键重放遵循通用权限复核与原响应规则。

## 7. 错误码与入口切换

| HTTP / code | 含义 |
| --- | --- |
| 400 / 40001 | 正文/ID/查询/UUID 键无效，或同键异体 |
| 401 / 40100 | 会话到期/撤销，员工/绑定/商家失效 |
| 403 / 40300 | 角色错误，包含未完成绑定的凭证 |
| 404 / 40400 | 不存在、非本店/本人资源或不可选员工 |
| 409 / 40905 | 状态不允许、已派给另一人或历史记录不一致 |
| 409 / 43001 | 接车检查/有效接车单缺失 |
| 409 / 43003 | 车主确认或确认记录缺失/不一致 |
| 409 / 43004 | 施工开始必须通过本人接单；A5.2 上线后通用商家 START_SERVICE 始终返回此码 |
| 503 / 50300 | 数据库未配置或读写事务失败，使用原键重试写操作 |

角色/参数校验在缺数据库环境下仍执行；资源身份和归属优先校验后才返回业务前置码。商家 `allowed_actions` 在 A5.2 同时移除 START_SERVICE；该动作对应的状态矩阵保留供技师服务端接单使用。

## 8. OpenAPI 与版本范围

六个操作已纳入 OpenAPI 生成器，标记 `technician-dispatch-backend-implemented`，同步严格 schema、分页、角色、幂等键与错误码。运行本版本需 V001–V012、有效数据库、JWT 配置与当前 WECHAT_APP_ID；新 Compose 卷自动迁移，已有库备份后补 V012。前端工作台尚待 A5.3；CI 与本机证据见[A5.2 执行记录](../progress/A5_2_DISPATCH_BACKEND_EXECUTION.md)。
