import test from 'node:test'
import assert from 'node:assert/strict'
import { createMerchantOrdersApi } from '../src/services/merchant-orders.js'
import { ORDER_STATES } from '../src/services/order-status.js'

const base = { order_id: 1, order_no: 'TEST-1', status: 'PAID', amount_due: '12.34', created_at: '2026-10-07T01:00:00Z', expires_at: null, closed_at: null, close_reason: null,
  payment_summary: null, project_snapshot: { project_name: '测试服务' }, merchant_snapshot: { merchant_name: '甲店' }, appointment_snapshot: { starts_at: '2026-10-08T02:00:00Z' }, has_payment_exception: true,
  allowed_actions: [{ action: 'RECEIVE', to_status: 'RECEIVED' }] }
const harness = response => { let call; return { api: createMerchantOrdersApi({ baseUrl: 'http://local', runtime: () => ({ request: options => { call = options; options.success({ statusCode: 200, data: { code: 0, data: response } }) } }) }), request: () => call } }
const failing = (statusCode, code, message) => createMerchantOrdersApi({ baseUrl: 'http://local', runtime: () => ({ request: options => options.success({ statusCode, data: { code, message } }) }) })
const offline = () => createMerchantOrdersApi({ baseUrl: 'http://local', runtime: () => ({ request: () => {} }) })
const CONFLICTS = { 40905: '当前订单状态不支持该操作，请刷新后重试', 43001: '接车检查未完成，请先完成接车检查', 43003: '车主尚未确认接车，不能开始施工', 43004: '尚未派工，不能开始施工', 43005: '施工报工未完成，不能送核销', 43006: '该操作的前置校验尚未接入，暂不能执行' }

test('本店列表发送状态与北京时间日期筛选，并保留整单异常标记', async () => {
  const h = harness({ items: [base], total: 1, page: 1, page_size: 20 })
  const result = await h.api.list('shop-token', 1, 'PAID', '2026-10-08')
  assert.equal(result.items[0].has_payment_exception, true)
  assert.deepEqual(result.items[0].allowed_actions, [{ action: 'RECEIVE', to_status: 'RECEIVED' }])
  assert.equal(h.request().url, 'http://local/api/merchant/orders?page=1&page_size=20&status=PAID&date=2026-10-08')
  assert.equal(h.request().header.Authorization, 'Bearer shop-token')
  assert.equal(h.request().method, 'GET')
})

test('商家订单拒绝带客户身份的响应', async () => {
  const h = harness({ ...base, vehicle_id: 9, price_snapshot: null })
  await assert.rejects(h.api.detail('shop-token', 1), error => error.kind === 'protocol')
})

test('本店详情允许安全快照，拒绝越界分页与非法日期', async () => {
  const h = harness({ ...base, price_snapshot: { version: 2, price: '12.34' } })
  assert.equal((await h.api.detail('shop-token', 1)).price_snapshot.version, 2)
  assert.throws(() => h.api.list('shop-token', 1000001), error => error.kind === 'invalid')
  assert.throws(() => h.api.list('shop-token', 1, '', '2026/10/08'), error => error.kind === 'invalid')
})

test('本店订单接受全部八种状态筛选并拒绝未知状态', async () => {
  for (const status of ORDER_STATES) {
    const h = harness({ items: [], total: 0, page: 1, page_size: 20 })
    await h.api.list('shop-token', 1, status)
    assert.equal(h.request().url, `http://local/api/merchant/orders?page=1&page_size=20&status=${status}`)
  }
  assert.throws(() => offline().list('shop-token', 1, 'CANCELLED'), error => error.kind === 'invalid')
})

test('本店订单拒绝越界的 allowed_actions 与未知状态', async () => {
  for (const value of [{ action: 'PAY', to_status: 'PAID' }, { action: 'RECEIVE', to_status: 'CANCELLED' }, { action: 'RECEIVE' }, null, 'RECEIVE'])
    await assert.rejects(harness({ ...base, allowed_actions: [value] }).api.detail('shop-token', 1), error => error.kind === 'protocol')
  await assert.rejects(harness({ ...base, status: 'CANCELLED' }).api.detail('shop-token', 1), error => error.kind === 'protocol')
  await assert.rejects(harness({ ...base, allowed_actions: undefined }).api.detail('shop-token', 1), error => error.kind === 'protocol')
})

test('商家动作发送 POST、动作名与幂等键，目标状态不由前端指定', async () => {
  const h = harness({ ...base, status: 'RECEIVED', action: 'RECEIVE', from_status: 'PAID', changed: true })
  const result = await h.api.act('shop-token', 1, 'RECEIVE', undefined, '11111111-2222-3333-4444-555555555555')
  assert.equal(result.changed, true)
  assert.equal(result.from_status, 'PAID')
  assert.equal(h.request().url, 'http://local/api/merchant/orders/1/actions')
  assert.equal(h.request().method, 'POST')
  assert.deepEqual(h.request().data, { action: 'RECEIVE' })
  assert.equal(h.request().header['Idempotency-Key'], '11111111-2222-3333-4444-555555555555')
  const withNote = harness({ ...base, status: 'RECEIVED', action: 'RECEIVE', from_status: 'PAID', changed: true })
  await withNote.api.act('shop-token', 1, 'RECEIVE', '实到十分钟')
  assert.deepEqual(withNote.request().data, { action: 'RECEIVE', note: '实到十分钟' })
})

test('商家动作拒绝未知动作、非法备注与非法订单号', () => {
  for (const name of ['', 'receive', 'RECEIVED', 'ORDER_CHECK_IN', null, undefined])
    assert.throws(() => offline().act('shop-token', 1, name, undefined, 'k'), error => error.kind === 'invalid', String(name))
  assert.throws(() => offline().act('shop-token', 1, 'RECEIVE', '', 'k'), error => error.kind === 'invalid')
  assert.throws(() => offline().act('shop-token', 1, 'RECEIVE', 'x'.repeat(201), 'k'), error => error.kind === 'invalid')
  assert.throws(() => offline().act('shop-token', 0, 'RECEIVE', undefined, 'k'), error => error.kind === 'invalid')
})

test('商家动作把服务端拒绝码翻译成可读原因', async () => {
  for (const code of Object.keys(CONFLICTS))
    await assert.rejects(failing(409, Number(code), 'server message').act('shop-token', 1, 'RECEIVE', undefined, undefined),
      error => error.kind === 'conflict' && error.message === CONFLICTS[code], code)
  await assert.rejects(failing(409, 99999, '').act('shop-token', 1, 'RECEIVE', undefined, undefined), error => error.kind === 'conflict' && error.message === '本店订单请求未成功，请稍后重试')
  await assert.rejects(failing(404, 40400, 'x').detail('shop-token', 1), error => error.kind === 'missing')
  await assert.rejects(failing(400, 40001, 'x').act('shop-token', 1, 'RECEIVE', undefined, undefined), error => error.kind === 'invalid')
  await assert.rejects(failing(503, 50300, 'x').act('shop-token', 1, 'RECEIVE', undefined, undefined), error => error.kind === 'unavailable')
})
