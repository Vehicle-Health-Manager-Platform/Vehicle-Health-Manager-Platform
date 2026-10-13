# 本地车辆方案证据与精确车型适用性（R4c2）

关联 F05/F06；[规格](../superpowers/specs/2026-10-13-r4c2-vehicle-plan-evidence-design.md)、[实施计划](../superpowers/plans/2026-10-13-r4c2-vehicle-plan-evidence-plan.md)、[验收](../testing/LOCAL_VEHICLE_PLAN_EVIDENCE_ACCEPTANCE.md)。三个接口禁止 query，成功与业务错误均 no-store，禁止重复/未知 JSON 字段，统一 ApiResponse。未配置 MySQL 或数据库故障返回503，不暴露SQL。

## 1. 车主读取

`POST /api/ai/service-plan/vehicle-evidence`：当前 OWNER 会话、本人未删除车辆；body仅 `vehicle_id` 正安全整数、`project_ids` 最多8个唯一正安全整数；每个项目必须当前发布。只读请求，不要幂等键，不产生审计，不调用 DeepSeek。

data包含：

- `vehicle`：vehicle_id、model_id、model_name、model_source固定OWNER_REGISTERED、power_type。品牌/车系/车型链任一停用/删除则车型号与名称null；不返回VIN、车牌、里程或用户身份。动力类型不参与判定。
- `observations`：复用 [六指标读数](HEALTH_OBSERVATIONS.md) list协议：vehicle_id、today、固定顺序6条list，缺失NOT_SET，真实本人读数RECORDED_UNVERIFIED，来源失效SOURCE_UNAVAILABLE且不返回旧读数。保留日期、方法、来源和版本；没有评分或诊断。
- `experiences`：复用 [同款获批摘要](EXPERIENCE_PUBLICATION.md) 的 items（最多20）、next_cursor（数值/null）。当前有效车型一致，重新检查车主授权、审核版本及有效来源；未开启经验展示或车型无效时空数组。仅施工工时/配件类别/月度摘要；无施工原文/图片或原车主身份。
- `applicability`：与请求项目顺序一致，每条project_id、verdict、rule_version、source_reference。精确项目—车型规则存在才APPLICABLE/NOT_APPLICABLE；无规则、撤销或有效车型未知为UNKNOWN/0/null。规则来源是运营声明，未自动核验资料真实性，不是技术必要性证明。

同一重复读事务内读取，车辆证据不会加入AI请求；后续需要主动刷新以看到来源/规则变化。方案页选择本车后要求主动读取证据；NOT_APPLICABLE关闭该页项目跳转，UNKNOWN提示人工核对后允许查看。该页面限制不是全站下单资格规则；服务详情/预约仍按其现有接口复核报价、容量及资格。无本车选择可以继续按公开目录手动选择。

## 2. 运营维护

`GET /api/admin/project-applicability/{project}/{model}`：当前运营会话且can_catalog；不复用can_review或can_onboard。有效标准项目及完整有效车型链。data：project_id、model_id、version、rule。无存储version0/rulenull；撤销后保留正version/rulenull；rule非空只有verdict和source_reference。

`PUT` 同路径，需UUID Idempotency-Key，body仅：

```json
{"verdict":"APPLICABLE","source_reference":"资料名称、位置及精确适用车型；真实性由运营核对","expected_version":0}
```

verdict允许APPLICABLE/NOT_APPLICABLE/WITHDRAW；前两者来源为1–256 Unicode码点、规范无首尾空白、无控制/格式/孤立代理字符；WITHDRAW必须 `source_reference: null`。版本安全整数且未达到增长上限；CAS冲突409需GET重新核对。写入锁定账号/会话、项目、品牌/车系/车型，重新核对当前权限及目录；规则、版本、审计和幂等响应同事务。旧成功键仅重放历史结果，不恢复已撤销规则；重放也重新验证权限和有效目录。页面成功后GET当前规则；网络/暂不可用可保留同键重试，账号切换/隐藏清理私有状态。

## 3. 迁移与界限

[V036](../sql/migrations/V036__project_model_applicability.sql)新增一表，PK(project_id,model_id)，无规则回填/生产样例。WITHDRAW清空结论与来源但保留version/operator_id，历史前后状态在审计。完整V036后77表，迁移可重放；旧44表基线不改写。没有以动力类型推断的技术规则，没有评分、必做或真实模型效果验收；下一R4c3报价与时段衔接，R4d再推进有依据规则。正式接入/真机发布最后R9。
