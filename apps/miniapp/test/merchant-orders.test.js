import test from 'node:test'
import assert from 'node:assert/strict'
import { createMerchantOrdersApi } from '../src/services/merchant-orders.js'

const base = { order_id: 1, order_no: 'TEST-1', status: 'PAID', amount_due: '12.34', created_at: '2026-10-07T01:00:00Z', expires_at: null, closed_at: null, close_reason: null,
  payment_summary: null, project_snapshot: { project_name: '测试服务' }, merchant_snapshot: { merchant_name: '甲店' }, appointment_snapshot: { starts_at: '2026-10-08T02:00:00Z' }, has_payment_exception: true }
const harness = response => { let call; return { api: createMerchantOrdersApi({ baseUrl: 'http://local', runtime: () => ({ request: options => { call = options; options.success({ statusCode: 200, data: { code: 0, data: response } }) } }) }), request: () => call } }

test('本店列表发送状态与北京时间日期筛选，并保留整单异常标记', async () => {
  const h = harness({ items: [base], total: 1, page: 1, page_size: 20 })
  const result = await h.api.list('shop-token', 1, 'PAID', '2026-10-08')
  assert.equal(result.items[0].has_payment_exception, true)
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
