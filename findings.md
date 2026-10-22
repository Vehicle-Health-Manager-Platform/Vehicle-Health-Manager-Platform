## 2026-10-20 收口：12 支堆叠 PR 全部合并进 main

R9 期间形成的整条栈（#181 → #192，12 支）已按链式顺序全部合并进 `main`，合并后 `main` = `13354bf8`，`open PR = 0`。合并前后做了三项核对：

1. **逐支改 base 再合并**：每支先 `PATCH /pulls/{n} {"base":"main"}` 再 merge（`merge_method: merge`），否则后一支的提交会被合进前一支的分支而不是 `main`。
2. **内容一致**：`git diff origin/codex/r9-a4d3d-protection origin/main` 为空，说明栈顶内容一字不差地进了 `main`（旧 `main` 到新 `main` 之间共 41 个提交），不存在"合了 12 支只进 1 支"。
3. **曾经失败的那一项已复验**：#181 的 `database-recovery` 在 04:18Z 那次 run 里失败，同 head 重跑即通过（基础设施抖动），真正的竞态修复在 #182（"修复数据库恢复 CI 自动清理竞态"）；合并后 `main` 上的 `database-recovery`、`web`、`miniapp` 均已 success。

两条工具事实，写下来避免下次踩：

- REST 的 `PATCH /pulls/{n} {draft:false}` **不能**把草稿转为正式（返回 2xx 但状态不变，随后 merge 报 405 "Pull Request is still a draft"），必须用 GraphQL `markPullRequestReadyForReview`。
- 转正式后立即 merge 仍可能读到旧草稿态，要轮询确认 `draft=false` 再合并。

分支未删除（合并时保留原分支），各 PR 的 `merged=true` 记录可追溯。后续阶段直接从 `main` 开分支，不再叠栈。

## 2026-10-20 A4d-3d：商家施工防护

本阶段成果已上传 [PR192](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/192)（依赖PR191）；已随全栈合并进 main（八项 CI 在合并前全部成功，见顶部收口条目）。

A4d-3d 本地PASS：商家施工防护页统一到设计系统，新增 11 个导出（必选项与常量、五阶段、勾选行、提交缺口、提交卡、已提交记录、报工两态三组、读取失败、写入状态、照片状态）与10项单测；小程序单测980/980（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss。修掉六处缺陷：四层v-if链不可断言、未知状态可能被当成可提交、勾选行靠下标、提交按钮不说原因、报工记录条件散在模板里、读取失败只有重试没有重新登录。保留 protectionBody + confirmStillHolds 的二次确认与幂等、WorkPhotos 四个事件；照片只认 PROTECTION 那一张，未知报工状态当待签字。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 商家侧接车→防护→订单→时段→派工五个主流程页已收口；下一核销退款等缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-3c：商家接车检查

本阶段成果已上传 [PR191](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/191)（依赖PR190）；已随全栈合并进 main（八项 CI 在合并前全部成功，见顶部收口条目）。

A4d-3c 本地PASS：商家接车检查页统一到设计系统，新增 13 个映射（照片三态/八张进度/基线来源/里程差值/油量下标/损伤额度/标注前提/损伤行标题/订单事实/读取失败/提交前置/写入状态）与13项单测；小程序单测970/970（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss。修掉六处缺陷：八张照片只有complete布尔、缺片不说还差哪几张、里程差值算在模板里没有回退判断、「需填原因」是永远显示的静态标签、点照片没反应不解释原因、上传失败的「待上传」看不到。保留 pickup-form / pickup-slot-* / pickup-photo-* 三个既有锚点与 pickupBody 前置校验；mark() 改由 canMarkDamage 判定并在 boundingClientRect 回调里复检。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一施工防护页→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-3b：商家时段与派工

本阶段成果已上传 [PR190](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/190)（依赖PR189）；已随全栈合并进 main（八项 CI 在合并前全部成功，见顶部收口条目）。

A4d-3b 本地PASS：商家时段与派工两页统一到设计系统，新增 10 个映射（时段行/列表/项目选项/发布表单、派工六阶段/候选行/候选列表/已有派工/提交文案/候选加载时机）与11项单测；小程序单测957/957（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss。修掉四处缺陷：约满没有独立状态、发布表单不说差什么、派工 4 层 v-if 链不可断言、候选加载挂在 order watcher 上而 ready 依赖后到的派工结果（可能永不加载，改监听阶段）。另记录一条工具事实：绑定形式的 data-testid 会被 mp-weixin 压成 {{O}}，编译产物里搜不到原文，行级锚点只能靠源码扫描。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一商家接车检查与防护→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-3a：商家订单阅读面

本阶段成果已上传 [PR189](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/189)（依赖PR188）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline；`backend` 48 分 11 秒、38 步全部通过）；已随全栈合并进 main。

A4d-3a 本地PASS：商家订单列表与详情统一到设计系统，新增 9 个映射（状态/支付/行/筛选/空态/价格/入口矩阵/动作提示）与11项单测，并把徽标色调抽成评价域与商家域共用的 ui-tone；小程序单测946/946（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss、两页编译产物保留既有锚点。修掉三类真实缺陷：列表把RECEIVED/IN_SERVICE等后端枚举直接渲染、详情把支付码SUCCEEDED直出、7组状态数组被复制进模板。既有端到端流程的四条断言（行内按钮、预约日期文案、已支付子串、付款异常原文）逐条保留。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一商家工位与派工→其他独立缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-2h5：运营审核台

本阶段成果已上传 [PR188](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/188)（依赖PR187）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline；`backend` 38 步全部通过，含第 31 步 `--ci-built-jar --reviews-cases`）；已随全栈合并进 main。

A4d-2h5 本地PASS：运营审核台两页（入口分组 + 待审文字审核）统一到设计系统，新增 7 个纯映射（入口分组/权限摘要/待审行/队列/理由选择/摘要卡事实）与8项单测；小程序单测935/935（0失败/0跳过）、微信构建与H5构建均exit 0，10个共享选择器逐项在app.wxss、两页编译产物含ux-hero/ux-badge。修掉四处审核台缺陷：入驻审核入口未按can_onboard收口、待审文字“不能批准”要试才知道、队列缺待审数、驳回理由两套实现。并发与竞态不再列为缺口：service层已有4条竞态测试（双写只落一条、批准vs撤回必有一409、旧版本授权只改一次、第三个并发摘要请求降级）。后端与接口零改动；真机与模拟器未执行（本机开发者工具未打开该项目），不声明渲染通过。 下一其他独立缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-2h4：评价与公开展示面

本阶段成果已上传 [PR187](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/187)（依赖PR186）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline，其中 `backend` 38 步全部通过，含第 31 步 `--ci-built-jar --reviews-cases`）；已随全栈合并进 main。

A4d-2h4 本地PASS：6个评价相关页面（公开星级/公开文字/评价要点+三个公开授权入口）统一到设计系统，新增 `public-review-display.js` 十个纯展示映射与11项单测；小程序单测927/927（0失败/0跳过）、微信构建与H5构建均exit 0，10个共享选择器在app.wxss逐项存在、6个页面编译产物含ux-hero/ux-badge、两个列表页含ux-empty。修掉三处展示缺陷：要点来源撤回后渲染“发布于 undefined”、平均分为空显示null、“已批准但停止展示”与“未公开”共用文案。后端与接口零改动；真机与模拟器未执行（本机开发者工具未打开该项目），不声明渲染通过。 下一其他独立缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-2h3：车主评价与独立公开授权

本阶段成果已上传 [PR186](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/186)（依赖PR185）；八项远程CI全部成功（含 `backend` job 的 `--ci-built-jar --reviews-cases` 真实隔离执行）；已随全栈合并进 main。

A4d-2h3 本地PASS（218.992秒）：4条评价/公开链路共14接口139业务请求/465断言、338匿名请求/1014断言通过；新评价集合及352模板默认执行均实际0请求。评价/星级/文本/摘要四条独立授权由HTTP逐条驱动：TEST订单与未完成/无核销/开放争议/他人订单的资格投影、评价不可修改（新键重发返回同一review且request_id不同）、图片位置顺序、脱敏预览与投稿逐字一致否则422、审核通过后版本2、重复审核按幂等重放返回200且不二次写审计、版本冲突与决定冲突返回409、星级与文本撤回后公开列表归零而审核履历保留。最终2条评价、2条图片、10条幂等记录、8条审计，11项数据库检查通过。62项Java定向（9组HTTP/JDBC）、11项新增/既有Python门禁测试通过；源码前后稳定，隔离后端/MySQL/网络清理成功。两个公开开关仅在隔离环境显式开启、生产回退值仍为false；单测同时断言这两件事。评价/订单/支付/核销/图片为SQL合成夹具，评价与四种授权全部走HTTP；正式微信身份与资金、真实对象存储、真实第三方模型、评分统计显著性与真机未覆盖。 下一公开文本及展示→其他独立缺口→A5交付→B外部接入→C真机。固定用例串行执行，不能替代并发下的公开资格竞态与审核并发；脱敏为规则式替换，漏匹配不等于合规认证。

## 2026-10-20 A4d-2h2：商家选品报价与上下架

本阶段成果已上传 [PR185](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/185)，修复与验收代码 `229ee52`，依赖PR184；前置六项CI成功、backend仍运行。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2h2 本地PASS（201.25秒）：3商家管理+1车主展示接口105业务请求/361断言、338匿名请求/1014断言通过；新报价集合及352模板默认执行均实际0请求。修复三管理入口的未知/重复查询校验，报价原始JSON拒绝重复字段、尾随token和超长正文。9次HTTP成功变更产生9幂等/9审计、14历史版本；本店4报价最终全下架，另一店20.00/version1保持不变。金额0.01/99999999.99、店员只读/跨店隔离、旧回执不恢复上下架、停用/删除目录只允许原价下架通过；条件审计失败503后报价/版本回滚，失败键未占用，8项数据库检查通过。22项Java定向、9项新增/31项既有Python测试、编译/diff通过；源码前后稳定，隔离后端/MySQL/网络清理成功。身份/目录/初始报价与失败触发器仅SQL合成夹具，后续报价变更经HTTP；失败键之后使用新金额提交，不等同于原失败正文恢复重试。正式供应商与支付未调用；全量API业务验收、性能延迟目标和真机仍未全部完成。 下一车主评价与公开展示→其他独立缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-2h2 首轮夹具修正

首轮105业务案例仅technician_write失败：期望403，实际401。SecurityConfig对TECHNICIAN令牌强制app_id等于配置WECHAT_APP_ID；合成令牌使用fulfillment-cases而本轮未配置appId，因而在角色边界前被认证拒绝。添加同名合成appId，不配置AppSecret或供应商开关；保持期望403来实际验证已认证技师无写报价权限。首轮私有证据和已清理资源保留，完整重跑。Java22项定向已通过，源码与测试未变不重复运行。

## 2026-10-20 A4d-2h2

GET商家标准项目/本店报价和POST报价仍缺未知/重复查询拒绝；POST直接绑定JsonNode会丢失重复键信息。使用既有CatalogQuery并对原始JSON启用重复检测、尾随token拒绝和大小限制。报价模型已有精确金额、店长写入、当前身份/目录检查和事务审计，保持既有规则。验收重点是实际写入及上下架后展示、跨店/店员隔离、旧回执不改状态、审计失败整笔回滚；合成停用/删除目录及失败审计触发器不代表运营治理已通过HTTP。

## 2026-10-20 A4d-2h1：服务发现与门店位置

本阶段成果已上传 [PR184](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/184)，修复与验收代码 `d7e1730`，依赖PR183；前置六项CI成功、backend仍运行。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2h1 本地PASS（311.344秒）：9接口105业务请求/349断言、338匿名请求/1014断言通过，默认业务集合实际零请求。修复目录/门店详情/报价发现四GET入口忽略未知或重复参数；分类/停用源过滤、最新报价/区域/价格排序、搜索通配符按字面量处理、附近门店/单店球面距离、未知位置排末、店员只读和跨店隔离通过。2位置记录最终本店清空version2、另一店保留version1，3幂等/3审计；旧回执未恢复坐标，临时车主坐标未写审计，只读距离未增加写记录，6项数据库检查通过。42项Java定向、9项新增及29项既有Python测试通过；源码前后稳定、后端/MySQL/网络清理成功。身份/目录/报价仅SQL合成夹具，位置维护由HTTP执行；距离为本地直线估计，不代表地图路网、真机定位或正式外部供应商。全量API业务验收与性能延迟目标仍未全部完成。 下一商家选品/报价维护→评价与公开展示等独立缺口→A5交付→B外部接口→C真机。

## 2026-10-20 A4d-2h1

初步按operationId与原生旅程参数名归一匹配，当前352契约中198有专项案例资产，154尚需核对；仅资产映射，不是执行覆盖率。本阶段选服务发现/搜索/门店位置9接口（均可本地独立），不宣称全量完成。ServiceCatalogController、MerchantDiscoveryController与报价发现GET的typed RequestParam会忽略未知/部分重复参数，补统一白名单/唯一值校验。前端现有服务只发送这些已声明参数。临时车主位置仍只放POST正文，不写审计或URL；门店主动确认的坐标可持久化。

## 2026-10-20 A4d-2g3：辅助材料与工作台真实验收

