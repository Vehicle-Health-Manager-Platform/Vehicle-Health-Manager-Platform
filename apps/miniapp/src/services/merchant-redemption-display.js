/** 到店核销的纯展示映射：预约核销（六位码）与服务券核销（扫码/手填授权）。
 * 只读已校验的响应与本地表单，不改变"允许什么"——码的格式、幂等键、券的归属仍由
 * order-redemption.js / coupon-transactions.js 与后端判定。*/
import { stateLabel } from './order-status.js'
import { displayTime } from './reservations.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const list = v => (Array.isArray(v) ? v : [])
/** 金额只认契约里的 `^\d+\.\d{2}$` 字符串（reservations/coupon-transactions 的校验器同款）；
 * 不合法值退化成占位符，绝不 Number() 化后再拼——那样会把 0.1+0.2 的浮点尾巴显示给门店。 */
const money = v => (typeof v === 'string' && /^(?:0|[1-9]\d{0,7})\.\d{2}$/.test(v)) ? v
  : (typeof v === 'number' && Number.isFinite(v)) ? v.toFixed(2) : '—'

export const REDEEM_CODE_LENGTH = 6
/** 与 coupon-transactions.js 的 validServiceBody 同源：32 位小写十六进制。 */
export const COUPON_CODE_LENGTH = 32

/** 六位核销码：填了几位、还差几位、能不能提交。 */
export function redeemCodeView(code) {
  const digits = String(code ?? '').replace(/\D/g, '').slice(0, REDEEM_CODE_LENGTH)
  const complete = digits.length === REDEEM_CODE_LENGTH
  const missing = REDEEM_CODE_LENGTH - digits.length
  return {
    digits,
    complete,
    missing,
    tone: complete ? 'ok' : 'muted',
    hint: complete ? '码已填满，核对车主出示的六位数字后提交' : `还需 ${missing} 位数字`,
  }
}

/** 预约核销阶段。顺序即规则；`loading` 这一段修掉了"核销记录未读到时整块区域空白"。
 * `receipt` 是归一化后的核销状态 `{ loaded, redemption }`：`loaded` 来自读取流程，
 * `redemption` 可以是读取结果里的凭据，也可以是刚提交成功拿到的回执（两者字段同为
 * `{redeemed_at, test_mode}`），这样提交到重新读取之间的空档不会又冒出验码框。 */
export function redeemStageView(detail, receipt) {
  if (!detail) {
    return { stage: 'no-order', tone: 'muted', title: '订单尚未加载', copy: '正在读取订单与核销记录，如长时间无响应请刷新。' }
  }
  if (receipt?.redemption) {
    return { stage: 'redeemed', tone: 'ok', title: '已核销', copy: '本单已完成核销，页面只读。' }
  }
  const status = say(detail.status, '')
  if (status === 'PENDING_VERIFY' && receipt?.loaded !== true) {
    return { stage: 'loading', tone: 'muted', title: '正在核对核销记录', copy: '读到核销记录之前不显示验码框，避免对已核销的订单重复提交。' }
  }
  if (status === 'COMPLETED') {
    return { stage: 'history-no-record', tone: 'warn', title: '完成订单无可信核销记录', copy: '此历史完成订单没有可信核销记录，请人工核对收款凭据。' }
  }
  if (detail.has_payment_exception === true) {
    return { stage: 'blocked', tone: 'risk', title: '付款存在异常', copy: '付款存在异常，暂不可核销，请先核对付款记录。' }
  }
  if (status !== 'PENDING_VERIFY') {
    return { stage: 'not-now', tone: 'muted', title: '当前订单不能核销', copy: '请按订单进度完成前序步骤（接车、防护、派工、报工）后再核销。' }
  }
  return { stage: 'ready', tone: 'warn', title: '可以验码核销', copy: '请向车主核对六位核销码，确认车辆服务完成后提交。' }
}

/** 订单事实：金额按字符串透传，绝不 Number() 化。 */
export function redeemOrderFacts(detail) {
  const testMode = detail?.payment_summary?.test_mode === true
  return {
    project: say(detail?.project_snapshot?.project_name, '历史订单'),
    orderNoCopy: `订单号 ${say(detail?.order_no, '未提供')}`,
    amountCopy: `应付 ¥${money(detail?.amount_due)}`,
    statusLabel: stateLabel(detail?.status),
    testModeCopy: testMode ? '测试核销，未真实扣款，不可作为收款凭据' : '',
    exceptionCopy: detail?.has_payment_exception === true ? '付款存在异常，暂不可核销，请核对付款记录。' : '',
  }
}

/** 提交按钮：码没填满、冷却中、提交中三种情况各自说清楚。 */
export function redeemSubmitView(code, state, remaining) {
  const view = redeemCodeView(code)
  const cooldown = Math.max(0, Math.ceil(num(remaining)))
  const busy = state?.busy === true
  return {
    code: view,
    cooldown,
    busy,
    ready: view.complete && cooldown === 0 && !busy,
    label: cooldown > 0 ? `请等待 ${cooldown} 秒` : '验码并核销',
    hint: busy ? '正在提交核销…' : cooldown > 0 ? `尝试过于频繁，请 ${cooldown} 秒后再试` : view.hint,
  }
}

/** 核销凭据：测试核销与真实核销的标题、语气必须不同。
 * 入参是凭据行本身（`{redeemed_at, test_mode}`），页面负责从"读取结果"或"提交回执"里挑行——
 * 提交成功那一刻后端回执同样是合法凭据，不必等重新读取才显示。 */
