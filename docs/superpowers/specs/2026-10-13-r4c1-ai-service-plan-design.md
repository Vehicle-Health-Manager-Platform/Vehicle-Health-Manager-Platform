# R4c1 结构化项目建议与现有预约入口

2026-10-13，依赖#145。用户要求规划后实施；R4c拆为本单元和下一车辆证据/适用性/同款经验单元。

## 范围与选择

自由文本方案无法保证项目、商家和价格有效；本单元让模型只从当前最多40个有效标准项目选择至多5个推荐/可选候选，车主可明确指定最多3个项目，服务器以REQUESTED单独标记。未有可核对的车辆必要性规则，不把模型建议标成临床“必做”；车主指定也不是技术必要性证明。下一R4c2再做车辆证据、适用性和同款已审核经验，必要性判定依有依据规则推进。

问题1..300码点，复用TEXT_REDACTION_V1规则规范化和常见标识替换，不入持久存储/URL/业务审计。车主单独确认将问题及公开项目目录发送至第三方DeepSeek，不传车辆档案、用户/车辆标识、商家或价格；规则不能保证全部语义隐私，填写页提示勿填身份/地址/联系信息。模型只选project_id、tier（RECOMMENDED/OPTIONAL）、evidence_quote（必须等于完整规范问题）；理由采用固定reason_code，不显示自由生成的故障/必做/价格断言。车主指定项由服务器生成OWNER_SELECTED，模型只能输出MODEL_MATCH。

## 契约与一致性

GET /api/ai/service-plan/catalog：有效车主，no-store，无query；当前有效公开项目最多40个（sort_order/id升序），data={items:[{project_id,project_name,category,service_content}],total,limit:40}，限定目录不代表全部，可跳转现有服务目录。

POST /api/ai/service-plan：只读，严格正文{question,requested_project_ids,consent_to_provider:true}，无query/幂等键，拒绝重复键/尾随/未知字段，安全正整数ID、不重复、最多3个。服务器将指定项目并入40候选（指定最多3+其余补至40）；任何指定项目停用/删除404，不默默丢弃。先重复读快照校验会话/有效目录；模型IO在事务外，每进程两并发许可、沿用45秒超时；输出严格JSON{items:[{project_id,tier,evidence_quote}]}，未知/重复/指定项重选/未支持等级/改写证据/额外字段等整批降级。

模型回来重新核对会话及整个候选快照（名字、内容、类别、revision、排序等）和指定项，变更返回SOURCE_CHANGED空方案；指定项停用返回404，失权401。AI_SERVICE_PLAN_ENABLED默认false。关闭/无配置/失败/限流为MANUAL，保留服务器校验的车主指定项，模型推荐为空；无候选为EMPTY。

data固定{status,question,candidate_count,items}；状态AI_READY/MANUAL/EMPTY/SOURCE_CHANGED；items每项{project_id,project_name,category,tier,reason_code,evidence_quote}，REQUESTED在前、推荐在可选前，最多8条，任何返回项目名称/类别来自当前服务器目录。未返回价格、商家、时段，不把标准参考价当真实报价。

## 页面与预约

独立结构化项目页：加载限定目录、选最多3个指定项目、填写问题，确认用途后主动生成。显示规范问题和等级/固定理由/完整问题依据。每个条目进入现有 `/pages/service/detail?id=标准项目`，重新查当前项目及报价；现有详情→门店/报价→预约页继续重新核对最新报价/可用时段/下单资格，不以AI结果直接下单或冻结价格。无有效报价/时段仍可手动选择其他服务。

隐藏/账号切换清除草稿、候选和结果；改变问题或指定项使旧结果失效，生成期间迟到回调不恢复；不以URL或本地缓存携带问题/方案。首次功能只提供候选和明确指定，车辆档案和同款经验不作为本次证据。

## 验证与交付

真实MySQL+模型替身验证目录停用/快照变化/失权、指定保留/重复/跨目录、只传规范问题+目录、严格输出及降级、事务外IO；mock HTTP验证角色/严格输入/no-store；小程序确认/指定上限/编辑失效/账号隐藏/协议白名单和标准项目跳转。无新迁移，保持76表；生成OpenAPI、微信/H5、阶段文档及独立PR六项CI。真实模型/真机及正式渠道最后R9。下一R4c2车辆证据/适用性/同款经验和更细预约联动。
