import test from 'node:test'
import assert from 'node:assert/strict'
import {
  createDisputeApi, disputeView, disputeRecord, disputeStatusLabel, disputeActionLabel, RESUMABLE_STATES,
} from '../src/services/dispute.js'
import { createPickupApi, PICKUP_SLOTS } from '../src/services/pickup.js'
import { createReservationWriteFlow, initialReservationWriteState } from '../src/services/reservations.js'

const key = '01234567-89ab-4cde-8fab-0123456789ab'
const openDispute = (over = {}) => ({
  dispute_id: 9, status: 'OPEN', reason: '车门划痕记录不符', from_status: 'RECEIVED',
  opened_at: '2026-10-08T02:00:00Z', resolved_at: null,
  records: [{ action: 'HANDLE', note: '已核对照片，同意补拍左后门', created_at: '2026-10-08T03:00:00Z' }],
  can_review: true, ...over,
})
const handled = (over = {}) => ({ dispute_id: 9, order_id: 2, dispute_status: 'OPEN', order_status: 'DISPUTED', owner_confirm: 2, record_count: 1, last_action: 'HANDLE', updated_at: '2026-10-08T03:00:00Z', changed: true, ...over })
const accepted = (over = {}) => ({ dispute_id: 9, order_id: 2, dispute_status: 'RESOLVED', order_status: 'RECEIVED', owner_confirm: 3, record_count: 2, last_action: 'ACCEPT', updated_at: '2026-10-08T04:00:00Z', changed: true, ...over })
const rejected = (over = {}) => ({ dispute_id: 9, order_id: 2, dispute_status: 'OPEN', order_status: 'DISPUTED', owner_confirm: 2, record_count: 2, last_action: 'REJECT', updated_at: '2026-10-08T04:00:00Z', changed: true, ...over })
const stub = data => ({ request: o => o.success({ statusCode: 200, data: { code: 0, data } }) })
const apiWith = (data, capture) => createDisputeApi({
  baseUrl: 'http://local',
  runtime: () => ({ request: o => { capture?.(o); o.success({ statusCode: 200, data: { code: 0, data } }) } }),
})

test('商家处理记录要求 1–500 字并走专用路径与幂等键', async () => {
  let sent; const api = apiWith(handled(), o => { sent = o })
  await api.handle('shop', 2, ' 已核对照片，同意补拍左后门 ', key)
  assert.equal(sent.url, 'http://local/api/merchant/orders/2/dispute/handle')
  assert.equal(sent.header.Authorization, 'Bearer shop')
  assert.equal(sent.header['Idempotency-Key'], key)
  assert.deepEqual(sent.data, { note: '已核对照片，同意补拍左后门' })
  for (const note of ['', '   ', 'x'.repeat(501)]) assert.throws(() => api.handle('shop', 2, note, key))
  assert.throws(() => api.handle('shop', 0, '处理', key))
  assert.throws(() => api.handle('shop', 2, '处理', 'not-a-uuid'))
  assert.throws(() => api.handle('', 2, '处理', key), e => e.kind === 'unauthorized')
})

test('车主复核：接受可不写说明，不接受必须写原因，非法决定被拒', async () => {
  let sent; const accepting = apiWith(accepted(), o => { sent = o })
  await accepting.review('owner', 2, 'ACCEPT', '', key)
  assert.equal(sent.url, 'http://local/api/check/pickup/dispute/review')
  assert.deepEqual(sent.data, { order_id: 2, decision: 'ACCEPT' })
  const rejecting = apiWith(rejected(), o => { sent = o })
  await rejecting.review('owner', 2, 'REJECT', ' 左后门仍未补拍 ', key)
  assert.deepEqual(sent.data, { order_id: 2, decision: 'REJECT', note: '左后门仍未补拍' })
  for (const note of ['', '   ']) assert.throws(() => rejecting.review('owner', 2, 'REJECT', note, key))
  assert.throws(() => rejecting.review('owner', 2, 'REJECT', 'x'.repeat(501), key))
  assert.throws(() => accepting.review('owner', 2, 'RESOLVE', '', key))
  assert.throws(() => accepting.review('owner', 2, 'ACCEPT', '', 'not-a-uuid'))
})