本阶段已上传 [PR183](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/183)，修复与验收代码 `5728ec5`，依赖PR182；前置恢复CI已SUCCESS，后端仍待结束。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2g3 本地PASS（396.109秒）：4接口80业务HTTP请求、15项检查，5份CLEAN辅助材料/5成功上传回执、0图片元数据；AI/ARCHIVE与音频/PDF组合、幂等重放/冲突、车主/车辆/用途隔离通过。真实MinIO下载逐字节一致，附件/MIME/no-store/60秒签名参数、匿名和篡改拒绝通过；数据库拒绝非CLEAN元数据，材料删除/车辆转移/会话撤销拒绝新访问。商家/技师八分类、分页21任务无重漏、最小字段、跨店/跨技师与当前绑定/员工状态检查通过。55项Java定向（含继承回归），10项新增及25项既有Python测试、编译/diff通过；源码稳定，4容器和网络实际移除。状态/派工/接车/身份变化仅SQL合成夹具；音频为结构验证帧，不代表真实播放或识别。签发后URL具有最长60秒租期，本轮未等待自然到期；本轮未重跑338匿名矩阵。生产辅助材料开关默认关闭、扫描鲜度和限流保持既有策略；外部服务/真机与全量API业务验收、性能延迟目标仍未全部完成。 下一核对剩余API业务案例并补齐本地缺口→A5交付→B外部接口→C真机。

## 2026-10-20 A4d-2g3 首轮实际失败与修正

首轮材料/签名HTTP已执行至状态变更处；V062的ck_input_material强制scan_status=CLEAN，合成SQL改INFECTED被MySQL3819拒绝。修正夹具为断言约束拒绝，再确认原CLEAN材料可下载；不禁用约束。额外断言真实扫描错误消息及唯一REJECTED/422上传回执，区分扫描拒绝与格式验证拒绝。首轮报告/JUnit/日志备份到忽略目录，失败不计为通过；冻结来源后完整重跑。

## 2026-10-20 A4d-2g3

辅助材料上传已限制单file字段、显式audio/pdf类别及参数白名单，但GET访问未检查查询参数/ID业务边界。本阶段补严格入口并通过实际HTTP验证。现有JDBC材料测试覆盖默认关闭、事务与签名重核；工作台已有JDBC分类/分页与MockMvc入口测试，缺四接口一起运行的真实存储/扫描/权限证据。新旅程独立创建资源，不继承旧测试，以免重复跑旧业务。

## 2026-10-20 A4d-2g3a：优先修复恢复 CI 阻塞

本阶段成果已上传 [PR182](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/182)，修复及验收提交 `98c3f1d`，依赖PR181；远程CI待新提交结果；已随全栈合并进 main。

A4d-2g3a 本地恢复演练 PASS（121.313秒，19项检查），两台独立 MySQL 均核验清理成功；16项恢复测试及3项既有清理测试通过。修复 stop/异步自动移除竞态、严格区分对象不存在与守护进程失败，并保留恢复原异常。完整迁移/实现输入前后哈希一致。 前置PR181恢复CI曾失败，历史结果不改写；本轮远程CI待新提交结果。下一辅助材料上传/访问与商家/技师工作台→其他独立缺口→A5交付→B外部接口→C真机。全量API业务验收与性能延迟目标仍未全部完成。

## 2026-10-20 原始报告核对结论

进一步检查原CI报告和本地首轮报告：两者均有19项通过记录，最后一项为恢复后约束/触发器/过程/事件检查。由此确认本次已发生的失败位于清理确认；没有证据显示迁移摘要不一致。保留原异常的改动则防止将来同时发生执行/清理故障时丢失首个异常。

## 2026-10-20 恢复重跑定位补充

首轮本地重跑 f2bbb3896237 已完成 verify_migration_replay 后全部约束检查，但两容器移除检查失败。独立只读实测当前 Docker 对不存在对象返回 exit1、小写 `error: no such object: <完整ID>`；新增检查原先仅识别大写。已统一错误文本大小写，仍严格校验退出码1和完整ID；不接受其他ID、退出码2或守护进程故障。原始失败报告留忽略目录，修复后重新完整执行。

## 2026-10-20 恢复 CI 阻塞

PR181 head d1c9f0f 的 database-recovery 作业失败；已下载官方作业日志及脱敏报告至忽略目录。报告在 verify_migration_replay 后记录 restore 清理失败，旧 finally 会覆盖原异常，暂不能判定是否同时存在迁移比较失败。先修复有限等待与错误保留，再实际重跑；不把任意 Docker inspect 失败当作已移除。辅助材料访问查询参数缺口留待下一阶段验证。

# 当前阶段：R9 A4d-2g2 档案授权与施工上下文

2026-10-20：前置main八项CI全绿。A4d-2g2 本地PASS（347.016秒）：7授权/施工本域+3支撑接口，共10接口105真实HTTP请求、18项状态/隐私/事务/有效期检查通过。实际上传1个CLEAN文件并创建2份档案，唯一授权最终REVOKED/revision5、5次授权审计；默认不共享，未选档案不外泄，撤销后旧回执不复活；结构化内容更新改变源哈希，重新确认前正文和附件隐藏。审计失败整笔回滚、当前指派/已接单/车辆归属/扫描状态/订单关闭均重新核验；真实签名下载逐字节一致，已签发URL按10秒合成配置自然到期，新签发入口立即受当前权限约束。38项Java定向（含继承回归）及10项新增/27项既有Python、编译/diff通过，源码含资源配置前后稳定；4容器和网络实际移除。身份/已接受任务及后续改派/归属/关闭变化仅SQL合成夹具，授权/档案/结构化写入为HTTP；不是正式派工履约或微信接入验收。原始正文/JWT/签名URL仅留忽略目录。本阶段补齐可复现验收和CI门禁，生产逻辑沿用既有实现；全量API业务验收、性能延迟目标、正式外部服务与真机仍未全部完成。下一本地辅助材料上传/访问与商家/技师工作台→其他独立缺口→A5文档与交付核验→B外部接入→C真机。 本阶段已上传 [PR181](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/181)，验收代码`008ecd6`，基于main；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：2026-10-20 近期成果汇总合并完成

2026-10-20：已按用户授权通过PR180将PR156–PR180共25个阶段完整合入main；合并提交`1a62644`，汇总head`943aa41`八项CI全部成功。54个原始阶段提交、25个原PR头和汇总文档均已逐项确认可从origin/main到达，合并树与审核head一致。初始184个本地分支与182个远程分支均无独有遗漏，未推送分支提交0；近期关闭但未合并的PR151–153也已包含。旧快照53文件中51个原始blob已上传，另2个忽略的微信工具本机配置保留本地；源码/文档无意外遗漏。原阶段PR已按内容包含证据收口，未删除本地分支、快照或配置。此次只合并仓库，不部署。后续仍先档案授权、商家/技师施工上下文和撤销/改派权限；正式外部接口、全量API业务验收、性能延迟达标及真机仍未全部完成。 [完整性核对](docs/progress/MERGE_RECONCILIATION_2026_10_19.md)。

# 当前阶段：R9 A4d-2g1 人工诊断、队列与消息验收

2026-10-19：A4d-2g1 本地PASS（186.5秒）：20诊断接口169业务HTTP请求/566断言、338匿名请求/1014断言通过。2工单最终CLOSED/revision7及2，5正文/9事件/9已投递outbox/24接收目标及站内通知，2已读审计；转派立即撤销旧处理者正文与通知权限，管理者只可读队列，跨车主不可读/关闭/标已读。停用/暂停阻止新提交，历史答复保留，旧回执不重开工单或服务；通知ID绑定各接收者实际列表响应，正文/版本/角色/幂等键审计通过。143项Java定向（含继承事务回归）及10项新增/27项既有Python、默认新集合实际0请求0失败通过。源码前后稳定，后端/MySQL/网络清理成功。身份/月度资格仅SQL合成夹具，真实定时站内投递不代表微信消息送达；0奖励/积分/支付。前置PR179六项CI成功、后端待结束；全量API业务验收和性能延迟达标仍未完成。下一受控资料/档案授权与施工服务上下文→其他独立缺口→A5文档与交付核验→B微信/电话/外部服务→C真机。 本阶段成果已上传 [PR180](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/180)，验收代码 `86c0cea`，依赖PR179；完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2f2 邀请奖励冻结与后台发券

2026-10-19：A4d-2f2 本地PASS（184.0秒）：14接口144真实HTTP请求、16资格/快照/事务/隐私检查通过。实际生产资格bean接收合成上游结果，7注册、4批次（2 COMPLETE/1 UNKNOWN_POLICY/1 WAIT_SOURCE）；真实定时worker处理审计失败整对回滚/旧政策快照重试、缺库存不部分发、来源停用及员工单边奖励。3到账券/3发券/3库存事件，现金券issued=3、服务券0，3政策均STOPPED；旧回执不复活、不追补未知政策、完成批次不重发，员工/车主同ID不误发、仅本人券ID可见。8项Java定向（含144请求旅程）及9项新增/27项既有Python、编译/diff通过；源码含资源配置绑定、执行前后稳定，独立MySQL容器移除成功。前置PR178后端CI待结束。身份/手机号仅合成协议结果，0真实供应商/支付/积分事件；全量API验收和性能延迟达标仍未完成。下一人工诊断服务/处理队列/消息通知→受控资料与施工授权等剩余独立缺口→A5→B正式微信/电话/外部服务→C真机。 本阶段成果已上传 [PR179](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/179)，验收代码 `9290538`，依赖PR178；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2f1 邀请关系与月度资格验收

2026-10-19：前置PR177八项CI全绿。A4d-2f1 本地PASS（209.61秒）：14接口136业务请求/462断言、338匿名请求/1014断言通过。实际新建2推广码，最终4码唯一；19条SQL合成归因/15条电话证据不变。车主当月11合格/1周期、员工3合格；上月和TEST/UNKNOWN/PENDING/REJECTED不解锁，跨车主/门店隔离，TEST不因建码被升级。3解锁政策最终STOPPED revision4/STOPPED revision3/PUBLISHED revision2；旧回执不复活，12门槛LOCKED→11门槛ELIGIBLE但service_available=false→未来规则NOT_STARTED。源码稳定、后端/MySQL/网络清理成功；61项Java定向与9项新增/27项既有Python测试、编译/diff通过，新集合默认实际0请求0失败。真实微信注册/电话核验与本轮HTTP奖励发券未验，0奖励批次/发券/积分事件/支付。下一A4d-2f2奖励政策/冻结与后台发券→其余缺口→A5→B真实外部接入→C真机；全量API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR178](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/178)，验收代码 `dc25914`，依赖PR177；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2e4 积分兑换与券到账验收

2026-10-19：前置PR176八项CI全绿。A4d-2e4 本地PASS（205.734秒）：8兑换本域+9串联支撑=17接口，196业务请求/668断言、338匿名请求/1014断言通过。实际签到入账20分、现金券扣6分/服务券扣9分，最终余额5/revision3、3分录、2兑换/2到账券/2库存事件；3目录均STOPPED。换目录不重置券模板终身限额，旧回执不重复扣分，池版本重选不恢复旧资格，历史99999积分及另一车主不变。独立积分/券池权限、正文/源版本/幂等键审计通过，源码稳定、后端/MySQL/网络清理成功；98项Java定向与9项新增/18项既有Python测试、编译/diff通过，新集合默认实际0请求0失败。仅SQL合成身份与测试政策，正式微信/外部服务/真机未验。下一邀请奖励及其余独立缺口→A5→B真实外部接入→C真机；全量API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR177](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/177)，验收代码 `a86b868`，依赖PR176；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2e3 积分规则与本人账本验收

2026-10-19：前置PR175八项CI全绿。A4d-2e3 本地PASS（179.296秒）：8接口116业务请求/397断言、338匿名请求/1014断言通过。4积分规则，唯一签到事件CREDITED、1余额/1分录7分、3签到请求回执；停用规则revision4不被旧回执恢复，新规则11分不重复发放，事件冻结原规则revision3/7分；历史99999积分未导入、另一车主0余额/流水。独立积分权限、实际正文/日期/规则版本/幂等键审计通过，源码稳定、后端/MySQL/网络清理成功。69项Java定向复跑通过（首轮继承券并发用例超时，整类原样隔离重跑通过，原因未定，记录保留），9项新增/27项既有Python测试、编译/diff通过；新集合默认实际0请求0失败。身份仅SQL合成VERIFIED_WECHAT/UNKNOWN，真实微信/外部AI获分未验。下一兑换目录及兑换/券到账事务→邀请奖励与剩余缺口→A5→B真实外部接入→C真机；全量API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR176](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/176)，验收代码 `5fab216`，依赖PR175；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2e2b 私有上传与签名附件真实链路

2026-10-19：A4d-2e2b 本地PASS（349.594秒）：真实Spring HTTP/MySQL/MinIO/ClamAV，8接口53业务场景；实际下载与匿名/篡改/自然过期签名检查通过。数据库3个CLEAN文件、3成功上传/3扫描拒绝、1档案附件；4容器及网络移除核对通过。修复三角色文件访问忽略query，以及车主拍照/手动档案附件404；新增路径严格检查车辆、挂接、主体、扫描、类型/大小，施工档案授权不变。54项Java定向（含38项档案MySQL回归）及9项新增/15项既有Python测试通过，源码稳定。前置PR174八项CI全绿。合成身份与病毒库仅证明本地真实适配链，正式病毒库/外部供应商/微信/真机未验；全量API验收和性能延迟达标仍未完成。下一积分规则/本人余额流水/签到→兑换事务→邀请奖励及其余缺口→A5→B外部接入→C真机。 本阶段成果已上传 [PR175](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/175)，修复与验收代码 `d67e3d8`，依赖PR174；本轮完整远程CI待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2e2a 私有PDF导出与摘要分享验收

