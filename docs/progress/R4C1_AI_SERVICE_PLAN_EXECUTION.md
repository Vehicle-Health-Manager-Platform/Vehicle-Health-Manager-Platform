# R4c1 结构化项目建议执行

> 收口补记：2026-10-13，[PR #146](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/146) 已上传，d341173 / CI run38378123798六项成功；完整后端696无跳过、小程序451。未合并/发布。下一R4c2执行见[新阶段](R4C2_VEHICLE_PLAN_EVIDENCE_EXECUTION.md)，更细报价/时段衔接拆为R4c3。

2026-10-13，分支`codex/r4c1-ai-service-plan`，依赖#145（六项CI通过、未合并）。[规格](../superpowers/specs/2026-10-13-r4c1-ai-service-plan-design.md)、[计划](../superpowers/plans/2026-10-13-r4c1-ai-service-plan-plan.md)、[API](../api/AI_SERVICE_PLANS.md)、[验收](../testing/LOCAL_AI_SERVICE_PLAN_ACCEPTANCE.md)。

已实现当前最多40候选、最多3个车主指定/5个模型推荐可选、规范问题+第三方用途确认、严格ID/证据/等级校验、固定理由/明确降级、事务外IO及生成后会话/目录复核。独立页面与AI入口、编辑/账号/隐藏隔离、标准项目详情→现有报价/预约入口。默认关闭配置，无新增迁移/持久化问题，76表保持不变。

## 验证

- 小程序451/451（新增10项）通过；协议、网络、确认与生命周期使用离线替身。
- 后端10项定向均通过（分次最终验证）：新真实MySQL7（模型替身）、mock HTTP3，均无跳过；首轮夹具缺少标准项目必填参考价格导致7项准备失败，HTTP3通过；补齐夹具后重跑，新SQL7项中6项通过，1项断言把MySQL无符号ID的BigInteger与Long直接比较；改为数值比较后1项定向复验通过（与7项重叠，不重复计数），不改生产约束。
- 微信/H5及原生样式/测试身份桥检查通过、schema76表完整重复迁移通过、OpenAPI174操作/基线SQL44表生成、旧契约语义/文档链接/差异检查通过；操作数包含历史草案，不等于所有功能已交付。最终提交和CI证据以阶段PR正文为准。旧报价/预约接口保持其独立资格与版本校验，导航只携带标准项目号。

本地沿用隔离JDK17/Maven、Docker API兼容和关闭本机不可用Ryuk，JUnit清理真实MySQL。模型为替身，未真实调用第三方效果/真机验收。

下一R4c2车辆证据/适用性、同款获批经验与更细时段预约衔接；必要性判定需有依据规则，车主指定不等于技术必做。供应商识别、正式短信/微信身份/资金、真机及发布最后R9。阶段代码/规格/接口/验收/进度文档一并上传GitHub，不自动合并/发布。
