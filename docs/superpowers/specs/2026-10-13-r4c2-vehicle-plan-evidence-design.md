# R4c2 本人车辆证据、车型适用性与同款经验

2026-10-13，依赖R4c1#146（CI收口中）。用户要求规划后实施。使用brainstorming核对既有读数/经验/目录后选择：本地读取可核对证据及明确规则，不由模型凭动力类型推断适用性或技术必做。

## 本地证据

POST /api/ai/service-plan/vehicle-evidence，严格{vehicle_id,project_ids}，安全正整数、最多8个不重复项目；有效本人会话/未删除本人车辆，目录项目须有效。no-store，无query/幂等，无外部IO。data={vehicle,observations,experiences,applicability}。

vehicle白名单vehicle_id/model_id/model_name/model_source/power_type：model_id/model_name仅当前有效品牌/车系/车型；缺失/停用为null。model_source固定OWNER_REGISTERED，说明登记车型未做VIN/实车核验；power_type仅本人登记值，不推导型号。不返回VIN/车牌/姓名/里程等额外标识。

observations复用HealthObservationService.list六类本人可追溯当前观测和已有源失效隐藏，不改读数单位/日期/方法口径，不制造健康分或来源有效期。experiences复用ExperienceModeration.experiences当前获批且仍有效的同款摘要，最多20条；无有效车型/开关关闭为空。它是可核对经验参考，不证明本车同故障或必做。整个证据/项目规则读取同一数据库重复读快照。不会自动发送这些内容给第三方，既有R4c1仍只传问题和公开目录。

## 可维护适用性规则

V036 project_model_applicability单行每(project_id,model_id)：verdict=APPLICABLE/NOT_APPLICABLE、source_reference（1..256字人工来源说明）、version、operator_id；不回填或预置示例技术规则。只有有效运营且can_catalog可以维护；can_review/can_onboard不能替代。

GET /api/admin/project-applicability/{project}/{model}返回{project_id,model_id,version,rule}，无记录version0/rule null。PUT严格{verdict,source_reference,expected_version}，verdict两值或WITHDRAW；撤回source_reference必须null，其余非空规范文本；UUID幂等键、版本比较、目录/身份重新复核，规则/版本/审计同事务，重放不重复写。撤回保留版本且清空当前规则，重新给规则需新确认。source_reference是运营声明，平台不联网核验其真伪，必须人工核对；规则不是必要性、故障或质量认证。

证据applicability每项{project_id,verdict,rule_version,source_reference}，无规则/已撤回/无当前有效登记车型为UNKNOWN、version0、source null；已有有效规则按确切车型匹配。项目停用404。不向模型发送该规则，屏幕逐项列明规则来源。NOT_APPLICABLE在证据模式禁用对应详情按钮；UNKNOWN仍允许手动核对；未选车普通R4c1入口继续可用。实际预约仍核对本人的车型、项目与店方适用性，不能把本地规则作为自动施工许可。

## 页面与一致性

方案页显式选择本人车辆后提供“读取本车证据”，生成候选后再按结果项目ID查询适用性；不自动带车给AI。读证据前清空旧证据，车辆/问题/项目选择或方案变化使证据失效；账号/隐藏清空。已收到的证据可能变化，进入服务详情后仍需重新核对。证据仅在内存，无URL携带读数/经验。

独立运营规则页输入确切标准项目/车型ID、查询规则、选择适用/不适用与来源说明、确认发布/撤回；成功后GET当前状态，旧版本409刷新，网络同键同正文重试，离开/换账号清草稿和迟到结果。入口现有运营目录维护页，JWT权限实时复核。

## 验证与交付

真实MySQL：本人/有效车型、未知/确切匹配、规则维护/撤回/版本/跨权限/幂等重授权/审计回滚、观测/同款来源复用；mock HTTP严格输入/角色/no-store/单层信封；小程序选择车辆/结果变化/规则未知/禁用/确认取消/重试/迟到响应。schema77表重放，微信/H5、生成/文档/独立PR六项CI。真实模型/正式渠道/真机最后R9。

技术必做与可信健康阈值仍需R4d有依据规则；下一R4c3当前报价/时段的更细方案预约衔接，随后R4d。R4c1#146先完成GitHub收口，本单元依赖它上传，不自动合并或发布。