2026-10-19：A4d-2e2a 本地PASS（191.297秒）：7接口（PDF/分享6+档案新增1）、66业务请求/228断言、338匿名请求/1014断言通过。4份实际PDF完成严格解析与中文/隐藏来源检查；2分享均撤销，冻结摘要分别5/6条，3次合法接收审计；新增档案不扩旧快照，跨车主管理和私有PDF权限不扩大。修复分享未知/重复query及PDF中文ToUnicode映射；17项Java定向及12项新增/18项既有Python测试、编译/diff通过。源码稳定，容器/网络清理成功；默认集合0请求。原始PDF/能力令牌/JWT仅留.cache。前置PR173完整CI待结束。下一独立本地存储/扫描环境下上传/签名访问/档案附件→其余缺口→A5→B真实外部服务→C真机；全量API验收与性能延迟达标仍未完成。 本阶段成果已上传 [PR174](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/174)，修复与验收代码 `923befd`，依赖PR173；完整远程CI待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2e1 车主结构化档案、健康与提醒验收

2026-10-19：A4d-2e1 本地PASS（210.187秒）：14接口（车主12+规则2）、199业务请求/668断言、338匿名请求/1014断言通过。修复三组早期控制器忽略未知/重复query，写接口禁止query；22项Java定向测试及9项新增/19项既有Python测试、编译/diff通过。3份结构化资料、3指标当前值/7条历史、1确认/1已撤回规则、3提醒/1偏好；更正/换来源/清空、旧回执不恢复撤销状态、提醒状态与另一车主隔离检查通过。源码稳定，容器/网络清理成功；默认新集合0请求。健康规则仅合成协议值，不是生产阈值；本地提醒不代表外部消息送达。前置PR172完整CI待结束。下一文件/PDF/分享→其余缺口→A5→B外部接口→C真机；全部API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR173](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/173)，修复与验收代码 `c4addbd`，依赖PR172；完整远程CI待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2d3 经验卡与精选治理验收

2026-10-19：A4d-2d3 本地PASS（258.719秒）：14接口、131业务请求/448断言、338匿名请求/1014断言通过。真实消费者生成2档案/2卡片；合成非测试来源卡最终WITHDRAWN/revision9，LOCAL_TEST对照卡始终DRAFT/revision0；3审核/2公开UUID，1精选STOPPED/revision4。公开字段白名单、独立权限、来源哈希、真实时间窗、撤回同步隐藏、旧键/旧UUID不复活与旧精选不改绑检查通过。来源稳定，容器/网络清理成功；默认新集合0请求；新增10项和既有17项测试、编译/diff通过。WECHAT仅SQL合成来源字段，不代表真实付款/身份核验。前置PR171八项CI全绿。下一车主结构化档案/健康/提醒12接口→文件/PDF/分享→其他缺口→A5→B外部接口→C真机；全部API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR172](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/172)，验收代码 `ab43581`，依赖PR171；完整远程CI待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2d2 公开问答授权治理验收

2026-10-19：A4d-2d2 本地PASS（176.953秒）：14接口、134业务请求/456断言、338匿名请求/1014断言通过。3次申请均WITHDRAWN/revision3，9授权事件、2个不同公开UUID；2问答/5消息/7问答事件，0积分任务事件/支付。脱敏、独立审核权限、未知来源拒绝、公开字段白名单、补充不扩权、撤回与旧UUID不复活验证通过。来源稳定，容器/网络清理成功；默认新集合0请求；新增8项和既有26项测试、编译/diff通过。仅合成来源协议夹具，不代表真实微信身份。下一经验卡授权/审核/撤回与精选治理→剩余车主→A5→B外部接口→C真机；全部API业务验收和性能延迟达标仍未完成。 本阶段成果已上传 [PR171](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/171)，验收代码 `b05d885`，依赖PR170；完整远程CI待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2d1 私有问答与许可资料检索验收

2026-10-19：A4d-2d1 本地PASS（205.641秒）：23接口、176业务请求/590断言、338匿名请求/1014断言通过。2条问答均关闭，5消息/10事件；1份合成资料STOPPED、4资料事件；私有正文/独立权限/旧处理者撤权/动态检索引用绑定与许可投影检查通过，0公开问答/诊断/支付。来源稳定、后端/MySQL/网络清理成功；默认新集合0请求，10项新增及48项既有测试与编译/diff通过。前置PR169八项CI全绿。下一公开社区/经验授权治理→剩余车主→A5→B外部身份/资金/识别→C真机；全部API验收和性能延迟达标仍未完成，正式语料/向量/外部AI未验。 本阶段全部成果已上传 [PR170](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/170)，业务验收代码 `db27b05`，依赖PR169；完整远程CI待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2c 商家管理与运营权限验收

2026-10-19：A4d-2c 本地 PASS（191.547秒）：17接口、119业务请求/397断言、338匿名请求/1014断言通过。1申请/2审核，累计3门店/5员工；新店待激活且无密码、店员管理面受限、员工启用不恢复旧会话/绑定、配额/门店白名单/码隐私检查通过。修复 Docker --rm 清理竞态，来源稳定、后端/MySQL/网络清理全部成功；默认新集合0请求。7项业务证据/3项清理/55项既有脚本测试及编译/diff通过。下一社区/知识与剩余车主接口→A5→B外部身份/资金/识别→C真机；全部API验收及性能延迟达标仍未完成。 本阶段全部成果已上传 [PR169](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/169)，业务验收及清理修复代码 `edfa3a9`，依赖 PR168；完整远程 CI 待结果；已随全栈合并进 main。

# 当前阶段：R9 A4d-2b2 券生命周期业务验收

2026-10-19：A4d-2b2 本地 PASS（213.594 秒）。20 接口、160 业务请求/547 断言及338匿名请求/1014断言全部通过；3 组支付/退款通知原文签名重放通过。现金券预占/释放/消费/失败不返/成功返原券/再次预占正确，原有效期不变；3 订单/合同、8 券事件、1 支付、1 退款/2 退款事件、1 服务券零佣金核销、0 档案，跨用户/店数据不变。来源稳定，容器/网络清理成功。新集合默认 0 请求，11 项券证据、6 项构建来源及24项既有测试、编译/diff通过。下一商家入驻/员工与运营权限→其他域→A5→B 外部接入→C 真机；供应商调用、全部 API 验收和性能延迟达标仍未完成。 本阶段全部成果已上传 [PR168](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/168)，业务验收代码 `90c6f4a`，依赖 PR167；完整远程 CI 待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2b1 预约履约业务验收

2026-10-19：A4d-2b1 本地 PASS（183.172 秒）。16 接口、102 业务请求/341 断言，338 匿名请求/1014 断言全部通过；HMAC 正确/无效/过期、原文重放及跨用户/门店检查通过。订单最终 COMPLETED，支付/支付事件/接车/派工/防护/报工/核销/归档任务/档案各 1 条；其他用户数据不变、来源哈希一致、隔离后端/MySQL/网络清理成功。新集合默认运行实际 0 请求/零失败，38 项脚本测试及编译/diff通过。下一 A4d-2b2 券预占/消费/释放/退款回退→其他域→A5→B 外部接入→C 真机；全部 API 验收和性能延迟达标仍未完成。 本阶段全部成果已上传 [PR167](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/167)，业务验收代码 `26fac80`，依赖 PR166；完整远程 CI 待结束；已随全栈合并进 main。

# 当前阶段：R9 A4d-2a 车主车辆与档案业务验收

2026-10-19：A4d-2a 本地 PASS（172.671 秒）：8 接口的正常/边界/错误/未授权案例共 61 请求/204 断言全部通过；338 匿名请求/1014 断言通过，默认模板实际 0 请求。131 表匿名前后行数一致，业务结束车辆/档案各 2 条（其中本用户各 1 条）、幂等/审计各 2 条，跨用户数据不变，源码哈希一致，后端/MySQL/网络清理成功。7 项新增、9 项既有脚本测试及编译/diff通过。下一 A4d-2b 预约履约/券/本地资金→其他域→A5→B 外部接入→C 真机；全部 API 业务验收仍未完成，性能延迟未达标保留。 阶段全部成果已上传 [PR166](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/166)，业务验收代码提交 `843a02f`，依赖 PR165；完整远程 CI 待结束，保持草稿。

# 当前阶段：R9 A4d-1 API验收资产与匿名门禁

2026-10-19：A4d-1本地PASS（144.266秒）：352验收资产、338匿名请求/1014断言通过，352业务模板默认0请求，131表行数不变，源码哈希一致，容器/网络清理成功；9项脚本测试与编译/diff通过。全阶段脚本/资产/测试/CI/文档/报告已上传[PR165](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/165)，代码ab5d1e6，完整CI待结束、保持草稿，下一A4d-2分域补业务案例；性能延迟仍未达标，正式渠道留B。

# 当前阶段：R9 车主端视觉刷新

2026-10-18：车主端9个页面/组件视觉刷新完成（`design-system.css` 令牌挂 `page` + `ux-*` 原语，`uni.scss`/`reservations.css` 换语义取值），业务逻辑、接口调用、`data-testid`、路由与生命周期零改动。916/916前端测试、微信构建与两项原生门禁、H5构建通过；模拟器冒烟2项后 `Devtools RPC timeout` 中断，不计入通过。两条环境结论值得留住：①H5 `Build failed with errors` 实为本工具环境拦截 `uni build` 清理262个旧 `assets`，移走旧产物即 exit 0，普通终端无此问题；②微信构建会把页面内重复 `@import` 去重，共享样式必须落在 `App.vue`→`app.wxss`，否则出现"某页样式随机消失"。已上传PR164（e1d8fd2），依赖PR163，保持草稿。另做全仓色板统一（54个页面文件+6个组件/服务文件、400+处硬编码色值归入新令牌；签名板纯白与二维码纯黑有意保留），复跑916/916与两端构建仍通过。商家/技师/运营端页面结构仍是旧布局。

# 当前阶段：R9 A4c-2 核心接口性能基线

2026-10-18：A4c-2完成500并发三场景实测，错误预算达标（4000采样0错误、1520单全部落库），延迟门禁未达标（读P95 590.001/683.877ms、写P95 613.913ms），原因定位为24核被后端与MySQL占满，池80诊断排除池因素。修复档案附件N+1、共享目录行排他锁、席位判定整表锁、过期占位间隙锁四处实现缺陷（修复前50并发下单42/50返回503）。14项门禁单测、80项预约/退款/支付回归通过。代码/CI/文档/实际报告已上传[PR163](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/163)，代码dd49b2a，依赖PR162，完整CI待完成、保持草稿。下一A段收口→A5→B外部接入→C真机。

# 当前阶段：R9 A4c-1 隔离数据库恢复

2026-10-18：A4c-1本地恢复演练PASS，19项检查/116.453秒，V061→V062故障及备份回退、跨实例131表恢复、损坏检测修复和全迁移重跑均通过。13项脚本测试、Python编译、62挂载/diff检查通过；实现/迁移哈希一致，两测试容器已清理。代码/测试/CI/运维/实际报告已上传[PR162](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/162)，代码8a298fb，完整CI待完成、保持草稿。前置PR161完整CI仍待结束。下一A4c-2性能500并发，正式渠道留B。

# 当前阶段：R9 A4b 隔离核心业务端到端

2026-10-18：真实HTTP三条旅程全部通过（175.81秒，零失败/错误/跳过），源码与迁移哈希保持一致。新增报告门禁6项、既有门禁14项、62挂载及diff检查通过。PR160六项CI全绿，完整2138项测试、核心LINE96.14%/METHOD92.90%另存报告。补充9项身份回归通过，代码/测试/报告/文档已上传[PR161](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/161)，代码7dd43a8，完整CI待完成、保持草稿；正式激活留B1，全部API工具化验收仍有差距。下一A4c隔离备份/迁移与性能。

## 2026-10-18 A4a

A4a实际基线六组均通过LINE/METHOD70；总行93.43%、方法89.91%、分支68.13%。四输入类定向单类低值如实保留，待完整CI/HTTP核对。A4b旧e2e绑定容器与A4c性能/恢复尚未执行。

## 2026-10-18 A3

候选确认不直接保存，四个原保存/发送封装装配测试通过；默认适配不可用。原生自动化不能穿透组件，截图已证实渲染，不冒认交互端到端通过。无HTTP/SQL改动。

## 2026-10-18 A2b

最终相关后端64/64、补充定向7/7、前端899/899、微信/H5、完整131表重放及13项模拟器通过。真实MinIO新附件头测试待全量CI；预览无上传成功造假，普通产物无演示钩子。

# 2026-10-18：剩余功能执行发现

- A2a使用录音单例租约直到stop/error最终事件，防止取消后的旧录音串入新组件；本机材料从未写入持久存储或服务端正文。
- A2a最终891项前端、微信/H5/预览构建通过；真实录音/文件选择/转写仍独立验收。API和数据库无变化。

- 当前main为65c80e3，远端一致；当前未提交包含本会话的预览、入口修复与进度规划，先独立收口上传。
- 普通沙箱gh读不到有效凭据；经授权的系统密钥环读取可正常访问目标GitHub仓库。不要求用户重新登录，不输出凭据值。
- 原生运行时未透传getRecorderManager/createInnerAudioContext/chooseMessageFile；AI只有文字，档案只有拍照/手动。后端FileValidator仍仅图片，不能直接开放音频/PDF上传。
- 按用户最新要求每阶段提交代码、测试和文档，正式外部服务后置；当前预览测试874通过，13项模拟器和24次合成请求通过。

以下为历史记录。

# 2026-10-18 仓库进度核对

当前工作区干净，HEAD5f21598与远端main8373341文件树完全一致，GitHub PR155已合并；合并后main运行39045134951六项检查均success。没有遗漏的当前业务代码；README及进度索引/计划仍残留待上传或待CI表述，需要同步正式合并结果。旧主工作区初次读取受沙箱限制，改为仅文件状态的授权读取；不读取凭据值。

以下为历史记录。

# 2026-10-14 findings

> 当前R8a2本地完成：54不同后端/679前端/微信H5/V050共109表，下一R8a3兑换；整R8完成前不上传。

