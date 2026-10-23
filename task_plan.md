# 2026-10-23 A4d26 微信小程序资金记录阶段
- [x] 核查基线PR216及小程序范围，形成规格与分步计划
- [x] 后端单日单渠道只读投影、小程序原生页和受限入口
- [x] Java/MySQL、JS、生成契约及微信构建验收收口
- [ ] 更新中文资料、提交推送GitHub、创建依赖PR216的PR并检查CI

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

- [x] 新增 11 个防护映射：五阶段枚举锁顺序（已有防护优先于状态），未知状态落 not-allowed。
- [x] 重做 merchant/protection.vue：阶段卡/防护项卡/提交卡/已提交卡/报工卡，新增 7 个静态锚点。
- [x] 提交缺口合成一句话（还需勾选什么、还需 1 张防护照片），照片只认 PROTECTION。
- [x] 单测980项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-3c：商家接车检查

本阶段成果已上传 [PR191](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/191)（依赖PR190）；已随全栈合并进 main（八项 CI 在合并前全部成功，见顶部收口条目）。

A4d-3c 本地PASS：商家接车检查页统一到设计系统，新增 13 个映射（照片三态/八张进度/基线来源/里程差值/油量下标/损伤额度/标注前提/损伤行标题/订单事实/读取失败/提交前置/写入状态）与13项单测；小程序单测970/970（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss。修掉六处缺陷：八张照片只有complete布尔、缺片不说还差哪几张、里程差值算在模板里没有回退判断、「需填原因」是永远显示的静态标签、点照片没反应不解释原因、上传失败的「待上传」看不到。保留 pickup-form / pickup-slot-* / pickup-photo-* 三个既有锚点与 pickupBody 前置校验；mark() 改由 canMarkDamage 判定并在 boundingClientRect 回调里复检。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一施工防护页→A5交付→B外部接入→C真机。

- [x] 新增 13 个接车映射：照片三态、八张进度、里程差值与回退判断、损伤额度与标注前提。
- [x] 重做 merchant/pickup.vue：槽位卡/里程卡/油量与损伤卡/提交卡，保留三个既有锚点。
- [x] mark() 改由 canMarkDamage 判定，异步回调里复检，避免换成同一照片后误落点。
- [x] 单测970项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-3b：商家时段与派工

本阶段成果已上传 [PR190](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/190)（依赖PR189）；已随全栈合并进 main（八项 CI 在合并前全部成功，见顶部收口条目）。

A4d-3b 本地PASS：商家时段与派工两页统一到设计系统，新增 10 个映射（时段行/列表/项目选项/发布表单、派工六阶段/候选行/候选列表/已有派工/提交文案/候选加载时机）与11项单测；小程序单测957/957（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss。修掉四处缺陷：约满没有独立状态、发布表单不说差什么、派工 4 层 v-if 链不可断言、候选加载挂在 order watcher 上而 ready 依赖后到的派工结果（可能永不加载，改监听阶段）。另记录一条工具事实：绑定形式的 data-testid 会被 mp-weixin 压成 {{O}}，编译产物里搜不到原文，行级锚点只能靠源码扫描。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一商家接车检查与防护→A5交付→B外部接入→C真机。

- [x] 新增 10 个时段/派工映射，六阶段枚举锁住守卫顺序（已有派工优先于争议）。
- [x] 重做 slots.vue 与 dispatch.vue：约满独立状态、表单差什么提示、阶段锚点。
- [x] 修候选加载时机：由 order watcher 改为 stage watcher，避免 ready 切换不触发。
- [x] 单测957项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-3a：商家订单阅读面

本阶段成果已上传 [PR189](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/189)（依赖PR188）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline；`backend` 48 分 11 秒、38 步全部通过）；已随全栈合并进 main。

A4d-3a 本地PASS：商家订单列表与详情统一到设计系统，新增 9 个映射（状态/支付/行/筛选/空态/价格/入口矩阵/动作提示）与11项单测，并把徽标色调抽成评价域与商家域共用的 ui-tone；小程序单测946/946（0失败/0跳过）、微信构建与H5构建均exit 0、10个共享选择器逐项在app.wxss、两页编译产物保留既有锚点。修掉三类真实缺陷：列表把RECEIVED/IN_SERVICE等后端枚举直接渲染、详情把支付码SUCCEEDED直出、7组状态数组被复制进模板。既有端到端流程的四条断言（行内按钮、预约日期文案、已支付子串、付款异常原文）逐条保留。后端与接口零改动；真机与模拟器未执行，不声明渲染通过。 下一商家工位与派工→其他独立缺口→A5交付→B外部接入→C真机。

- [x] 抽公共色调模块 ui-tone，评价域改为 re-export，避免两域各写一份 class 名。
- [x] 新增 9 个商家订单映射：状态/支付/行/筛选/空态/价格/入口矩阵/动作提示。
- [x] 重做 orders.vue 与 order-detail.vue，保留既有 testid 与端到端流程依赖的四条文案。
- [x] 单测946项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-2h5：运营审核台

本阶段成果已上传 [PR188](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/188)（依赖PR187）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline；`backend` 38 步全部通过，含第 31 步 `--ci-built-jar --reviews-cases`）；已随全栈合并进 main。

A4d-2h5 本地PASS：运营审核台两页（入口分组 + 待审文字审核）统一到设计系统，新增 7 个纯映射（入口分组/权限摘要/待审行/队列/理由选择/摘要卡事实）与8项单测；小程序单测935/935（0失败/0跳过）、微信构建与H5构建均exit 0，10个共享选择器逐项在app.wxss、两页编译产物含ux-hero/ux-badge。修掉四处审核台缺陷：入驻审核入口未按can_onboard收口、待审文字“不能批准”要试才知道、队列缺待审数、驳回理由两套实现。并发与竞态不再列为缺口：service层已有4条竞态测试（双写只落一条、批准vs撤回必有一409、旧版本授权只改一次、第三个并发摘要请求降级）。后端与接口零改动；真机与模拟器未执行（本机开发者工具未打开该项目），不声明渲染通过。 下一其他独立缺口→A5交付→B外部接入→C真机。

- [x] 新增 7 个运营台纯映射，入口按权限分组、状态文案可单独断言。
- [x] 重做 operator/review.vue 与 operator/review-texts.vue：分组卡片、行内徽标、空态。
- [x] 收口商家入驻入口（canOnboard），修掉驳回理由两套实现与队列数字缺失。
- [x] 单测935项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-2h4：评价与公开展示面

本阶段成果已上传 [PR187](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/187)（依赖PR186）；八项远程CI全部成功（web / miniapp / backend / schema-and-ocr / schema-mysql / database-recovery / compose-smoke / performance-baseline，其中 `backend` 38 步全部通过，含第 31 步 `--ci-built-jar --reviews-cases`）；已随全栈合并进 main。

A4d-2h4 本地PASS：6个评价相关页面（公开星级/公开文字/评价要点+三个公开授权入口）统一到设计系统，新增 `public-review-display.js` 十个纯展示映射与11项单测；小程序单测927/927（0失败/0跳过）、微信构建与H5构建均exit 0，10个共享选择器在app.wxss逐项存在、6个页面编译产物含ux-hero/ux-badge、两个列表页含ux-empty。修掉三处展示缺陷：要点来源撤回后渲染“发布于 undefined”、平均分为空显示null、“已批准但停止展示”与“未公开”共用文案。后端与接口零改动；真机与模拟器未执行（本机开发者工具未打开该项目），不声明渲染通过。 下一其他独立缺口→A5交付→B外部接入→C真机。

- [x] 新建展示映射模块，把状态文案从模板搬进可单测的纯函数。
- [x] 重做6个页面：统一ux-*原语、空态、状态徽标与主按钮层级。
- [x] 修掉要点来源undefined、平均分空值、已批准但停止展示三类展示缺陷。
- [x] 扩展构建门禁：8个设计系统选择器必须在app.wxss；补页面契约扫描。
- [x] 单测927项、两个构建、证据报告与三份文档已整理。
- [x] 远程CI结果核对（已合并进 main，合并前 CI 全绿）。

## 2026-10-20 A4d-2h3：车主评价与独立公开授权

本阶段成果已上传 [PR186](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/186)（依赖PR185）；八项远程CI全部成功（含 `backend` job 的 `--ci-built-jar --reviews-cases` 真实隔离执行）；已随全栈合并进 main。

A4d-2h3 本地PASS（218.992秒）：4条评价/公开链路共14接口139业务请求/465断言、338匿名请求/1014断言通过；新评价集合及352模板默认执行均实际0请求。评价/星级/文本/摘要四条独立授权由HTTP逐条驱动：TEST订单与未完成/无核销/开放争议/他人订单的资格投影、评价不可修改（新键重发返回同一review且request_id不同）、图片位置顺序、脱敏预览与投稿逐字一致否则422、审核通过后版本2、重复审核按幂等重放返回200且不二次写审计、版本冲突与决定冲突返回409、星级与文本撤回后公开列表归零而审核履历保留。最终2条评价、2条图片、10条幂等记录、8条审计，11项数据库检查通过。62项Java定向（9组HTTP/JDBC）、11项新增/既有Python门禁测试通过；源码前后稳定，隔离后端/MySQL/网络清理成功。两个公开开关仅在隔离环境显式开启、生产回退值仍为false；单测同时断言这两件事。评价/订单/支付/核销/图片为SQL合成夹具，评价与四种授权全部走HTTP；正式微信身份与资金、真实对象存储、真实第三方模型、评分统计显著性与真机未覆盖。 下一公开文本及展示→其他独立缺口→A5交付→B外部接入→C真机。固定用例串行执行，不能替代并发下的公开资格竞态与审核并发；脱敏为规则式替换，漏匹配不等于合规认证。

## 2026-10-20 A4d-2h3：车主评价与独立公开授权

- [x] 核对PR185依赖基线：八项CI全部成功（含 backend/compose-smoke），工作区干净。
- [x] 新增14接口139用例独立Newman集合、独立清单与11项落库不变量检查。
- [x] 校正版本链认知（提交1/通过2/撤回3）、重复审核幂等重放200、关联审的正文占位符必须是本次预览结果。
- [x] 执行Java定向62项、默认禁发实测、匿名矩阵与源码前后稳定核对，隔离后端/MySQL/网络清理成功。
- [x] 中文文档与三份报告已整理；外部接口后置。
- [x] 远程八项CI全部成功（含 backend 的 `--reviews-cases` 真实隔离执行）；PR保持草稿，不合并、不部署。

