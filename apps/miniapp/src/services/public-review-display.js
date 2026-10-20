/** 评价与公开展示面的纯展示映射：状态 → 文案/色调，绝不把 null/undefined 直接渲染到页面。
 * 与 service 层无关：只吃已校验过的响应对象，任何字段缺失都退化为可读文案而不是抛错。 */
const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const clamp = (v, min, max) => Math.min(max, Math.max(min, v))

/** 徽标色调 → design-system 的 ux-badge-* 后缀。 */
export const TONE_CLASS = { ok: 'ux-badge-ok', warn: 'ux-badge-warn', risk: 'ux-badge-risk', muted: 'ux-badge-muted' }
export const toneClass = tone => TONE_CLASS[tone] || TONE_CLASS.muted

/** 公开星级头部读数：空样本给破折号而不是 null/NaN。 */
export function ratingHeadline(summary) {
  const count = num(summary?.count)
  if (count <= 0) return { value: '—', unit: '', caption: '暂无有效公开评价', tone: 'muted' }
  return { value: say(summary?.average_rating, '—'), unit: '/ 5', caption: `${count} 条有效公开评价`, tone: 'ok' }
}

/** 单条星级：分数与 UTC 日期，附条形宽度用于视觉分级。 */
export function ratingRow(item) {
  const score = clamp(Math.round(num(item?.rating)), 0, 5)
  return { score, date: say(item?.submitted_date, '日期未提供'), bar: `${score * 20}%`, label: `${score} / 5 分` }
}

/** 星级授权状态。测试订单与来源不合格必须分开说，撤销入口两种都要在。 */
export function publicationView(state) {
  if (!state) return { label: '正在核对', tone: 'muted', copy: '正在核对公开授权状态。', action: null }
  if (state.can_publish === false) {
    const test = state.unavailable_reason === 'TEST_ORDER'
    return {
      label: test ? '测试订单不可公开' : '来源未通过核对', tone: test ? 'muted' : 'risk',
      copy: test ? '测试评价不计入商家评分，也不会向其他车主展示。' : '本次履约或资金来源不满足公开条件，暂不能授权；你仍可撤销已有授权。',
      action: state.consented ? '撤销公开授权' : null,
    }
  }
  if (state.consented) return { label: '已公开', tone: 'ok', copy: '评分与评价日期正在向登录车主展示，并计入平均评分。', action: '撤销公开授权' }
  return { label: '未公开', tone: 'muted', copy: '评分与评价日期仅本人可见；授权后才会公开。', action: '单独授权公开星级' }
}

/** 公开文字状态：待审/获批未展示/已驳回/已撤销/功能未开启必须各自可辨。 */
export function textStatusView(state, reasons = {}) {
  if (!state) return { label: '正在核对', tone: 'muted', copy: '正在核对公开文字状态。', action: null }
  if (state.can_submit === false && state.unavailable_reason === 'DISABLED') return { label: '功能未开启', tone: 'muted', copy: '公开文本投稿与审核尚未开启。已授权文字仍可撤销。', action: null }
  if (state.can_submit === false) return { label: '来源未通过核对', tone: 'risk', copy: '本次履约或资金来源不满足公开条件，暂不能投稿；已授权文字仍可撤销。', action: null }
  const status = say(state.status, 'NONE')
  if (status === 'APPROVED' && state.visible) return { label: '已公开', tone: 'ok', copy: '经人工审核的脱敏文字正在门店页展示。', action: '撤销文字授权并清除' }
  if (status === 'APPROVED') return { label: '已批准但停止展示', tone: 'warn', copy: '文字已获批，但当前来源核对不再通过，已停止展示。', action: '撤销文字授权并清除' }
  if (status === 'PENDING') return { label: '待审核', tone: 'warn', copy: '已提交，审核通过前不会展示给任何人。', action: '撤销文字授权并清除' }
  if (status === 'REJECTED') return { label: '已驳回', tone: 'risk', copy: `审核未通过：${say(reasons[state.reason_code], '可修改后重新投稿')}。`, action: null }
  if (status === 'WITHDRAWN') return { label: '已撤销', tone: 'muted', copy: '已停止公开并清除展示文字；原私人评价不变，可重新投稿。', action: null }
  return { label: '尚未投稿', tone: 'muted', copy: '独立公开文字需要单独编写、预览脱敏并授权审核。', action: null }
}

