import test from 'node:test'
import assert from 'node:assert/strict'
import { confirmStillHolds, createDispatchApi } from '../src/services/technician-dispatch.js'
import { createReservationWriteFlow, initialReservationWriteState } from '../src/services/reservations.js'

const MERCHANT = 'merchant-token'
const TECH = 'tech-token'
const KEY = '11111111-2222-3333-4444-555555555555'
const FAIL = { errMsg: 'request:fail network' }

const assignment = { assignment_id: 7, order_id: 12, technician_id: 3, status: 'ASSIGNED', technician_label: 'tech-demo',
  assigned_at: '2026-10-08T02:00:00Z', accepted_at: null }
const work = { assignment_id: 7, order_id: 12, order_no: 'AC-12', order_status: 'RECEIVED', assignment_status: 'ASSIGNED',
  assigned_at: '2026-10-08T02:00:00Z', accepted_at: null, can_accept: true,
  project_snapshot: { standard_project_id: 5, project_name: '小保养' },
  appointment_snapshot: { slot_id: 9, starts_at: '2026-10-09T02:00:00Z', ends_at: '2026-10-09T03:00:00Z' } }
const accepted = { ...work, assignment_status: 'ACCEPTED', order_status: 'IN_SERVICE', can_accept: false, accepted_at: '2026-10-08T03:00:00Z' }
const writeOk = { assignment_id: 7, order_id: 12, technician_id: 3, assignment_status: 'ASSIGNED', order_status: 'RECEIVED',
  assigned_at: '2026-10-08T02:00:00Z', accepted_at: null, changed: true }

const harness = response => {
  let call
  const api = createDispatchApi({ baseUrl: 'http://local', runtime: () => ({ request: options => { call = options; options.success({ statusCode: 200, data: { code: 0, data: response } }) } }) })
  return { api, request: () => call }
}
const failing = (statusCode, code) => createDispatchApi({ baseUrl: 'http://local', runtime: () => ({ request: options => options.success({ statusCode, data: { code, message: '内部错误文本' } }) }) })
const offline = () => createDispatchApi({ baseUrl: 'http://local', runtime: () => ({ request: () => {} }) })
const page = items => ({ items, total: items.length, page: 1, page_size: 20 })

test('候选技师按页码请求，只接受员工 ID 与标签，读请求不带幂等键', async () => {
  const h = harness({ items: [{ technician_id: 3, label: 'tech-demo' }], total: 1, page: 2, page_size: 20 })
  const result = await h.api.candidates(MERCHANT, 2)
  assert.equal(h.request().url, 'http://local/api/merchant/technicians?page=2&page_size=20')
  assert.equal(h.request().method, 'GET')
  assert.equal(h.request().header.Authorization, 'Bearer merchant-token')
  assert.equal('Idempotency-Key' in h.request().header, false)
  assert.equal(result.items[0].label, 'tech-demo')
})

test('候选技师把手机号、空标签、非法 ID 一律判为协议错误', async () => {
  for (const row of [{ technician_id: 3, label: 'tech-demo', phone: '13800000000' }, { technician_id: 3, label: '' },
    { technician_id: 0, label: 'tech-demo' }, { label: 'tech-demo' }, { technician_id: 3, label: 'tech-demo', binding_id: 1 }]) {
    await assert.rejects(harness(page([row])).api.candidates(MERCHANT, 1), error => error.kind === 'protocol', JSON.stringify(row))
  }
  assert.throws(() => offline().candidates(MERCHANT, 0), error => error.kind === 'invalid')
  assert.throws(() => offline().candidates(MERCHANT, 1000001), error => error.kind === 'invalid')
})

test('派工查询区分「未派工」与「已派工」，并拒绝混入身份字段', async () => {
  assert.equal((await harness({ assignment: null }).api.assignment(MERCHANT, 12)).assignment, null)
  const h = harness({ assignment })
  assert.equal((await h.api.assignment(MERCHANT, 12)).assignment.technician_label, 'tech-demo')
  assert.equal(h.request().url, 'http://local/api/merchant/orders/12/assignment')
  for (const patch of [{ technician_id: undefined }, { status: 'DONE' }, { assigned_at: null }, { user_id: 5 }, { technician_label: '' }]) {
    const row = { ...assignment, ...patch }
    await assert.rejects(harness({ assignment: row }).api.assignment(MERCHANT, 12), error => error.kind === 'protocol', JSON.stringify(patch))
  }
  await assert.rejects(harness({ assignment, extra: 1 }).api.assignment(MERCHANT, 12), error => error.kind === 'protocol')
  assert.throws(() => offline().assignment(MERCHANT, 0), error => error.kind === 'invalid')
})