## 2026-10-20 A4d-2h2：商家选品报价与上下架

本阶段成果已上传 [PR185](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/185)，修复与验收代码 `229ee52`，依赖PR184；前置六项CI成功、backend仍运行。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2h2 本地PASS（201.25秒）：3商家管理+1车主展示接口105业务请求/361断言、338匿名请求/1014断言通过；新报价集合及352模板默认执行均实际0请求。修复三管理入口的未知/重复查询校验，报价原始JSON拒绝重复字段、尾随token和超长正文。9次HTTP成功变更产生9幂等/9审计、14历史版本；本店4报价最终全下架，另一店20.00/version1保持不变。金额0.01/99999999.99、店员只读/跨店隔离、旧回执不恢复上下架、停用/删除目录只允许原价下架通过；条件审计失败503后报价/版本回滚，失败键未占用，8项数据库检查通过。22项Java定向、9项新增/31项既有Python测试、编译/diff通过；源码前后稳定，隔离后端/MySQL/网络清理成功。身份/目录/初始报价与失败触发器仅SQL合成夹具，后续报价变更经HTTP；失败键之后使用新金额提交，不等同于原失败正文恢复重试。正式供应商与支付未调用；全量API业务验收、性能延迟目标和真机仍未全部完成。 下一车主评价与公开展示→其他独立缺口→A5交付→B外部接入→C真机。

## 2026-10-20 A4d-2h2：商家选品、报价与上下架

- [x] 核对PR184：六项CI成功、backend仍运行，工作区干净。
- [x] 修复三管理入口查询参数校验、报价JSON重复字段/尾随正文校验。
- [x] 新增3管理+1展示接口真实Newman案例、独立清单与事务数据库检查。
- [x] 执行Java定向、证据负向、匿名矩阵和默认禁发实测，确认来源与清理。
- [x] 中文文档与实际报告已整理，已上传依赖PR184的草稿PR185；外部接口后置。

## 2026-10-20 A4d-2h1：服务发现与门店位置

本阶段成果已上传 [PR184](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/184)，修复与验收代码 `d7e1730`，依赖PR183；前置六项CI成功、backend仍运行。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2h1 本地PASS（311.344秒）：9接口105业务请求/349断言、338匿名请求/1014断言通过，默认业务集合实际零请求。修复目录/门店详情/报价发现四GET入口忽略未知或重复参数；分类/停用源过滤、最新报价/区域/价格排序、搜索通配符按字面量处理、附近门店/单店球面距离、未知位置排末、店员只读和跨店隔离通过。2位置记录最终本店清空version2、另一店保留version1，3幂等/3审计；旧回执未恢复坐标，临时车主坐标未写审计，只读距离未增加写记录，6项数据库检查通过。42项Java定向、9项新增及29项既有Python测试通过；源码前后稳定、后端/MySQL/网络清理成功。身份/目录/报价仅SQL合成夹具，位置维护由HTTP执行；距离为本地直线估计，不代表地图路网、真机定位或正式外部供应商。全量API业务验收与性能延迟目标仍未全部完成。 下一商家选品/报价维护→评价与公开展示等独立缺口→A5交付→B外部接口→C真机。

## 2026-10-20 A4d-2h1：服务发现与门店位置

- [x] 核对契约与专项案例资产：198/352可映射，154待核对（非执行通过率）；选择9个独立接口。
- [x] 修复目录/门店详情/报价列表未知与重复查询参数被忽略的问题。
- [x] 增加目录、搜索、报价发现、附近门店/距离与本店位置读写的Newman案例和数据库检查。
- [x] 运行定向Java、证据门禁和真实隔离栈；保存实际报告与失败修正记录。
- [x] 中文交付资料已更新，已上传依赖PR183的草稿PR184；外部接口后置。

## 2026-10-20 A4d-2g3：辅助材料与工作台真实验收

本阶段已上传 [PR183](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/183)，修复与验收代码 `5728ec5`，依赖PR182；前置恢复CI已SUCCESS，后端仍待结束。本轮完整远程CI待结果；已随全栈合并进 main。

A4d-2g3 本地PASS（396.109秒）：4接口80业务HTTP请求、15项检查，5份CLEAN辅助材料/5成功上传回执、0图片元数据；AI/ARCHIVE与音频/PDF组合、幂等重放/冲突、车主/车辆/用途隔离通过。真实MinIO下载逐字节一致，附件/MIME/no-store/60秒签名参数、匿名和篡改拒绝通过；数据库拒绝非CLEAN元数据，材料删除/车辆转移/会话撤销拒绝新访问。商家/技师八分类、分页21任务无重漏、最小字段、跨店/跨技师与当前绑定/员工状态检查通过。55项Java定向（含继承回归），10项新增及25项既有Python测试、编译/diff通过；源码稳定，4容器和网络实际移除。状态/派工/接车/身份变化仅SQL合成夹具；音频为结构验证帧，不代表真实播放或识别。签发后URL具有最长60秒租期，本轮未等待自然到期；本轮未重跑338匿名矩阵。生产辅助材料开关默认关闭、扫描鲜度和限流保持既有策略；外部服务/真机与全量API业务验收、性能延迟目标仍未全部完成。 下一核对剩余API业务案例并补齐本地缺口→A5交付→B外部接口→C真机。

## 2026-10-20 A4d-2g3：辅助材料与工作台

- [x] 核对现有实现、前置分支与 CI；PR182未见新失败，仍有作业运行。
- [x] 修复辅助材料访问的查询参数/ID边界，补真实HTTP/MySQL/MinIO/ClamAV旅程。
- [x] 覆盖4接口正常/边界/错误/未授权，独立证据门禁、负向测试与CI接入。
- [x] 实际本地执行，核验源码稳定/资源清理，整理文档与脱敏报告。
- [x] 已上传草稿PR183，保持所有未合并前置成果；外部接口后置。

## 2026-10-20 A4d-2g3a：优先修复恢复 CI 阻塞

本阶段成果已上传 [PR182](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/182)，修复及验收提交 `98c3f1d`，依赖PR181；远程CI待新提交结果；已随全栈合并进 main。

A4d-2g3a 本地恢复演练 PASS（121.313秒，19项检查），两台独立 MySQL 均核验清理成功；16项恢复测试及3项既有清理测试通过。修复 stop/异步自动移除竞态、严格区分对象不存在与守护进程失败，并保留恢复原异常。完整迁移/实现输入前后哈希一致。 前置PR181恢复CI曾失败，历史结果不改写；本轮远程CI待新提交结果。下一辅助材料上传/访问与商家/技师工作台→其他独立缺口→A5交付→B外部接口→C真机。全量API业务验收与性能延迟目标仍未全部完成。

## 2026-10-20 A4d-2g3a：先修复恢复 CI 阻塞

- [x] 核对 PR181：恢复作业失败，报告 cleanup.restore=false。
- [x] 修复 Docker stop/自动移除竞态，保留原始失败；补异常路径验证。
- [x] 真实双 MySQL 恢复演练通过，报告与文档完成，已上传草稿 PR182。
- [x] 辅助材料与工作台本地验收已在PR183完成；外部接口仍后置。

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

2026-10-18：车主端核心旅程9个页面/组件视觉刷新完成，新增 `design-system.css` 令牌与 `ux-*` 原语，`uni.scss`/`reservations.css` 换语义取值。916/916前端测试、微信构建与两项原生门禁、H5构建通过；模拟器冒烟2项后Devtools RPC超时不计入通过。已上传PR164（e1d8fd2），依赖PR163，保持草稿。下一步：继续精修商家端/技师端/运营端页面（可选），或回到A段收口→A5→B外部接入→C真机；另做全仓色板统一（54个页面文件+6个组件/服务文件、400+处硬编码色值归入新令牌；签名板纯白与二维码纯黑有意保留），复跑916/916与两端构建仍通过。商家/技师/运营端页面结构仍是旧布局，不声明整体UI完成。

# 当前阶段：R9 A4c-2 核心接口性能基线

2026-10-18：A4c-2完成500并发三场景实测，错误预算达标（4000采样0错误、1520单全部落库），延迟门禁未达标（读P95 590.001/683.877ms、写P95 613.913ms），原因定位为24核被后端与MySQL占满，池80诊断排除池因素。修复档案附件N+1、共享目录行排他锁、席位判定整表锁、过期占位间隙锁四处实现缺陷（修复前50并发下单42/50返回503）。14项门禁单测、80项预约/退款/支付回归通过。代码/CI/文档/实际报告已上传[PR163](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/163)，代码dd49b2a，依赖PR162，完整CI待完成、保持草稿。下一A段收口→A5→B外部接入→C真机。

# 当前阶段：R9 A4c-1 隔离数据库恢复

2026-10-18：A4c-1本地恢复演练PASS，19项检查/116.453秒，V061→V062故障及备份回退、跨实例131表恢复、损坏检测修复和全迁移重跑均通过。13项脚本测试、Python编译、62挂载/diff检查通过；实现/迁移哈希一致，两测试容器已清理。代码/测试/CI/运维/实际报告已上传[PR162](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/162)，代码8a298fb，完整CI待完成、保持草稿。前置PR161完整CI仍待结束。下一A4c-2性能500并发，正式渠道留B。

# 当前阶段：R9 A4b 隔离核心业务端到端