/** 字数计量：按码点，超限单独标记，供输入框旁的计数器使用。 */
export function draftMeter(value, limit = 300) {
  const count = typeof value === 'string' ? [...value].length : 0
  return { count, limit, over: count > limit, text: `${count} / ${limit} 字`, empty: count === 0 }
}

/** 公开文字列表的空态与页脚：区分"没有内容"和"还没加载"。 */
export function textListCopy(list) {
  if (!list) return { empty: false, emptyTitle: '', emptyCopy: '', footer: '' }
  const total = num(list.total)
  if (total === 0) return { empty: true, emptyTitle: '暂无公开文字', emptyCopy: '需要车主单独授权、经规则脱敏与人工审核，且当前来源核对通过才会出现在这里。', footer: '' }
  return { empty: false, emptyTitle: '', emptyCopy: '', footer: `共 ${total} 条有效公开文字 · 日期为 UTC 日，不代表平台质量认证` }
}

/** AI 用途授权：文字改版后授权自动失效，必须能看出来。 */
export function consentStatusView(state) {
  if (!state) return { label: '正在核对', tone: 'muted', copy: '正在核对 AI 用途授权。', action: null }
  if (state.can_consent === false) return { label: '无可授权文字', tone: 'muted', copy: '需要先有一条已获批的公开文字，才能授权 AI 用途。', action: null }
  if (state.effective) return { label: '已授权且有效', tone: 'ok', copy: '当前批准的这段文字可用于生成要点；改稿后需重新授权。', action: '撤销 AI 用途授权' }
  if (state.consented) return { label: '授权已失效', tone: 'warn', copy: '文字版本已变化，旧授权不再生效；要点不会使用这段文字。', action: '按当前文字重新授权' }
  return { label: '未授权', tone: 'muted', copy: '不授权则要点生成不会使用你的文字。', action: '授权用于生成要点' }
}

/** 要点生成结果：五种状态各自说清"为什么是这个结果"。 */
export function summaryView(summary) {
  const status = say(summary?.status, 'EMPTY')
  if (status === 'AI_READY') return { title: 'AI 选取的完整原句', tone: 'ok', copy: '模型只能逐字复制候选原句，且已核对来源仍有效。' }
  if (status === 'FALLBACK') return { title: '原文摘录（未采用 AI 输出）', tone: 'warn', copy: '本次 AI 输出未通过校验，改用按句首摘录，内容仍逐字来自授权文字。' }
  if (status === 'SOURCE_CHANGED') return { title: '来源已变化，本次结果作废', tone: 'risk', copy: '生成期间授权或来源发生变化，旧内容已丢弃，请重新生成。' }
  if (status === 'DISABLED') return { title: '评价要点尚未开启', tone: 'muted', copy: '该功能当前未对生产开放，可返回查看公开文字与星级。' }
  return { title: '暂无可用于要点的文字', tone: 'muted', copy: '需要已获批且另行授权 AI 用途的公开文字才会出现要点。' }
}

/** 要点样本说明：只覆盖最近 20 条，不能说成"全部评价"。 */
export function summarySampleCopy(summary) {
  const sample = num(summary?.sample_count), eligible = num(summary?.eligible_count)
  if (sample <= 0) return ''
  return `本次候选 ${sample} 条 · 符合 AI 用途的文字 ${eligible} 条 · 仅覆盖最近 20 条`
}

/** 引用来源兜底：文字被撤回后来源会缺失，页面不能渲染 undefined。 */
export function quoteSource(sources, publicId) {
  const row = Array.isArray(sources) ? sources.find(s => s && s.public_id === publicId) : null
  if (!row) return { public_text: '', published_date: '来源已不可见', missing: true }
  return { public_text: say(row.public_text, ''), published_date: say(row.published_date, '日期未提供'), missing: false }
}

/* ———————————————— 运营审核台 ————————————————
 * 审核台与车主端面对同一批授权数据，但读者不同：运营要快速判断"能不能批、为什么不能批"。
 * 这里只做纯映射，页面不再自己拼状态文案，权限过滤也不写在模板里。 */

/** 审核台入口分组：组名与顺序固定，空组不下发。 */
export const OPERATOR_GROUPS = [
  { key: 'content', title: '内容与授权审核', note: '每条都要人工检查隐私与内容，规则不能替代人工判断。' },
  { key: 'queue', title: '我的处理工作区', note: '只列出分配给我的处理任务。' },
  { key: 'operate', title: '经营配置', note: '改动会立即影响车主端可见的目录、价格与券池。' },
  { key: 'read', title: '只读与结算', note: '只读查看，不改变业务状态。' },
]

