# 下一步规划

更新：2026-10-09。以Spec与对应业务规格为准。

## 当前交付

A4–A6的#35–#44已按顺序改回main后合并，main基线43903c9。A7.1核销#45→#48已完成并上传，最终后端370/370、小程序181/181、真实HTTP51/51，六项CI全绿，未合并。

A7.2a本人订单评价已完成评分/文字/0–3私有安全图片、本人资格与只读记录、不可修改、UUID幂等、唯一评价与事务摘要审计。规格[#49](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/49)→后端[#50](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/50)→页面[#51](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/51)均六项CI全绿；后端389/389、小程序193/193、微信/H5构建、57表/OpenAPI101操作、真实HTTP44/44与gstack实际带图评价通过。收口分支codex/a7-review-e2e最终CI见其PR当前head。[执行记录](A7_OWNER_REVIEWS_EXECUTION.md)、[本机验收](../testing/LOCAL_ORDER_REVIEWS_ACCEPTANCE.md)。仅本人查看，不公开或计入商家评分，测试核销未真实扣款。

## A7.2b 已实现并上传

规格#53、后端#54、页面#55六项CI全绿；评价后可靠任务、唯一施工档案、冻结来源、失败恢复、私有施工照片和测试标注已实现。后端398/398、小程序196/196、本机数据库27/27、真实HTTP29/29及gstack档案/来源订单/首页通过。收口codex/a7-archive-e2e最终CI见PR，未合并。[规格](../superpowers/specs/2026-10-09-a7-service-archive-design.md)、[计划](../superpowers/plans/2026-10-09-a7-service-archive.md)、[执行](A7_SERVICE_ARCHIVE_EXECUTION.md)。V017先迁移，升级页面后显式开启消费者；历史评价不补写。

## A7.2c1 已实现：私有经验卡片与车主授权

规格#57→后端#58→页面#59六项CI全绿；默认私有结构化摘要、归档同事务生成/可靠恢复、原车主与当前车辆隔离、明确授权送审/撤回、revision与旧键防重授权、测试禁止送审已完成。后端411/411、小程序204/204、真实HTTP36/36、gstack草稿/测试标注/撤回/来源订单/重入通过。收口codex/a7-experience-e2e对应PR最终CI为准，未合并。见[规格](../superpowers/specs/2026-10-09-a7-experience-drafts-design.md)、[执行](A7_EXPERIENCE_DRAFTS_EXECUTION.md)、[验收](../testing/LOCAL_EXPERIENCE_CARDS_ACCEPTANCE.md)。

## 下一步：A7.2c2 运营身份、审核与公开同款经验

1. **运营认证与权限**：目前运营端仍为空壳；先建立独立运营账号、可信会话、失效/禁用与内容审核最小权限，不复用车主或商家角色。明确首次账号配置与登录渠道，凭据不入库仓。
2. **审核待办**：只能读取车主已授权的非测试PENDING_REVIEW；显示结构化摘要，不暴露车牌/VIN/签字/原图和任意施工原文。批准/驳回须有不可修改审计、幂等与版本检查，批准时再复核有效授权和冻结来源。
3. **公开同款经验**：单独公共DTO与查询，不复用带来源订单/档案ID的本人DTO；可信车型关联、分页、测试永久排除、撤回立即隐藏与缓存处理。无可信车型的卡片不可伪归入同款推荐，不捏造效果。
4. **角色页面与验收**：运营登录/审核页、车主卡片批准/驳回/撤回及同款列表；分规格、认证基础、审核/公开、页面、真实联调上传，先检查堆叠前序状态。

当前没有批准或公开接口，PENDING_REVIEW尚未公开；EXPERIENCE_CARD_ENABLED默认关闭。图片公开需要独立脱敏处理规格，现阶段仅文字结构化事实。退款/取消争议订单、第二次异议与超时自动处理继续单列。

## 后续拆分

| 阶段 | 范围与验收目标 |
| --- | --- |
| A7.2b已实现 | 实际施工档案回写、来源追溯、订单唯一、恢复与原手动档案兼容；发布开关/真机另验收 |
| A7.2c1已实现 | 私有卡片生成、结构化脱敏、本人授权/撤回、同订单去重与可靠恢复 |
| A7.2c2下一步 | 运营可信身份与权限、审核批准/驳回、公开同款经验与撤回隐藏 |
| 评价展示规则 | 公开/匿名、修改窗口、商家聚合评分与AI总结来源和测试评价隔离；当前本人反馈不自动公开 |
| 资金与争议终结 | 正式收款、退款/取消争议订单、凭据/回调/对账、状态与审计独立规格；LOCAL_TEST不可代替真实退款 |

第二次异议、超时自动处理继续不实施；历史缺核销/争议记录不自动回填。运营、增长、佣金、AI后续按角色缺口另排。

## 阶段上传与堆叠合并

每阶段验证→中文文档→提交→推送codex分支→PR→最终CI。前序未合并时指向直接依赖分支。当前核销#45→#46→#47→#48→评价#49→#50→#51→#52→施工档案#53→#54→#55→#56→私有卡片#57→#58→#59→卡片收口；每合并前序，先将下一支base改回main，再复核head/差异/CI并合并，禁止合入前一功能分支。本次仅上传，未合并A7或部署生产。

## 独立验收

真实相机、手机真机/真实微信账号、本机测试证书直连图片预览、正式短信、微信收款/退款、正式公网和运维仍分别待验收。继续本机优先；合成登录与无头文件选择辅助不会被报告为真机通过。

- [真实相机](../testing/REAL_CAMERA_ACCEPTANCE.md)、[真机登录](../operations/LAN_DEVICE_LOGIN_RUNBOOK.md)。
- [外部依赖](S0_DEPENDENCIES.md)、[角色缺口](ROLE_GAP_ANALYSIS.md)、[阶段规则](STAGE_DELIVERY.md)。