> 当前R8a1已本地完成，46不同后端/671前端/微信H5/105表通过；下一R8a2事件与账本。用户要求整个R8完成后才统一上传，当前不得push/更新PR。

## R5b final findings

User approved one coupon/issuing store/project, cash-paid commission with existing waiver, full-refund original-expiry return, >=0.01 cash DISCOUNT and standalone noncash SERVICE. V039 adds four tables and permanent creation intent: generic24-hour receipts cannot recreate closed coupon orders. Payment/close/refund convert coupons atomically; persistent recovery rejects contradictory paid/closed facts and defers bad rows. Service code stores digest only, previews actual project, uses current merchant/staff, unique noncash receipt/0 commission, no fake payment or work archive. R5 is a+b; invitation/points rewards are laterR6/R8. Local165 distinct backend/535 frontend cases, builds and88-table replay passed; upload only after wholeR5 closure.

R4 is merged and final main CI is green. Do not repeat historical health/ratings/AI gaps as unimplemented. Coupon/invite/points/assessment/notification base tables exist, but current business Java contains no corresponding complete services. ReservationInput.Booking accepts exactly quote/version/vehicle/slot; no coupon flow. CommissionLedger leaves coupon orders WAIT_COUPON, not implemented coupon exemption. Mine page explicitly postpones welfare/points/community entries; no welfare/index.vue exists. Owner order detail exposes status/pickup/refund/redemption, not full construction evidence or before/after comparison.

Supplied document text equals tracked DELIVERY_V1 after newline/BOM normalization; raw SHA differs only encoding/newlines.63 actual registered pages, no welfare/coupon/invite/point/assessment/notification routes. Existing private order review accepts up to3 photos; only public photo projection is missing. Professional knowledge retrieval/vector/source corpus not present in inspected business code; current AI uses limited SQL context/catalog, so evidence cannot imply complete external RAG. Original cash-discount coupon commission behavior unspecified; plan separates explicit no-fee coupon redemption from cash-order commission policy to freeze beforeR5b.
# R5a implementation findings — 2026-10-14

Existing coupon/user_coupon rows had no trusted issuance source. V038 adds four evidence tables and independent can_coupon (default0); no legacy promotion, policy seed or implicit grant. Published rights frozen, single issuing merchant/project, lifetime one claim per template. Merchant/owner/operator mutations follow current auth, ordered source locks, canonical WriteIntegrityService, atomic audit and receipt. Network ambiguity preserves exact payload/UUID; successful write followed by failed GET closes merchant draft to prevent accidental recreation. UI selects/displays Beijing validity; UTC only on wire. 62 distinct backend targeted tests and 514 frontend tests passed; no R5b trading behavior added.
# R6a findings (2026-10-14)

## R6b 2026-10-15

- Completed local R6b1 configuration, global shop/project lock ordering for multi-coupon publication and generated active-scope uniqueness. Reward issuance remains R6b2; public claim UNIQUE(coupon_id,user_id) unchanged.
- Docker engine initially stopped; desktop CLI started local engine29.6.2, real MySQL tests completed, no skipped database acceptance. Final evidence36 distinct backend/561 frontend/92 tables/74 pages/212 actual/draft OpenAPI operations.

- R6a PR152 exacthead1b2161e, remote six CI successful, backend842/frontend553. User now forbids intermediate upload until all R6 complete.
- R5 coupon_issue has UNIQUE(coupon_id,user_id), so repeat reward issuance requires a separately constrained issuance origin in R6b2; do not silently remove public-claim uniqueness in R6b1.
- User approved owner two-sided reward per valid invite and staff invitee-only reward. Reward config references current published, operator-selected coupon sources; no production seed or fabricated staff beneficiary.

- Remote first full run isolated stale coupon @BeforeAll total-schema assertion; updated88 to91 and35/35 passed. Public Mine invitation action now renders outside private signed-in content gate, preserving first-registration attribution entry. Local total101 backend cases,553 frontend, final builds passed.

- Implemented V040 (91 total tables), nine growth routes, opaque registration record UUIDs and private source-scoped reads; strict login/phone payloads and source flags cannot be client supplied.
- Official evidence gate defaults false, cloud source UNKNOWN; server official endpoint plus same-app verified phone required for formal qualification. Source/phone/month/audit facts atomic, earlier unknown/test evidence never promoted.
- R6b staff rewards need real owner beneficiary provenance; never treat staff_id as user_id. No automatic human-diagnosis unlock without configured source/rule.
- Final focused session gate33/33 passed;66 distinct backend cases in total,553 frontend. Compose bootstrap missing V027-V039 dependencies was identified and corrected alongside V040; remote smoke verifies91 actual tables.

- R6 next after uploaded R5. R6a must reject existing/self/duplicate attribution and freeze owner or staff source at first registration; R6b uses R5 coupon issuance with recoverable reward events.
- User approved each Beijing calendar month incremental 1/2/3/4 targets, cumulative 1/3/6/10, repeated after 10; each qualified new user produces one reward pair rather than tier payouts.
- Current createOwnerOrRead inserts or reads owner by unique openid but records neither new-registration evidence nor invitation context. Legacy inviter_id/invite_record are not reliable proof.
- Actual formal WeChat identity and native share remain R9. Test identities must be explicit and excluded from formal progress/rewards; never infer verification from client flags or timestamps.


- R6b2 V042 public_user_id keeps PUBLIC unique per template/user; INVITE uses unique reward_item_id and batch/role. Qualification snapshots published policy inside original phone transaction; legacy/no-policy event remains UNKNOWN_POLICY. GET is read-only and other-party coupon IDs are null.
- R6b2 fixed published quotas have no top-up API: WAIT_STOCK persists without a partial pair; no automatic replacement/expiry extension. Original issuer/account can recover through durable retries. Formal reward scheduler defaults off.

- R6b2 lock-expiry test failed because its fixed observation budget included worker startup/pre-lock reads; failure snapshots showed both actual LOCK WAIT and not-yet-at-lock states. Added an observed JdbcTemplate checkpoint and reused monitoring connection, keeping the actual database wait assertion. R6b3 accepted monthly eligibility excludes currently disabled invitees and never exposes an operational message endpoint.

- Final review: qualification month and qualified_at previously used separate clock reads and could disagree across Beijing month boundary; one snapshot plus moving-clock DB regression added. Native preview could hide/unmount and unlink its image; use in-page preview, retire in-use album files until completion. Operator forms retained stale CAS revision after confirmed writes; reset on confirmed receipt, including retry and refresh failure.
- Local whole-R6 integration environment: only Maven/MySQL/ClamAV images available after interruption; Docker Hub auth resolution/connect fails for alpine:3.21. Explicit local exclusion of RealUploadAdaptersTest and failed Compose startup are documented; repository six-job CI remains unmodified and mandatory on final uploaded head.

# R7 start

- User authorized full R7 planning and stepwise implementation; no upload until all R7 complete. Start R7a using already reviewed next-plan. ServiceWork currently accepts only merchant/technician scope; existing report/evidence relationships and short-lived signing can support an independently projected owner read.

- R7a first checks exposed numeric JDBC wrapper comparison in safe-file owner/is_deleted checks (replaced Object equality with Number value checks) and frontend predicate returning null for absent signed report (now explicit boolean). HTTP3 and existing work HTTP7 passed; first DB20 had17 pass/1 failure/2 errors, frontend593 had592 pass/1 failure. Failure runs retained; revalidate after fixes.

- R7a private URL boundary: existing storage signer returns fractional Java Instant and maximum300 seconds. Client must accept fractional UTC expiry, reauthorize each open, and isolate preview-request generation from page generation so stale expiry callbacks cannot clear a newer photo. Signed URLs already delivered remain usable until original expiry; no instant revocation claim. Protection lacks frozen uploader field, so require unique relation plus current original-shop merchant/staff uploader; report photos match historical technician_id.

# R7b start

- Existing merchant/technician lists are separate order screens, not aggregated workbenches. TechnicianAssignments.own rechecks current assignment; accepted-only is needed for private archives. Current schema has no order-scoped private archive consent, so user explicitly approved per-record grants/revocation with no default sharing. Archive types1/2/10 are maintenance/repair/fault; structured detail is separate owner/version table. New V044 grant table will default empty; source fingerprint prevents changed records from inheriting prior consent.

## R7b本地收口

用户确认逐单逐条授权，已实现工作台、车型/本单资料最小投影及档案源指纹/CAS/撤销；自动归档来源和签名IO前后复核。最终47后端、606前端、微信H5、96表重放通过，79页/230操作；原141追踪/44基线/根README和首路由保留。文档见docs/progress/R7B_WORKSPACE_EXECUTION.md。下一R7c，整个R7完成前不上传。

## R7c1进行中

按已授权整R7计划推进六项履约事实的事务事件、去重投递与三身份最小收件箱/已读；设计见docs/progress/R7C1_NOTIFICATION_DESIGN.md。现有notification仅基线表，无实际收件箱。无历史回填，诊断权限及持久恢复后续，整个R7完成前不上传。

## R7c2规则已确认

用户采用独立授权运营诊断方案、可受理/离线留言/暂停受理三态及来源说明，提交事务重验当月资格，仅共享主动文字与车型，历史档案另行授权。下一步契约见docs/progress/R7C2_DIAGNOSIS_NEXT_PLAN.md；先完成R7c1兼容回归，不提前上传。

### R7c1兼容夹具修复

最终相关回归中JdbcPickupInspectionTest仅加载到V013，事件埋点需要的V012派工/V045事件表均未加载，导致1失败/12错误。已定位缺表SQL异常，将该夹具改为完整排序迁移并保留V010/V011/V013重复执行，后续仅重跑受影响接车组；生产代码不跳过事件写入。支付35、核销17、券事务37已通过。

## R7c1最终本地验收

相关140项后端覆盖通过：通知31/HTTP4/支付35/接车16/核销17/券事务37。扩展首跑接车缺V012/V045表导致1失败12错误，改完整迁移保留原重复检查后16/16重跑BUILD SUCCESS（2026-10-15T12:14:13Z）；其余124首跑通过。614前端、微信H5、97表重放、236 OpenAPI/80路由及引用链接通过。Compose V045挂载复制时目标重名已在差异复核修正，init+V002至V045共45唯一有序目标验证通过，config --quiet通过，未宣称整栈Compose运行。R7c2规则已获确认且计划已写，尚待实现；所有R7代码/文档留本地。

## R7c2开始实施

用户已确认运营诊断方案，契约见docs/progress/R7C2_DIAGNOSIS_DESIGN.md。按独立权限/配置、车主留言/分配回复、页面/验收推进；管理队列无正文，只有当前获派且有处理权限者可读正文；所有成果保留本地至整R7完成。

## R7c2本地完成

独立权限/来源三态受理、当前R6资格与车辆复核、车主文字/当前获派处理、分配/追加/答复/关闭、专用事件/审计/长期提交意图、16API及4页面已实现。首次59初始化错误为合成车牌过长，修正夹具后首轮72通过；补当前读取/控制锁次序/真实锁等待和并发发布后最终111/111（61诊断含48继承+13新增、HTTP5、运营身份8、券事务37），626前端及微信H5、V046/102表迁移通过。84路由/252操作，原基线保留，阶段API/运维/验收已整理。下一R7c3持久恢复和诊断/补充业务消息；所有代码和文档仅本地，整个R7完成后再上传。

### R7c2最终授权入口与收口复核

现有离线operator-admin补齐诊断处理/管理独立授予撤销，必须显式环境布尔、无HTTP，变更与审计/会话撤销原子。新增3项定向真实MySQL测试通过，未重复计入其8继承测试；加主组111，合计114不同后端用例覆盖。诊断专属账号登录落点已修正，最终微信/H5构建通过；626前端、102表重放，252操作/全部引用、84路由/首路由、文档链接、46有序唯一SQL挂载/Compose config、141追踪/44基线/README不变通过。所有成果仍仅本地。

R7c3 检查：诊断流程原 pending 只在闭包内，隐藏保留但页面重建/重启丢失；车主和运营登录响应均有服务器账号 ID，但内存会话未保留。手机号绑定 setOwnerSession 调用无 ID，需保留同令牌已有 ID。

## R7c3 第一步完成
诊断显式草稿、稳定账号 ID、服务器复核后原请求持久恢复及多页冲突保护已实现。初次638项1失败定位运行时同步存储漏透传，补正确透传与装配回归，最终641/641、微信/H5通过。新14恢复/身份+1运行时断言；无后端或迁移改动，无R7上传。下一步补诊断/退款/异议消息，再统一门禁。详见docs/progress/R7C3_RECOVERY_EXECUTION.md。

R7c3 第二步发现：原(kind,order_id)唯一会吞掉重复退款申请和多轮异议处理。V047 新增 source_key（旧事件ONCE），退款用ID/revision、异议用追加记录、渠道结果用ID/结果去重。所有埋点位于真实业务事务，不扫描状态回填。读取脚本时猜测了不存在的V042文件名和JdbcRefundsTest，已改用实际JdbcOrderRefundsTest及文件清单，未改业务阈值。

R7c3 第二步数据库新测试发现：已生成9条事件但固定业务时钟下只投递2条；next_attempt_at原由MySQL默认墙钟生成，与恢复扫描db.now不是同一时源。改为入队显式使用业务db.now（不放宽9条断言），避免跨主机时钟偏移造成延迟。同时来源捕获用当前锁定读取，避免先前RR读视图取旧接收人，新增真实双连接回归。最终定向重跑覆盖这些修复及新补关单/旧结构升级测试。

诊断已有五类 case_event，但无投递接收人。现有 notification 支持 VARCHAR24 recipient_type，能复用 user/operator_account；用独立来源/用途表区分当时处理/管理接收人，同账号去重。读写统一 control 锁避免运营已读锁账号后等待病例、病例写反向等待账号形成死锁。

