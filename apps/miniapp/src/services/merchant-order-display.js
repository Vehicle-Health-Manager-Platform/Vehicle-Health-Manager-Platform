/** 商家订单阅读面的纯展示映射：状态 → 文案/色调/入口，绝不把枚举或 null 直接渲染到页面。
 * 只吃已校验过的响应对象；字段缺失退化为可读文案，不抛错。
 * 服务端仍是唯一权威：这里不判断"能不能操作"，只决定展示哪些既有入口与怎样解释原因。 */
import { stateLabel, closeReasonLabel } from './order-status.js'
import { displayTime } from './reservations.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const moneyText = v => (typeof v === 'string' && /^(0|[1-9]\d{0,7})\.\d{2}$/.test(v) ? v : '金额未提供')

/** 状态色调：只有"需要人动手"和"出问题"两种状态给强色，其余用中性，避免满屏绿。 */
const STATUS_TONE = { PENDING_PAYMENT: 'warn', PAID: 'ok', RECEIVED: 'ok', IN_SERVICE: 'ok', PENDING_VERIFY: 'warn', COMPLETED: 'muted', CLOSED: 'muted', DISPUTED: 'risk' }
const STATUS_COPY = {
  PENDING_PAYMENT: '等待车主支付；到期未付会自动关闭，未支付不要接车。',
  PAID: '已收款待接车检查；接车单提交后才能派工。',
  RECEIVED: '已接车；车主确认后可派工给本店已绑定微信的技师。',
  IN_SERVICE: '施工中；完工报工后才能送核销。',
  PENDING_VERIFY: '待核销；核销完成后本单结束。',
  COMPLETED: '已完成，仅可查看记录与核销结果。',
  CLOSED: '已关闭；如涉及退款请看退款状态，不要重复收取。',
  DISPUTED: '存在争议，派工与施工已阻断；是否解除由车主复核决定，商家不能单方面恢复。',
}

/** 订单状态：label 复用 order-status.js 的唯一文案表，这里只补色调与解释。 */
export function merchantOrderStatusView(status) {
  const key = say(status, '')
  return {
    label: stateLabel(key),
    tone: STATUS_TONE[key] || 'muted',
    copy: STATUS_COPY[key] || '状态未提供，请刷新后重试。',
  }
}

const PAYMENT_LABELS = { CREATED: '已创建待支付', PENDING: '支付处理中', SUCCEEDED: '支付成功', FAILED: '支付失败', CLOSED: '支付已关闭' }
const PAYMENT_TONES = { CREATED: 'warn', PENDING: 'warn', SUCCEEDED: 'ok', FAILED: 'risk', CLOSED: 'muted' }

/** 支付摘要：渠道与"是否需要人工核对"必须同时说清，测试渠道不能当作收款凭据。 */
export function merchantPaymentView(summary) {
  if (!summary) return { label: '未发起支付', tone: 'muted', copy: '本单还没有支付记录，请先确认车主是否已付款。', testMode: false }
  const status = say(summary.status, '')
  const testMode = summary.test_mode === true
  const review = summary.requires_review === true
  return {
    label: PAYMENT_LABELS[status] || '支付状态未提供',
    tone: review ? 'risk' : (PAYMENT_TONES[status] || 'muted'),
    copy: testMode ? '测试渠道，未真实扣款，不能作为收款凭据。' : review ? '存在需要人工核对的支付异常，请先核对再操作。' : '支付结果以服务端记录为准。',
    testMode,
  }
}

/** 付款异常提示：原文案被端到端流程断言过，不要改字。 */
export const paymentExceptionCopy = row => (row?.has_payment_exception === true ? '本订单存在付款异常，待核对' : '')

/** 列表行：订单号之外，商家真正要扫的是"什么项目、什么状态、多少钱、什么时候"。 */
export function merchantOrderRow(row) {
  const status = merchantOrderStatusView(row?.status)
  return {
    title: say(row?.project_snapshot?.project_name, '历史订单'),
    statusLabel: status.label,
    statusTone: status.tone,
    statusCopy: status.copy,
    amount: `¥${moneyText(row?.amount_due)}`,
    appointment: `预约 ${displayTime(row?.appointment_snapshot?.starts_at)}`,
    testMode: row?.payment_summary?.test_mode === true,
    exception: row?.has_payment_exception === true,
    exceptionCopy: paymentExceptionCopy(row),
    closeCopy: row?.close_reason ? `关闭原因 ${closeReasonLabel(row.close_reason)}` : '',
  }
}