/** 入口清单：can 可以是单个权限键，也可以是"任一满足"的数组。 */
const OPERATOR_ENTRIES = [
  { group: 'content', key: 'knowledge', label: '独立许可资料管理', note: '维护可被逐字引用的许可资料', url: '/pages/operator/knowledge', can: 'canKnowledge' },
  { group: 'content', key: 'public-images', label: '独立脱敏图片审核', note: '只看车主单独授权的脱敏图片', url: '/pages/community/public-images?mode=review', can: 'canReview' },
  { group: 'content', key: 'public-questions', label: '公开问答脱敏摘要审核', note: '审核问答对外的脱敏摘要', url: '/pages/community/public-questions?mode=review', can: 'canReview' },
  { group: 'content', key: 'review-texts', label: '公开评价文字审核', note: '只查看脱敏文字，批准即公开确切原文', url: '/pages/operator/review-texts', can: 'canReview' },
  { group: 'content', key: 'curations', label: '同款经验运营精选', note: '从已授权经验里挑选同款案例', url: '/pages/operator/experience-curations', can: 'canCommunityCurate' },
  { group: 'queue', key: 'diagnosis-cases', label: '诊断处理工作区', note: '受理与处理人工诊断任务', url: '/pages/operator/diagnosis-cases', can: ['canDiagnose', 'canDiagnoseManage'] },
  { group: 'queue', key: 'questions', label: '我的私有问答处理', note: '只含分配给我的问答', url: '/pages/operator/questions?scope=mine', can: 'canCommunityAnswer' },
  { group: 'operate', key: 'catalog', label: '标准项目与车型库维护', note: '标准项目、车型库与适用性', url: '/pages/operator/catalog', can: 'canCatalog' },
  { group: 'operate', key: 'diagnosis-service', label: '诊断受理配置与分配', note: '受理规则、分配与转派', url: '/pages/operator/diagnosis-service', can: 'canDiagnoseManage' },
  { group: 'operate', key: 'question-service', label: '私有问答受理与分配', note: '受理策略与处理者分配', url: '/pages/operator/question-service', can: 'canCommunityManage' },
  { group: 'operate', key: 'coupons', label: '运营精选券池', note: '券池与兑换库存', url: '/pages/operator/coupons', can: 'canCoupon' },
  { group: 'operate', key: 'points-policies', label: '积分任务规则', note: '任务与发放规则', url: '/pages/operator/points-policies', can: 'canPoints' },
  { group: 'operate', key: 'points-exchange', label: '积分兑换目录', note: '兑换项与库存', url: '/pages/operator/points-exchange', can: 'canPoints' },
  { group: 'operate', key: 'invite-policies', label: '邀请奖励规则配置', note: '奖励口径与月度资格', url: '/pages/operator/invite-policies', can: 'canInvite' },
  { group: 'operate', key: 'diagnosis-policies', label: '人工诊断邀请条件', note: '邀请条件与门槛', url: '/pages/operator/diagnosis-policies', can: 'canInvite' },
  { group: 'operate', key: 'onboarding', label: '商家入驻审核', note: '资质与门店信息核对', url: '/pages/operator/onboarding', can: 'canOnboard' },
  { group: 'read', key: 'price-comparisons', label: '只读价格比较', note: '各门店同项目报价对比', url: '/pages/operator/price-comparisons', can: 'canPriceMonitor' },
  { group: 'read', key: 'price-policies', label: '价格监控来源依据', note: '监控口径与来源留档', url: '/pages/operator/price-policies', can: 'canPriceMonitor' },
  { group: 'read', key: 'business-dashboard', label: '门店经营看板', note: '聚合指标，不含车主明细', url: '/pages/operator/business-dashboard', can: 'canAnalytics' },
  { group: 'read', key: 'monthly-assessment', label: '门店月度结算与历史', note: '考核结算与历史快照', url: '/pages/operator/monthly-assessment', can: 'canAssessment' },
  { group: 'read', key: 'assessment-policies', label: '门店月度考核政策', note: '考核口径配置', url: '/pages/operator/assessment-policies', can: 'canAssessment' },
]

const granted = (session, need) => (Array.isArray(need) ? need : [need]).some(key => session?.[key] === true)