R7c3 诊断消息首轮127项126通过、1失败：新已读测试将不同幂等键的新请求 request_id 也要求相同，业务 data/read_at 实际一致。修正为同键比较完整回执、新键仅比较业务data，仍严格验证首次审计一次及审计失败回滚，定向重跑该方法。无生产权限或事务逻辑放宽。前端648、微信/H5、V048/104表重放已过。额外给车主角色页直接入口，已有答复不依赖邀请资格接口成功才能进入。

## R7c3 第三步本地完成
五类诊断事务消息、V048独立来源/捕获用途、四API、车主/运营消息页及队列导航已实现。首轮127中126通过，1项新键request_id比较错误已纠正，同键完整回执/新键业务事实/仅一次审计/审计失败回滚定向1/1通过，合并去重127不同用例全部通过；648前端、最终微信/H5、104表重放、85路由/首路由、256操作/全部引用、48有序唯一SQL挂载/Compose config通过。下一按R7_COMPLETION_NEXT_PLAN.md核对恢复/登录回跳等销项和统一门禁，通过后再同批上传全部代码/文档。当前无上传、合并或发布。

整R7本地上传镜像再次构建失败：auth.docker.io连接超时，原Dockerfile/校验未放宽。启动除RealUploadAdaptersTest外全部后端，其余验证继续；真实上传/完整Compose需可达镜像或最终远程CI验证，不冒称本地全套通过。


## 整R7收口补缺与统一门禁进展
登录回跳、技师原请求/显式草稿、身份ID先于token发布、诊断配置23小时安全窗口及比较清理、共享履约未知请求冻结均已实施。664前端、微信/H5、四兼容Web、104表真实迁移重复验证和Compose config通过；完整其余后端运行中。销项、恢复说明、验收、执行及R8计划已整理，暂未上传。
过程记录：旧共享写测试曾期望未知后改正文换新键，首轮两项失败，已按防重复要求修正生产行为及原键断言；UI批量插入遇额外root属性断言，改精确匹配后构建通过。静态挂载检查误把init.sql当V001漏报，按真实001-init+V002–V048校验；身份帮助器直接运行不能导入scripts包，改python -m unittest scripts.test_check_auth_readiness，6项通过。PowerShell通配符路径及猜测文件名读取失败，改rg实际文件后读取，无业务修改。真实上传基础镜像认证超时未放宽镜像/校验；全后端只排除RealUploadAdaptersTest。

整R7全部功能、收尾及文档已齐备。因本机Docker Hub阻断真实上传/Compose，调整最终门禁为完整R7一次性上传后在原有六项CI执行，本地其余全套后端继续；无中间子阶段push。AST确认26个变更Vue内24处重试绑定有效（最初简单正则漏掉逗号声明，改编译器绑定检查）。47份变更文档本地链接通过，85路由/256操作及832引用、48挂载和原基线保留通过。

## R8开始（2026-10-16）
用户要求整个R8完成后才统一上传，分支codex/r8a-points-policies基于d4c7763。R8a1先积分任务来源配置，再a2账本/a3兑换、b经营考核、c社区来源检索和整阶段门禁。已确认三任务频率及分值/兑换来源可配置、未知/不补历史/TEST隔离。原基线仅有point_balance/point_flow/point_exchange表，无积分业务。首个rg无AGENTS文件返回1正常无匹配；宽point搜索误匹配appointment输出过多，后续使用精确积分/文件路径读取。所有成果保留本地。

R8a1过程记录：Windows rg文件通配路径再次报123，改目录+--glob；共享Change的before/after/data均要求Map，永久创建回执JsonNode导致两次编译失败，读取实际record签名后使用TypeReference<Map<String,Object>>恢复，未放宽回执/审计断言。最终重跑日志r8a1-backend-verified.log。

R8a1首轮业务回归发现新权限测试断言错误：生成测试时广泛替换把独立权限求和里的can_diagnose+can_diagnose_manage也替换成can_points，授予后误期待0。已纠正为仅比较其他四权限，追加真实登录can_points契约。新controller进一步先验证query/正文再解析可用服务，避免无数据库时将坏输入报503，追加无库验证。最终仅复验受影响组，不重复累计。

## R8a1本地完成
独立积分权限、三任务来源/分值/时间配置、版本/CAS/发布冻结/停止、永久创建意图与当前只读任务（earning_available=false），受限页面/精确新权限登录和账号隔离原请求恢复已完成。首轮43中42通过1断言修复；最终7/7定向（权限+登录2、HTTP3、无库2），合并去重46不同后端全过。671前端、最终微信/H5、V049/105表重放、86路由/首路由、261操作/852引用、49SQL挂载/Compose config、141/44/README不变通过。文档协议/运维/执行/验收及R8a2计划已整理，无积分/券发放、无旧余额导入、无R8上传/PR更新/合并/发布。

## R8a2开始
用户确认人工保养/维修/故障任一完整本人确认记录作为终身一次来源。采用新可信账本保留旧余额不导入，源事务冻结事件+可靠恢复。需新增会话来源、官方AI成功后重验，开关默认false不当正式接入。先后端事件/账本/API，再本人页面；整R8完成前不得push/更新PR。设计R8A2_POINTS_LEDGER_DESIGN.md。

R8a2审查：签到需在所有锁等待后冻结一个业务时点，用于规则窗口、北京日期、事件时间和首次恢复时间，避免午夜多次取时不一致；AI任务READY还需当前官方端点可用，不能只看功能开关。已据此收紧，新增真实政策锁等待边界回归。一次猜测vehicle包下测试文件不存在，已通过rg文件清单定位实际测试，不改变业务数据。

R8a2首轮43中42通过、1新测试错误：使用未配置JavaTime模块的测试mapper序列化原始JDBC时间列做隐私断言失败。改为对完整行字符串检查无答复正文并保留冻结/去重断言，不改生产逻辑；最终重跑账本组同时覆盖单时点锁等待修复。

R8a2最终审查按既有InvitationRewards对齐：后台到账审计actor_type=system/actor_id=0，受益用户明确在after及事件来源，避免误记为用户直接执行。新增系统主体断言并定向复验4项捕获/到账/回滚，不重复计数。

R8a2收尾修正概览已登记任务的规则展示：PENDING/CREDITED使用该事件冻结的政策快照并标“登记时规则”，避免停用/换版后拿当前规则暗示旧奖励分值；未登记任务仍用当前政策。定向验证原规则停用后快照仍在及停用账号恢复，保留同一个用例不累加。

## R8a2本地完成
三任务可信事件、来源会话、源事务/AI成功后复核、可靠冻结事件/已核验账本、系统到账审计、永久原日期签到、本人积分页/原请求恢复已完成。首轮43中42通过1隐私测试的裸mapper时间列序列化错误已修正；最终主组38+原登录/AI14+身份钩子2，合并去重54不同后端全过；再定向系统审计4/4、冻结政策展示1/1通过，不重计。679前端、最终微信/H5、V050/109表重放、87路由/263操作/863引用、50有序SQL挂载/Compose config及原README/141/44基线保留通过。协议、运维、执行、验收、当前状态和下一R8a3计划已更新；所有R8代码/迁移/测试/文档仅本地，无push/PR更新/合并/发布。

R8a3a：CouponInventory.source既有门店→项目→券锁序，可供只读来源复核；PUBLIC领取不能嵌套调用（独立事务且公共限领语义不同）。当前point_verified_entry为正数收入账，扣分留下一子步显式迁移。初读误用缺platform的Java路径及不存在login-return/openapi路径，已改rg定位，未修改文件。

R8a3a：真实来源复用CouponInventory门店→项目→券锁序，目录发布按源锁后目录锁并最终复核时点；停止不依赖仍有效来源。独立can_points读取来源不扩大can_coupon。已完成31后端/686前端。首次并行构建微信输出目录EPERM，独立重跑通过；默认Git Bash安装路径不在C盘，Get-Command定位D:/Dinstall/Git/bin/bash.exe后迁移成功；静态SQL正则漏算init导致断言，改检查51个全初始化挂载；基线README字节比对受CRLF影响，改规范化文本比对，不修改基线内容。OpenAPI生成器首次来源schema名误用CouponInventoryItem，校正真实CouponInventory后884引用全解析。

R8a3b：V042存在ck_coupon_issue_origin只允许PUBLIC/INVITE，须V052原子扩CHECK并加独立POINTS凭证，不可仅插新origin。V050收入delta无符号且event必填，须幂等显式迁移为严格双来源统一账本并同步前端，不能只扣账户隐藏支出。初查ServiceCouponRedemption.java路径不存在，已改rg文件定位，不影响代码。

R8a3b首轮并发末券用例失败：[200,503]而非[200,409]，其余46事务/兼容用例通过。未放宽断言；正在从InnoDB死锁记录确认锁序，重点检查在公共券源锁前缺失永久意图查询的间隙锁。

锁诊断定向运行先被新增R5兼容测试编译错误挡住：OrderRefunds没有notify(Notice)方法。改用既有RefundRecovery查询回调入口，未改生产退款代码。

真实InnoDB诊断确认：trx5623持point_exchange_record.uk_point_exchange_submission缺失键间隙锁等待merchant.PRIMARY，trx5622持merchant锁等待该索引插入意向锁，形成死锁。本人/运营账号行已在authorize回调锁定，同账号永久提交天然串行、已成功回执不可变，故移除缺失回执FOR UPDATE；同时修复R8任务配置、目录配置、签到同类写法，不修改来源/库存/余额锁。增加不同运营创建草稿、不同车主签到获分的真实并发回归。

最终组的R5兼容类在BeforeAll被既有R7表数硬编码104挡住，实际V001–V052为111（迁移门禁已证明）。仅把现有表数断言更新为111；未删除断言或改券业务规则，随后定向重跑该类全部40用例。

事务最终复核补充：源引用初读必须已为PUBLISHED且修订匹配，再锁券来源，锁后仍重验当前目录。避免恶意猜测草稿发布版本在锁等待期间变为发布，导致首次快照指向可变券项目；重放已成功回执仍优先，不受前置检查影响。针对新前置检查再定向验证草稿拒绝、源码期限锁等待、末券和原回执恢复。

R5兼容40中39通过1在setup连接MySQL时Connection refused，未进入业务断言；其余三项新兑换使用/退款用例均通过。无生产代码变更、不加吞错或自动重试；定向重跑该既有邀请服务券用例，并复验最新发布前置检查及三项兑换R5兼容。

最终定向9/9通过；R8a3b合并去重98不同后端：50核心/原账本/原库存+40R5+4HTTP+1无库+3原HTTP。新增草稿前置1及初始连接失败1经复验计入，其他重跑不累计。689前端、微信/H5、111表初始化/重放/正收入及公共邀请凭证保留、269操作/893引用、52挂载和Compose config通过。阶段文档完备，下一R8a3c，R8整体完成前无上传。

R8a3c：R8a3b已提供永久成功回执和本人历史，实际提交只接受catalog_id/catalog_revision。现有generic异步流程支持服务器刷新后原请求恢复和epoch保护，可复用；新增OWNER目录快照与独立存储键，获分开关不得当成兑换资格。券入口确认为/pages/coupon/mine。本机未发现仓库AGENTS.md，沿用用户提供gstack浏览约束，无网页浏览。

R8a3c前端首轮701中700通过1登录回跳断言失败：静态新兑换页允许任意options。收紧新路由只接受空查询，不允许user_id等选择器；保留已有积分页返回行为，未放宽测试。

R8a3c最终62/62后端、701/701前端通过，新增各12，原继承50不重复计数；最终微信/H5及111表重放/旧凭证保留、89页面/270操作/900引用/52挂载与Compose config通过。原文F19权重为拉新40%/券40%/过程20%，后续需核对分母/阈值/等级，不自动制造正式标准；R8b1计划已落文。当前R8a完成，R8b/c及全阶段门禁仍待实施，无上传。

R8b1盘点：assessment只有默认0字段且无实现，原文F19仅40/40/20，无阈值。R6invite_registration具有身份/时点/员工门店归因；R5核销与R7接车/防护/报工/质检有证据。新增can_assessment会令OperatorIdentity.login的user超过Map.of十对上限，须显式Map构造；不移除既有权限。

R8b1最终：独立can_assessment/V053、来源政策/真实门店/未来完整月、发布冻结及永久规范化嵌套JSON创建恢复完成。43不同后端通过（新18/原25），最终精确登录字段断言源码晚于首轮class，已单独重编译1/1通过，不重复累计。709前端、微信/H5、112表重放、90路由/276操作/927引用/53挂载及Compose config通过。OpenAPI此前运营登录为通用ApiResponse，补精确OperatorLoginResult/OperatorAuthenticatedUser，user.role实际小写operator，JWT角色仍大写；不以类型名推断值。无旧成绩导入/假分数。下一R8b2仍需来源去重、退款跨月及结算细则，全R8完成前无上传。

R8b2a：用户已确认次月结算、金额券完成单/服务券独立凭证各一次、成功全退原使用月剔除、正式完成过程分母、缺项不齐全/无来源未知以及修订留旧。服务券原表无test_mode，不能把无支付直接当正式，采用R6永久服务器身份区分TEST/VERIFIED_WECHAT/未知；未核验不猜。政策无stopped_at，V054只记录新停止，不给旧STOPPED猜日期；完成工单无通用completed_at，用order_redemption及ORDER_COMPLETE流转时点，不能用updated_at猜月。初读几处旧猜测文件名（V011退款/OrderLifecycle等）不存在，已rg --files定位实际V025退款和OrderRedemption。