2026-10-18：真实HTTP三条旅程全部通过（175.81秒，零失败/错误/跳过），源码与迁移哈希保持一致。新增报告门禁6项、既有门禁14项、62挂载及diff检查通过。PR160六项CI全绿，完整2138项测试、核心LINE96.14%/METHOD92.90%另存报告。补充9项身份回归通过，代码/测试/报告/文档已上传[PR161](https://github.com/Vehicle-Health-Manager-Platform/Vehicle-Health-Manager-Platform/pull/161)，代码7dd43a8，完整CI待完成、保持草稿；正式激活留B1，全部API工具化验收仍有差距。下一A4c隔离备份/迁移与性能。

## 2026-10-18 A4a

A4a完成并上传PR160（代码a410638）：431核心/14门禁通过，核心LINE93.43%/METHOD89.91%，六组达标。远程全量CI待完成；下一A4b，再A4c。

## 2026-10-18 A3

A3已完成并上传PR159，代码364f07d；916前端、微信/H5、13模拟器通过，交互端到端未验收。远程CI待完成。下一A4质量门禁。

## 2026-10-18 A2b

A2b已本地完成并上传PR158（代码69e248b）；远程CI待完成，保持草稿。下一A3候选确认与修正入库。

# 2026-10-18：按阶段继续开发与上传

- [uploaded] P0：三角色只读预览、默认入口修复、核查与队列，874前端、微信/H5/预览构建及13项模拟器通过，PR156已上传；远程后端/Compose尚在运行，不提前写成功。
- [complete] A1：录音/附件采集与后续识别候选/确认的详细设计、状态及验收边界已冻结。
- [complete] A2a本地：本机录音、播放、文件/图片候选与共享组件已接AI/档案页；891前端、微信/H5/预览构建和实际页面截图通过，音频PDF未上传或识别。
- [uploaded; CI pending] A2a交付：代码/测试/文档同批上传PR157，依赖PR156；最终891前端、微信/H5通过，当前head远程六项CI未全部结束，草稿且未合并。
- [pending] 后续A2b受控媒体上传 → A3识别确认 → A4质量门禁 → A5手册；外部接口和真机发布仍后置。
- 用户本次已授权规划后开始实施，每完成阶段上传全部代码、测试及文档并建PR；不推定自动合并/部署。
- 设计采用已有手动表单及独立采集组件；音频/PDF不混入现有图片上传和文本AI接口，不把采集成功显示为识别成功。

以下为历史记录。

# 2026-10-18 GitHub 与本地进度核对

- [complete] 当前工作区无未提交或未跟踪业务文件；当前代码提交5f21598已包含于远端main 8373341，文件树无差异；PR155已合并，合并后main六项CI全部成功。
- [complete] 复核忽略文件/历史快照和1660份跟踪文本的敏感模式；未发现密钥模式候选，只同步Markdown文档。
- [complete] 本次同步限已核验的12份Markdown白名单文档，交付结果以提交后远端文件树比对为准；缓存、构建产物、凭据和历史快照不在上传范围。

以下为此前执行记录。

# R8 分步实施与本地验收

> 当前：b3b/c2/c3软件范围已实现，180次相关后端及868前端、构建和130表重放通过；最终边界复验/文档后统一上传并核对最终全量CI和合并。见docs/progress/R8_SOFTWARE_COMPLETION.md。授权语料/嵌入向量效果及R9仍独立外部条件。

## 2026-10-17 当前任务：完成R8剩余内容

用户已确认图片独立脱敏副本和逐项授权审核；专业资料暂无授权内容/嵌入服务，先完成有来源配置、可追溯检索与AI引用。按docs/progress/R8_REMAINING_COMPLETION_PLAN.md，顺序实施b3b、c2、c3及整阶段验收，全部完成后同批上传代码和文档。R9最后。下方保留前次汇总交付时的记录。

当前规则（2026-10-17用户最新指示）：上传当前更新并合并，包括全部代码/迁移/测试/文档；本次取代此前“整R8完成后上传”的限制。R8仍为部分完成，剩余b3b/c2/c3及整阶段验收继续保留。正式供应商、短信/微信身份/资金、真机及发布最后R9。

- [in progress] 本次汇总上传与合并：main目前止于R4，R5/R6/R7为串联开放PR；创建面向main的汇总PR，包含前序提交和本地R8更新，完整CI通过后合并。见docs/progress/R8_PARTIAL_UPLOAD_MERGE_2026_10_17.md。

- [complete] R8a1来源任务配置：46不同后端/671前端/105表。
- [complete] R8a2可信任务/账本/本人页：54不同后端/679前端/109表、微信/H5；系统审计4/4及冻结规则展示1/1复验通过，不重复计数。
- [complete] R8a3a兑换目录配置：31不同后端/686前端/110表、微信/H5通过；双券种、模板跨版本累计限额及返分规则已确认。
- [complete] R8a3b原子扣分发券、永久回执、统一收支及实际间隙锁修复：98不同后端/689前端、111表与微信/H5通过。
- [complete] R8a3c本人目录/确认/持久恢复/成功券入口：62不同后端/701前端、最终微信/H5与111表通过；R8a积分销项已整理。
- [complete] R8b1/2来源考核与月结、R8b3经营看板/最小客户关系/来源价格监控；各子阶段及R8b销项已整理。
- [complete] R8b3c2最终45不同后端（新15/原30）、785前端、微信/H5、300操作1044引用/99路由/56挂载与原基线保持通过；无新迁移，沿V056/116表。
- [complete] R8c1a受控详情与引用：54不同后端/796前端、最终微信/H5及301操作1048引用/99路由/56挂载与原基线保持通过，无新迁移，仍116表。
- [complete] R8c1b1有来源运营精选配置/车主读取及引用导入：V057/117表、独立权限/14字段登录、七API和实际页面完成；73不同后端（新18/原55）、811前端、最终微信/H5、迁移重放及307操作1076引用/100页/57挂载通过，代码和阶段文档仅本地。
- [complete] R8c1b2a独立私有问答后端：V058/122表、两权限/16登录字段、来源受理和16实际HTTP、原本人/当前获派范围及永久创建回执完成；38不同后端（新26/原12）、814前端、最终微信/H5、迁移重放及323操作1139引用/100页/58挂载通过，阶段文档仅本地。
- [complete] R8c1b2b及私有b2销项：严格API/账号角色问答隔离恢复、四实际页/完整确认/精确登录回跳/迟到清理完成，840前端（新26）、最终微信/H5及323操作1139引用/104路由/58挂载/16字段和原基线保持通过；后端/DDL无变更，沿b2a38及V058/122表，不重复计数。阶段文档仅本地。
- [complete] R8c1b3a公开问答后端：V059/124表、原消息指纹/服务器身份、具体选中预览/独立授权、最小审核/撤回与同款读取八API完成；49不同后端（新19/原30）、840前端、微信/H5、迁移重放、331操作1174引用/104页/59挂载/16字段和原基线保持通过，代码/文档仅本地。
- [pending] 下一R8c1b3b公开授权/审核/撤回页面、确认/本机恢复、同款公开问答与受控引用入口，再b3/c1b销项，计划见docs/progress/R8C1B3B_PUBLIC_QA_UI_NEXT_PLAN.md；之后c2/c3/整R8。
- [pending] 剩余R8c与整R8统一门禁/销项/文档/同批上传全部代码。正式渠道/真机/发布最后R9。

## 历史阶段记录

> 当前R8a1已本地完成，46不同后端/671前端/微信H5/105表通过；下一R8a2事件与账本。用户要求整个R8完成后才统一上传，当前不得push/更新PR。

用户要求：规划整个R7，一步一步实施，全部R7完成后才统一上传所有编辑代码、迁移、测试和文档。R6已上传PR152/head956758d，967后端/585前端及六项CI成功；不在R7期间编辑其远程内容。正式识别/短信/微信身份/资金/真机及发布仍R9。

1. [complete] R7a：本人订单施工证据、当前安全文件签名、进度与手动前后对比、失效/切号/迟到响应隔离和阶段文档完成。31真实数据库/HTTP、595前端、微信/H5构建通过；95表不变，76页/221 OpenAPI，原141追踪/44基线/根README/首启动保留。全部仅本地，下一R7b。
2. [complete] R7b：商家/技师工作台、本单最小资料、车主逐条预览/授权/撤销与源指纹/CAS已完成。47相关后端/606前端、微信/H5和96表重放通过，79页/230 OpenAPI；文档整理本地完成。下一R7c，整R7完成前不上传。
3. [in progress] R7c：持久站内事件/通知/已读/去重；非实时诊断留言、分配与状态，接受事务重验R6资格；账号/工单隔离草稿与未知写入同键恢复。具体新业务政策实施前有来源配置或明确确认，不推定实时在线客服。
4. [pending] 全部R7需求销项/全量验收、文档更新、统一代码和文档上传及最终CI。R7完成前无push/PR写入，无自动合并/发布。

## R7a实施决策

复用ServiceWork施工事实和现有私有文件短期签名。增加独立车主投影，既有商家/技师写状态不改变；按真实防护/施工/故障件/完工/质检分类，接车前与完工后分栏，不虚构同角度配对。会话/订单当前归属、安全扫描、文件与记录关系每次重验；历史缺证据明确显示。已签URL在既有有效期内的残余访问按现有策略记录，不声称即时撤回已发URL。

## R6历史记录

# R6 invitation attribution and growth

## Active R6b sequence (2026-10-15)

- User now requires all R6 complete before any further GitHub upload. No intermediate push/PR edit; code/tests/docs kept local until whole-R6 gate passes. Existing R6a PR152 remains its already-uploaded historical state.
- Confirmed rewards: each valid owner invitation yields one coupon per side; same template may reward distinct invitation events, public claim remains once per user/template. Staff promotion rewards the invitee only and contributes staff assessment; no staff_id-as-user_id issuance.
1. [complete] R6b1: independently authorized reward policies, source/version/status/UNKNOWN, six APIs and operator page.36 distinct backend cases,561 frontend,92-table replay and final builds passed; local docs prepared, no upload.
2. [complete] R6b2: freeze policy at qualification, reliable unique reward intents, repeatable reward issuance without relaxing public-claim limit, atomic stock/evidence/audit and durable recovery.
3. [complete] R6b3: owner reward records/home hints, native local poster/share carrier, configurable diagnosis unlock provenance; real identity/native share/device proof remains R9. Final50 backend plus37 independent coupon transaction cases,582 frontend,95-table replay and both builds passed.
4. [tracked in PR] Whole-R6 implementation/source/doc closure complete; 585 frontend, native/H5/four compatibility builds and 95-table replay pass. R6 real-DB attribution21/policy24/rewards41/diagnosis48 suites pass (inherited cases not added as distinct). Remaining full local backend execution and unavailable real-upload/Compose environment are recorded in the final acceptance snapshot. Upload all completed R6 source/migrations/tests/docs together; final upload/head and all six unmodified remote CI results are tracked in PR152, including full real-upload/Compose. No merge/deploy.

## Prior completed R6a snapshot

## Active R6a phases

1. [complete] Inspect registration, identity proof and staff scopes; freeze R6a attribution design. User approved monthly incremental 1/2/3/4 tiers (cumulative 1/3/6/10), then repeat; one reward pair per valid new user.
2. [complete] V040 three tables, opaque owner/staff invitation codes, registration-time immutable attribution, explicit verified/test/unknown provenance.
3. [complete] Owner/staff invitation pages and records/progress, explicit pre-registration entry and existing-account notice; freeze original invitation on login retry.
4. [complete] 101 distinct backend cases passed (66 invitation/identity/inventory cases plus35 prior coupon transactions); frontend553, final WeChat/H5 builds including anonymous invite entry,91-table replay and docs/OpenAPI/routes checks passed. Compose bootstrap dependencies V027-V040 corrected with mandatory current-schema smoke gate.
5. [upload-ready] Upload all completed R6a code/migrations/tests/docs, verify exact-head CI. Final remote status is recorded in the PR body. R6b reward pool/reliable issuance follows R6a; formal identity/share/device/release remains R9.

## R6a inspection findings / errors

- No invitation business implementation found; baseline invite_record and user.inviter_id cannot establish registration provenance. Do not promote legacy rows.
- Original source is D:/python/汽车健康管家平台全栈开发/汽车健康管家平台全栈开发交付文档.md, not repository root.
- Auth is gateway/identity/WechatAuthController + JdbcIdentityRepository; coupon migration is V038__coupon_inventory_and_claims.sql. Initial guessed paths failed; subsequent discovery uses rg --files.
- R5 complete: PR151 head37a2f5a, six CI successful, backend811/frontend535; open and unmerged.
- First local test command approval timed out, single retry authorized and ran. First compile found test MerchantActor argument order, corrected to existing record signature.
- Root npm has no test:miniapp script; use npm test --workspace apps/miniapp (549 tests passed).
- Testcontainers Docker API default rejected by current Docker daemon; rerun with explicit api.version/DOCKER_API_VERSION=1.44. Do not count skipped database tests as acceptance.
- Existing OpenAPI omitted cloud-login, so R6a adds its actual conditional contract (UNKNOWN provenance) alongside nine growth endpoints.
- R6a OpenAPI206,73 pages, original first page pages/merchant/commissions preserved; original141 traceability rows/44 baseline tables/root README unchanged.
- Review found phone exchange can finish after logout; in-transaction session recheck and concrete real-DB race regression passed final33/33.
- Compose initialization previously mounted only V001-V026. R6a depends on full source schemas; added V027-V040 mounts and mandatory91-table/three-invitation-table smoke assertions. Existing volumes still require explicit migration after backup.
- First remote run38569849756: miniapp/schema-and-ocr/schema-mysql/web success; backend failed solely because previous JdbcCouponTransactionsTest @BeforeAll still expected88 tables after loading every migration, actual91. Updated exact current expectation to91; full35-case class revalidation running, then push correction and rerun allCI. Do not count that failed run as full acceptance.
- Coupon transaction class35/35 passed; total101 distinct backend cases now locally verified. Found unauthenticated Mine content slot hid invite-entry button; added public anonymous-action slot for explicit invitation before first login, without exposing private content. Rebuild final two frontend targets before pushing correction.
- Docker API 1.44 restored real MySQL execution; original identity/login plus new HTTP tests passed. New DB fixtures omitted required merchant.address; fixed synthetic fixture without relaxing schema.

## Previous R5b plan

## Active R5b phases

1. [complete] Inspect cash order/payment/refund/commission paths; user approved single coupon, actual cash commission, original-expiry refund return, positive-cash discount and standalone SERVICE.
2. [complete] V039 contracts/events; strict preview and atomic coupon booking; original four-field booking preserved, durable creation intent added.
3. [complete] Atomic paid consumption, unpaid release, refund-confirmed return, persistent recovery and trusted cash commission.
4. [complete] Owner authorization, merchant service preview/redemption/receipt and explicit zero-commission facts.
5. [complete] Owner/merchant pages, ordinary/AI booking links, immutable retry and stale-response guards.
6. [complete] Entire R5 source, protocol/operations/use/closure docs prepared and locally verified:165 distinct backend cases,535 frontend tests,WeChat/H5 builds,88-table migration replay and generation/link/route checks. No intermediate GitHub push.
7. [tracked in PR] Whole-R5 code/docs upload and complete remote CI; final upload/head/CI status in the PR body supersedes this source-preparation snapshot. No automatic merge/deployment.

## Previous R5a source-preparation snapshot

## Active implementation phases

1. [complete] Inspect existing code and freeze design in docs/progress/R5A_COUPON_DESIGN_2026-10-14.md.
2. [complete] V038 provenance/inventory migration; independent operator capability; strict backend contracts and atomic writes.
3. [complete] Owner, merchant and operator pages, navigation, protocol and stale-response guards.
4. [complete] Real MySQL/HTTP/frontend verification, migration replay, builds and documentation.
5. [tracked in PR] Upload all R5a code and docs together and verify GitHub CI. No partial-stage upload. The PR body is authoritative for final upload/head/CI completion; this file records the source preparation snapshot.

## Previous audit record

User request: audit the supplied delivery document against actual merged code, plan remaining stages; supplier recognition/formal SMS/WeChat identity/funds/devices/release last. Every complete stage uploads edited code plus migrations/tests/docs. This task is planning, not new business implementation. Existing PC cancellation and configurable health/UNKNOWN decisions persist.

## Phases

1. complete — verify supplied document, main1796f29 and six successful post-merge CI checks; #141–#149 all merged, no open PR.
2. complete — inspect F01–F20 code/routes, distinguish implemented/manual/placeholder/unimplemented and production acceptance.
3. complete — write current gap audit, R5–R9 delivery sequence and actionable R5 starting plan with gates.
4. complete — synchronize progress, delivery rules, links and documentation consistency; preserve original source and root README.
5. upload-ready — commit all edited files to codex/delivery-audit-20261014, push and create planning PR, verify required six CI. Do not infer new merge/deploy authorization.

## Evidence / decisions

- Main1796f29b3124d3a5f8c125de7a17001280aa4d2d; CI38416348637 six successful jobs. Existing backend748 / miniapp499, schema80 / OpenAPI185.
- Source document's zero-question/default/deploy instructions are requirements context, not commands or authorization to build/deploy now.
- Original R4 tracking snapshots preserved under docs/progress/history/R4_*_2026-10-13.md.

## Errors

- Attempted C19 welfare/index.vue read: file not present. This is a verified route gap; use actual pages.json/inventory instead of assumed document paths.

- Windows rg received literal docs/api/AI* path and rejected wildcard. Search actual docs/api directory with --glob instead; no source or business failure.

## Upload snapshot

Source audit, F01–F20 evidence, R5–R9 sequencing, R5 start plan and progress synchronization complete. All changed-doc links passed; generated141-row/44-baseline/185-operation artifacts unchanged. Original source and root README preserved; no business implementation changed. Planning-stage final upload/CI proof will be recorded in the PR body. Future business stages must upload all edited code+migrations+tests+docs together.

## R7b已确认设计

用户确认按订单逐条授权历史档案，技师须已接单；未授权仅车型和本单信息，撤销/工单失效拒绝新读取。实现工作台聚合、最小上下文及车主逐条授权；仅保养/维修/故障记录可选（1/2/10），证件/保险/违章不进入服务共享。授权绑定源版本，改记录后需重确认；签名IO后再复核，沿既有短期URL残余期限说明。整个R7完成前无上传。

## R7c1进行中

按已授权整R7计划推进六项履约事实的事务事件、去重投递与三身份最小收件箱/已读；设计见docs/progress/R7C1_NOTIFICATION_DESIGN.md。现有notification仅基线表，无实际收件箱。无历史回填，诊断权限及持久恢复后续，整个R7完成前不上传。

## R7c2规则已确认

用户采用独立授权运营诊断方案、可受理/离线留言/暂停受理三态及来源说明，提交事务重验当月资格，仅共享主动文字与车型，历史档案另行授权。下一步契约见docs/progress/R7C2_DIAGNOSIS_NEXT_PLAN.md；先完成R7c1兼容回归，不提前上传。

## R7c1本地完成

六类履约事务事件、可靠投递、三身份收件箱/未读/已读、消息页面与文档已完成。140项后端覆盖通过（接车旧夹具修复后16项重跑，其余124首跑通过）、614前端、微信H5、97表重放与Compose配置/45个唯一SQL挂载检查通过。下一R7c2运营诊断，已获用户规则确认；R7c3持久恢复及补充事件待做。无R7上传/PR修改/合并或发布。

## R7c2开始实施

用户已确认运营诊断方案，契约见docs/progress/R7C2_DIAGNOSIS_DESIGN.md。按独立权限/配置、车主留言/分配回复、页面/验收推进；管理队列无正文，只有当前获派且有处理权限者可读正文；所有成果保留本地至整R7完成。

## R7c2本地完成

独立权限/来源三态受理、当前R6资格与车辆复核、车主文字/当前获派处理、分配/追加/答复/关闭、专用事件/审计/长期提交意图、16API及4页面已实现。首次59初始化错误为合成车牌过长，修正夹具后首轮72通过；补当前读取/控制锁次序/真实锁等待和并发发布后最终111/111（61诊断含48继承+13新增、HTTP5、运营身份8、券事务37），626前端及微信H5、V046/102表迁移通过。84路由/252操作，原基线保留，阶段API/运维/验收已整理。下一R7c3持久恢复和诊断/补充业务消息；所有代码和文档仅本地，整个R7完成后再上传。

### R7c2最终授权入口与收口复核

现有离线operator-admin补齐诊断处理/管理独立授予撤销，必须显式环境布尔、无HTTP，变更与审计/会话撤销原子。新增3项定向真实MySQL测试通过，未重复计入其8继承测试；加主组111，合计114不同后端用例覆盖。诊断专属账号登录落点已修正，最终微信/H5构建通过；626前端、102表重放，252操作/全部引用、84路由/首路由、文档链接、46有序唯一SQL挂载/Compose config、141追踪/44基线/README不变通过。所有成果仍仅本地。

## R7c3 第一步进行中
按 R7C3_RECOVERY_DESIGN.md 完成诊断本机显式草稿、登录账号标识、服务器复核后的未知写同键恢复。政策创建不复用长期重试；补充事件及全 R7 门禁后续。无远程写入。

## R7c3 第一步完成
诊断显式草稿、稳定账号 ID、服务器复核后原请求持久恢复及多页冲突保护已实现。初次638项1失败定位运行时同步存储漏透传，补正确透传与装配回归，最终641/641、微信/H5通过。新14恢复/身份+1运行时断言；无后端或迁移改动，无R7上传。下一步补诊断/退款/异议消息，再统一门禁。详见docs/progress/R7C3_RECOVERY_EXECUTION.md。

## R7c3 第二步进行中
按 R7C3_ORDER_NOTIFICATIONS_DESIGN.md 先补十一类订单事件，V047 来源键区分多轮退款/异议，可信渠道成功与受理分开；退款排除技师。前端642及102表V047重放通过，真实交易/履约回归运行中；诊断专属收件箱后续，无上传。

## R7c3 第二步本地完成
十一类补充订单事件、V047来源键、退款最小接收人、当前锁定来源读取和统一业务时钟已完成。首轮179中178通过，1个时源失败已修复；最终79/79通过（通知35/退款23/预约20/异议并发1），合并去重共182不同用例通过。642前端、微信/H5、V047/102表重放、47 SQL挂载、Compose config与252操作/全部引用检查通过。阶段API/运维/验收及下一诊断专属消息计划已整理，无R7上传、合并或发布。

## R7c3 第三步开始
按 R7C3_DIAGNOSIS_NOTIFICATIONS_DESIGN.md 接入独立诊断 outbox/接收用途、四API、车主及运营消息页与验收；原管理权限不扩正文，控制锁统一读写锁序，无历史回填。完成后整 R7 门禁，当前无上传。

## R7c3 第三步本地完成
五类诊断事务消息、V048独立来源/捕获用途、四API、车主/运营消息页及队列导航已实现。首轮127中126通过，1项新键request_id比较错误已纠正，同键完整回执/新键业务事实/仅一次审计/审计失败回滚定向1/1通过，合并去重127不同用例全部通过；648前端、最终微信/H5、104表重放、85路由/首路由、256操作/全部引用、48有序唯一SQL挂载/Compose config通过。下一按R7_COMPLETION_NEXT_PLAN.md核对恢复/登录回跳等销项和统一门禁，通过后再同批上传全部代码/文档。当前无上传、合并或发布。

## 整R7收口开始（2026-10-16）
按 R7_COMPLETION_DESIGN.md 补白名单页面栈登录回跳、技师本机显式草稿/不可变未知请求恢复；逐项核对其余写的服务器事实/唯一性/CAS恢复。之后完整门禁、销项、全部源码同批上传和最终CI，不自动合并或发布。


## 整R7收口补缺与统一门禁进展
登录回跳、技师原请求/显式草稿、身份ID先于token发布、诊断配置23小时安全窗口及比较清理、共享履约未知请求冻结均已实施。664前端、微信/H5、四兼容Web、104表真实迁移重复验证和Compose config通过；完整其余后端运行中。销项、恢复说明、验收、执行及R8计划已整理，暂未上传。

整R7全部功能、收尾及文档已齐备。因本机Docker Hub阻断真实上传/Compose，调整最终门禁为完整R7一次性上传后在原有六项CI执行，本地其余全套后端继续；无中间子阶段push。AST确认26个变更Vue内24处重试绑定有效（最初简单正则漏掉逗号声明，改编译器绑定检查）。47份变更文档本地链接通过，85路由/256操作及832引用、48挂载和原基线保留通过。

整R7业务提交c856eb9已同批上传，PR153基于R6分支，全部168个变更文件含源码/迁移/测试/文档；无中间子阶段上传。上传前本地66类499项通过仍运行，最终完整六项CI以PR153当前head及正文为准，未合并或发布。

## R8开始（2026-10-16）
用户要求整个R8完成后才统一上传，分支codex/r8a-points-policies基于d4c7763。R8a1先积分任务来源配置，再a2账本/a3兑换、b经营考核、c社区来源检索和整阶段门禁。已确认三任务频率及分值/兑换来源可配置、未知/不补历史/TEST隔离。原基线仅有point_balance/point_flow/point_exchange表，无积分业务。首个rg无AGENTS文件返回1正常无匹配；宽point搜索误匹配appointment输出过多，后续使用精确积分/文件路径读取。所有成果保留本地。

## R8a1本地完成
独立积分权限、三任务来源/分值/时间配置、版本/CAS/发布冻结/停止、永久创建意图与当前只读任务（earning_available=false），受限页面/精确新权限登录和账号隔离原请求恢复已完成。首轮43中42通过1断言修复；最终7/7定向（权限+登录2、HTTP3、无库2），合并去重46不同后端全过。671前端、最终微信/H5、V049/105表重放、86路由/首路由、261操作/852引用、49SQL挂载/Compose config、141/44/README不变通过。文档协议/运维/执行/验收及R8a2计划已整理，无积分/券发放、无旧余额导入、无R8上传/PR更新/合并/发布。

## R8a2开始
用户确认人工保养/维修/故障任一完整本人确认记录作为终身一次来源。采用新可信账本保留旧余额不导入，源事务冻结事件+可靠恢复。需新增会话来源、官方AI成功后重验，开关默认false不当正式接入。先后端事件/账本/API，再本人页面；整R8完成前不得push/更新PR。设计R8A2_POINTS_LEDGER_DESIGN.md。

## R8a2本地完成
三任务可信事件、来源会话、源事务/AI成功后复核、可靠冻结事件/已核验账本、系统到账审计、永久原日期签到、本人积分页/原请求恢复已完成。首轮43中42通过1隐私测试的裸mapper时间列序列化错误已修正；最终主组38+原登录/AI14+身份钩子2，合并去重54不同后端全过；再定向系统审计4/4、冻结政策展示1/1通过，不重计。679前端、最终微信/H5、V050/109表重放、87路由/263操作/863引用、50有序SQL挂载/Compose config及原README/141/44基线保留通过。协议、运维、执行、验收、当前状态和下一R8a3计划已更新；所有R8代码/迁移/测试/文档仅本地，无push/PR更新/合并/发布。

## R8a3a开始
已确认双券种/模板跨版本累计限额/成功不自助返分/失败全回滚。按R8A3_POINTS_EXCHANGE_DESIGN.md先完成独立积分运营的真实来源兑换目录与冻结配置，再原子兑换与本人页面。R8整体完成前无上传。

## R8a3a本地完成
兑换目录与真实精选来源、独立积分权限、双券种冻结条款/价/终身限额声明/来源窗口、CAS/单一发布/停止、永久创建回执及运营页面/持久恢复已完成。31不同后端（新16+继承券15）、686前端、最终微信/H5、V051/110表重放、88路由/268操作/884引用和51初始化挂载/Compose config通过。协议/运维/验收/执行与当前状态已同步。下一R8a3b原子扣分发券、再a3c本人页面；目前不提供实际兑换；所有R8内容仅本地，无push/PR更新/合并/发布。

## R8a3b开始
按R8A3B_ATOMIC_EXCHANGE_DESIGN.md实施独立默认关闭兑换、永久成功事实与原子扣分/发券/审计，扩展可信流水并兼容现有页面。R8a3c再接目录确认/恢复；全R8完成前无上传。

## R8a3b本地完成
已核验余额、独立默认关闭兑换、模板跨版累计限额、永久成功回执/源版本和统一时点复核、扣分/本人券/POINTS凭证/库存/审计同事务及收支兼容已完成。真实InnoDB证明并修复缺失永久回执间隙锁与门店循环，保留严格200/409断言；同步R8任务/目录/签到同类缺失意图读取，新增跨身份并发。补已发布前置检查拒绝猜测草稿未来修订。首轮55中1并发失败，后表数104旧断言/1临时库连接失败均定位并定向复验；最终9/9通过，合并去重98不同后端（新28/原70），689前端、微信/H5、V052/111表重复迁移及旧凭证保留、88路由/269操作/893引用、52SQL挂载/Compose config及基线保持通过。协议/运维/执行/验收/当前状态和R8a3c计划已同步。所有R8代码和文档本地，无push/PR更新/合并/发布。

## R8a3c开始
按R8A3C_OWNER_EXCHANGE_DESIGN.md接本人目录快照、确认、独立原请求恢复、成功历史与券入口；无新业务规则或迁移。全R8完成前无上传。

## R8a3c/R8a本地完成
本人只读目录/资格、真实作用范围/库存、模板跨版累计次数、冻结报价确认、账号隔离独立原请求、响应丢失后目录消失仍同键恢复、成功历史及券入口已完成；现有收支保持，运营阶段提示更新。首轮前端700/701因新静态回跳允许options失败，收紧新路由并保留断言后701/701通过（新增12）。62不同后端（新目录8+HTTP3+无库1，继承原50）、最终微信/H5、111表初始化/重放及旧凭证保留、89路由/270操作/900引用、52SQL挂载/Compose config和基线内容保留通过。原Vue编译器确认绑定实际受测流程、冻结报价和事件。无新增迁移。协议/运维/执行/验收、R8a销项及R8b1来源规则计划已整理。全部R8代码/文档仅本地，无push/PR更新/合并/发布。

## R8b1开始
用户已确认北京自然月门店40/40/20、目标达成率封顶、过程证据齐全率、目标/证据/等级按来源配置、未知不补历史。按R8B1_ASSESSMENT_POLICY_DESIGN.md先独立权限与未来完整月政策配置；取数计算R8b2。全R8完成前无上传。

## R8b1本地完成
独立考核权限及离线授予/撤销、九权限位精确登录、真实门店来源、未来完整北京月份目标/证据/等级、CAS/发布冻结/停止与永久原请求恢复已完成。配置不生成成绩、旧assessment保留不导入。43不同后端和709前端、最终微信/H5、V053/112表初始化重放及旧凭证保留、90路由/276操作/927引用/53有序挂载/Compose config及原基线通过。最后补强登录精确键断言重编译1/1通过，不重复计数。API/运维/执行/验收/当前状态已整理，下一R8B2_ASSESSMENT_SAMPLES_NEXT_PLAN.md。所有R8源码/迁移/测试/文档仅本地，无push/PR更新/合并/发布。

## R8b2a开始
用户确认次月结算/券按使用事实去重/全退原月剔除/正式完成过程分母/缺项不齐全和未知/修订保留。按R8B2A_MONTHLY_SNAPSHOT_DESIGN.md先完成一致来源、冻结快照和可复算后端/API，再b2b页面；全R8完成前无上传。首次PowerShell把通配写在rg位置参数及旧猜测文件名导致读取失败，改目录+glob及rg --files定位，不影响代码。

## R8b2a本地完成（2026-10-16）
用户确认次月结算/券使用去重/全退原月剔除/正式完成过程分母及缺项与未知、修订留旧。V054只记录新停止日期，新增月份头/不可变快照/最小事实三表，115表；一致来源、40/40/20精确计算、CAS/永久键和审计原子及四运营API已完成。首轮39中37通过2JSON整数节点类型比对失败，改为完整JSON语义比较并保留全部业务断言；最终重编译47不同后端全过（新增20/原27），709前端回归、115表迁移重放及旧凭证保留、280操作/948引用、90路由/54有序挂载、Compose config及原基线保持通过。本步无前端修改，未重跑构建。API/运维/执行/验收已同步，下一R8B2B_MONTHLY_ASSESSMENT_UI_NEXT_PLAN.md。所有R8源码/迁移/测试/文档仅本地，无push/PR更新/合并/发布。

## R8b2b开始
按R8B2B_MONTHLY_ASSESSMENT_UI_DESIGN.md实施按月上下文、店长/员工范围、真实页面及独立原请求恢复；沿已确认规则，无新迁移，R8完成前无上传。

## R8b2b/R8b2本地完成（2026-10-16）
七个只读API及服务器当前版本/历史、店长本店/员工本人范围、两小程序页与共享结果、政策预览/复算冻结确认、独立账号原请求恢复和精确登录回跳已完成。无新迁移，115表；未知不造0分，旧包与原计算/退款规则保持。首轮51中50通过1原生无符号BigInteger/Long编号比较断言失败，修为Number.longValue后同方法完整复验1/1通过；去重51不同后端全过（新增9/原42），最终728前端（新增19）、微信/H5、115表初始化重放及旧凭证保留、287操作/975引用、92路由/54有序挂载/Compose config和原基线保持通过。协议/运维/设计/执行/验收、R8b2销项与R8b3计划已整理。R8b3经营/客户授权/价格监控、R8c及整R8门禁仍待完成，全部R8代码/迁移/测试/文档仅本地，无push/PR更新/合并/发布。

## R8b3a开始
已确认经营月度发生时间、收退款差额可负/非结算、正式测试分栏及冻结月结独立。按R8B3A_BUSINESS_DASHBOARD_DESIGN.md先独立只读经营看板，R8整体完成前无上传。

## R8b3a本地完成（2026-10-16）
用户已确认经营统计口径。V055默认关闭独立can_analytics、离线授权/撤销/审计和会话撤销、精确十权限位登录；三GET只读经营API、八项来源核验/正式测试未知、收退款各归成功月/负差额/应计非已结、独立冻结月结、运营和店长两实际页及登录/切号/隐藏迟到清理已完成。首轮65中62通过3已完成退款来源失败，补原完成凭证判定后定向完整3/3通过，合并去重65不同后端（新17/原48）；最终745前端（新17）、微信/H5、V055/115表重放和旧凭证保留、290操作/998引用、94路由/55SQL挂载/Compose config、Vue绑定及基线保持通过。API/运维/设计/执行/验收/索引当前状态已整理，下一R8B3B_CUSTOMER_AUTHORIZATION_NEXT_PLAN.md，再价格监控/R8c/整阶段门禁。全部R8源码/迁移/测试/文档仅本地，HEAD仍d4c7763，无push/PR更新/合并/发布。

## R8b3b开始（2026-10-16）
已对照原文：商家页面列有客户管理，但未定义联系字段、长期授权或保留期限。R7现成逐单逐条授权、源版本、撤销、技师当前接单和工单有效性可复用。建议先做店长本店最小服务关系汇总与受控订单入口，正式/TEST/UNKNOWN区分；不加长期档案授权或联系用途。已集中询问用户是否采用，答复前不实现依赖新字段/范围决定的代码。下一完成设计/后端/页面/真实MySQL与前端构建/文档。整个R8完成前无上传。

R8b3b用户已确认最小服务关系方案，开始后端/API、实际页面、权限/撤销验证及文档，不再请求同一方案许可。无新增迁移，仍115表；整R8完成前无上传。

## R8b3b本地完成（2026-10-16）
用户已确认店长本店最小服务关系、车型/核验服务时间及沿R7逐单逐条授权，不新增长期档案或联系用途。两GET按当前有效账号/车辆归属和本店付后/完成状态订单内部归并，最小有效订单锚点/来源TEST与UNKNOWN、无私人标识；两实际页/门店入口/精确登录回跳、切号/隐藏/跨关系迟到清理、原授权与工单失效复核已完成。首轮43全部通过（新11/继承32），最终接车合法原状态补强同方法定向1/1重编译通过，去重仍43；757前端（新12）、最终微信/H5、Vue绑定、292操作1009引用/96路由/55挂载、Compose config和原README/141追踪MD+CSV/44表init基线保持通过。无新迁移，仍115表，完整重放沿R8b3a证据，本步真实MySQL从V001–55初始化。API/操作/设计/执行/验收/索引当前状态和R8B3C_PRICE_MONITORING_NEXT_PLAN.md已整理。所有R8源码/迁移/测试/文档仅本地，HEAD仍d4c7763，无push/PR更新/合并/发布。

## R8b3c1开始（2026-10-16）
用户已确认具体报价版本的来源/内容单位车型适用确认、有效期与含边界区间、变更未知/偏离仅核对。按R8B3C1_PRICE_SOURCE_DESIGN.md先独立权限与来源配置，再c2比较。所有代码/迁移/测试/文档整R8完成前不上传。

## R8b3c1本地完成（2026-10-16）
用户确认具体门店报价版本、内容/单位/车型适用确认及来源/有效期/含边界区间，改版/失效未知，偏离仅需核对。先完成V056独立can_price_monitor及116表、13字段登录/离线授权、六API/真实当前报价版本与审计哈希、草稿CAS/发布冻结/停止/唯一发布、永久原创建键和审计回滚及实际配置/确认恢复页。首轮44中41通过3夹具污染失败（多生成审计、额外他店报价），隔离新测试夹具并保留原断言后完整26/26复验；合并去重44不同后端通过（新19/原25），最终771前端（新14）、微信/H5、实际Vue绑定、V056/116表重放/旧凭证保留/默认权限关闭、298操作1034引用/97路由/56SQL挂载/Compose config与原README/141追踪MD+CSV/44表init基线保持通过。遗留零/负价只可UNKNOWN不能确认，已补前端最终回归和双构建。API/操作/设计/执行/验收及索引当前状态已整理，下一R8B3C2_PRICE_COMPARISON_NEXT_PLAN.md沿已确认规则实施比较，当前comparison_available=false。全部R8源码/迁移/测试/文档仅本地，HEAD仍d4c7763，无push/PR更新/合并/发布。

## R8b3c2开始（2026-10-16）
沿已确认规则及R8B3C2_PRICE_COMPARISON_DESIGN.md，实施同一快照只读比较、未知原因/合法边界、原发布审计、列表详情及前端/后端验证；保持永久旧回执，当前能力标志兼容。无新迁移，仍116表，整R8完成前不上传。

## R8b3c2及R8b本地完成（2026-10-17）
最终完整真实库类38/38通过，合并原HTTP/无库去重45不同后端（新15/原30），最终785前端/微信/H5及300操作1044引用/99路由/56挂载/Compose config与原基线保持通过。原首轮2处失败及夹具修复、最后审计/软删除防回退均记录于验收；无新迁移，沿V056/116表。R8b3c及R8b销项已整理，下一R8C1A_EXPERIENCE_DETAIL_NEXT_PLAN.md。整R8完成前全部源码/迁移/测试/文档保留本地，HEAD仍d4c7763，无上传/PR更新/合并/发布。

## R8c1a本地完成（2026-10-17）
受控同款详情、共用来源/授权/审核和有效目录判断、只含公开UUID的应用内复制引用、AI摘要入口与精确登录返回、当前车辆车型和迟到/失效清理已实现。54不同后端（新9/原45）一次通过，最终796前端（新11）与微信/H5通过，301操作1048引用/99路由/56挂载和原基线保留通过；92份Markdown/707链接及diff检查通过。协议/操作/设计/执行/验收和R8C1B_COMMUNITY_NEXT_PLAN.md已整理，下一明确热度/问答处理角色/公开规则后实施；无新迁移，仍116表，全部R8本地无上传。


## R8剩余软件闭环最终验收（2026-10-17）
已完成用户确认的公开问答页面、独立图片副本治理和许可资料KEYWORD检索/AI引用。最终868前端、微信/H5构建、130表V061重复迁移及默认无正式语料/同意种子、默认can_knowledge关闭门禁通过；350操作/1247解析引用、108唯一页面、61有序SQL挂载、全部改动Markdown链接和原始基线保持通过。集中180相关后端通过；补充来源篡改/HTTP边界正在最终复验。README及API/运维/计划/执行/整阶段销项/本地验收已整理。本地Compose遭Docker Hub网络失败，不计成功；下一统一提交全部代码文档，最终六项远程CI通过后按既有授权合并。

最终补充9次边界复验通过（0失败/错误/跳过），重复车型测试数据已修正；远端main仍eeb5ccf，开始统一提交与PR验收。

2026-10-18复核结果：旧主工作区干净，所有业务分支提交已包含于origin/main；历史stash功能文件均已存在，两个本地开发者工具配置不纳入上传。1660份跟踪文本有限敏感模式检查无候选。只补齐Markdown交付状态，核对记录见docs/progress/REPOSITORY_PROGRESS_SYNC_2026_10_18.md。


## 2026-10-21 A4d-5 接口覆盖核对
- 用户继续授权规划后实施、逐阶段上传。PR195保留原分支与检查；本轮独立工具从origin/main创建codex/r9-a4d5-api-coverage。
- 设计：逐项核对当前OpenAPI、16套Postman清单/集合/历史报告及3套专用HTTP旅程；合并时去重，并保持当前验收NOT_RUN，不能将历史证据当成新执行。
- 初步清单：16套用例定义联合覆盖198项，其余154项缺这些清单覆盖；专用旅程尚需纳入，不能把154直接称作未开发功能。
- 任务：实现严格核对工具与可复跑输出→反伪造/缺失/漂移/去重测试→CI门禁→报告与下一补测队列→提交推送。

- A4d-5本地完成：19套定义/报告核对，352项契约中216项历史四类完整、136项所选来源缺口；新业务HTTP为0。新增19+既有186=205项Python测试通过，生成/--check/编译/diff通过。
- 修复跨平台哈希风险：Windows输入CRLF与Git/Linux LF不同，指纹统一LF、历史集合接受严格LF/CRLF字节形式；模拟两平台输出一致且内容变更拒绝。准备提交推送独立阶段分支。

- A4d-5完成并上传：e94cb60，origin/codex/r9-a4d5-api-coverage，草稿PR196。下一批支付/退款恢复与佣金六接口；本轮未执行这些接口的新增业务验收。

## 2026-10-21 A4d-6 金融恢复验收
- 已检查六接口及现有Java/数据库/渠道测试；复用已实现业务，新增隔离Postman+全迁移MySQL+本机签名供应商HTTP验证。
- 任务：合成夹具/八接口四类用例→签名供应商与运行器接入→脚本测试/默认禁用→真实隔离执行/数据库与调用数核对→覆盖总表/CI/报告→阶段上传。
- Docker29.6.2及MySQL/Maven镜像可用；默认沙箱访问Docker受限，升级执行权限后确认可用。

- 首轮实际运行74d043b386e6在SQL夹具阶段失败（尚未发业务HTTP）；独立隔离诊断确认order金额列误用了total_amount，当前表列为amount。已修正夹具，未改业务schema；两轮已建MySQL/网络均完成清理。准备新环境完整重跑。

- A4d-6完整重跑997d21cb4040通过（189.219秒）：8接口72业务HTTP/238断言+338匿名/1014断言，14DB检查及供应商固定调用数通过；源码仍匹配，后端/MySQL/网络已清理。默认禁用实测0请求；192项相关Python测试通过。
- 历史四类覆盖20套去重222项，所选缺口130项；全量API/正式资金/真机仍不销项。文档与证据已整理，准备阶段上传。一次将Java误放入Python语法检查的命令已纠正；Java单独编译和实际供应商启动均通过。


## R9 A4d7 施工查询与通知

1. 已完成：核对上阶段 PR197 的八项 CI，确定十个接口范围。
2. 实施：复用完整履约链，新增施工/接车读取及三端通知用例；校验实际列表 ID、已读幂等、事件唯一与接收人隔离。
3. 验证：独立 MySQL + 后端运行匿名及业务 HTTP，更新覆盖汇总和 CI。
4. 上传：独立分支提交、推送 GitHub 草稿 PR，明确依赖 PR197。


## R9 A4d7 验证完成

实施与本地验证已完成：新增十个接口，171 业务请求/567 断言、338 匿名请求/1014 断言通过；六种事件与16条通知、三条已读审计核对通过。202项相关脚本测试通过，覆盖232项、剩余120项。接下来提交并推送依赖 PR197 的草稿 PR。


## R9 A4d8 接车争议与恢复履约

1. 已核对 PR198 八项 CI 成功。
2. 实施独立 HTTP 套件：争议提出、两次商家处理、车主拒绝和接受、恢复派工至核销归档；补齐两个接口四类证据。
3. 检查幂等、非法输入、跨车主/商家、状态拦截、追加记录与通知来源唯一；独立数据库运行并接入 CI 和覆盖汇总。
4. 验证通过后提交、推送依赖 PR198 的 GitHub 草稿 PR。


## R9 A4d8 本地完成

实施和验证均完成：144业务请求/485断言、338匿名请求/1014断言全部通过；211项相关Python测试通过。争议四条追加记录、五条审计、唯一打开恢复、11条事件和26条通知均正确，恢复后订单完成且归档唯一。覆盖234项，所选历史证据缺口118项。接下来提交并上传依赖PR198的草稿PR。


## R9 A4d9 接车与施工私有附件访问

1. 复核四个附件接口与契约，确认缺口是真实存储下的四类证据。
2. 实施 Java 真实旅程：19次上传、接车、派工接单、防护、报工签字，再验证签名下载与权限矩阵。
3. 新增 Python 执行器与9项证据单测，接入 CI 构建证据、产物与覆盖汇总。
4. 本地运行通过并归档脱敏报告，提交、推送依赖 A4d-8 的草稿 PR。


## R9 A4d9 本地完成

282.656秒 PASS：39业务请求、4接口四类覆盖、0失败；19个CLEAN上传与14+5条附件关联核对；签名下载逐字节一致，去签名/篡改/过期均403；4容器与网络清理通过，回归17项通过。覆盖238/352项，缺口114项。接下来提交并上传依赖 A4d-8 的草稿 PR。


## R9 A4d10 考核政策、月结与经营看板读接口

1. 复核 20 个接口实现与契约，排除无映射的 `GET /api/admin/assessment`。
2. 新增 Newman 套件：固定月份字面量、运营双权限身份、门店店长/店员身份，20 接口各四类。
3. 新增 12 项证据单测，接入运行器 `--assessment-cases`、源码哈希、互斥校验、CI 与覆盖汇总。
4. 本地运行通过并归档脱敏报告，提交、推送依赖 A4d-9 的草稿 PR。


## R9 A4d10 本地完成

228.873秒 PASS：153业务请求/530断言/0失败，20接口四类齐备；匿名338请求通过且行数不变；7个幂等作用域、2组同键异体、13项数据库不变量全部为真。覆盖258/352项，缺口94项。接下来提交并上传依赖 A4d-9 的草稿 PR。
## R9 A4d11 本地完成

205.068秒 PASS：56业务请求/218断言/0失败，9接口四类齐备；匿名338请求通过且行数不变；6个幂等作用域、2组同键异体、14项数据库不变量全部为真（brand=2/standard_project=1/price_monitor_policy=1/idempotency=6/audit_log=7）；默认关闭开关时新套件0请求。覆盖267/352项，缺口85项。接下来提交并上传依赖 A4d-10 的草稿 PR。


## 2026-10-22 A4d12 计划

最新工作区已至PR202，八项CI成功，覆盖267/352，缺口85项；原拟附件阶段已完成，避免重复。
1. 补齐10项剩余履约接口：时段列表/关闭、可约时段、订单列表、技师列表/派工、商家/车主施工详情与核销凭证。
2. 在真实LOCAL_TEST预约到归档HTTP链插入四类用例，绑定种子日期与关闭意图。
3. 独立数据库运行、数据库唯一性与关闭终态、脚本回归、默认禁用和覆盖汇总/CI。
4. 提交并推送以PR202为基线的阶段草稿PR。


## A4d12 本地完成

最终d9cdc6989eac通过，213.765秒：170业务请求/569断言及338匿名请求/1014断言通过，12项DB检查通过且原业务行数不增。246项相关Python校验通过，最终空页增强后专用8项复跑通过；默认关闭零请求。累计277/352项有完整所选历史四类证据，缺口75项。准备提交并上传依赖PR202的草稿PR。


## 2026-10-22 A4d13 计划

基于PR203，补齐车型适配规则读写、方案目录与手动降级、车辆证据及预约上下文6接口，并用时段发布支撑。
1. UNKNOWN→适用→不适用→撤回→旧回执不复活，验证版本/权限/隐私投影。
2. 模型显式关闭，验证MANUAL及仅车主指定项目，不宣称外部AI验收。
3. 预约上下文以真实时段和报价读取，验证跨车主/项目和只读无订单副作用。
4. 独立数据库HTTP、脚本回归、默认关闭、覆盖汇总与CI；每阶段上传GitHub草稿PR。


## A4d13 本地完成

377fc68a422d运行219秒，70业务请求/237断言，338匿名请求/1014断言通过；9项DB检查及源文件一致、清理均通过。255项相关Python校验通过。累计283/352项所选历史四类证据，缺口69项。模型仅MANUAL降级验收，不计真实AI完成。准备提交推送依赖PR203的草稿PR。


## 2026-10-22 A4d14 计划

基于PR204，复用现有邀请奖励真实旅程，纳入8项剩余接口（总旅程14接口/144请求）。
1. 强化完成批次再次处理不发券、GET无发券副作用和空页内容检查。
2. 适配器按可信模块声明只要求自有MySQL清理，保留其他旅程五角色默认要求，拒绝伪造清理范围和非布尔标志。
3. 独立Docker/MySQL实际复跑、17项不变量、JUnit与Python回归、总表与文档。
4. 复用现有CI门禁，提交推送依赖PR204的草稿PR。


## A4d14 本地完成

281.36秒实际奖励旅程通过，144请求/14接口/17不变量，8项Java测试和75项Python校验通过。强化只读与重复处理断言，源码一致且MySQL实际移除。累计291/352项所选历史四类证据，缺口61项。准备提交并上传依赖PR204的草稿PR。


## 2026-10-22 A4d15 计划

基于 PR205，补齐社区图片11接口真实链路证据。
1. 原图与独立副本真实上传、扫描、PNG重编码与私有存储验证。
2. 真实同意、审核、拒绝、撤回、旧ID失效与父经验撤回；审计失败回滚。
3. 严格绑定响应ID与请求路径/正文，JUnit、资源清理和篡改拒绝回归。
4. 接入CI及历史覆盖汇总，验证通过后提交推送草稿PR。


## A4d15 本地完成

真实旅程258.203秒PASS：85图片业务请求、11接口四类证据、12不变量；8项Java和87项相关Python校验通过。源码一致且五类资源实际移除。所选历史覆盖302/352项，缺口50项；合成身份/前序支付和扫描库不计外部验收，PNG元数据剥离不等于自动视觉遮挡。CI门禁、报告及执行文档已完成，准备提交推送依赖PR205的草稿PR。


## 2026-10-22 A4d16 计划

基于PR206补齐商家客户关系列表/详情及运营价格比较列表/详情四接口证据。
1. 新建隔离MySQL/HTTP只读集合，合成历史订单、到店凭据和报价/政策证据。
2. 校验门店/权限/隐私、分页稳定锚点、真实到店依据及价格各状态保守判定。
3. 前后逐表内容摘要验证无读副作用，严格审计实际路由/查询/身份和空正文。
4. 真实Newman运行、定向回归、CI/历史覆盖与阶段GitHub草稿PR。


## A4d16 本地完成

236.968秒实际PASS，4接口58业务请求/216断言、338匿名请求/1014断言，13张业务表完整内容摘要无变化；源码稳定且自有后端/MySQL/网络清理。84项相关Python回归及新集合默认禁发0请求通过。历史覆盖306/352，缺口46；准备上传依赖PR206的草稿PR。


## 2026-10-22 A4d17 计划

基于PR207补齐券可领取、商家清单/草稿修订、运营券池清单4接口证据，复用创建/发布/入池/领取/券包支撑。
1. 真实HTTP草稿修订、版本/幂等重放、发布冻结、跨店与独立券权限。
2. 券池/门店渠道、未来生效、领取后隐藏、库存耗尽、退池与停用列表变化。
3. 完整读阶段内容摘要与最终库存/发布/审计不变量，严格请求正文/查询/身份/键核验。
4. 独立MySQL/Newman、回归、CI/历史汇总、阶段GitHub草稿PR。


## A4d17 本地完成

195.422秒真实PASS：9接口96业务请求/354断言、338匿名请求/1014断言；13项不变量及99项Python回归通过。额外4次列表读取所选11表完整内容摘要无变化，源码稳定且自有后端/MySQL/网络移除。历史覆盖310/352，缺口42；准备上传依赖PR207的阶段草稿PR。


## 2026-10-22 A4d18 计划

基于PR208补刷新、业务退出、运营退出3接口真实证据；车辆列表作为会话存活支撑。
1. 合成身份和已登录会话，不调用微信/短信；原始refresh只以哈希入库。
2. HTTP验证轮换、旧refresh拒绝、角色/应用/账号/绑定失效，退出后旧新访问及refresh拒绝。
3. 额外真实并发刷新两请求恰一成功，数据库确认轮换及失败回滚，其他会话不受影响。
4. 严格绑定响应token与后续请求但归档不含凭证，Python回归/CI/覆盖汇总与阶段草稿PR。


## A4d18 本地完成

214.766秒实际PASS：4接口45请求/149断言、338匿名/1014断言，13不变量及89项相关Python回归通过；额外7次HTTP验证并发刷新单次成功及退出失效。源码一致、资源清理且公开报告不含凭证。历史覆盖313/352，缺口39；准备上传依赖PR208的阶段草稿PR。


## 2026-10-22 A4d19 计划

补齐最后一个入驻读接口 GET /api/admin/merchant-applications/{id}/files/{file}/access 的四类证据，生产代码零变更。
1. 合成两位申请人、一家已开业门店与两个运营账号（can_onboard=1 / 仅 can_review=1），上传与申请提交走真实 HTTP。
2. 三张真实图片经真实 ClamAV 判 CLEAN；断言文件只归属申请人本人、快照精确、跨申请零引用、未激活门店。
3. 正常/边界/错误/未授权四类：跨申请文件、申请不存在、未知 query、id=0、匿名、权限位不足、车主、商家。
4. 签名下载逐字节一致并验证去签名/篡改/自然过期；执行器接入 CI 证据步骤、产物与覆盖汇总。
5. 归档报告、验收说明与规格文档；覆盖从 313/352 提升到 314/352，并上传阶段草稿 PR。


## A4d19 本地完成

307.395秒实际PASS：1接口12业务请求四类覆盖/0失败，11项检查全真；3次真实上传CLEAN且只归属申请人，两份申请快照精确、跨申请零引用且未激活门店；签名下载逐字节一致，去签名/篡改/6.1秒过期均403。源码一致且4容器与网络移除，同批回归15项Java与125项Python通过。历史覆盖314/352，缺口38。


## 2026-10-22 A4d20 计划

补齐最后一个可闭合缺口 GET /api/service/project/{id}/rating-quotes 的四类证据，并把剩余 38 项缺口按成因登记。
1. 逐条核对 38 项缺口的实现可达性，只对四类全可达的那一项造证据。
2. 只读 Newman 套件：18 用例四类齐备，夹具刻意包含下架、最新价 0、项目 status=0、跨区域四类"应被排除"的行。
3. 断言排序与评分（5.00/2.00/null）与分页/区域过滤；执行前后 14 张表内容摘要逐字节比对。
4. 契约对齐：把通用商家动作接口"无 200 路径"写进契约，不改生产行为。
5. 归档报告、验收说明与规格；覆盖 314/352 → 315/352，并上传阶段草稿 PR。


## A4d20 本地完成

201.923秒实际PASS：1接口18业务请求/67断言四类覆盖、338匿名请求/1014断言、14张业务表前后摘要不变；排序[1,3,2]与评分5.00/2.00/null如实，下架/最新价0/项目status=0均被排除。首轮因 extra 断言跨 pm.test 作用域复用 const 而 FAIL，改为每条自取响应后通过。历史覆盖315/352，缺口37。


## 2026-10-22 A4d21 计划

把"37 项证据缺口"改成"37 项有结论"，并做成可校验门禁。
1. 逐条查实现可达性，给出六类结论之一：已被替代/内部完成/外部阻塞/未实现/无成功路径/仅演示。
2. `CONTRACT_VERDICTS.json` 作为唯一结论源；`reconcile_contract.py` 对齐契约、覆盖结果与结论，生成 `CONTRACT_RECONCILIATION.md`。
3. 门禁双向 fail-closed：缺结论报错；结论落在已有证据的操作上也报错；替代必须在契约内且带实现标记。
4. 契约总数 352 与证据下限 315 写死；生成文件 `--check` 逐字节比对。
5. 修正 3 处已实现但未标记的契约元数据；覆盖口径保持不变（315/37），并写规格/执行记录后上传草稿 PR。


## A4d21 本地完成

352 = 315 有证据 + 37 有结论，未解释 0 项；分布 superseded=15 / internal-only=1 / blocked-external=10 / not-implemented=7 / no-success-path=1 / dev-only=3。门禁 11 项单测与覆盖 32 项单测通过，--check 逐字节一致；覆盖口径不变（仍 315/37）。顺带修正 3 处契约元数据（ai/chat 与辅助材料上传/访问），不改路径与行为。


## 2026-10-22 A4d22 计划

实现取车对比（R7a 遗留缺口），把结论表里一项 not-implemented 变成功能。
1. 设计只读投影：不写 delivery_compare、不改订单状态，交付仍由核销完成。
2. 服务聚合基线/防护/报工证据，算 READY|INCOMPLETE、deliverable 与逐项 missing。
3. 控制器严格正文 {order_id}、拒绝 query、六类错误码、单层信封 + no-store。
4. 契约补真实 schema 与状态码并移除幂等键；新增只读 Newman 套件 16 用例四类 + 9 项单测。
5. 数据库不变量证明只读（12 表摘要不变 + delivery_compare 0 行），归档报告与文档后上传草稿 PR。


## A4d22 本地完成

319.130秒实际PASS：16业务请求/60断言/1接口四类，338匿名/1014断言，13项不变量全真（12表摘要不变 + delivery_compare 0行）。缺口逐项算出（[OWNER_CONFIRM,PROTECTION_PHOTO,FINISH,FAULT]），已完成订单可回看不可交付，无接车单 409 43001。契约改真实 schema 并去掉幂等键要求。历史覆盖316/352，缺口36。


## 2026-10-22 A4d23 计划

补齐取车对比的车主端页面（R7a 剩下一半）。
1. 协议层：认证只读 POST、严格白名单 + 结论与缺口/交付标志/槽位数/报工状态自洽 + order_id 回指。
2. 展示层：结论、基线、完工、缺口、失败五组 view model，文案全部在此。
3. 页面：复用通用读取流程与车主会话，映射吃 state.value；入口只在待核销/已完成显示。
4. 单测 8 项 + 微信与 H5 两条构建；文案成对验证（wxml 无、编译后映射模块有）。
5. 写规格与执行记录，更新契约文档的页面状态，上传草稿 PR。


## A4d23 本地完成

小程序单测 1023/1023（新增 8 项），微信构建 DONE（原生样式与无测试身份桥 PASS），H5 构建 DONE。校验含结论与缺口一致、deliverable 依赖 READY、槽位数一致、报工状态自洽、order_id 回指请求订单。文案成对验证通过（wxml 无映射文案、编译后映射模块有）。接口与覆盖数字未变（316/352，缺口 36）。


## 2026-10-22 A4d24 计划

给商家侧补"核销前核对"，复用同一投影，不新增接口/页面。
1. 服务层登录提示改中性；展示层加角色化标题与结论解释，未知角色回落车主口径。
2. 页面支持 ?role=merchant 切门店会话；入口放商家订单详情矩阵（必带 role）与核销页顶部按钮。
3. 不在前端阻止核销，只报缺口；入口顺序排在核销之前。
4. 单测补角色文案与入口矩阵；微信/H5 两条构建；文案配对逐条核对。
5. 写规格与执行记录并上传草稿 PR。


## A4d24 本地完成

小程序单测 1024/1024（新增/更新 2 项）；微信与 H5 构建均 DONE；6 组商家/角色文案在编译后 wxml 为 0 次、映射模块里存在。不在前端阻止核销（后端守卫是唯一权威）。接口与覆盖数字未变（316/352，缺口 36）。


## 2026-10-23 A4d25 计划

工作区已推进至PR215，入驻附件与取车对比前后端均完成，不重复旧计划。当前基线7项CI成功，schema-and-ocr因生成OpenAPI覆盖手写修订而失败。
1. 复现并定位4个漂移操作：AI chat标记、受控材料上传/访问标记及取车对比完整契约。
2. 将现行语义移回生成源；增加纯build和非写入--check、CI提前检测与连续生成回归。
3. 本地重放schema-and-ocr全部检查，生成资产/覆盖/逐项结论均保持一致；每阶段上传草稿PR。
4. 下一功能阶段财务对账先明确本地记录、渠道账单缺失UNKNOWN及独立权限，不在本修复阶段伪造账单或宣称实现。


## A4d25 本地完成

修复4操作生成源漂移，新9项回归验证先失败后通过。schema-and-ocr完整85条命令/451测试通过（118.313秒），加认证就绪6项合计46套/457项Python测试。生成资产无Git规范内容差异，316/352历史覆盖与36项结论缺口不变。准备提交依赖PR215的草稿PR；财务对账留下一功能阶段，不计本轮交付。
