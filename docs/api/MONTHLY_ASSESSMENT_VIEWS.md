# R8b2b 月结上下文、范围读取与页面

2026-10-16。沿用户已确认规则接通R8b2a冻结结果与实际小程序操作，仍无正式支付供应商或历史成绩导入。整个R8完成后才上传代码和文档。

## 七项只读接口

全部no-store，统一ApiResponse；未知/重复query均400。month为YYYY-MM；分页只允许page=1–1000000、page_size=20。请求身份和当前会话/权限每次实时复核。

- `GET /api/admin/assessment/month?merchant_id=...&period_month=...`：独立can_assessment。当前有效真实门店的服务器月结上下文。
- `GET /api/admin/assessment/history?merchant_id=...&period_month=...&page=1&page_size=20`：该门店/月全部修订，按revision降序。可核对后来停用门店的旧记录；不得从一页历史推测当前修订。
- `GET /api/merchant/assessment/month?period_month=...`：仅当前真实店长，本店上下文，只读。
- `GET /api/merchant/assessment/history?period_month=...&page=1&page_size=20`：仅店长、本店/月历史。
- `GET /api/merchant/assessment/snapshots/{id}`：仅店长、该ID须属于当前本店，否则404。
- `GET /api/merchant/assessment/snapshots/{id}/facts?page=1&page_size=20`：同上，最小事实分页，不公开私有正文/图片路径/手机号。
- `GET /api/merchant/assessment/staff?period_month=...`：当前店长或员工，仅本人员工归因计数。禁止staff_id、merchant_id等选择器，员工不能调用全店上下文/历史/详情/事实。

前三类按月请求必须有period_month；admin同时必须merchant_id。详情不接收月份等query，事实仅分页。角色不符403，失效会话401，资源不在本范围404，数据库/版本不可确认503。无库时合法读取503、非法选择器仍400。

## 月份上下文

严格字段：merchant（id/name/address）、period_month、timezone=Asia/Shanghai、server_now、month_end（下一北京月初UTC）、revision、latest（冻结结果或null）、policy（整月政策或null）、state、can_settle。

state为MONTH_OPEN、POLICY_UNKNOWN、READY。只有运营且月份已结束时can_settle=true；无整月政策可显式冻结未知结果，不伪造0分。店长始终false。revision由服务器月份头与最新冻结记录同一只读事务核对，二者不符503；首次为0/latest=null。

policy按与月结相同的整月覆盖证明只读查询，不能使用FOR UPDATE或顺便生成记录。latest及详情、事实沿[冻结快照协议](ASSESSMENT_SNAPSHOTS.md)。读取只是当时视图，提交仍依expected_revision重新CAS；多请求期间修订变化由页面拒绝混合并刷新。

本人归因响应仅role=MERCHANT|STAFF、staff_id、merchant_id、period_month、timezone、state=NO_SNAPSHOT|FROZEN、revision、captured_from/to、attribution={included,excluded,unknown}。从本店该月最新快照筛选本人员工的ACQUISITION事实，只计对应三类状态；没有快照时revision=0、采集时间null和零候选计数，界面明确未结算，不作为真实拉新成绩。无新客标识或其他员工明细。

R8b1店长政策接口的scoring_available现为true，含义是月结读取能力可用；不承诺有政策、有效分母或已生成成绩。

## 页面与恢复

运营`/pages/operator/monthly-assessment`，店长/员工`/pages/merchant/assessment`。独立权限入口与静态登录回跳只接受空查询，拒绝携带门店/员工选择器。共享只读结果组件展示每个维度及未知、政策/目标/等级、修订、采集时间和最小逐项依据。

运营确认冻结merchant_id、period_month、expected_revision，仍仅调用既有POST `/api/admin/assessment/snapshots`，不提交分数或私有来源。月结原键存储与政策配置独立，按运营actor_id隔离；先独立服务器权限验证再恢复。原门店后来失效时，历史可读、成功回执仍可同键恢复，不允许新建失效门店的结算。

存储失败阻写，未知/503/协议异常保留原请求，401保留至同账号重新登录，明确失权拒绝；成功清理失败同键完成清理，CAS冲突重读新版本；切号/隐藏与迟到响应不带入新身份，清理需比较本机原记录。结果不自动重算，退款需要回原使用月显式新增修订。
