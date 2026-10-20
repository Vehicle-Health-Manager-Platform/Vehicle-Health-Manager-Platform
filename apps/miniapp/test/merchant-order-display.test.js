import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  MERCHANT_ORDER_FILTERS, merchantActionHint, merchantEntryList, merchantOrderListCopy,
  merchantOrderRow, merchantOrderStatusView, merchantPaymentView, merchantPriceFacts, paymentExceptionCopy,
} from '../src/services/merchant-order-display.js'
import { ORDER_STATES } from '../src/services/order-status.js'
import { TONE_CLASS as REVIEW_TONES } from '../src/services/public-review-display.js'
import { TONE_CLASS } from '../src/services/ui-tone.js'

const keys = order => merchantEntryList(order).map(entry => entry.key)

test('every order state gets a label, a tone and an explanation', () => {
  const tones = {}
  for (const status of ORDER_STATES) {
    const view = merchantOrderStatusView(status)
    assert.ok(view.label && view.label !== '状态未提供', `${status} 缺少可读标签`)
    assert.ok(['ok', 'warn', 'risk', 'muted'].includes(view.tone), `${status} 色调未定义`)
    assert.ok(view.copy.length > 6, `${status} 缺少解释`)
    tones[status] = view.tone
  }
  // 待付款/待核销是"要动手"，争议是"出问题"，已完成/已关闭是"只读"。
  assert.equal(tones.PENDING_PAYMENT, 'warn'); assert.equal(tones.PENDING_VERIFY, 'warn')
  assert.equal(tones.DISPUTED, 'risk')
  assert.equal(tones.COMPLETED, 'muted'); assert.equal(tones.CLOSED, 'muted')
  assert.equal(merchantOrderStatusView('PAID').label, '已支付待接车')
  // 端到端浏览器流程断言过列表含"已支付"，标签必须仍然包含它。
  assert.match(merchantOrderStatusView('PAID').label, /已支付/)
  assert.equal(merchantOrderStatusView(undefined).label, '状态未提供')
  assert.equal(merchantOrderStatusView('WHATEVER').tone, 'muted')
})

test('payment summary separates test channel, review flag and unknown status', () => {
  assert.deepEqual(merchantPaymentView(null), { label: '未发起支付', tone: 'muted', copy: '本单还没有支付记录，请先确认车主是否已付款。', testMode: false })
  assert.equal(merchantPaymentView({ status: 'SUCCEEDED', test_mode: false, requires_review: false }).tone, 'ok')
  const test = merchantPaymentView({ status: 'SUCCEEDED', test_mode: true, requires_review: false })
  assert.equal(test.testMode, true); assert.equal(test.label, '支付成功')
  assert.match(test.copy, /测试渠道.*不能作为收款凭据/)
  const review = merchantPaymentView({ status: 'FAILED', test_mode: false, requires_review: true })
  assert.equal(review.tone, 'risk'); assert.match(review.copy, /人工核对/)
  assert.equal(merchantPaymentView({ status: 'CLOSED' }).label, '支付已关闭')
  assert.equal(merchantPaymentView({ status: 'UNKNOWN' }).label, '支付状态未提供')
})

test('payment exception copy keeps the audited wording and stays empty otherwise', () => {
  assert.equal(paymentExceptionCopy({ has_payment_exception: true }), '本订单存在付款异常，待核对')
  assert.equal(paymentExceptionCopy({ has_payment_exception: false }), '')
  assert.equal(paymentExceptionCopy(undefined), '')
})

