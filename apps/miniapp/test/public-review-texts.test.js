import test from 'node:test'
import assert from 'node:assert/strict'
import { ServiceError } from '../src/services/service-catalog.js'
import { createPublicReviewTextsApi, validTextState, initialTextState, createTextFlow, createTextListFlow, initialTextListState } from '../src/services/public-review-texts.js'
const key = '11111111-1111-4111-8111-111111111111'
const detail = (v = 0, status = 'NONE', value = null) => ({ order_id: 1, version: v, status, public_text: value, reason_code: null, visible: status === 'APPROVED', can_submit: true, unavailable_reason: null })
const preview = { public_text: '服务完成 [已隐藏]', redaction_version: 'TEXT_REDACTION_V1' }
const row = { public_id: key, public_text: '独立公开文字', published_date: '2026-01-01' }
const publicPage = (p = 1) => ({ merchant_id: 1, page: p, page_size: 20, total: 1, items: [row] })
const pendingRow = { review_id: 1, version: 1, public_text: '独立公开文字', can_approve: true }
const pendingPage = () => ({ page: 1, page_size: 20, total: 1, items: [pendingRow] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function ownerFlow(api, confirm = async () => true) { const state = initialTextState(), flow = createTextFlow({ state, api, token: () => 'owner', order: () => 1, newKey: () => key, confirm }); return { state, flow } }
test('state rejects private fields, impossible visibility and invalid consent status', () => {
  assert.ok(validTextState(detail(), 1))
  for (const bad of [{ ...detail(), user_id: 2 }, { ...detail(), visible: true }, detail(0, 'PENDING', 'text'), detail(1, 'WITHDRAWN', 'retained'), { ...detail(), status: 'OTHER' }]) assert.equal(!!validTextState(bad, 1), false)
})
test('preview is a read-only POST without idempotency or raw text URL', async () => {
  let request
  const api = createPublicReviewTextsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { request = r; r.success({ statusCode: 200, data: { code: 0, data: preview } }) } }) })
  assert.deepEqual(await api.preview('owner', 1, '服务完成 13800000000'), preview)
  assert.equal(request.method, 'POST'); assert.equal(request.header['Idempotency-Key'], undefined); assert.equal(request.url, 'https://test.invalid/api/order/1/public-review-text/preview'); assert.deepEqual(request.data, { public_text: '服务完成 13800000000' })
})
test('public and operator lists reject leaked source fields and malformed rows', async () => {
  for (const data of [{ ...publicPage(), items: [{ ...row, order_id: 1 }] }, { ...publicPage(), items: [null] }, { ...publicPage(), items: [{ ...row, photo_file_ids: [] }] }]) {
    const api = createPublicReviewTextsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data } }) }) }); await assert.rejects(api.list('owner', 1), { kind: 'protocol' })
  }
  const bad = createPublicReviewTextsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: { ...pendingPage(), items: [{ ...pendingRow, private_content: 'private' }] } } }) }) }); await assert.rejects(bad.pending('operator'), { kind: 'protocol' })
})
test('invalid action and reasons never request and server error details stay private', async () => {
  let calls = 0
  const api = createPublicReviewTextsApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { calls++; r.success({ statusCode: 422, data: { message: 'private input' } }) } }) })
  assert.throws(() => api.update('owner', 1, { action: 'WITHDRAW', expected_version: 0, public_text: 'extra' }, key)); assert.throws(() => api.moderate('op', 1, { expected_version: 1, decision: 'APPROVE', reason_code: 'PRIVACY' }, key)); assert.equal(calls, 0)
  await assert.rejects(api.preview('owner', 1, 'text'), e => e.kind === 'preview' && !e.message.includes('private'))
})
test('editing invalidates preview and a late preview cannot authorize changed text', async () => {
  const gate = deferred(), { state, flow } = ownerFlow({ detail: async () => detail(), preview: () => gate.promise })
  await flow.load(); flow.edit('old'); const task = flow.preview(); flow.edit('new'); gate.resolve(preview); await task; assert.equal(state.preview, null); assert.equal(state.draft, 'new'); assert.equal(state.busy, false)
})
test('submission requires preview and explicit confirmation, cancel writes nothing', async () => {
  let calls = 0
  const { state, flow } = ownerFlow({ detail: async () => detail(), preview: async () => preview, update: async () => { calls++ } }, async () => false)
  await flow.load(); flow.edit('raw'); await flow.save('SUBMIT'); assert.equal(calls, 0); await flow.preview(); await flow.save('SUBMIT'); assert.equal(calls, 0); assert.ok(state.preview)
})
test('submit sends exact redacted preview and retries original canonical body and key', async () => {
  let reads = 0, calls = 0, confirmations = 0; const requests = []
  const { state, flow } = ownerFlow({ detail: async () => ++reads === 1 ? detail() : detail(1, 'PENDING', preview.public_text), preview: async () => preview,
    update: async (t, o, b, k) => { requests.push([b, k]); if (++calls === 1) throw new ServiceError('network', 'fail'); return detail(1, 'PENDING', preview.public_text) } }, async (a, text) => { confirmations++; assert.equal(text, preview.public_text); return true })
  await flow.load(); flow.edit('raw 13800000000'); await flow.preview(); await flow.save('SUBMIT'); assert.equal(state.retryAction, 'SUBMIT'); await flow.save('SUBMIT')
  assert.deepEqual(requests[0], requests[1]); assert.equal(confirmations, 1); assert.equal(requests[0][0].public_text, preview.public_text); assert.equal(state.preview, null); assert.equal(state.value.status, 'PENDING')
})
test('withdraw remains available when source invalid and clears draft after current read', async () => {
  const requests = []; let reads = 0
  const { state, flow } = ownerFlow({ detail: async () => ++reads === 1 ? { ...detail(2, 'APPROVED', 'old'), can_submit: false, visible: false, unavailable_reason: 'PUBLIC_SOURCE_UNVERIFIED' } : detail(3, 'WITHDRAWN'), update: async (t, o, b) => requests.push(b) })
  await flow.load(); await flow.save('WITHDRAW'); assert.deepEqual(requests, [{ action: 'WITHDRAW', expected_version: 2 }]); assert.equal(state.draft, ''); assert.equal(state.value.status, 'WITHDRAWN')
})
test('conflict clears sensitive draft and 422 requires a new preview', async () => {
  for (const kind of ['conflict', 'preview']) {
    const { state, flow } = ownerFlow({ detail: async () => detail(), preview: async () => preview, update: async () => { throw new ServiceError(kind, 'fail') } }); await flow.load(); flow.edit('draft'); await flow.preview(); await flow.save('SUBMIT'); assert.equal(state.preview, null); assert.equal(state.retryAction, null); if (kind === 'conflict') { assert.equal(state.draft, ''); assert.equal(state.value, null) }
  }
})
test('hide while confirming cannot post or retain a private draft', async () => {
  let calls = 0; const gate = deferred(), { state, flow } = ownerFlow({ detail: async () => detail(), preview: async () => preview, update: async () => { calls++ } }, () => gate.promise)
  await flow.load(); flow.edit('draft'); await flow.preview(); const task = flow.save('SUBMIT'); flow.reset(); gate.resolve(true); await task; assert.equal(calls, 0); assert.deepEqual(state, initialTextState())
})
test('account change during a write ignores its completion and does not read old actor data', async () => {
  let token = 'a', reads = 0; const gate = deferred(), started = deferred(), state = initialTextState()
  const flow = createTextFlow({ state, token: () => token, order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => { reads++; return detail() }, preview: async () => preview, update: () => { started.resolve(); return gate.promise } } })
  await flow.load(); flow.edit('draft'); await flow.preview(); const task = flow.save('SUBMIT'); await started.promise; token = 'b'; flow.reset(); gate.resolve(detail(1, 'PENDING', 'text')); await task; assert.equal(reads, 1); assert.deepEqual(state, initialTextState())
})
test('success then refresh failure never displays historical write response', async () => {
  let reads = 0; const { state, flow } = ownerFlow({ detail: async () => { if (++reads > 1) throw new ServiceError('network', 'fail'); return detail() }, preview: async () => preview, update: async () => detail(1, 'PENDING', 'old') })
  await flow.load(); flow.edit('draft'); await flow.preview(); await flow.save('SUBMIT'); assert.equal(state.value, null); assert.equal(state.draft, ''); assert.equal(state.retryAction, null)
})
test('public list resets on hide and deduplicates moving pages by public id', async () => {
  const state = initialTextListState(), gate = deferred()
  const flow = createTextListFlow({ state, token: () => 'a', merchant: () => 1, api: { list: async (t, m, p) => publicPage(p) } })
  await flow.load(); await flow.load(true); assert.equal(state.value.items.length, 1)
  const late = createTextListFlow({ state, token: () => 'a', merchant: () => 1, api: { list: () => gate.promise } }); const task = late.load(); late.reset(); gate.resolve(publicPage()); await task; assert.deepEqual(state, initialTextListState())
})
test('operator confirms approval, retries same key, and refreshes queue after success', async () => {
  let calls = 0, confirmations = 0; const requests = [], state = initialTextListState()
  const flow = createTextListFlow({ state, token: () => 'op', operator: true, newKey: () => key, confirm: async () => { confirmations++; return true }, api: { pending: async () => pendingPage(), moderate: async (t, r, b, k) => { requests.push([r, b, k]); if (++calls === 1) throw new ServiceError('network', 'fail') } } })
  await flow.load(); await flow.moderate(state.value.items[0], 'APPROVE', null); await flow.moderate(state.value.items[0], 'APPROVE', null); assert.deepEqual(requests[0], requests[1]); assert.equal(confirmations, 1); assert.equal(state.busy, false)
})
test('operator stale-version conflict or account failure clears pending text', async () => {
  for (const kind of ['conflict', 'unauthorized', 'forbidden']) {
    const state = initialTextListState(), flow = createTextListFlow({ state, token: () => 'op', operator: true, newKey: () => key, confirm: async () => true, api: { pending: async () => pendingPage(), moderate: async () => { throw new ServiceError(kind, 'fail') } } }); await flow.load(); await flow.moderate(state.value.items[0], 'REJECT', 'PRIVACY'); assert.equal(state.value, null)
  }
})
