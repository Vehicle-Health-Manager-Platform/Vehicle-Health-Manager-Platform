/** 商家券发行页的纯展示映射：券类型、库存、状态、行内动作、表单缺口、写入与分页。
 *
 * 只解释已经读到的数据，不改变"允许什么"——券规则、状态机、库存与幂等仍由 coupons.js 的
 * createCouponFlow / validCouponTerms 判定；这里的 `can*` 只是把同样的口径说给门店看。 */
import { couponStatus } from './coupons.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const list = v => (Array.isArray(v) ? v : [])
/** 与 coupons.js 的 decimal() 同源：最多 6 位整数 + 两位小数。 */
const DECIMAL = /^(?:0|[1-9][0-9]{0,5})\.[0-9]{2}$/
const cents = v => Number(String(v).replace('.', ''))

export const COUPON_TYPES = [
  { key: 'DISCOUNT', label: '金额优惠券', hint: '抵扣固定金额，须设置最低消费与发行库存' },
  { key: 'SERVICE', label: '指定项目服务兑换券', hint: '兑换指定项目，优惠与门槛固定为 0.00' },
]
export const COUPON_TYPE_LABELS = Object.fromEntries(COUPON_TYPES.map(row => [row.key, row.label]))
export const couponTypeLabel = value => COUPON_TYPE_LABELS[value] || '券类型未提供'

/** 类型选择器的行：与页面 picker 的顺序一致，不能按字母排序。 */
export function couponTypeRows(activeType) {
  return COUPON_TYPES.map((row, index) => ({ ...row, index, selected: row.key === activeType }))
}

/** 单张券的事实与可执行动作。`DRAFT` 才能改、才能发布；`PUBLISHED` 只能停止新领取。 */
export function couponIssueRowView(row) {
  const status = say(row?.status, '')
  const issuance = num(row?.terms?.total_quantity)
  const stockLeft = num(row?.stock_left)
  const base = {
    couponId: num(row?.coupon_id),
    typeLabel: couponTypeLabel(row?.terms?.type),
    statusLabel: couponStatus[status] || '状态未提供',
    tone: status === 'PUBLISHED' ? 'ok' : status === 'STOPPED' ? 'muted' : 'warn',
    stockCopy: `剩余 ${stockLeft} / 发行 ${issuance}`,
    revisionCopy: status === 'DRAFT' ? `草稿版本 ${num(row?.revision)}` : `发布版本 ${num(row?.publication_revision)}`,
    actions: [],
    noteCopy: '',
  }
  if (status === 'DRAFT') {
    return {
      ...base,
      actions: [
        { key: 'update', action: 'update', label: '修改草稿' },
        { key: 'publish', action: 'status', label: '发布并冻结条款', target: 'PUBLISHED' },
      ],
      noteCopy: '草稿可以反复修改；发布后条款冻结，不能撤回。',
    }
  }
  if (status === 'PUBLISHED') {
    return {
      ...base,
      actions: [{ key: 'stop', action: 'status', label: '停止新领取', target: 'STOPPED' }],
      noteCopy: '停止新领取不影响已领取的权益。',
    }
  }
  if (status === 'STOPPED') {
    return { ...base, noteCopy: '已停止新领取：已领取的券仍按原条款使用。' }
  }
  // 未知状态一律不给动作，避免把不认识的枚举当成可发布。
  return { ...base, noteCopy: '当前状态无法在本页操作，请刷新后核对。' }
}

/** 列表区：忙碌 / 空 / 已加载三态，空态文案与是否店长无关（本页只有本店券）。 */
export function couponIssueEmptyView(state) {
  const rows = list(state?.rows)
  if (state?.busy === true) return { visible: false, title: '', copy: '', tone: 'muted' }
  if (state?.loaded !== true) return { visible: false, title: '', copy: '', tone: 'muted' }
  if (rows.length > 0) return { visible: false, title: '', copy: '', tone: 'muted' }
  return {
    visible: true,
    title: '本店还没有新发行券',
    copy: '先创建券草稿，核对类型、项目、金额与库存后再发布；发布即冻结条款。',
    tone: 'muted',
  }
}

