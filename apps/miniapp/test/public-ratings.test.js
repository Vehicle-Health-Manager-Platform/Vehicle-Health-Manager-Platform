import test from 'node:test'
import assert from 'node:assert/strict'
import { ServiceError } from '../src/services/service-catalog.js'
import { createPublicRatingsApi, validPublication, validRatings, createPublicationFlow, initialPublicationState, createRatingsFlow, initialRatingsState } from '../src/services/public-ratings.js'
const key = '11111111-1111-4111-8111-111111111111'
const publication = (version = 0, consented = false) => ({ order_id: 1, version, consented, visible: consented, can_publish: true, unavailable_reason: null })
const page = (p = 1) => ({ merchant_id: 1, page: p, page_size: 20, summary: { count: 2, rating_sum: 7, average_rating: '3.50' }, items: [{ rating: p === 1 ? 5 : 2, submitted_date: '2026-01-01' }] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
test('publication rejects extra fields, inconsistent visibility, test reason and unsafe versions', () => {
  assert.ok(validPublication(publication(), 1))
  for (const bad of [{ ...publication(), content: 'private' }, { ...publication(), visible: true }, { ...publication(), version: -1 }, { ...publication(), version: 9007199254740992 }, { ...publication(), can_publish: false }, { ...publication(), order_id: 2 }]) assert.equal(!!validPublication(bad, 1), false)
  assert.ok(validPublication({ ...publication(), can_publish: false, unavailable_reason: 'TEST_ORDER' }, 1))
})
test('ratings require anonymous fields and reproducible average with an honest empty state', () => {
  assert.ok(validRatings(page(), 1, 1)); assert.ok(validRatings({ ...page(), items: [], summary: { count: 0, rating_sum: 0, average_rating: null } }, 1, 1))
  for (const bad of [{ ...page(), summary: { ...page().summary, average_rating: '5.00' } }, { ...page(), items: [null] }, { ...page(), items: [{ ...page().items[0], user_id: 1 }] }, { ...page(), items: [{ ...page().items[0], content: 'private' }] }]) assert.equal(!!validRatings(bad, 1, 1), false)
})
test('requests send only explicit consent and version and whitelist response', async () => {
  const requests = []
  const api = createPublicRatingsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { requests.push(r); r.success({ statusCode: 200, data: { code: 0, data: publication() } }) } }) })
  await api.update('owner', 1, { publish: true, expected_version: 0 }, key)
  assert.equal(requests[0].method, 'PUT'); assert.equal(requests[0].header['Idempotency-Key'], key); assert.deepEqual(requests[0].data, { publish: true, expected_version: 0 })
  assert.throws(() => api.update('owner', 1, { publish: true, expected_version: 0, content: 'private' }, key))
  const bad = createPublicRatingsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: { ...publication(), user_id: 2 } } }) }) })
  await assert.rejects(bad.detail('owner', 1), { kind: 'protocol' })
})
test('HTTP error details never surface and invalid input never requests', async () => {
  let count = 0
  const api = createPublicRatingsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { count++; r.success({ statusCode: 409, data: { message: 'private SQL' } }) } }) })
  assert.throws(() => api.list('owner', 1, 0)); assert.equal(count, 0)
  await assert.rejects(api.update('owner', 1, { publish: true, expected_version: 0 }, key), e => e.kind === 'conflict' && !e.message.includes('private'))
})
test('cancel confirmation writes nothing and test eligibility cannot be bypassed', async () => {
  let writes = 0
  const state = initialPublicationState(), flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => false, api: { detail: async () => publication(), update: async () => { writes++ } } })
  await flow.load(); await flow.save(true); assert.equal(writes, 0); assert.equal(state.busy, false)
  state.value = { ...publication(), can_publish: false, unavailable_reason: 'TEST_ORDER' }; await flow.save(true); assert.equal(writes, 0)
})
test('network retry reuses original key and body and rereads current state after historical response', async () => {
  let writes = 0, confirmations = 0, keys = 0
  const calls = [], state = initialPublicationState(), flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => { keys++; return key }, confirm: async () => { confirmations++; return true }, api: {
    detail: async () => publication(writes ? 2 : 0, false), update: async (t, o, b, k) => { calls.push([b, k]); if (++writes === 1) throw new ServiceError('network', 'fail'); return publication(1, true) },
  } })
  await flow.load(); await flow.save(true); assert.equal(state.retryPublish, true); await flow.save(true)
  assert.deepEqual(calls[0], calls[1]); assert.equal(confirmations, 1); assert.equal(keys, 1); assert.equal(state.value.consented, false); assert.equal(state.value.version, 2)
})
test('conflict clears old state and requires reload before a new consent', async () => {
  const state = initialPublicationState(), flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => publication(), update: async () => { throw new ServiceError('conflict', 'changed') } } })
  await flow.load(); await flow.save(true); assert.equal(state.value, null); assert.equal(state.retryPublish, null); assert.equal(state.failureKind, 'conflict')
})
test('hide while confirmation or write is in flight cannot restore state', async () => {
  const gate = deferred(), state = initialPublicationState(); let writes = 0
  const flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: () => gate.promise, api: { detail: async () => publication(), update: async () => { writes++ } } })
  await flow.load(); const task = flow.save(true); flow.reset(); gate.resolve(true); await task; assert.equal(writes, 0); assert.deepEqual(state, initialPublicationState())
})
test('account change prevents an old publication response from reaching the new account', async () => {
  let token = 'a'; const gate = deferred(), state = initialPublicationState()
  const flow = createPublicationFlow({ state, token: () => token, order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: () => gate.promise } })
  const task = flow.load(); token = 'b'; flow.reset(); gate.resolve(publication()); await task; assert.deepEqual(state, initialPublicationState())
})
test('hide during an in-flight write discards completion and does not fetch old actor state', async () => {
  const gate = deferred(), started = deferred(), state = initialPublicationState(); let reads = 0
  const flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => { reads++; return publication() }, update: () => { started.resolve(); return gate.promise } } })
  await flow.load(); const task = flow.save(true); await started.promise; flow.reset(); gate.resolve(publication(1, true)); await task
  assert.equal(reads, 1); assert.deepEqual(state, initialPublicationState())
})
test('write success followed by read failure hides historical response and cannot repeat mutation', async () => {
  let reads = 0, writes = 0; const state = initialPublicationState()
  const flow = createPublicationFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => { if (++reads > 1) throw new ServiceError('network', 'fail'); return publication() }, update: async () => { writes++; return publication(1, true) } } })
  await flow.load(); await flow.save(true); assert.equal(state.value, null); assert.equal(state.retryPublish, null); await flow.save(true); assert.equal(writes, 1)
})
test('ratings paginate and refresh from page one; late reads vanish on hide', async () => {
  const state = initialRatingsState(), flow = createRatingsFlow({ state, token: () => 'a', merchant: () => 1, api: { list: async (t, m, p) => page(p) } })
  await flow.load(); await flow.load(true); assert.equal(state.value.items.length, 2); await flow.load(); assert.equal(state.value.page, 1); assert.equal(state.value.items.length, 1)
  const gate = deferred(), late = createRatingsFlow({ state, token: () => 'a', merchant: () => 1, api: { list: () => gate.promise } }); const task = late.load(); late.reset(); gate.resolve(page()); await task; assert.deepEqual(state, initialRatingsState())
})