/** 按权限把入口分到固定分组；没有可用入口的组整组不出现。 */
export function operatorEntryGroups(session) {
  const visible = OPERATOR_ENTRIES.filter(entry => granted(session, entry.can))
  return OPERATOR_GROUPS.map(group => ({
    key: group.key, title: group.title, note: group.note,
    entries: visible.filter(entry => entry.group === group.key).map(({ key, label, note, url }) => ({ key, label, note, url })),
  })).filter(group => group.entries.length > 0)
}

/** 权限摘要：说清"为什么只看到这些入口"，避免被当成权限缺失或越权。 */
const CAPABILITY_LABELS = [
  ['canReview', '内容审核'], ['canKnowledge', '许可资料'], ['canCatalog', '目录维护'],
  ['canDiagnose', '诊断处理'], ['canDiagnoseManage', '诊断配置'], ['canCommunityManage', '问答受理'],
  ['canCommunityAnswer', '问答答复'], ['canCommunityCurate', '经验精选'], ['canCoupon', '券池'],
  ['canInvite', '邀请奖励'], ['canPoints', '积分规则'], ['canAssessment', '月度考核'],
  ['canAnalytics', '经营看板'], ['canPriceMonitor', '价格监控'], ['canOnboard', '商家入驻'],
]
export function operatorCapabilitySummary(session) {
  const keys = CAPABILITY_LABELS.filter(([key]) => session?.[key] === true).map(([, label]) => label)
  if (keys.length === 0) return { granted: keys, tone: 'risk', copy: '当前账号没有运营权限，请联系管理员开通后再使用审核台。' }
  return { granted: keys, tone: 'ok', copy: `${keys.length} 项权限；未授权的入口不会出现在下方，避免点进去才发现无权限。` }
}

/** 待审文字行：能不能批准必须写在行内，而不是让运营点一次才发现。 */
export function moderationCardView(item) {
  const approve = item?.can_approve === true
  const version = num(item?.version)
  return {
    approve,
    label: approve ? '可批准' : '来源已失效',
    tone: approve ? 'ok' : 'risk',
    versionCopy: version > 0 ? `第 ${version} 版` : '版本未知',
    copy: approve ? '批准即公开这段确切文字，不能代改；驳回则车主修改后需重新授权。' : '来源核对不再通过，只能驳回；车主重新授权后才会回到待审。',
  }
}

/** 待审列表的空态与页脚：区分"真的没有"和"还没加载"。 */
export function moderationConsoleCopy(list) {
  if (!list || !Array.isArray(list.items)) return { empty: false, pending: 0, loaded: 0, blocked: 0, emptyTitle: '', emptyCopy: '', footer: '' }
  const pending = num(list.total), loaded = list.items.length, blocked = list.items.filter(item => item?.can_approve !== true).length
  if (loaded === 0) return { empty: true, pending, loaded, blocked, emptyTitle: '当前没有待审文字', emptyCopy: '只有车主单独投稿、且来源核对通过的脱敏文字才会进入这里。', footer: '' }
  return { empty: false, pending, loaded, blocked, emptyTitle: '', emptyCopy: '', footer: `待审 ${pending} 条 · 本页 ${loaded} 条${blocked > 0 ? ` · 其中 ${blocked} 条来源已失效只能驳回` : ''}` }
}

/** 驳回理由选择器：code 与可读文案分离，模板只渲染文案。 */
export function reasonPicker(reasons = {}) {
  const codes = Object.keys(reasons)
  return { codes, labels: codes.map(code => say(reasons[code], code)), placeholder: '请选择驳回理由' }
}
export function reasonLabel(code, reasons = {}) {
  return typeof code === 'string' && Object.hasOwn(reasons, code) ? say(reasons[code], code) : ''
}

/** 待审经验摘要卡：把"能不能批准"的事实（车型）与摘要事实分开说。 */
export function operatorCardFacts(card) {
  const summary = card?.summary || {}
  const approvedModel = Number.isSafeInteger(card?.model_id) && card.model_id > 0
  return {
    title: say(card?.title, '未命名经验卡片'),
    revisionCopy: `第 ${num(card?.revision)} 次修订`,
    month: say(summary.recorded_month, '月份未提供'),
    work: `实际工时 ${num(summary.work_minutes)} 分钟`,
    parts: summary.no_parts === true ? '本次未使用配件' : `使用 ${num(summary.part_kinds)} 种配件`,
    model: approvedModel ? `有效车型编号 ${card.model_id}` : '缺少有效车型，不能批准',
    canApprove: approvedModel,
    tone: approvedModel ? 'ok' : 'risk',
  }
}

