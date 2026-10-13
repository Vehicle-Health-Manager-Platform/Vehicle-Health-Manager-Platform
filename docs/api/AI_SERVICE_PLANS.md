# 结构化项目建议 API（R4c1）

2026-10-13，[规格](../superpowers/specs/2026-10-13-r4c1-ai-service-plan-design.md)、[验收](../testing/LOCAL_AI_SERVICE_PLAN_ACCEPTANCE.md)。有效车主、no-store、单层ApiResponse、无query。无写接口/幂等键/业务审计，不持久化问题或方案。本阶段无迁移，保持76表。AI_SERVICE_PLAN_ENABLED默认false；关闭/上游失败仍提供经核对的车主指定项及手动选择入口。

`GET /api/ai/service-plan/catalog`：当前有效项目按sort_order/id排序、最多40。data={items,total,limit:40}，每项{project_id,project_name,category,service_content}。这是限定匹配目录，不代表全部服务；页面另提供现有全部服务入口。

`POST /api/ai/service-plan`：严格{question:string,requested_project_ids:[],consent_to_provider:true}，1..300 Unicode码点问题、最多3个不重复安全正整数项目号，未知/重复JSON键、尾随/无确认/额外字段拒绝400。问题复用TEXT_REDACTION_V1规范及常见标识替换，规则不覆盖所有语义隐私；确认明确允许规范问题与公开目录发送第三方DeepSeek。无车辆档案、用户/车辆号、商家、价格或时段出站。指定项必须当前有效，停用404；指定项即使在默认前40之外，也并入候选补至40。

data={status,question,candidate_count,items}。status=AI_READY/MANUAL/EMPTY/SOURCE_CHANGED；candidate_count0..40。每条{project_id,project_name,category,tier,reason_code,evidence_quote}，名字/分类来自服务器目录，证据必须等于完整规范问题。

- REQUESTED/OWNER_SELECTED：车主明确指定、按指定顺序在前；不是技术“必做”结论。
- RECOMMENDED或OPTIONAL/MODEL_MATCH：模型基于问题匹配至多5个目录项目；是否适用仍需实际检查。
- MANUAL：没有可用AI匹配（关闭/无配置/上游失败/输出无效/并发已满），仅保留指定项目，不造推荐。
- EMPTY：没有候选。SOURCE_CHANGED：生成期间候选/目录总量变化，空方案须刷新。

模型严格输出仅{items:[{project_id,tier,evidence_quote}]}，未知ID/重复/重选指定项/必做等级/改写证据/额外字段均整批降级。候选和总量同一重复读快照；上游IO在事务外、每进程最多2路、沿用45秒超时，页面60秒；回来新快照复核会话、目录属性/revision/排序/指定项目，失权401，指定停用404，其余变化SOURCE_CHANGED。当前问题证据仅为车主描述，不证明诊断或必要性。

条目进入`/pages/service/detail?id=标准项目号`，服务详情重新查当前项目和最新商家报价；现有报价进入预约页重新查报价版本和可用时段/容量，再由原下单接口复核。方案不冻结价格/名额、不直接下单。车辆适用性、同款获批经验与更细预约衔接为下一R4c2，有依据的必要性判定另续。真实模型效果、正式身份资金/识别/真机发布最后R9。