test('首次派工只提交员工 ID 与幂等键，重复投递以 changed=false 返回原结果', async () => {
  const h = harness(writeOk)
  const result = await h.api.assign(MERCHANT, 12, 3, KEY)
  assert.equal(result.changed, true)
  assert.equal(h.request().url, 'http://local/api/merchant/orders/12/assign')
  assert.equal(h.request().method, 'POST')
  assert.deepEqual(h.request().data, { technician_id: 3 })
  assert.equal(h.request().header['Idempotency-Key'], KEY)
  assert.equal((await harness({ ...writeOk, changed: false }).api.assign(MERCHANT, 12, 3, KEY)).changed, false)
})

test('派工拒绝非法订单、技师与幂等键，且不发出请求', () => {
  for (const [orderId, technicianId, key] of [[0, 3, KEY], [12, 0, KEY], [12, 3, 'not-a-uuid'], [12, 3, undefined], [12, 3, '']])
    assert.throws(() => offline().assign(MERCHANT, orderId, technicianId, key), error => error.kind === 'invalid', `${orderId}/${technicianId}/${key}`)
})

test('派工响应必须落在「待接单 + 已接车」，否则判为协议错误', async () => {
  for (const patch of [{ assignment_status: 'ACCEPTED' }, { order_status: 'IN_SERVICE' }, { changed: 'true' }, { technician_id: undefined }, { assigned_at: null }])
    await assert.rejects(harness({ ...writeOk, ...patch }).api.assign(MERCHANT, 12, 3, KEY), error => error.kind === 'protocol', JSON.stringify(patch))
})

test('技师工单列表按派工状态筛选并保留全部订单状态', async () => {
  const h = harness(page([work]))
  await h.api.orders(TECH, 1, 'ASSIGNED')
  assert.equal(h.request().url, 'http://local/api/tech/orders?page=1&page_size=20&assignment_status=ASSIGNED')
  assert.equal(h.request().header.Authorization, 'Bearer tech-token')
  const all = harness(page([work]))
  await all.api.orders(TECH, 1)
  assert.equal(all.request().url, 'http://local/api/tech/orders?page=1&page_size=20')
  const done = harness(page([accepted]))
  assert.equal((await done.api.orders(TECH, 1, 'ACCEPTED')).items[0].order_status, 'IN_SERVICE')
})

test('技师工单拒绝未知筛选、越界分页与非法工单号', () => {
  for (const value of ['PENDING', 'assigned', 'ALL', 'RECEIVED'])
    assert.throws(() => offline().orders(TECH, 1, value), error => error.kind === 'invalid', value)
  assert.throws(() => offline().orders(TECH, 0), error => error.kind === 'invalid')
  assert.throws(() => offline().orders(TECH, 1000001), error => error.kind === 'invalid')
  for (const orderId of [0, -1, 1.5, '12', undefined])
    assert.throws(() => offline().order(TECH, orderId), error => error.kind === 'invalid', String(orderId))
})

test('技师工单是最小投影：任何车主、车辆、付款或核销字段都判为协议错误', async () => {
  for (const patch of [{ user_id: 5 }, { vehicle_id: 6 }, { plate_no: '粤A12345' }, { phone: '13800000000' },
    { vin: 'LSV1234567890ABCD' }, { appointment_code: '123456' }, { verify_code: '123456' },
    { technician_id: 3 }, { merchant_id: 2 }, { assigned_by: 9 }, { binding_id: 1 }, { openid: 'o-x' },
    { changed: true }, { payment_summary: { status: 'PAID' } }]) {
    await assert.rejects(harness(page([{ ...work, ...patch }])).api.orders(TECH, 1), error => error.kind === 'protocol', JSON.stringify(patch))
    await assert.rejects(harness({ ...work, ...patch }).api.order(TECH, 12), error => error.kind === 'protocol', JSON.stringify(patch))
  }
  await assert.rejects(harness({ ...work, project_snapshot: { project_name: '小保养', cost: '1.00' } }).api.order(TECH, 12), error => error.kind === 'protocol')
})