/** 筛选条：状态筛选项与后端枚举一一对应，避免页面自己写死四组中文。 */
export const MERCHANT_ORDER_FILTERS = [
  { key: '', label: '全部' },
  { key: 'PENDING_PAYMENT', label: '待支付' },
  { key: 'PAID', label: '已支付' },
  { key: 'CLOSED', label: '已关闭' },
]

/** 列表空态与页脚：区分"这个筛选没有"和"本店还没有订单"。 */
export function merchantOrderListCopy(list, filters) {
  const items = Array.isArray(list?.items) ? list.items : []
  const total = num(list?.total)
  const filtered = !!say(filters?.status, '') || !!say(filters?.date, '')
  if (items.length === 0) {
    return {
      empty: true,
      emptyTitle: filtered ? '当前筛选下没有订单' : '本店还没有订单',
      emptyCopy: filtered ? '试试清除状态或日期筛选；历史与已关闭订单都在「全部」里。' : '车主下单后会出现在这里；扫码接车可直接进入接车检查。',
      footer: '',
    }
  }
  return { empty: false, emptyTitle: '', emptyCopy: '', footer: `本店订单 共 ${total} 条 · 已加载 ${items.length} 条` }
}

/** 价格事实：订单金额与"下单价来自哪个报价版本"分开说，避免把历史报价当成当前价。 */
export function merchantPriceFacts(row) {
  const version = num(row?.price_snapshot?.version)
  return {
    amount: `应付 ¥${moneyText(row?.amount_due)}`,
    quoteVersion: version > 0 ? `报价版本 ${version}` : '报价版本未提供',
    quotePrice: `下单价 ¥${moneyText(row?.price_snapshot?.price ?? row?.amount_due)}`,
  }
}

/** 详情页入口矩阵：把原来散在模板里的 7 组 `includes(...)` 收成一张表。
 * 这只是"什么时候值得显示入口"，真正的准入仍由服务端校验。 */
const ENTRY_DEFS = [
  { key: 'pickup', label: '接车检查', route: '/pages/merchant/pickup', params: {}, when: s => s.status === 'PAID' },
  { key: 'dispatch', label: s => (s.status === 'RECEIVED' ? '派工给本店技师' : '查看派工结果'), route: '/pages/merchant/dispatch', params: {}, when: s => ['RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED'].includes(s.status) },
  { key: 'protection', label: '施工防护与记录', route: '/pages/merchant/protection', params: {}, when: s => ['RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED', 'DISPUTED'].includes(s.status) },
  { key: 'redeem', label: s => (s.status === 'PENDING_VERIFY' ? '核销验码' : '查看核销记录'), route: '/pages/merchant/redeem', params: {}, when: s => ['PENDING_VERIFY', 'COMPLETED'].includes(s.status) },
  { key: 'pickup-detail', label: '查看接车单', route: '/pages/check/pickup-detail', params: { role: 'merchant' }, when: s => ['RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED', 'DISPUTED'].includes(s.status) },
  { key: 'refund', label: '店长取消退款处理', route: '/pages/order/refund', params: { role: 'merchant' }, when: s => ['PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'DISPUTED'].includes(s.status) || s.closeReason === 'REFUND_CANCELLED' },
  { key: 'workspace', label: '本单必要资料与已授权档案', route: '/pages/workspace/context', params: { role: 'merchant' }, when: s => ['RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY'].includes(s.status) },
]

export function merchantEntryList(order) {
  const state = { status: say(order?.status, ''), closeReason: say(order?.close_reason, '') }
  return ENTRY_DEFS.filter(entry => entry.when(state)).map(entry => ({
    key: entry.key,
    label: typeof entry.label === 'function' ? entry.label(state) : entry.label,
    route: entry.route,
    params: { ...entry.params },
  }))
}

/** 没有可执行履约动作时，说清下一步该干什么，而不是只留一句"无操作"。 */
export function merchantActionHint(status) {
  return say(status, '') === 'PAID'
    ? '请通过接车检查提交完整接车单，提交后才会出现派工入口。'
    : '当前状态在本店侧没有可执行的履约操作；如已被拒绝，请按提示先完成前置步骤。'
}