export function redeemReceiptView(redemption) {
  const row = redemption && typeof redemption === 'object' ? redemption : null
  if (!row) return { exists: false, title: '', timeCopy: '', testModeCopy: '', tone: 'muted' }
  const testMode = row.test_mode === true
  return {
    exists: true,
    title: testMode ? '测试核销已完成' : '已核销',
    timeCopy: `核销时间 ${displayTime(row.redeemed_at)}`,
    testModeCopy: testMode ? '未真实扣款，不可作为收款凭据' : '',
    tone: testMode ? 'warn' : 'ok',
  }
}

/** 订单或核销记录读取失败：越权归"重新登录"，其余给重试。 */
export function redeemQueryFailureView(detail, receipt) {
  const kinds = [say(detail?.failureKind, ''), say(receipt?.failureKind, '')]
  return {
    message: say(detail?.message, '') || say(receipt?.message, ''),
    canRelogin: kinds.some(kind => kind === 'unauthorized' || kind === 'forbidden'),
    tone: 'risk',
    title: '订单未能核对',
  }
}

/** 服务券手填/扫码表单：三格各自是否有效，缺什么一次列全。 */
export function couponFormView(form) {
  const coupon = String(form?.coupon ?? '')
  const revision = String(form?.revision ?? '')
  const code = String(form?.code ?? '')
  const couponOk = /^[1-9]\d{0,15}$/.test(coupon)
  const revisionOk = /^[1-9]\d{0,15}$/.test(revision)
  const codeOk = new RegExp(`^[a-f0-9]{${COUPON_CODE_LENGTH}}$`).test(code)
  const missing = []
  if (!couponOk) missing.push('本人券编号')
  if (!revisionOk) missing.push('授权版本')
  if (!codeOk) missing.push(`${COUPON_CODE_LENGTH} 位授权码`)
  return {
    coupon, revision, code,
    couponOk, revisionOk, codeOk,
    missing,
    ready: missing.length === 0,
    codeProgress: `${code.length} / ${COUPON_CODE_LENGTH}`,
    hint: missing.length ? `还需填写：${missing.join('、')}` : '信息齐了，可以核对本店服务券与授权。',
  }
}

/** 预览卡：发行门店、项目、授权截止与"为什么不能核销"。
 * 券若已被核销，预览一定会带 `redemption`——此时不是"不可核销"，而是"已经核销过"。 */
export function couponPreviewView(preview) {
  if (!preview) return { exists: false, name: '', projectCopy: '', issuerCopy: '', canRedeem: false, tone: 'muted', blockedCopy: '', redeemedCopy: '' }
  const name = say(preview.terms?.name, '服务券')
  const projectCopy = `${say(preview.project_name, '项目未提供')} · 标准项目 ${preview.project_id ?? '—'}`
  const issuerCopy = `发行门店 ${preview.merchant_id ?? '—'} · 授权截止 ${displayTime(preview.authorization_expires_at)}`
  if (preview.redemption) {
    return {
      exists: true, name, projectCopy, issuerCopy,
      canRedeem: false,
      tone: 'ok',
      blockedCopy: '',
      redeemedCopy: `该券已于 ${displayTime(preview.redemption.redeemed_at)} 核销，凭据见下方。`,
    }
  }
  const canRedeem = preview.can_redeem === true
  return {
    exists: true,
    name,
    projectCopy,
    issuerCopy,
    canRedeem,
    tone: canRedeem ? 'warn' : 'risk',
    blockedCopy: canRedeem ? '' : '当前不可核销：请核对券的发行门店、标准项目与授权有效期是否与本店一致。',
    redeemedCopy: '',
  }
}

/** 已核销凭据：明确"佣金 0.00、不产生现金支付"。 */
export function couponSavedView(saved) {
  if (!saved) return { exists: false, title: '', lines: [], tone: 'muted' }
  return {
    exists: true,
    title: '服务券已核销',
    tone: 'ok',
    lines: [
      `${say(saved.terms?.name, '服务券')} · ${displayTime(saved.redeemed_at)}`,
      `核销凭据 ${saved.redemption_id ?? '—'} · 佣金 ¥${money(saved.commission_amount)}`,
      '此凭据不表示微信或现金收款。',
    ],
  }
}

/** 本店核销记录：空态与页脚。 */
export function couponRecordsCopy(records) {
  const items = list(records?.items)
  if (items.length === 0) {
    return { empty: true, emptyTitle: '暂无核销记录', emptyCopy: '核销成功后，凭据会按时间倒序列在这里。', footer: '' }
  }
  return { empty: false, emptyTitle: '', emptyCopy: '', footer: `本店核销记录 共 ${num(records?.total)} 条 · 已加载 ${items.length} 条` }
}

/** 冷却：限流后按秒提示，避免用户连点。 */
export function couponCooldown(tick, waitUntil) {
  const left = Math.max(0, Math.ceil((num(waitUntil) - num(tick)) / 1000))
  return { active: left > 0, seconds: left, label: left > 0 ? `请等待 ${left} 秒再核对` : '' }
}

/** 服务券操作失败：`kind` 字段沿用 coupon-transactions 的状态名（不是 failureKind）。 */
export function couponFailureView(state) {
  const kind = say(state?.kind, '')
  return {
    message: say(state?.message, ''),
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    uncertain: state?.uncertain === true,
    tone: 'risk',
  }
}

/** 两个按钮的可用性：核对要三格齐、核销要预览明确 can_redeem。 */
export function couponSubmitView(state, tick, form) {
  const formView = couponFormView(form)
  const cooldown = couponCooldown(tick, state?.waitUntil)
  const busy = state?.busy === true
  const locked = busy || cooldown.active || state?.uncertain === true
  return {
    form: formView,
    cooldown,
    busy,
    canPreview: formView.ready && !locked,
    canRedeem: state?.preview?.can_redeem === true && !locked,
    previewLabel: '核对本店服务券与授权',
    redeemLabel: '确认交付并核销服务券',
    retryLabel: '重试原核销请求',
  }
}