test('can_accept 只承认「本人待接 + 订单已接车」的组合', async () => {
  for (const patch of [{ can_accept: true, assignment_status: 'ACCEPTED', order_status: 'IN_SERVICE' },
    { can_accept: true, order_status: 'PAID' }, { can_accept: 'yes' }, { can_accept: undefined }]) {
    const row = { ...work, ...patch }
    await assert.rejects(harness(page([row])).api.orders(TECH, 1), error => error.kind === 'protocol', JSON.stringify(patch))
  }
  const historical = { ...work, order_status: 'COMPLETED', assignment_status: 'ACCEPTED', can_accept: false, project_snapshot: null, appointment_snapshot: null }
  assert.equal((await harness(page([historical])).api.orders(TECH, 1)).items[0].order_status, 'COMPLETED')
})

test('技师接单只提交空正文与幂等键，并断言订单真的进入施工中', async () => {
  const h = harness({ assignment_id: 7, order_id: 12, technician_id: 3, assignment_status: 'ACCEPTED', order_status: 'IN_SERVICE',
    assigned_at: '2026-10-08T02:00:00Z', accepted_at: '2026-10-08T03:00:00Z', changed: true })
  const result = await h.api.accept(TECH, 12, KEY)
  assert.equal(result.order_status, 'IN_SERVICE')
  assert.equal(h.request().url, 'http://local/api/tech/orders/12/accept')
  assert.equal(h.request().method, 'POST')
  assert.deepEqual(h.request().data, {})
  assert.equal(h.request().header['Idempotency-Key'], KEY)
  assert.throws(() => offline().accept(TECH, 12, undefined), error => error.kind === 'invalid')
})

test('接单响应必须同时推进派工与订单状态，代填身份一律拒绝', async () => {
  const body = { assignment_id: 7, order_id: 12, technician_id: 3, assignment_status: 'ACCEPTED', order_status: 'IN_SERVICE',
    assigned_at: '2026-10-08T02:00:00Z', accepted_at: '2026-10-08T03:00:00Z', changed: true }
  for (const patch of [{ order_status: 'RECEIVED' }, { assignment_status: 'ASSIGNED' }, { accepted_at: null }, { changed: 'true' }])
    await assert.rejects(harness({ ...body, ...patch }).api.accept(TECH, 12, KEY), error => error.kind === 'protocol', JSON.stringify(patch))
})

test('服务端拒绝码翻译成可读原因，不泄露上游文本', async () => {
  const conflicts = { 40905: '派工或订单状态已变化，请刷新后重试', 43001: '接车检查未完成，请先完成接车检查',
    43003: '车主尚未确认接车，不能派工或接单', 43004: '请由被派工技师本人接单并开始施工' }
  for (const [code, message] of Object.entries(conflicts))
    await assert.rejects(failing(409, Number(code)).assign(MERCHANT, 12, 3, KEY),
      error => error.kind === 'conflict' && error.message === message && error.code === Number(code), code)
  await assert.rejects(failing(409, 99999).accept(TECH, 12, KEY), error => error.kind === 'conflict' && error.message === '派工或工单请求未成功，请稍后重试')
  await assert.rejects(failing(400, 40001).assign(MERCHANT, 12, 3, KEY), error => error.kind === 'invalid' && !error.message.includes('内部错误文本'))
  await assert.rejects(failing(401, 40100).orders(TECH, 1), error => error.kind === 'unauthorized')
  await assert.rejects(failing(403, 40300).orders(TECH, 1), error => error.kind === 'forbidden')
  await assert.rejects(failing(404, 40400).order(TECH, 12), error => error.kind === 'missing')
  await assert.rejects(failing(503, 50300).accept(TECH, 12, KEY), error => error.kind === 'unavailable' && /原幂等键/.test(error.message))
})

test('缺少技师或商家登录状态时不发出请求', () => {
  assert.throws(() => offline().candidates('', 1), error => error.kind === 'unauthorized')
  assert.throws(() => offline().assignment('', 12), error => error.kind === 'unauthorized')
  assert.throws(() => offline().orders('', 1), error => error.kind === 'unauthorized')
  assert.throws(() => offline().order('', 12), error => error.kind === 'unauthorized')
  assert.throws(() => offline().accept('', 12, KEY), error => error.kind === 'unauthorized')
})

