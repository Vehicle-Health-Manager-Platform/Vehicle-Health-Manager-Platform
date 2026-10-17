# 独立公开问答 API（R8c1b3a）

2026-10-17。本轮后端；页面、确认与本机恢复下一b3b。私有共享确认不等于公开授权；正式身份接入仍R9，公开问答开关默认关闭。

## 原来源与脱敏预览

V059为新community_qa_message补source_hash，并新增申请/状态事件两表，共124表。新问题和补充在原事务冻结服务器身份来源与app_id；只使用现有PointLedger.verifiedIdentity身份核验，不触发获分，也不启用正式身份开关。旧消息source_hash=NULL或没有原身份/原审计仍不可公开，不迁移或补造证据。

选择本人case中的OWNER问题消息和其后OPERATOR答复；问题必须是该答复前最近一条OWNER消息。两条消息均有匹配原正文/车型/分类/发送者/受理证据的指纹、原消息事件及未软删除原审计；问题须VERIFIED_WECHAT且与配置WECHAT_APP_ID匹配。测试或未知来源拒绝。当前原车主账号、原车辆归属、车型/车系/品牌有效，原提交车型仍匹配。

对完整两条文字先NFKC及既有电话/车牌/VIN/邮箱/网址遮盖，再各取前300 Unicode字符。不能先截断再遮盖。姓名、地址等仍须独立人工复核，不保证自动脱敏完整。preview为`{model_id,category,question_text,answer_text,redaction_version}`，redaction_version固定QA_TEXT_REDACTION_V1。服务器preview_hash绑定具体消息、原指纹、原owner/case和此最小预览；不接受任意public_text或客户端身份事实。

## 八项实际接口

- `POST /api/owner/community/questions/cases/{caseId}/publication-preview`：只读，无幂等键；严格`{question_message_id,answer_message_id}`，返回`{preview,preview_hash}`。
- `GET /api/owner/community/questions/cases/{caseId}/publications`：原本人申请分页，开关关闭或车辆转移后仍可读本人状态。
- `POST /api/owner/community/questions/cases/{caseId}/publications`：严格选中两消息ID、preview_hash、consent_version=R8C_PUBLIC_QA_V1和consent=true；重新核验源与hash。
- `POST /api/owner/community/question-publications/{id}/withdraw`：原本人、`{expected_revision}`；来源失效或开关关闭仍可撤回。
- `GET /api/admin/community/question-publications`：can_review待审分页，只返回明确提交的公开预览。
- `POST /api/admin/community/question-publications/{id}/moderate`：can_review、`{expected_revision,decision,reason_code}`；APPROVE理由null，REJECT理由PRIVACY/ABUSE/OFF_TOPIC/UNVERIFIABLE。
- `GET /api/community/questions?vehicle_id=...&cursor=...`：OWNER当前本人车辆同款公开列表，cursor可省略。
- `GET /api/community/questions/{publicId}?vehicle_id=...`：OWNER当前本人车辆同款公开UUID详情。

前三类列表页码1…1000000、page_size固定20；公开列表为游标，不接受page/owner_id等。拒绝未知/重复query、额外/重复/尾随JSON、错误角色/正整数/UUID/确认。除只读预览外所有POST写须UUID Idempotency-Key。所有成功与错误no-store，单层ApiResponse，依赖503不含私有原文或SQL。

## 返回允许字段

写回执`{application_id,revision,status}`。本人项另含preview/public_id/reason_code/visible/created_at；待审项另含preview/created_at/source_available，不含私有case/owner/消息/发送者ID、原正文或来源快照/指纹。无法匹配原冻结预览时preview=null，source_available=false，不能批准。纯管理/处理/精选权限不授予审核；can_review也不授予b2私有详情。

公开项仅`{public_id,model_id,category,question_text,answer_text,published_at}`。列表`{vehicle_id,items,next_cursor}`，按最多20基础APPROVED同款候选逐项重验，来源失效隐藏、不向后扫描凑满，需next_cursor继续；详情`{vehicle_id,question}`。源绑定的model_id与行索引也须一致，不能改行索引把内容跨车型展示。

## 授权状态和恢复

申请PENDING→APPROVED/REJECTED，可从任一非WITHDRAWN状态撤回；一case最多一PENDING/APPROVED。终态重新授权必须新申请/新公开UUID。新私有补充或答复不扩大旧公开范围；本次批准只针对明确选中原消息。旧UUID在撤回或申请状态回退后不会复活。来源账号/车辆/目录暂不可用时当前读隐藏；不声称撤销已下载内容。

申请提交永久保存原键、规范body哈希和原创建data；通用回执过期、开关关闭或源失效后按当前原提问人身份恢复原data，同键换正文/换case拒绝。撤回终态可同原请求恢复，审核回放仍必须处于对应当前决定，不能越过撤回。审核/撤回/私有消息写共用community_qa_control，CAS与消息/申请/事件/审计/回执同事务。

公开读取重验当前源/授权、源hash与快照、申请最后状态事件和最后原审计，包括软删除和旧行回退，批准时间也须匹配原事件；审核原预览篡改不能借管理员接口泄露原文。没有公开图片、通知投递、真实供应商或正式身份接入声明。

见[设计](../progress/R8C1B3A_PUBLIC_QA_DESIGN.md)、[操作](../operations/PUBLIC_COMMUNITY_QUESTIONS.md)、[下一页面与恢复](../progress/R8C1B3B_PUBLIC_QA_UI_NEXT_PLAN.md)。