首轮39后端37通过2比对断言失败，实际70.00及退款新旧快照业务数值正确；原因数据库解析IntNode与valueToTree(Map<Long>)生成LongNode的equals类型差异。按JSON序列化解析后做完整结构语义比较，保留总分、全部旧快照及同包复算断言，不放宽业务检查。最终代码补齐完整完成流转/原发行门店/正式退款证据及四类过程文件的回归，整组重编译验证中。

R8b2a最终47/47后端全过（14新源事务+3计算+2HTTP+1无库+25原政策/券+2原权限），709前端回归、115表重放/旧凭证保留、280操作/948引用/90页不变/54挂载/Compose config通过。沙箱内Git Bash-n曾因Windows命名对象权限失败，允许环境下相同只读检查通过，未改脚本业务规避。一次最终进度apply_patch因progress.md段落与task_plan不同而未匹配，检查确认整次未应用，随后按各文件实际尾部追加。原README/141追踪/44表基线内容保留，R8未上传。下一b2b实际月结页面/本店范围及恢复，不把后端阶段算全R8或页面交付。

R8b2b：policy(shop,month)原取数带FOR UPDATE不能用于只读上下文，新增显式lock=false分支；写入仍保持原锁序。当前版本从月份头/最新记录同一事务核对，前端多读响应检测版本/事实数量；失效原门店允许独立运营历史读取但上下文404，客户端仅对此缺失降级以恢复永久回执，401/403不降级。MerchantSession只存token，故先服务端本人归因接口确认角色，不猜本机角色。

首轮51中50通过1测试Long对MySQL无符号BigInteger编号比较失败，值相同，改Number.longValue后保留所有范围断言定向1/1通过，不把重复算52。前端初次补强脚本在miniapp cwd误用仓库前缀，未编辑任何文件，随后在根目录修正并最终728测试通过。首次rg仍曾把merchant*放位置参数，改目录定位；不影响实现。最终微信/H5、115表、287操作/975引用、92页/54挂载与原README/141追踪/44表基线保持通过。无新迁移，下一R8b3；所有R8尚未上传。

R8b3a：成功退款来源不能只用未完成取消动作的OrderRefunds.ELIGIBLE，否则R8b2已支持的有原完成凭证的退款会被错误显示未知。看板只读另核对COMPLETED来源的原order_redemption/ORDER_COMPLETE；无原凭证或不支持来源保持未知，不改变退款动作/状态机。首轮65中3失败，修复后完整定向3/3通过，不把复跑累计为68。前端745与最终双构建、V055/115表重放通过。静态检查首次错误期待迁移挂载V001，实际init.sql为V001；修正为init+V002–55共55有序唯一挂载后通过，未改初始化约定。起初一次Get-Content路径漏src、少量rg通配位置参数失败均只是读取失败，后使用实际路径/目录定位。下一客户关系用途/字段盘点，不继承看板权限扩大历史档案授权。

R8b3b盘点：原文179行仅列商家客户管理；687行要求手机号/车牌/VIN脱敏，1285行权限不清拒绝。R7 ServiceTaskScope.serviceActive仅RECEIVED/IN_SERVICE/PENDING_VERIFY且无OPEN异议，OrderArchiveGrants已绑定车主/车辆/单条源版本；context逐次复核，技师须当前已接单。客户关系入口可复用该能力，不需要复制档案或扩大经营运营权限。首次猜测ServiceWorkspace.java/V045档案迁移文件名不存在，已定位实际ServiceWorkbench.java/ServiceTaskScope.java及V044__order_archive_grants.sql。

R8b3b已实现两GET与两实际小程序页。客户关系只为同店付后/完成状态订单记录，不将身份来源当支付核验或有效新客；R6永久身份TEST/UNKNOWN明确标记。按当前有效账号/车辆归属过滤，用当前最小本店订单锚点，非锚点/跨店404。核验接车时间需接车行与同店同人同时间ORDER_CHECK_IN合法PAID→RECEIVED，不能从预约或更新时间猜；最终补强同方法重编译复验中。首轮43全部通过（新8源读取+2HTTP+1无库，继承R7 32）；757前端/最终微信H5/真实Vue绑定、292操作1009引用/96路由55挂载/Compose config及原基线通过。无新迁移，旧115表重放沿R8b3a记录，本步JDBC测试也从V001–55全部初始化，不冒称重新跑过整迁移脚本。R7原车主订单授权与撤销入口已核对，不新增长期档案或联系用途。

R8b3b最终接车合法原状态补强定向1/1重编译通过，完整同方法含破损from_status未知及跨店接车拒绝。43不同后端、757前端/微信H5/292操作1009引用与96路由通过，执行/验收和全部相关索引已同步，下一来源价格监控；R8全部仍本地，无上传。

R8b3c盘点：原文有价格监控但无阈值；merchant_project_version只有价格/上架/员工与时间，无单位/车型结构化适用信息，迁移旧版本actor_staff_id为NULL。用户确认按具体报价版本绑定来源，项目内容或适用来源变更未知，不跨店混比。新增独立can_price_monitor，来源须核对原保存审计与当前最新版本；标准项目现有参考区间不直接作限价。最初rg apps/web-*位置通配读取失败，改apps目录检索。

R8b3c1首轮44中3失败为子类夹具污染：为所有继承券方法多生成一次真实报价保存审计，使原3变4；另有他店上架报价导致新用例错误预期单一来源。仅针对新价格方法生成报价证据并隔离额外报价后，完整该类26/26通过，合并44不同后端，不重复累计70。原成功审计/查询范围/断言不放宽。最终771前端及双构建包含遗留非正报价UNKNOWN兼容；V056/116表重放、298操作1034引用与97路由通过。新权限13字段同步旧经营/考核精确测试，c2规则已确认，无需重复询问。

R8b3c2恢复盘点发现实际接口/页面/测试已部分实现，未覆盖前R8变更。初次42用例有1失败1夹具错误：删除最新报价版本后回退旧证据，已以最后保存审计阻断；分页夹具重复同店项目，拆为唯一标准项目后又漏原必填参考价，补base_price_low/high和唯一名称，未改生产范围/门禁。恢复时后台容器已结束、第二份日志因中断未写尾部，实际XML为36中35成功1夹具错误，不能算完成。

独立只读审查补发现发布行回退同类缺口：停止rev3后恢复rev2旧发布行仍能匹配旧审计。已增加最后状态审计一致性和未软删除凭证要求，后续已删除状态仍阻断旧凭证回退；报价原保存凭证同步拒绝软删除。增加两项真实库反例。前端草稿不再误标“原冻结依据”。前端最终785通过；后端与最终构建、API/文档核对继续中，整R8仍无上传。

R8b3c2最终（2026-10-17）：原43/43后端通过，最新证据保护重新编译完整JdbcPriceComparisonsTest 38/38通过（含新两项、无跳过）；合并45不同后端（新15/原30）不累计81。785前端、最终微信/H5、300操作1044引用/99唯一路由/56有序挂载/13字段登录、Compose config与原README/141追踪MD+CSV/44表init基线保持通过。无新DDL，不冒称重跑c1的迁移重放。R8b价格和整体销项/协议/操作/验收与下一R8c1a受控详情引用计划已整理，全部R8内容仍本地，无上传。

下一R8c盘点：ExperienceModeration仅同model_id、审核ID倒序的五字段获批摘要列表，尚无单条公共详情/热门指标/问答；现有AiContextProvider为本人SQL档案上下文，不等于有许可资料检索。先共用授权/审核/源有效性做受控单条详情与引用回查，再集中明确热门依据/问答处理角色与公开方式。沿既有无独立社区Tab/无点赞评论/无自由编辑器；图片公开授权不得扩大R7订单授权，正式渠道与真机仍R9。

R8c1a（2026-10-17）已按用户开始实施指令和现有计划完成设计记录。采用同款原五字段、共用列表/详情当前授权/审核/源判断，无新用途或迁移；补当前接收者及原来源车型/车系/品牌status有效性。新GET绑定本人vehicle_id与公开UUID，复制引用仅含UUID；同款现有页详情面板与AI方案引用接通，切号/换车车型/引用/隐藏清旧结果。新引用不携带原作者或接收车号，原列表/方案形状保持。最初猜测pages/vehicle/plan.vue与owner-login-return.js不存在，已用rg定位真实pages/ai/service-plan.vue，未因读取失败修改其他文件。后端真实库/HTTP和前端验证开始，整R8仍本地。

R8c1a最终：54不同后端一次全过（JdbcExperienceDetailTest44=新7/原37、审核HTTP6=新2/原4、原无库1扩断言/方案HTTP3；去重54=新9/原45），最终796前端（新11）、最终微信/H5及原生样式/无测试身份桥通过。补齐精确车主登录返回和列表会话/车辆失效同步清详情、联合刷新后重跑前端，不重复计数。301操作1048引用/99路由/56有序挂载与原README/141追踪MD+CSV/44表基线保持通过。提前读取未创建H5日志仅读取失败，最终构建完成才记通过。无新迁移，重放及Compose沿前阶段，不冒称全R8验收。协议/操作/设计/执行/验收和R8c1b来源/处理角色/公开规则规划已整理；HEAD仍d4c7763，全部R8无上传。

R8c1b1（2026-10-17）：用户两项规则已确认：有来源运营精选（明确标运营精选，无浏览热度），独立授权运营非实时答复、默认仅提问者和获派人员，公开另行授权审核，不复用R7诊断私有资料。先完整实现精选/引用导入，再b2问答。V057增加can_community_curate默认0和experience_curation一表，117表/14登录字段；源为当前获批五字段，来源说明仅运营，未来窗口/排序/发布冻结停止/永久创建回执，车主只读当前源与最新发布凭证复核，最多前20同款候选。锁序加入原作者user在order前，避免与车主撤回的user→order反向；并发撤回和双运营发布均列入真实库验证。前端初次811/811通过（新15），实际配置/持久恢复、车主精选与手动引用导入已接通。

本轮生成脚本初次广替换V056后同时改到了旧迁移文件名，已及时恢复V056实际路径并单独补V057两次重放，没有保留不存在路径；复制权限测试后漏改grant列已在运行前修正。新页猜测write-key模块不存在，改实际private-images.imageRequestKey；.env.example没有离线CLI权限字段，断言中止后未添加无用运行时变量，离线参数将在操作文档明确。均未以失败读取/检查当通过。后端及schema验证进行中，整R8仍本地无上传。

R8c1b1首轮73后端72通过1失败：已停止且来源撤回后重开先查来源得404，原断言409；已把状态/修订初检置于来源读取前、仍锁后复核，保留原断言/全部来源检查。当前完整54来源类重编译复验，其他权限/登录/HTTP/无库19项已过，不重复计数。811前端、最终微信/H5、V057/117表完整重放及旧凭证保留/默认0/无种子、307操作1076引用/100页/57挂载/14登录字段和Compose config/原基线保留已过。/hot原为占位，本轮替换并新增6操作，实际307而非308。执行/验收与b2已确认私有问答计划已整理，最终复验未结束前不标完成。

R8c1b1最终：完整来源类54/54重新编译复验通过（无错误或跳过，06:01:14 UTC结束），与首轮其他19项合并为73不同后端（新18/原55），不累计127。811前端、最终微信/H5与117表重放通过；已同步阶段执行/验收/索引/当前进度及b2私有问答下一步计划。用户已确认来源精选与独立运营默认私有答复，无需重复询问。整R8门禁未执行，不声称全R8完成；HEAD仍d4c7763，全部代码和文档留本地，无上传。

R8c1b2a开始：已有DiagnosisCases含邀请资格且使用R7私有表，故复用技术模式而独立社区业务/权限；受理来源默认关闭、私有按当前获派读取。拆为b2a后端和b2b页面以逐步验收。最初猜V045__diagnosis_cases.sql不存在，rg定位真实V046__async_diagnosis.sql，无失败读取当证据。沿现有永久创建回执与全局控制行避免跨身份锁反向。

R8c1b2a实现：V058新增五张独立表122表、两权限默认0与16字段登录；管理队列无正文，原车主历史不随车转移，当前获派重验，政策/提问永久原创建回执与业务控制行串行。生成克隆预检发现新创建回执被通用文本替换误引用空old列表、私有文字校验误指不存在方法，均在首次编译前修正，保留具体回执写入/共用严格校验。PowerShell花括号路径语法导致一次读取命令解析失败，改目录rg；本机无.m2改用实际autocare-maven-cache卷。814前端通过，真实库/HTTP/迁移/构建进行中，无上传。

R8c1b2a静态核对：323操作/1139引用、16实际私有HTTP/16登录字段、100页面及原首启动、init加V002–V058的58有序挂载、Compose config与原README/141追踪MD+CSV/44表init保持通过。微信/H5最终构建通过；代码独立性复核仅共用DiagnosisServicePolicies纯输入/文字校验，无R7数据/资格/通知依赖。Windows rg路径通配一次报123，改目录加--glob后完成核对，不算测试失败。真实库/HTTP组仍等待最终结束。

R8c1b2a最终：38/38不同后端一次全过（16问答真实库、13权限含原身份8、四旧精确登录、HTTP4/无库1；去重新26/原12），无失败/错误/跳过，2026-10-17北京时间14:17:32结束。814前端、最终微信/H5及122表重复迁移/原凭证保留通过；323操作1139引用/16登录字段/100页面/58有序挂载/Compose config与原基线保持通过。已同步API/操作/设计/执行/验收及下一b2b页面/确认/本机恢复计划。仅b2a完成，不声称整个b2、公开问答或整R8门禁完成。全部R8源码/迁移/测试/文档保留本地，无GitHub操作。

R8c1b2b开始：沿已确认独立运营默认私有方案，采用独立API与四页、独立账号/角色/问答未决请求，复用createAsyncDiagnosisFlow纯技术。现有车主车辆list返回list字段，不能复制诊断页面cars.items假定；已核对真实DTO。初次读取不存在diagnosis/cases.vue，真实页mine/detail；按实际文件继续。旧诊断队列允许管理关闭不适用于新问答，本次管理页仅分配，无正文/关闭。

