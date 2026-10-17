# R7a 本人订单施工证据

两个已实现只读接口，OWNER当前会话，响应与业务错误均no-store；不接受任何query。无数据库迁移或履约写操作。

- `GET /api/order/{id}/work`：仅当前 `order.user_id` 本人且订单未删除。跨车主/不存在返回404；会话撤销、账号停用返回401。
- `GET /api/order/{id}/work/files/{file}/access`：文件必须属于该本人订单的可信接车、防护或已提交报工/质检记录；当前扫描CLEAN、未删除、JPEG/PNG、1–10485760字节及原上传者匹配，质检只允许PNG。签名前后两次复核，存储调用期间撤权/记录删除不能返回旧授权URL。对象存储签名失败沿用已有文件服务错误处理。

ID为1–9007199254740991整数；只有服务端事实可提供证据。不返回内部员工/技师身份编号、手机号、openid、原始对象key或长期图片地址字段；报工方案/分析按原业务内容展示，不整体输出员工、订单或文件对象。已完成/取消订单仍可按当前本人订单归属查看历史事实，订单状态不是证据完整性的替代条件。

## 详情投影

`order_id/order_status/timeline/groups/report_state/report` 为全部顶层字段。

timeline只含event/occurred_at，按时间排序，事件限CREATED、CHECKED_IN、OWNER_CONFIRMED、ASSIGNED、PROTECTED、REPORTED、QUALITY_SIGNED、REDEEMED、CLOSED。缺失时间不编造，不输出动作操作者和内部备注。

groups固定顺序PICKUP、PROTECTION、PROCESS、FAULT、FINISH、SIGNATURE。每组只有kind、state、photos；state为MISSING（尚无事实）、UNKNOWN（原来源不完整/记录不可用）、RECORDED（已有相应记录）。RECORDED不代表每张图当前可用，photos每项file_id/slot/available逐张表达；slot仅接车照片有原分类，其余空字符串。无故障件必须有原报工no_fault_parts=true，不能以空数组推断“没有故障”。签字前SIGNATURE为MISSING。

report_state为MISSING/UNKNOWN/SUBMITTED/SIGNED。只有可信service_report_submission、有效报工形状和状态相符才返回report；其余null。report只包含维修方案、故障分析、配件四字段列表、no_parts/no_fault_parts、工时分钟、提交/签字时间。历史无提交来源、软删除或非法附加字段不会绕过验证。

## 图片与生命周期

每次打开图片重新获取签名，返回url/expires_at。沿用私有对象存储短期签名（最多300秒）；expires_at可带Java Instant小数秒。新的读取/签名在失权后拒绝，已经发出的URL在其有效期内仍可能有效，不声称即时撤回对象存储已签链接。

前端在当前页预览，不触发原生预览的页面隐藏；链接到期、关闭、刷新、切号、换订单或隐藏时移除内存/展示引用，迟到结果不能恢复旧图。页面不持久存储私有URL，不保存到相册或长期文件。

实际页面`pages/order/work-evidence`由本人订单详情进入；施工前接车照和施工后完工照按原分组查看，并可手动各选一张进行对比，不制造同角度配对。对比的两张图片分别重新授权，其中任一失败不展示部分旧图，按两者较早到期一起清理。后台私有存储签名与真实设备交互沿既有能力，真机验收仍R9。