test('order row exposes what the shop actually scans', () => {
  const row = merchantOrderRow({
    status: 'PAID', amount_due: '128.00', has_payment_exception: true,
    project_snapshot: { project_name: '小保养' },
    appointment_snapshot: { starts_at: '2026-10-21T02:00:00Z' },
    payment_summary: { status: 'SUCCEEDED', test_mode: true },
  })
  assert.equal(row.title, '小保养')
  assert.equal(row.amount, '¥128.00')
  assert.equal(row.statusLabel, '已支付待接车')
  assert.match(row.appointment, /^预约 2026-10-21 10:00/)
  assert.equal(row.testMode, true); assert.equal(row.exception, true)
  assert.equal(row.exceptionCopy, '本订单存在付款异常，待核对')
  assert.equal(row.closeCopy, '')
  // 历史订单没有项目快照，不能渲染 undefined。
  assert.equal(merchantOrderRow({}).title, '历史订单')
  assert.equal(merchantOrderRow({}).amount, '¥金额未提供')
  assert.equal(merchantOrderRow(undefined).appointment, '预约 未提供')
  assert.equal(merchantOrderRow({ close_reason: 'OWNER_CANCELLED' }).closeCopy, '关闭原因 车主取消')
})

test('list copy distinguishes a filtered miss from an empty shop', () => {
  const filtered = merchantOrderListCopy({ items: [], total: 0 }, { status: 'PAID' })
  assert.equal(filtered.empty, true); assert.equal(filtered.emptyTitle, '当前筛选下没有订单')
  assert.equal(filtered.footer, '')
  const dated = merchantOrderListCopy({ items: [], total: 0 }, { date: '2026-10-21' })
  assert.equal(dated.emptyTitle, '当前筛选下没有订单')
  const unfiltered = merchantOrderListCopy({ items: [], total: 0 }, { status: '', date: '' })
  assert.equal(unfiltered.emptyTitle, '本店还没有订单')
  assert.match(unfiltered.emptyCopy, /扫码接车/)
  const filled = merchantOrderListCopy({ items: [{}, {}], total: 7 }, {})
  assert.equal(filled.empty, false)
  assert.equal(filled.footer, '本店订单 共 7 条 · 已加载 2 条')
  assert.equal(merchantOrderListCopy(undefined, {}).empty, true)
})

test('price facts keep the order amount apart from the quote version', () => {
  assert.deepEqual(merchantPriceFacts({ amount_due: '88.00', price_snapshot: { version: 3, price: '90.00' } }), {
    amount: '应付 ¥88.00', quoteVersion: '报价版本 3', quotePrice: '下单价 ¥90.00',
  })
  const legacy = merchantPriceFacts({ amount_due: '88.00' })
  assert.equal(legacy.quoteVersion, '报价版本未提供')
  assert.equal(legacy.quotePrice, '下单价 ¥88.00')
  assert.equal(merchantPriceFacts({}).amount, '应付 ¥金额未提供')
})

test('detail entry matrix replaces seven inline includes() arrays', () => {
  assert.deepEqual(keys({ status: 'PENDING_PAYMENT' }), [])
  assert.deepEqual(keys({ status: 'PAID' }), ['pickup', 'refund'])
  assert.deepEqual(keys({ status: 'RECEIVED' }), ['dispatch', 'protection', 'pickup-detail', 'refund', 'workspace'])
  assert.deepEqual(keys({ status: 'IN_SERVICE' }), ['dispatch', 'protection', 'pickup-detail', 'refund', 'workspace'])
  assert.deepEqual(keys({ status: 'PENDING_VERIFY' }), ['dispatch', 'protection', 'redeem', 'pickup-detail', 'refund', 'workspace'])
  assert.deepEqual(keys({ status: 'COMPLETED' }), ['dispatch', 'protection', 'redeem', 'pickup-detail'])
  assert.deepEqual(keys({ status: 'DISPUTED' }), ['protection', 'pickup-detail', 'refund'])
  assert.deepEqual(keys({ status: 'CLOSED' }), [])
  // 已关闭但属于"双方同意取消"时，退款处理入口仍要给出。
  assert.deepEqual(keys({ status: 'CLOSED', close_reason: 'REFUND_CANCELLED' }), ['refund'])
  assert.deepEqual(keys(undefined), [])
  // 标签随状态变化，但入口 key 不变（testid 依赖 key）。
  assert.equal(merchantEntryList({ status: 'RECEIVED' }).find(e => e.key === 'dispatch').label, '派工给本店技师')
  assert.equal(merchantEntryList({ status: 'IN_SERVICE' }).find(e => e.key === 'dispatch').label, '查看派工结果')
  assert.equal(merchantEntryList({ status: 'PENDING_VERIFY' }).find(e => e.key === 'redeem').label, '核销验码')
  assert.equal(merchantEntryList({ status: 'COMPLETED' }).find(e => e.key === 'redeem').label, '查看核销记录')
  // 需要 role 的入口必须带上，否则后端按车主身份处理。
  assert.deepEqual(merchantEntryList({ status: 'DISPUTED' }).find(e => e.key === 'refund').params, { role: 'merchant' })
  assert.deepEqual(merchantEntryList({ status: 'RECEIVED' }).find(e => e.key === 'workspace').params, { role: 'merchant' })
  assert.deepEqual(merchantEntryList({ status: 'PAID' }).find(e => e.key === 'pickup').params, {})
})