R8c1b2b页面接通：车主新建/历史、按角色详情、管理政策及最小队列/获派列表四页，完整确认与独立未决恢复。车辆API实际list已使用list字段；受理或车辆暂不可读以null明确区分，保留本人历史读取。脚本预设mine页面有根view但实际OwnerTabShell导致一次substring失败；已按真实content插槽补入口，后续队列/政策清理补丁顺序完成，不回滚前部成功修改。

R8c1b2b首轮838前端中836通过/2失败：新增实际script setup执行夹具把返回的Vue ref当模板自动解包对象赋值，未实际设置choices.value和text.value，故分配/答复确认没有触发。已按真实ref.value修正夹具，保留确认及目标/撤权断言，未修改生产权限或放宽测试。恢复复核另采用授权读取快照和非观察peek，防错误后的自动清理擦掉其他页面新原请求；具体并发比较测试已覆盖。

R8c1b2b最终：840/840前端（新26/原814）通过，含四实际script setup行为、原请求冻结/隔离/过期/并发清理和精确登录返回。最终mp-verified及h5-verified包含可滚动完整确认层与提问车主标签，微信全局原生样式/无测试身份桥和H5通过。323操作1139引用/104唯一路由/58有序挂载/16字段及原README/141追踪MD+CSV/44表init保持通过，109份Markdown的803相对链接及diff通过。无后端/DDL变化，沿b2a38及V058/122表，不重复累计或冒称重跑全量。设计/API/操作/执行/验收、私有b2销项与下一b3公开授权审核计划和索引已同步；整个c1b/R8尚未销项，HEAD仍d4c7763，所有R8源码/迁移/测试/文档仅本地，无上传。

R8c1b3a开始：V058私有消息仅正文/受理证据，没有不可变身份或原文字审计哈希，不能回填为可公开来源。采用V059新消息指纹及现有PointLedger.verifiedIdentity服务器证据，原有记录UNKNOWN不可提升；独立公开默认关闭，完整消息先脱敏再截300字。审核仅本次公开预览，不取得私有详情。PowerShell rg误用了-TotalCount一次，已改普通rg并按实际PointLedger/WECHAT_APP_ID核对，不把读取错误当证据。

R8c1b3a首轮编译失败：新previewView一处Java var复合声明不允许，拆为两个var声明，未执行任何测试即失败，不计通过。主体8API/独立默认关闭开关、消息指纹/最小审核投影/撤回防回退已接通；预检修正两处新测试括号，具体旧来源/错app测试不共用未知来源断言，验证继续。

R8c1b3a首次可编译真实库运行发现批准被409拒绝：原审计JSON从数据库解析为IntNode，而JDBC Long转tree为LongNode，直接JsonNode.equals误判相同规范数值。已把所有源快照/预览/最后审计比较统一为规范JSON序列字符串（哈希已有相同规范化），保留原源码/授权/状态/软删除全部条件和原成功/拒绝断言；完整复验待首轮结束后顺序运行，未把409放入成功集合。

R8c1b3a首轮49项实际40过/3失败6错误，其他19项（旧公开文本DB9/HTTP5、新HTTP4/无库1）全过。修复JSON数值比较后顺序复验完整30来源/私有类；生产compile阶段补强原跨车型用例为真实第二车型及接收车，再篡改发布行model_id，确保验证范围不是因原索引过滤偶然拒绝；测试编译阶段随后统一包含该夹具，未放宽断言。

R8c1b3a规范JSON修复后完整来源类30中29过，1并发用例返回[200,503]而非[200,409]。同一控制行虽串行，等待前鉴权已建立RR快照，原active_case普通查询仍看不到刚提交的申请，触发唯一约束503。改有效申请及永久原意图查找为FOR UPDATE当前读，保留源/授权/唯一约束与[200,409]断言，添加具体并发阶段断言说明；完整30复验，不把503接受为成功。

R8c1b3a最终：最新完整30/30来源及私有类重编译复验通过，无错误或跳过，2026-10-17北京时间14:58:27结束。合并首轮其余19项为49不同后端（新19/原30），不累计79/109。840前端、微信/H5、V059/124表完整/重复迁移及旧凭证保留、331操作1174引用/104页/59有序挂载/16登录字段、Compose config和原README/141追踪MD+CSV/44表init基线保持通过。阶段设计/API/操作/执行/验收、下一b3b公开页面与恢复、相关索引/当前进度已同步。整个b3/c1b/R8尚未销项；HEAD仍d4c7763，所有R8源码/迁移/测试/文档仅本地，无上传/合并/发布。

R8c1b3a最终文档/测试凭证核对：115份变更或新增Markdown的835相对链接有效，五份最新XML合计49且无失败/错误/跳过，源码/前端/构建/124表迁移完成日志核对一致，diff通过。没有新增远程操作；下一b3b实施公开页面及恢复。
# 2026-10-17 R8剩余规则

用户确认图片逐张选择、独立脱敏副本、车主确认及人工审核；专业资料由运营录入并确认授权来源/版本/车型/有效期，暂无授权资料和嵌入服务，先完成可追溯检索与AI引用。不虚构向量效果或正式资料，外部供应商/身份/资金/真机发布仍R9。当前主分支已包含PR154与README更新，公开问答八API已实现，b3b页面未实现。

## R8剩余执行与验证

b3b/c2/c3代码、实际页面和独立恢复已完成；180次相关后端、868前端及构建/130表重放通过。数据库时间显式UTC、源变化重授权、公开事件跨秒、当前车型索引与原审计回退均纳入保护。图像独立PNG不签私有原件；资料KEYWORD、最多800字摘录、AI前后许可复核。gstack独立cache/BROWSE_PARENT_PID=0及IPv4服务能做未登录界面检查，已登录流程仅script setup和HTTP/DB验证。GitBash python3命中WindowsAppAlias退出49，以cache桥接解决；本地Compose最终受DockerHub鉴权连接失败阻碍，不声称通过。后续最终CI核验后再宣告远程交付。


## R8剩余软件闭环最终验收（2026-10-17）
已完成用户确认的公开问答页面、独立图片副本治理和许可资料KEYWORD检索/AI引用。最终868前端、微信/H5构建、130表V061重复迁移及默认无正式语料/同意种子、默认can_knowledge关闭门禁通过；350操作/1247解析引用、108唯一页面、61有序SQL挂载、全部改动Markdown链接和原始基线保持通过。集中180相关后端通过；补充来源篡改/HTTP边界正在最终复验。README及API/运维/计划/执行/整阶段销项/本地验收已整理。本地Compose遭Docker Hub网络失败，不计成功；下一统一提交全部代码文档，最终六项远程CI通过后按既有授权合并。

2026-10-18复核结果：旧主工作区干净，所有业务分支提交已包含于origin/main；历史stash功能文件均已存在，两个本地开发者工具配置不纳入上传。1660份跟踪文本有限敏感模式检查无候选。只补齐Markdown交付状态，核对记录见docs/progress/REPOSITORY_PROGRESS_SYNC_2026_10_18.md。

首轮真实执行 102 请求，3 条断言失败：错误假设接单前需要防护（实际约定是报工/签名前需要），以及他人施工文件预期 404（实际契约 422）。核对 TECHNICIAN_DISPATCH.md、SERVICE_WORK.md 与实现后修正案例顺序：接单成功→缺防护报工 409/43002→防护→报工成功，并采用约定 422；增加明确 43002 断言，总计 341 断言。未发现生产缺陷，不修改既定业务约束。首轮不计通过，隔离资源已全部清理；重新固定实现复跑。

开发态首轮真实 HTTP 的 160 请求/547 断言均通过，但运行器加载的是更早的重名清单，而开发过程中资产已更新，严格案例身份核对正确判整轮 FAIL；容器/网络清理成功。该报告不计成功。已修复重名并加生成前拒绝，固定全部来源后开始最终完整复跑。

## 远程门禁补充修复

交付核对时发现前置 PR166 的后端 CI 失败在 `CI jar predates source inputs`（完整业务回归后的旧 mtime 门禁）。已下载其脱敏失败报告定位。改为 CI 在全新 checkout、jar 尚不存在时记录 backend/src/main 与 pom 的内容 SHA-256；clean verify 后要求产物存在、源码内容与记录一致。既不按文件触碰时间否定内容相同的构建，也不允许旧产物或变动内容通过。新增 6 项测试覆盖时间戳变动/内容变化/旧产物/缺证明/缺产物/本地冒充CI，全部通过。实际远程修复结果仍待新 CI，不提前计通过；本阶段运行器来源变化后另做完整本地复跑，更新最终实际报告。PR168 初次查询暂无记录，随后 GitHub 已启动 CI；构建来源修复的最终提交完整 CI 仍待结果。

首轮真实119请求/397断言仅1条失败：提交回执也是 data.application.application_id，预期误写扁平字段；其他请求/断言通过，隔离资源全部清理。已按既有契约修正；同轮来源有开发修改，不计最终成功。清理等待采用单次inspect快照校验归属，避免检测存在后第二次guard时容器已删除的竞态。固定实现后完整复跑。


## 2026-10-21 A4d-5 接口覆盖核对
- 用户继续授权规划后实施、逐阶段上传。PR195保留原分支与检查；本轮独立工具从origin/main创建codex/r9-a4d5-api-coverage。
- 设计：逐项核对当前OpenAPI、16套Postman清单/集合/历史报告及3套专用HTTP旅程；合并时去重，并保持当前验收NOT_RUN，不能将历史证据当成新执行。
- 初步清单：16套用例定义联合覆盖198项，其余154项缺这些清单覆盖；专用旅程尚需纳入，不能把154直接称作未开发功能。
- 任务：实现严格核对工具与可复跑输出→反伪造/缺失/漂移/去重测试→CI门禁→报告与下一补测队列→提交推送。

- A4d-5本地完成：19套定义/报告核对，352项契约中216项历史四类完整、136项所选来源缺口；新业务HTTP为0。新增19+既有186=205项Python测试通过，生成/--check/编译/diff通过。
- 修复跨平台哈希风险：Windows输入CRLF与Git/Linux LF不同，指纹统一LF、历史集合接受严格LF/CRLF字节形式；模拟两平台输出一致且内容变更拒绝。准备提交推送独立阶段分支。

## 2026-10-21 A4d-6 金融恢复验收
- 已检查六接口及现有Java/数据库/渠道测试；复用已实现业务，新增隔离Postman+全迁移MySQL+本机签名供应商HTTP验证。
- 任务：合成夹具/八接口四类用例→签名供应商与运行器接入→脚本测试/默认禁用→真实隔离执行/数据库与调用数核对→覆盖总表/CI/报告→阶段上传。
- Docker29.6.2及MySQL/Maven镜像可用；默认沙箱访问Docker受限，升级执行权限后确认可用。

- A4d-6完整重跑997d21cb4040通过（189.219秒）：8接口72业务HTTP/238断言+338匿名/1014断言，14DB检查及供应商固定调用数通过；源码仍匹配，后端/MySQL/网络已清理。默认禁用实测0请求；192项相关Python测试通过。
- 历史四类覆盖20套去重222项，所选缺口130项；全量API/正式资金/真机仍不销项。文档与证据已整理，准备阶段上传。一次将Java误放入Python语法检查的命令已纠正；Java单独编译和实际供应商启动均通过。


## R9 A4d7 范围

PR197 八项 CI 均成功。剩余覆盖缺口包括接车上下文/详情、技师工单/施工详情及三端站内通知六接口，共十个。复用真实 LOCAL_TEST 履约事件生成通知；不伪造 outbox/notification，不纳入微信订阅消息或真实支付验收。


## R9 A4d7 结论

新增十接口真实四类验证通过。六种真实履约事务事件经后台 outbox 生成16条通知；三个角色重复已读仅三条审计。通知列表实际 ID 与回执通过证据审计绑定。当前累计232/352项有完整所选历史四类证据，120项缺口不等于未开发。


## R9 A4d8 范围与约束

两个剩余接口 POST /api/merchant/orders/{id}/dispute/handle 和 POST /api/check/pickup/dispute/review 已实现。争议只能从未确认的 RECEIVED 接车单提出；商家只能追加说明，车主接受后恢复原状态。无处理说明时接受返回43008，争议期间派工返回43007。本阶段不变更产品状态规则，新增可复现实证。


## R9 A4d8 验证结论

实际HTTP验证既有争议处理规则可完整恢复履约；商家只能追加说明，车主接受恢复原RECEIVED，owner_confirm=3被后续派工施工正确接受。失败/重放未增加处理记录或事件，未发现需修改生产代码的问题。下一批优先接车/技师施工私有附件访问。


## R9 A4d9 范围与约束

四个附件访问接口（接车、商家/技师/车主施工证据）已实现且契约齐备；缺口是"真实对象存储下的四类执行证据"。本阶段不变更权限模型，只修接车附件访问忽略未知/重复query的真实缺口，其余逻辑零改动。


## R9 A4d9 验证结论

真实MinIO下载逐字节一致、去签名与篡改签名403、5秒租期自然过期403；跨订单、未关联文件、跨店、同店未派工技师、角色不符与匿名全部按契约拒绝。生产限流为每主体每分钟10次上传，因此第二单改由同店另一名员工接车，不放宽限流。未发现新的权限漏洞。


## R9 A4d10 范围与约束

20 个考核/看板接口已实现且契约齐备，缺口是四类执行证据。`GET /api/admin/assessment` 无 x-roles 也无实现标记、代码无映射，只能得到 404，不能当权限用例。产品约束 `POST /policies` 要求 valid_from 晚于当前时间，因此"已结束月份的政策"只能是 SQL 历史夹具，接口写入能力改由未来窗口政策证明；`uk_assessment_published` 一店一份已发布，写入链固定门店2、读取链固定门店1。


