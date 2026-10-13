import test from 'node:test'
import assert from 'node:assert/strict'
import { createPlanBookingHandoff } from '../src/services/plan-booking-handoff.js'
import { createPlanBookingApi, validPlanBookingContext, createPlanBookingFlow, initialPlanBookingState } from '../src/services/plan-booking.js'
import { healthMetrics } from '../src/services/health-observations.js'
import { ReservationError } from '../src/services/reservations.js'
const key = '11111111-1111-4111-8111-111111111111'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const body = (page = 1, selected = null) => ({ merchant_project_id: 1, standard_project_id: 2, vehicle_id: 3, date: '2026-10-14', page, selected_slot_id: selected })
const slot = (id = 4) => ({ slot_id: id, standard_project_id: 2, starts_at: '2026-10-14T01:00:00Z', ends_at: '2026-10-14T02:00:00Z', capacity: 2, capacity_left: 1, open: true, project_name: '合成项目' })
function context(b = body()) { return { quote: { merchant_project_id: 1, quote_version_id: 5, version: 1, merchant_id: 6, standard_project_id: 2, merchant_name: '合成门店', address: '合成地址', project_name: '合成项目', price: '12.00' }, evidence: { vehicle: { vehicle_id: 3, model_id: 7, model_name: '合成车型', model_source: 'OWNER_REGISTERED', power_type: 'EV' }, observations: { vehicle_id: 3, today: '2026-10-13', list: healthMetrics.map(metric => ({ metric, revision: 0, archive_id: null, recorded_date: null, measurement: {}, status: 'NOT_SET' })) }, experiences: { items: [], next_cursor: null }, applicability: [{ project_id: 2, verdict: 'UNKNOWN', rule_version: 0, source_reference: null }] }, slots: { items: [slot()], total: 1, page: b.page, page_size: 20 }, selected_slot: b.selected_slot_id ? slot(b.selected_slot_id) : null } }
function setup({ read, create } = {}) { const state = initialPlanBookingState(); state.day = '2026-10-14'; const row = { merchant_project_id: 1 }, calls = []; let actor = 'owner'; const flow = createPlanBookingFlow({ state, token: () => actor, selection: () => ({ project: 2, vehicle: 3 }), newKey: () => key, api: { read: read || (async (t, b) => { calls.push(b); return context(b) }) }, quotes: { ownerList: async () => ({ items: [row], total: 1 }) }, create: create || (async (t, b, k) => { calls.push({ b, k }); return { order_id: 8 } }) }); return { state, flow, calls, setActor: v => { actor = v }, ready: async () => { await flow.loadQuotes(); await flow.chooseQuote(row); flow.chooseSlot(state.context.slots.items[0]); flow.confirm() } } }
test('handoff carries no private URL data and is one shot, account bound and expiring', () => {
  let actor = 'owner', time = 1; const h = createPlanBookingHandoff({ token: () => actor, now: () => time })
  assert.equal(h.route(2, 3), '/pages/ai/plan-booking?project_id=2'); assert.deepEqual(h.take(2), { actor: 'owner', project: 2, vehicle: 3 }); assert.equal(h.take(2), null)
  h.route(2, 3); actor = 'other'; assert.equal(h.take(2), null); h.route(2, 3); time += 900001; assert.equal(h.take(2), null); h.route(2, 3); assert.equal(h.take(99), null); assert.equal(h.take(2), null)
})
test('context API sends six minimal read fields with no write key', async () => {
  const api = createPlanBookingApi({ baseUrl: 'https://synthetic.invalid', runtime: () => ({ request: r => { assert.equal(r.method, 'POST'); assert.equal(r.url, 'https://synthetic.invalid/api/order/plan-booking-context'); assert.deepEqual(r.data, body()); assert.deepEqual(r.header, { Authorization: 'Bearer owner' }); r.success({ statusCode: 200, data: { code: 0, data: context() } }) } }) })
  assert.deepEqual(await api.read('owner', body()), context()); for (const b of [{ ...body(), vehicle_id: 0 }, { ...body(), date: '2026-02-30' }, { ...body(), page: 1000001 }, { ...body(), question: 'private' }]) assert.throws(() => api.read('owner', b))
})
test('context protocol rejects leaked fields, wrong project, date, slot and zero capacity', () => {
  assert.equal(validPlanBookingContext(context(body(1, 4)), body(1, 4)), true)
  for (const change of [r => { r.quote.standard_project_id = 99 }, r => { r.evidence.vehicle.vin = 'private' }, r => { r.slots.items[0].starts_at = '2026-10-15T01:00:00Z' }, r => { r.slots.items[0].capacity_left = 0 }, r => { r.selected_slot = slot(99) }, r => { r.selected_slot = slot() }]) { const r = context(); change(r); assert.equal(validPlanBookingContext(r, body()), false) }
})
test('context maps authorization and malformed response failures', async () => {
  const api = result => createPlanBookingApi({ baseUrl: 'https://synthetic.invalid', runtime: () => ({ request: r => r.success(result) }) })
  await assert.rejects(api({ statusCode: 401 }).read('owner', body()), { kind: 'unauthorized' }); await assert.rejects(api({ statusCode: 200, data: { code: 0, data: {} } }).read('owner', body()), { kind: 'protocol' })
})
test('new quote or date clears old slot and confirmation', async () => {
  const s = setup(); await s.ready(); assert.equal(s.state.confirmed, true); await s.flow.chooseDay('2026-10-15'); assert.equal(s.state.confirmed, false); assert.equal(s.state.slot, 0); await s.flow.loadQuotes(false, 'price_desc'); assert.equal(s.state.context, null); assert.equal(s.state.quoteId, 0)
})
test('preflight price, model and rule changes prevent creation and clear confirmation', async () => {
  for (const change of [r => { r.quote.quote_version_id = 9; r.quote.price = '13.00' }, r => { r.evidence.vehicle.model_id = 9 }, r => { r.evidence.applicability[0] = { project_id: 2, verdict: 'NOT_APPLICABLE', rule_version: 1, source_reference: '合成来源' } }]) { let reads = 0, writes = 0; const s = setup({ read: async (t, b) => { const r = context(b); if (++reads > 1) change(r); return r }, create: async () => { writes++ } }); await s.ready(); await s.flow.save(); assert.equal(writes, 0); assert.equal(s.state.confirmed, false); assert.equal(s.state.slot, 0); assert.equal(s.state.kind, 'conflict') }
})
test('cross page chosen slot is separately preflighted and order gets only four current fields', async () => {
  const s = setup(); await s.ready(); s.state.context.slots.items.push(slot(20)); s.flow.chooseSlot(s.state.context.slots.items[1]); await s.flow.save(); assert.equal(s.calls[1].selected_slot_id, 20); assert.deepEqual(s.calls[2], { b: { merchant_project_id: 1, quote_version_id: 5, vehicle_id: 3, slot_id: 20 }, k: key }); assert.equal(s.state.saved.order_id, 8)
})
test('closed or sold out selected slot prevents creation', async () => {
  let reads = 0, writes = 0; const s = setup({ read: async (t, b) => { const r = context(b); if (++reads > 1) r.selected_slot = null; return r }, create: async () => { writes++ } }); await s.ready(); await s.flow.save(); assert.equal(writes, 0); assert.equal(s.state.slot, 0); assert.equal(s.state.confirmed, false)
})
test('ambiguous create freezes choices and retries original body key without another preflight', async () => {
  const writes = []; const s = setup({ create: async (t, b, k) => { writes.push({ b, k }); if (writes.length === 1) throw new ReservationError('network', 'unknown'); return { order_id: 8 } } }); await s.ready(); await s.flow.save(); assert.equal(s.state.uncertain, true); const day = s.state.day; await s.flow.chooseDay('2026-10-16'); await s.flow.loadQuotes(); assert.equal(s.state.day, day); s.flow.suspend(); assert.equal(s.state.context, null); await s.flow.save(); assert.deepEqual(writes[0], writes[1]); assert.equal(s.calls.length, 2); assert.equal(s.state.uncertain, false); assert.equal(s.state.saved.order_id, 8)
})
test('account switch while preflighting cannot dispatch an order', async () => {
  const wait = deferred(); let reads = 0, writes = 0; const s = setup({ read: async (t, b) => ++reads === 1 ? context(b) : wait.promise, create: async () => { writes++ } }); await s.ready(); const task = s.flow.save(); s.setActor('other'); s.flow.reset(); wait.resolve(context(body(1, 4))); await task; assert.equal(writes, 0); assert.equal(s.state.context, null); assert.equal(s.state.saved, null)
})
test('hide during sent create keeps original pending request for receipt retry and ignores late result', async () => {
  const wait = deferred(); let writes = 0; const s = setup({ create: async () => ++writes === 1 ? wait.promise : { order_id: 8 } }); await s.ready(); const p = s.flow.save(); await new Promise(r => setImmediate(r)); s.flow.suspend(); wait.resolve({ order_id: 8 }); await p; assert.equal(s.state.saved, null); assert.equal(s.state.uncertain, true); await s.flow.save(); assert.equal(writes, 2); assert.equal(s.state.saved.order_id, 8)
})
test('atomic create conflict refreshes context and requires fresh user confirmation', async () => {
  const s = setup({ create: async () => { throw new ReservationError('conflict', 'updated', 40901) } }); await s.ready(); await s.flow.save(); assert.equal(s.state.uncertain, false); assert.equal(s.state.confirmed, false); assert.equal(s.state.slot, 0); assert.ok(s.state.context); assert.equal(s.calls.length, 3)
})
test('paging across changed rule resets old rows and rereads first page', async () => {
  let reads = 0; const pages = []; const s = setup({ read: async (t, b) => { pages.push(b.page); const r = context(b); if (++reads > 1) r.evidence.applicability[0] = { project_id: 2, verdict: 'APPLICABLE', rule_version: 1, source_reference: '合成来源' }; return r } }); await s.ready(); await s.flow.loadContext(true); assert.deepEqual(pages, [1, 2, 1]); assert.equal(s.state.context.slots.page, 1); assert.equal(s.state.confirmed, false); assert.equal(s.state.slot, 0)
})
test('double submit waits for single preflight and single create', async () => {
  const wait = deferred(); let reads = 0, writes = 0; const s = setup({ read: async (t, b) => ++reads === 1 ? context(b) : wait.promise, create: async () => { writes++; return { order_id: 8 } } }); await s.ready(); const p = s.flow.save(); await s.flow.save(); assert.equal(reads, 2); wait.resolve(context(body(1, 4))); await p; assert.equal(writes, 1)
})
