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
