# 诊断专属站内消息

[设计](../progress/R7C3_DIAGNOSIS_NOTIFICATIONS_DESIGN.md)。五类事件：DIAGNOSIS_CREATED/MESSAGE/ASSIGNED/REPLIED/CLOSED，分别表示提交、追加、分配、答复和关闭。通知来源独立关联诊断，不使用订单字段，不含正文、车型、账号身份、档案或私有链接。

## 四项接口

- GET `/api/owner/diagnosis/notifications`
- GET `/api/admin/diagnosis/notifications`
- PUT `/api/owner/diagnosis/notifications/{id}/read`
- PUT `/api/admin/diagnosis/notifications/{id}/read`

GET仅允许page（1至1000000，默认1）和page_size（固定20），禁止未知/重复参数。返回ApiResponse.data含unread_count及notifications分页items/total/page/page_size。每项严格为notification_id、kind、case_id、case_status（当前WAITING/PROCESSING/REPLIED/CLOSED）、target（CASE/QUEUE）、occurred_at和read_at（UTC，可空）。事件类型代表历史事实，case_status是当前状态。

PUT无query，必填UUID Idempotency-Key，正文严格为{}，ID为1至9007199254740991整数。返回data为notification_id/read_at。第一次已读、审计和原键回执原子提交；新键读取已读不改变原read_at、不重复审计。原键重放仍重新授权，不能通过旧回执绕过转派或撤权。

全部响应及控制器错误no-store。400参数/正文/键无效；401会话/账号无效；403身份或当前诊断权限不足；404不在历史接收人与当前用途交集；503存储/事务不可用。客户端不能指定接收人或target。

## 历史接收人与当前用途

车主：通知目标是事件当时的本人账号，且当前诊断记录仍属于本人，target仅CASE。历史读取不要求仍满足本月邀请资格；新增文字继续由诊断业务写入重新验资格。

处理人：须事件发生时已获派且有处理权限，当前仍是本条诊断获派者并有处理权限，才可收到CASE入口。转派后原人不可读，接手者不继承旧处理人消息。

管理人：事件发生时有独立管理权限且当前仍有管理权限，仅获得QUEUE提示。拥有管理权限不等于有正文权限；旧管理消息不会因后来获派而提升为CASE。同账号同时具有两种用途时只一条通知，当前处理用途失效而管理用途仍有效时降为QUEUE。任何正文读取还必须通过诊断详情接口当前权限复核。

## 事务与投递

V048增加diagnosis_notification_outbox及diagnosis_notification_target，总104表。诊断case_event与通知来源/接收用途、业务文字/状态、审计/回执同事务；任一失败整体回滚。来源event ID、case/revision与事件UUID分别唯一，接收人按事件/身份/账号唯一。

捕获当时本人、当前有效获派人和当前有效管理人，当前锁定读取；投递不重新选择接收人。逐事件独立事务、SKIP LOCKED、全体通知与DONE原子提交；每批最多20、默认5000ms，失败固定DELIVERY_UNAVAILABLE及5至300秒退避，重启持续恢复。首次投递和扫描采用同一业务时钟。旧case_event和旧无来源notification不回填或自动提升为可信消息。

当前总OpenAPI256操作、85小程序页面。诊断消息与订单消息分开计数/入口；正式短信/微信消息仍R9。已读未决键仅页面内存，页面重建可从服务器真实read_at重新核对；诊断文字本机恢复另见[诊断接口](ASYNC_DIAGNOSIS.md)。