test('传输失败与未配置服务给出可区分的重试分类', async () => {
  const api = createDispatchApi({ baseUrl: 'http://local', runtime: () => ({ request: options => options.fail(FAIL) }) })
  await assert.rejects(api.orders(TECH, 1), error => error.kind === 'network')
  await assert.rejects(api.candidates(MERCHANT, 1), error => error.kind === 'network')
  const unconfigured = createDispatchApi({ baseUrl: '', runtime: () => ({ request: () => {} }) })
  assert.throws(() => unconfigured.orders(TECH, 1), error => error.kind === 'unconfigured')
})

// 断网重试必须沿用原幂等键；换人必须换新键，否则服务端会判成「同键异体」而拒绝。
test('派工写流程失败后沿用原键，换人换新键并带上新目标', async () => {
  const state = initialReservationWriteState()
  const keys = ['11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222']
  const seen = []
  let target = 3
  const flow = createReservationWriteFlow({
    state, token: () => MERCHANT, newKey: () => keys.shift(),
    body: () => ({ technician_id: target }),
    request: (actor, body, key) => { seen.push({ key, body }); return Promise.reject(new Error('offline')) },
  })
  await flow.save()
  assert.equal(state.saved, null)
  assert.equal(state.failureKind, 'network')
  await flow.save()
  assert.equal(seen[0].key, seen[1].key)
  target = 4
  await flow.save()
  assert.equal(seen.length, 3)
  assert.notEqual(seen[2].key, seen[1].key)
  assert.deepEqual(seen[2].body, { technician_id: 4 })
})

test('提交期间重复点击不会再发一次请求，成功后也不再重复提交', async () => {
  const state = initialReservationWriteState()
  let resolveRequest
  let calls = 0
  const flow = createReservationWriteFlow({
    state, token: () => MERCHANT, newKey: () => '33333333-3333-4333-8333-333333333333',
    body: () => ({ technician_id: 3 }),
    request: () => { calls++; return new Promise(resolve => { resolveRequest = resolve }) },
  })
  const first = flow.save()
  assert.equal(state.busy, true)
  await flow.save()
  assert.equal(calls, 1)
  resolveRequest({ changed: true })
  await first
  assert.equal(state.saved.changed, true)
  assert.equal(state.busy, false)
  await flow.save()
  assert.equal(calls, 1)
})

test('切换账号或离开页面后，迟到的接单响应不写入状态', async () => {
  const state = initialReservationWriteState()
  let resolveRequest
  let actor = TECH
  const flow = createReservationWriteFlow({
    state, token: () => actor, newKey: () => '44444444-4444-4444-8444-444444444444',
    body: () => ({ order_id: 12 }),
    request: () => new Promise(resolve => { resolveRequest = resolve }),
  })
  const pending = flow.save()
  actor = 'other-technician'
  resolveRequest({ changed: true })
  await pending
  assert.equal(state.saved, null)
  assert.equal(state.busy, false)

  const suspended = initialReservationWriteState()
  let resolveSecond
  const second = createReservationWriteFlow({
    state: suspended, token: () => TECH, newKey: () => '55555555-5555-4555-8555-555555555555',
    body: () => ({ order_id: 12 }),
    request: () => new Promise(resolve => { resolveSecond = resolve }),
  })
  const running = second.save()
  second.suspend()
  resolveSecond({ changed: true })
  await running
  assert.equal(suspended.saved, null)
  assert.equal(suspended.busy, false)
})

// 模态确认回调在任意时刻返回：身份、页面或目标任一变化都必须作废旧确认。
test('确认回调守卫在身份、页面可见性或目标变化后作废', () => {
  const opened = { actor: MERCHANT, target: 3 }
  assert.equal(confirmStillHolds(opened, { visible: true, token: MERCHANT, target: 3 }), true)
  assert.equal(confirmStillHolds(opened, { visible: true, token: 'another-merchant', target: 3 }), false)
  assert.equal(confirmStillHolds(opened, { visible: false, token: MERCHANT, target: 3 }), false)
  assert.equal(confirmStillHolds(opened, { visible: true, token: MERCHANT, target: 4 }), false)
  assert.equal(confirmStillHolds({ actor: TECH }, { visible: true, token: TECH }), true)
  assert.equal(confirmStillHolds({ actor: TECH }, { visible: true, token: MERCHANT }), false)
  assert.equal(confirmStillHolds({ actor: TECH }, { visible: true, token: '' }), false)
})