/** 分页：与 coupons.js 的 page_size=20 同源，且写入挂起时不允许翻页。 */
export function couponIssuePagingView(state) {
  const page = num(state?.page, 1)
  const total = num(state?.total)
  const locked = state?.busy === true || state?.retryWrite === true
  const canPrev = !locked && page > 1
  const canNext = !locked && page * 20 < total
  return {
    page,
    total,
    pageCopy: `第 ${page} 页 · 共 ${total} 张`,
    canPrev,
    canNext,
    // 只有一页时整块翻页区不出：`hidden` 只由"有没有别的页"决定，不受锁影响。
    hidden: page <= 1 && page * 20 >= total,
  }
}

/** 写入结果：成功是 ok，失败要区分"能不能重新登录"与"要不要用原请求重试"。 */
export function couponIssueWriteView(state) {
  const kind = say(state?.kind, '')
  const message = say(state?.message, '')
  const retryWrite = state?.retryWrite === true
  return {
    message,
    tone: message ? (kind ? 'risk' : 'ok') : 'muted',
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    retryWrite,
    retryCopy: retryWrite ? '原操作结果尚未确认：请用原请求重试，不要改动表单另建一张券。' : '',
  }
}

/** 表单缺口：与 validCouponTerms 的口径一致，但只用于提示，不替代服务端校验。 */
export function couponTermsFormView(form, dates, projects) {
  const type = say(form?.type, 'DISCOUNT')
  const name = String(form?.name ?? '')
  const amount = String(form?.amount ?? '')
  const minSpend = String(form?.min_spend ?? '')
  const quantity = String(form?.total_quantity ?? '')
  const projectId = num(form?.project_id)
  const missing = []

  if (!name.trim()) missing.push('券名称')
  else if ([...name].length > 128) missing.push('券名称（不超过 128 字）')
  if (!projectId) missing.push('在售标准项目')
  if (type === 'DISCOUNT') {
    if (!DECIMAL.test(amount) || cents(amount) <= 0) missing.push('优惠金额（两位小数，且大于 0）')
    else if (!DECIMAL.test(minSpend) || cents(minSpend) < cents(amount)) missing.push('最低消费（两位小数，且不小于优惠金额）')
  }
  if (!/^[1-9][0-9]{0,9}$/.test(quantity)) missing.push('发行库存（正整数）')
  if (!say(form?.valid_from, '')) missing.push('生效时间')
  if (!say(form?.valid_to, '')) missing.push('截止时间')
  const from = say(form?.valid_from, '')
  const to = say(form?.valid_to, '')
  const ordered = from && to && Date.parse(from) < Date.parse(to)
  if (from && to && !ordered) missing.push('有效期（截止须晚于生效）')

  const waiting = list(projects?.items).filter(row => row?.available === true && row?.status === 1)
  return {
    type,
    typeLabel: couponTypeLabel(type),
    isService: type === 'SERVICE',
    missing,
    ready: missing.length === 0,
    dateCopy: dates?.fromDate && dates?.toDate
      ? `${dates.fromDate} ${dates.fromTime || '00:00'} → ${dates.toDate} ${dates.toTime || '00:00'}（北京时间）`
      : '有效期按北京时间填写，两项都要选',
    hint: missing.length ? `还需填写：${missing.join('、')}` : '信息齐了，可以保存草稿。',
    projectOptions: waiting.length,
    projectHint: waiting.length ? '' : '本店暂无可选的在售项目，请先在「本店服务与报价」上架项目。',
    // 服务券的优惠与门槛恒为 0.00，页面据此隐藏两个输入框。
    amountLocked: type === 'SERVICE',
  }
}

/** 项目选择器：只能选本店有效且状态为 1（在售）的项目，与页面 choose() 同源。 */
export function couponProjectOptionRows(projects) {
  return list(projects?.items).map((row, index) => ({
    index,
    projectId: num(row?.standard_project_id),
    label: say(row?.project_name, '项目未命名'),
    selectable: row?.available === true && row?.status === 1,
  }))
}

/** 编辑态初值：把 UTC 条款时间拆回四个本地输入框。 */
export function couponDateFields(terms, localTime) {
  const from = typeof localTime === 'function' ? localTime(terms?.valid_from) : ''
  const to = typeof localTime === 'function' ? localTime(terms?.valid_to) : ''
  return {
    fromDate: from.slice(0, 10),
    fromTime: from.slice(11),
    toDate: to.slice(0, 10),
    toTime: to.slice(11),
  }
}