## R9 A4d10 验证结论

固定月份字面量（2024-01 / 2999-01 / 3000-01）是必须的：审计要求实际 query 与声明 query 逐字相同，模板变量会让证据核对失败。摘要口径实测确认"证据不全不出分"——过程维度分母只数非 EXCLUDED（=1），存在 UNKNOWN 事实即整体 UNKNOWN、score 与总分为 null；拉新与券月用政策目标作分母，无事实也给 KNOWN/0.00。看板把无法验证来源的订单计入 unclassified_count，正式与测试桶同时 UNKNOWN。首次执行失败两处：Newman 每条 extra 是独立 pm.test 作用域（跨条引用 const d 报 is not defined），以及看板"无来源门店"其实引用了那份 UNKNOWN 月结快照（应为断言 snapshot_id 而非 null）。
## R9 A4d11 验证结论

目录列表与订单域的分页规则不同：`CatalogGovernance.list` 只拒绝 `page_size>50`，`InvitationController.query` 才把 `page_size` 锁死为 20，所以"目录 `page_size=10` 会 400"的直觉是错的（首跑唯一失败点）。幂等键必须让主写与重放共用同一个 `uuid5('...:replay:'+name)`，否则审计里"重放作用域"会数少（自查时 6 个只统计到 2 个），已把审计收紧为"`_replay`/`_changed_body` 必须与唯一主写同键"。价格政策确认报价时要求 `standard_project.source_note` 非空、`merchant_project_version` 与当前价格一致、且存在唯一一条 `MERCHANT_PROJECT_SAVE` 审计，所以"先证明 UNKNOWN 再证明 READY"必须靠目录修订接口来翻转，而不能靠改夹具。


## 2026-10-22 A4d12 最新状态复核

工作区已有A4d9附件、A4d10考核和A4d11目录治理的提交与证据。PR202全部CI成功，当前267/352有完整所选历史四类证据。此次选择10个剩余履约接口；日期查询必须由隔离种子派生并在证据审计时核对，关闭时段应在完成订单后验证以免影响正常履约。


## A4d12 CI时间预算

PR202后端CI从2026-10-21T16:01:54Z到16:56:30Z，54分36秒。新增一套隔离验收后，后端timeout从60调整为75分钟，保留全部原有测试，避免接近时间上限造成偶发超时。


## A4d12 验收结论

时段关闭后不再可约，已完成订单及派工、核销、归档保持原记录；四个第二页为空，跨车主/门店及非法输入均符合预期。汇总新增多词套件时发现文件名下划线/连字符不匹配，已修复并通过24项汇总测试，包含LF/CRLF一致性。下一阶段优先项目/车型适配及服务方案证据链。


## A4d13 初步结论

当前HEAD43b5d85，PR203除后端仍运行外其余已完成检查均成功。6个目标接口均已实现，规则由运营显式维护，撤回保留版本；车型或规则未知时不按动力类型推断。模型关闭应返回MANUAL，预约上下文不创建订单。


## A4d13 验收结论

规则撤回运营版本保留3，旧回执未复活；车主UNKNOWN投影规则版本为0，与运营版本语义区分。同系列其他车型无规则不继承结论；手动方案仅保留明确选择，预约上下文无订单和支付副作用。下一批优先邀请奖励政策及发放记录。


## A4d14 当前证据缺口

PR204后端仍运行，其余已完成检查成功。奖励已有run_reward_acceptance和RealInvitationRewardJourneyTest及CI，但未纳入覆盖总表。其清理范围只有mysql，不能套用上传旅程五角色要求，也不能全局放松清理验证。


## A4d14 验收结论

旧政策快照重试、整对回滚、库存不足与来源停止均保持正确；读取和再次处理完成批次未重复发券。总表按可信模块清理声明兼容MySQL单资源旅程，原五角色默认要求未放松。下一批11个社区图片授权/审核/公开/撤回接口。


## A4d15 范围

生产图片接口已实现，缺少所选历史验收证据。采用真实MySQL、MinIO、ClamAV引擎与HTTP测试，保留合成身份、前序订单/支付/报告及扫描库边界。PNG仅剥离元数据并保留提交像素，不能证明自动视觉脱敏。不得把历史证据覆盖计为产品完成率。


## A4d15 本地完成

真实旅程258.203秒PASS：85图片业务请求、11接口四类证据、12不变量；8项Java和87项相关Python校验通过。源码一致且五类资源实际移除。所选历史覆盖302/352项，缺口50项；合成身份/前序支付和扫描库不计外部验收，PNG元数据剥离不等于自动视觉遮挡。CI门禁、报告及执行文档已完成，准备提交推送依赖PR205的草稿PR。


## A4d16 设计取舍

4个接口已有实现。选择复用隔离Newman链路，固定历史/未来日期及显式SQL合成凭据，避免等待真实政策生效或引入测试生产时钟。客户关系只读不授予档案；价格UNKNOWN不能误判正常或自动调价。PR206已完成的六项CI成功，后端仍运行，不记为全绿。


## A4d16 验收结论

SQL合成13种价格来源/政策状态与23名当前客户；真实HTTP证明端点/分差、UNKNOWN保守判定、门店范围及稳定关系锚点。完整内容摘要覆盖13张选定业务表，不扩大为全数据库内容证明。不计真实身份、到店、商家来源真实性或资金验收。


## A4d17 范围

4个目标接口已有实现。券池清单包含已发布的来源，含未入池/停用行；全局可领取仅选中且当前可用来源，门店定向可读取未入池的该店可用券。发布后条款不可修订；旧草稿/退池前回执不能恢复当前状态。生产规则不放宽，合成身份不计真实微信验收。


## A4d17 结果

五来源/四冻结发布、两发行/库存事件、17券审计与17幂等回执。旧修订回执不能更改已发布条款，旧入池回执不能恢复退池状态。运营清单保留停用发布来源，门店可领取与全局入池渠道不同；11表纯读内容摘要不变。身份与项目合成，真实HTTP券业务不计真实资金验收。


## A4d18 设计边界

刷新公开入口以refresh凭证授权，不能把缺Authorization当作必然401。业务退出接受车主/商家/技师，运营应使用独立退出接口且不支持该刷新入口。旧access在refresh后仍可用，退出同一jti后新旧access均失效。用真实MySQL事务验证轮换失败回滚与并发单次成功。


## A4d18 结果

数据库12会话不增，4份成功轮换，失败刷新整行/哈希不变，6份指定会话最终撤销，其他会话整行不变。并发双刷新恰一200一401，赢家退出后三份凭证均失效。停用员工检查不扩大为所有绑定撤销组合，一轮竞争不计性能验收；真实微信/短信另列。


## A4d19 设计边界

入驻附件授权与审核权限是两个位：can_review 只有审核权限时读附件必须 403，不能借用 can_onboard。归属判定吃的是申请里的 qualification_files 快照，不是文件的 owner_id 反查，所以"同一申请人另一个申请里的文件"也必须 404。越权读统一 404、角色不符统一 403（OperatorActor.from 只认 operator_type + OPERATOR + operator-account）。读附件不得顺带推进审核：断言 merchant 行数不变且 merchant_id/reviewed_at 仍为 NULL。


## A4d19 结果

3个CLEAN文件全部 user 归属且无第三方行；两份申请 revision=1、快照 [1,2] 与 [3]，跨申请 JSON_CONTAINS 双向为 0；运营A三个附件均200并逐字节取回，运营B(can_review=1) 与车主/商家均403，跨申请与不存在申请均404，query/id=0 均40001。签名租期5秒、expires_at有界，去签名/篡改/6.1秒重放均403。MINIO_PUBLIC_ENDPOINT 指向测试内回环转发，从而不放宽生产公网HTTP端点限制。审核决策、配额与建店不在本阶段，由既有 MySqlTest 与 --merchant-cases 覆盖。


## A4d20 设计边界

评分报价是只读投影，证据价值全在"排除规则"而不是"能返回数据"：必须同时满足项目 status=1、门店 status=1、报价 on_shelf=1、最新版本价>0；评分只认已完成订单+WECHAT 成功支付+非测试核销+test_mode=0 评价+consented=1 授权，且无支付异常/未结争议/退款。零样本时 average_rating 必须是 null 而不是 0。夹具要刻意造"应被排除"的行，否则数据恰好正确也证明不了过滤生效。


## A4d20 结果

18请求/67断言/0失败，一人一接口四类齐备；三家在架报价按有评价优先→平均分降序→价格升序返回[1,3,2]，5.00/2.00/null；下架报价、最新版本价0、项目 status=0 均未进入结果（后者 404）；region_code 过滤把两家降到 1 家。14 张表执行前后摘要逐字节不变，行数 order=2/payment=2/order_review=2/review_publication=2/merchant_project=6。

顺带核实两处契约偏差：POST /api/merchant/orders/{id}/actions 四个动作分别 43001/43004/43005/43006，200 不可达；POST /api/knowledge/answer 未配置 AI 即 503。前者已改契约文档，后者登记为外部依赖阻塞。剩余 37 项缺口=27 无实现+7 需真实上游+2 设计封堵+1 示例端点。


## A4d21 设计边界

"缺口"这个词把四种情况挤进一个数字，导致每轮都要重新人工排查。结案的关键是给每项一个**可校验的结论**，而不是把结论算成证据：覆盖口径必须保持"有证据/无证据"，结论只回答"为什么没有证据"。门禁必须双向：缺结论要报错，"把结论写到已有证据的操作上"更要报错——否则最容易发生的作弊就是用一句"已替代"把真实缺口抹掉。superseded 的替代必须指向契约内、带实现标记的操作，避免链条指向另一个同样没实现的东西。


## A4d21 结果

37 项全部结案：15 项已被具名实现替代（如 home/dashboard→health-dashboard、notifications→owner/notifications、order/verify→merchant/orders/{id}/redeem）、1 项由 CouponRecovery 内部完成、10 项依赖大模型或微信/短信上游、7 项未实现（对账报表、取车对比、OCR、VIN、真实渠道回调、cloud-login）、1 项无 200 路径、3 项仅本地演示。契约总数 352 与证据下限 315 写入门禁；`/api/ai/chat` 与两项辅助材料上传/访问补上实现标记（探针数 338 与证据数 315 不变）。结论会过期：替代下线、上游接入、功能立项都必须同步改 CONTRACT_VERDICTS.json，否则门禁报"未解析操作"。


## A4d22 设计边界

取车对比的诱惑是顺手写 delivery_compare 并推进状态，但交付已经由核销承担（带支付/免佣/争议语义），第二条写路径会与核销争同一订单状态，锁序与幂等都要重做。所以做成只读投影：不写库、不改状态、不要幂等键，用"12 表摘要不变 + delivery_compare 0 行"证明没有偷偷写。缺口必须逐项算（FAULT 只在既无照片又未声明无故障件时出现），不能返回固定文案。


## A4d22 结果

POST /api/check/delivery/compare 实测：齐备订单 READY/deliverable=true、七槽位排序正确、mileage_delta 0、防护 {submitted,item_count:2,photo}、报工 SIGNED 且四类证据各 1；缺证据订单 INCOMPLETE + [OWNER_CONFIRM,PROTECTION_PHOTO,FINISH,FAULT]；已完成订单 READY 但 deliverable=false；无接车单 409/43001；订单不存在 404；空正文/多余字段/字符串 id/数组/query 全 400；匿名 401、技师 403、他人车主与他店 404。契约去掉 Idempotency-Key（读操作不该要），补 schema 与 200/400/401/403/404/409/503。


## A4d23 设计边界

页面最容易出的错不是样式，是"把可疑数据渲染成结论"：投影自带 result 与 missing，如果前端只做字段存在性检查，"说齐备却列着缺口"也会显示出去。所以校验里加了三条自洽断言（result 与 missing 必须互推、deliverable 只能在 READY、报工 submitted/status/signed_at 自洽），并绑定 order_id 回指请求订单——拿到别的订单的对比等于展示错误车况。


## A4d23 结果

1023/1023 单测通过（新增 8 项：schema 矛盾 29 种被拒形态、认证只读 POST 与订单绑定、六类状态码映射、四组展示映射、失败三路分流）。微信构建 DONE 且原生样式/无测试身份桥 PASS，H5 构建 DONE。静态提示与映射标签撞词（"证据齐备"）会让"wxml 搜不到、映射模块里有"的配对检查失效，已改写为"证据是否齐全"。渲染未在开发者工具与真机执行，不声明通过。


## A4d24 设计边界

商家侧最容易做错的是"再写一遍规则"：前端也判一遍证据齐不齐再拦核销，迟早与后端守卫漂移。所以页面只报缺口、不拦操作；能不能核销仍由后端决定。同理不新开接口——同一份投影按身份授权即可，第二份接口等于把语义维护两遍。文案分叉只在展示层，未知 role 必须回落车主口径，否则参数写错会让人以为自己是商家视角。


## A4d24 结果

1024/1024 单测通过（角色文案分叉 2 项 + 入口矩阵断言：PENDING_VERIFY/COMPLETED 出现、必带 role=merchant、IN_SERVICE 不出现、标签随状态）；微信构建 DONE（原生样式与无测试身份桥 PASS）、H5 构建 DONE。文案配对：核销前核对/核销前无需再补/再决定是否核销/暂不能核销/取车对比（核销前核对）/查看取车对比 在编译后 wxml 全为 0 次，在 delivery-compare-display.js 与 merchant-order-display.js 里存在；redeem.wxml 含 redeem-delivery-compare。渲染未在开发者工具与真机执行。