test('结果投影越界或与决定不符时按协议错误拒绝', async () => {
  await assert.rejects(apiWith(handled({ actor_id: 7 })).handle('shop', 2, '处理', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(handled({ merchant_id: 1 })).handle('shop', 2, '处理', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(handled({ dispute_status: 'RESOLVED' })).handle('shop', 2, '处理', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(accepted({ owner_confirm: 2 })).review('owner', 2, 'ACCEPT', '', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(accepted({ order_status: 'COMPLETED' })).review('owner', 2, 'ACCEPT', '', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(rejected({ dispute_status: 'RESOLVED' })).review('owner', 2, 'REJECT', '不行', key), e => e.kind === 'protocol')
  await assert.rejects(apiWith(rejected()).review('owner', 2, 'ACCEPT', '', key), e => e.kind === 'protocol')
})

test('争议时间线是白名单投影：允许 null，出现身份字段即拒绝', () => {
  assert.equal(disputeView(null), true)
  assert.equal(disputeView(openDispute()), true)
  assert.equal(disputeView(openDispute({ records: [] , can_review: false })), true)
  assert.equal(disputeView(openDispute({ status: 'RESOLVED', resolved_at: '2026-10-08T04:00:00Z', can_review: false })), true)
  // can_review 只可能出现在「未解决 + 商家已提交处理记录」的组合上。
  assert.equal(disputeView(openDispute({ can_review: true, records: [{ action: 'REJECT', note: '不行', created_at: '2026-10-08T04:00:00Z' }] })), false)
  assert.equal(disputeView(openDispute({ can_review: true, status: 'RESOLVED' })), false)
  for (const leak of [{ opened_by: 1 }, { resolved_by: 1 }, { actor_id: 1 }, { merchant_id: 1 }, { verify_code: '123456' }])
    assert.equal(disputeView(openDispute(leak)), false, Object.keys(leak)[0])
  assert.equal(disputeView(openDispute({ status: 'CLOSED' })), false)
  assert.equal(disputeView(openDispute({ reason: '' })), false)
  assert.equal(disputeView(openDispute({ from_status: 'CANCELLED' })), false)
  assert.equal(disputeView(openDispute({ records: [{ action: 'HANDLE', note: 'x', created_at: '2026-10-08T03:00:00Z', actor_id: 1 }] })), false)
  assert.equal(disputeView(undefined), false)
  assert.equal(disputeRecord({ action: 'ACCEPT', note: null, created_at: '2026-10-08T04:00:00Z' }), true)
  assert.equal(disputeRecord({ action: 'NOPE', note: null, created_at: '2026-10-08T04:00:00Z' }), false)
  assert.equal(disputeStatusLabel('OPEN'), '争议处理中')
  assert.equal(disputeStatusLabel('NOPE'), '争议状态未提供')
  assert.equal(disputeActionLabel('ACCEPT'), '车主接受处理')
  assert.deepEqual(RESUMABLE_STATES, ['PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY'])
})

test('接车单投影共用同一份争议校验，越界争议会被拒绝', async () => {
  const sheet = (dispute) => ({ pickup_check_id: 1, order_id: 2, photos: Object.fromEntries(PICKUP_SLOTS.map((s, i) => [s, i + 1])), mileage: 123, owner_confirm: 2, damages: [], dispute })
  const pickup = createPickupApi({ baseUrl: 'http://local', runtime: () => stub(sheet(openDispute())) })
  assert.equal((await pickup.detail('owner', 2)).dispute.status, 'OPEN')
  const leaking = createPickupApi({ baseUrl: 'http://local', runtime: () => stub(sheet(openDispute({ opened_by: 1 }))) })
  await assert.rejects(leaking.detail('owner', 2), e => e.kind === 'protocol')
})

test('业务码文案解释拒绝原因，服务端仍是唯一权威', async () => {
  const failing = (code, statusCode) => createDisputeApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.success({ statusCode, data: { code, message: 'private SQL' } }) }) })
  await assert.rejects(failing(43008, 409).review('owner', 2, 'ACCEPT', '', key), e => e.kind === 'conflict' && e.code === 43008 && !e.message.includes('SQL'))
  await assert.rejects(failing(43007, 409).handle('shop', 2, '处理', key), e => e.message === '订单存在未解决的争议，请先在接车单处理争议')
  await assert.rejects(failing(40905, 409).review('owner', 2, 'ACCEPT', '', key), e => e.message === '争议已解决或订单状态已变化，请刷新后重试')
  await assert.rejects(failing(0, 404).handle('shop', 2, '处理', key), e => e.kind === 'missing')
  await assert.rejects(failing(0, 503).handle('shop', 2, '处理', key), e => e.kind === 'unavailable')
  const offline = createDisputeApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.fail({ errMsg: 'request:fail' }) }) })
  await assert.rejects(offline.handle('shop', 2, '处理', key), e => ['network', 'timeout', 'unavailable'].includes(e.kind))
})

test('处理记录重试保持原内容与键，切换身份后丢弃迟到响应', async () => {
  const state = initialReservationWriteState()
  let actor = 'shop', resolve, calls = [], attempt = 0
  const flow = createReservationWriteFlow({
    state, token: () => actor, newKey: () => key, body: () => ({ note: '已核对照片' }),
    request: (t, b, k) => { calls.push({ t, b, k }); return attempt++ === 0 ? Promise.reject(Object.assign(new Error(), { kind: 'unavailable' })) : new Promise(r => { resolve = r }) },
  })
  await flow.save()
  const work = flow.save()
  assert.equal(calls.length, 2)
  assert.deepEqual(calls[0], calls[1])
  flow.suspend()
  resolve(handled())
  await work
  assert.equal(state.saved, null)
  const switched = flow.save()
  actor = 'other'
  flow.reset()
  resolve(handled())
  await switched
  assert.equal(state.saved, null)
})
