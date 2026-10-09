# A7.2c2 微信小程序经验审核与展示规格

日期2026-10-09；依赖PR #60 / 555c718。用户已授权开始下一步，并明确微信小程序为产品交付方向。依brainstorming流程核对既有代码并自检规格后直接实施。

## 入口与范围

车主同款经验、本人审核状态/重新授权/撤回均在 `apps/miniapp` 的 uni-app 微信页面交付；运营增加小程序独立登录和审核入口，使用独立会话与服务端权限，入口不授予身份。原Spec运营PC后台另列，本阶段不以PC或H5页面充当微信交付。H5仅辅助浏览测试，微信构建、原生/云托管请求和开发者工具可用性单独验证，真机未验证则明确保留。

选择复用现有JWT、数据库会话、bcrypt与共享限流，建立独立operator_account及审核权限；车主/商家身份不可充当运营。相比复用商家权限，独立账号避免跨角色授权；相比一次建设完整PC运营后台，本阶段限定卡片审核的小程序流程。

## 运营身份

运营账号通过无HTTP服务器的专用CLI离线创建/禁用/设置审核权限，没有注册或公开提权API、默认账号或内置密码。密码bcrypt，手机号用于二次校验，凭据仅受控环境输入，不写仓库/日志。短信验证码5分钟有效、一次消费、账号独立用途，与账号密码共同验证；发码60秒一次/每日最多5次，登录IP30次/15分钟、账号10次/15分钟。短信供应商未配置时发码503，禁止固定验证码或自动绕过。

登录事务包含账号复核、验证码消费、15分钟运营会话与最小登录审计；失败全部回滚。access JWT subject_type=operator_account、role=OPERATOR、app_id=operator-account，无商家/绑定声明，不发长期refresh。每次请求验证会话与账号一致/有效/未禁用；审核接口另检查can_review。退出撤销服务端会话。客户端仅内存保存token，不持久化密码、验证码或token。

## 审核与状态

EXPERIENCE_PUBLICATION_ENABLED默认false，先迁移V019并升级微信页面再显式开启审核/展示。本人撤回不依赖发布开关。

待审列表仅非测试、已明确授权PENDING_REVIEW；返回卡片id/revision、固定摘要、可信车型id或null，不提供车主/订单/档案id、原文、图片或签名。运营按UUID幂等+预期revision批准/驳回；操作前锁原订单、卡片，复核权限、状态、当前授权；批准还锁车辆并复核冻结施工来源/当前档案/真实核销付款与无争议。缺可信车型或来源失效不能批准，可驳回。

批准PENDING_REVIEW→PUBLISHED，revision+1，冻结有效车型id、随机公共UUID、审核人和时间。驳回→REJECTED，revision+1，理由仅固定码INSUFFICIENT_DETAIL/NOT_SUITABLE，无自由文字泄露。每个卡片/送审revision唯一只追加审核记录；动作/幂等响应/最小审计原子提交。旧revision/旧幂等键不得覆盖撤回或重新授权；重放复核权限与当前状态。

车主可撤回PUBLISHED/REJECTED，与原阶段同样清除有效授权并revision+1；REJECTED可重新勾选授权进入PENDING_REVIEW，新一轮独立审核。PUBLISHED重复授权不得重置审核，需先撤回。原档案/评价不可变。旧DRAFT/PENDING/WITHDRAWN DTO保持兼容，REJECTED仅增加固定review_reason；新状态需要新版微信页面。

## 同款经验读取与隐私

正式OWNER以本人vehicle_id查询同款已发布摘要；并非匿名互联网接口。单独公共DTO：experience_id（随机UUID）、title、summary、model_id、published_at，不暴露内部card/vehicle/order/archive/user/review/运营id。只同model_id有效车型，原车主仍拥有有效车辆，授权有效、审核revision匹配、冻结来源仍可信；无有效车型不推荐，测试永不展示，不进入商家评分/AI。本阶段只五字段事实，无原文、照片、身份或虚构维修效果。

游标分页按审核记录id倒序，每次扫描最多20个候选并复核来源，可能返回空页和下一游标；无候选时next_cursor=null。每次新请求实时过滤撤回、转移、删除、付款异常、争议、来源变化。全部no-store，前端不持久缓存，离页/换车/换账号清理，重入重查；与撤回并发的已开始请求可能完成原快照，下一次请求须隐藏。

## API

- POST /api/auth/operator/code：严格account/password；POST /login另含sms_code；POST /logout要求运营JWT，严格空JSON。
- GET /api/admin/experience-cards：page/page_size，审核权限；POST /api/admin/experience-cards/{id}/moderate：严格revision/decision/reason_code（批准null，驳回固定码），UUID，无query。
- GET /api/community/experiences：本人vehicle_id、可选cursor（安全正整数）。

400非法/未知/重复字段，401登录/会话失效，403角色/权限，404不可用，409状态/revision/来源或车型不可信，429限流，503依赖/开关/事务不可用。数字JS安全整数，严格JSON无重复键/尾随，全部响应no-store，错误不暴露凭据或SQL。

## 阶段与验收

规格→运营认证基础→审核/同款读取后端→微信页面→真实联调逐阶段上传堆叠PR，不合并生产。MySQL覆盖登录2FA一次消费/回滚/停用/权限即时失效、审核唯一/并发/撤回竞争、旧键、真实来源/车型/测试隔离、公共字段与撤回隐藏；MockMvc输入/身份、原生uni.request与wx.cloud.callContainer协议/生命周期、微信/H5构建及微信开发者工具产物检查、真实HTTP与gstack辅助验收。正式短信/微信付款/真正微信账号与iOS/Android真机不由合成数据代替。

退款取消争议、第二次异议、超时处理、图片公开、自由社区编辑/评论点赞、商家评分、AI推荐与完整运营管理仍独立规划。