test('no-action hint tells the shop the next concrete step', () => {
  assert.match(merchantActionHint('PAID'), /接车检查/)
  assert.match(merchantActionHint('COMPLETED'), /没有可执行的履约操作/)
  assert.match(merchantActionHint(undefined), /没有可执行的履约操作/)
})

test('filter chips stay bound to the documented order states', () => {
  assert.deepEqual(MERCHANT_ORDER_FILTERS.map(option => option.key), ['', 'PENDING_PAYMENT', 'PAID', 'CLOSED'])
  for (const option of MERCHANT_ORDER_FILTERS) {
    if (option.key) assert.ok(ORDER_STATES.includes(option.key), `${option.key} 不在 ORDER_STATES 里`)
    assert.ok(option.label.length > 0)
  }
})

test('badge tone map has a single source for both domains', () => {
  assert.deepEqual(REVIEW_TONES, TONE_CLASS)
  assert.deepEqual(Object.values(TONE_CLASS).sort(), ['ux-badge-muted', 'ux-badge-ok', 'ux-badge-risk', 'ux-badge-warn'])
})

test('merchant order pages render the shared mapping instead of inline state checks', () => {
  const dir = resolve(import.meta.dirname, '../src/pages/merchant')
  const page = name => readFileSync(resolve(dir, name), 'utf8')
  for (const name of ['orders.vue', 'order-detail.vue']) {
    assert.match(page(name), /merchant-order-display\.js/, `${name} 必须走商家订单展示映射`)
    assert.match(page(name), /ux-hero/, `${name} 缺少页面头部`)
    assert.match(page(name), /ux-card/, `${name} 缺少卡片原语`)
    assert.match(page(name), /toneClass\(/, `${name} 必须用统一徽标色调`)
  }
  // 列表页：空态、行锚点、每行一个真实按钮（端到端浏览器流程按 uni-button 点击），日期筛选文案不能改。
  assert.match(page('orders.vue'), /merchant-orders-empty/)
  assert.match(page('orders.vue'), /data-testid="`merchant-order-\$\{row\.item\.order_id\}`"/)
  assert.match(page('orders.vue'), /<button @tap="open\(row\.item\)">查看订单详情<\/button>/)
  assert.match(page('orders.vue'), /预约日期：/)
  assert.match(page('orders.vue'), /MERCHANT_ORDER_FILTERS/)
  assert.doesNotMatch(page('orders.vue'), /row\.status === 'PENDING_PAYMENT' \?/)
  // 详情页：三个既有锚点必须还在，且状态判断不再散落成数组字面量。
  assert.match(page('order-detail.vue'), /data-testid="merchant-order-detail"/)
  assert.match(page('order-detail.vue'), /data-testid="merchant-order-status"/)
  assert.match(page('order-detail.vue'), /data-testid="merchant-order-actions"/)
  assert.match(page('order-detail.vue'), /merchantEntryList\(/)
  assert.doesNotMatch(page('order-detail.vue'), /\[('PAID'|'RECEIVED')/)
  assert.doesNotMatch(page('order-detail.vue'), /payment_summary\.status \}\}/)
})
