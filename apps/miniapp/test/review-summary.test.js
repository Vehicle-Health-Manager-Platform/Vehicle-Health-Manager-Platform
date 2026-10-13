import test from 'node:test'
import assert from 'node:assert/strict'
import { ServiceError } from '../src/services/service-catalog.js'
import { createReviewSummaryApi, validReviewSummary, validSummaryConsent, summarySentences, initialSummaryConsentState, createSummaryConsentFlow, initialReviewSummaryState, createReviewSummaryFlow } from '../src/services/review-summary.js'
const key = '11111111-1111-4111-8111-111111111111'
const consent = (version = 0, consented = false) => ({ order_id: 1, version, text_version: 2, consented, effective: consented, can_consent: true })
const text = () => ({ order_id: 1, version: 2, status: 'APPROVED', visible: true, public_text: '服务没有问题。处理完成。' })
const result = (status = 'AI_READY') => ({ merchant_id: 1, status, method: status === 'AI_READY' ? 'AI_EXTRACTIVE_V1' : 'EXACT_EXTRACT_V1', sample_count: 1, eligible_count: 1, highlights: [{ public_id: key, quote: '服务没有问题。' }], sources: [{ public_id: key, public_text: text().public_text, published_date: '2026-01-01' }] })
const empty = status => ({ merchant_id: 1, status, method: null, sample_count: 0, eligible_count: 0, highlights: [], sources: [] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
test('complete sentences retain negation and protocols reject partial or unsupported quotes', () => {
  assert.deepEqual(summarySentences(text().public_text), ['服务没有问题。', '处理完成。']); assert.ok(validReviewSummary(result(), 1)); assert.ok(validReviewSummary(result('FALLBACK'), 1))
  assert.deepEqual(summarySentences('！？'), ['！？'])
  for (const bad of [{ ...result(), highlights: [{ public_id: key, quote: '有问题' }] }, { ...result(), sources: [{ ...result().sources[0], order_id: 1 }] }, { ...result(), status: 'FALLBACK' }, { ...result(), sample_count: 21 }, { ...result(), highlights: [result().highlights[0], result().highlights[0]] }]) assert.equal(!!validReviewSummary(bad, 1), false)
  for (const status of ['EMPTY', 'DISABLED', 'SOURCE_CHANGED']) { assert.ok(validReviewSummary(empty(status), 1)); assert.equal(validReviewSummary({ ...empty(status), sources: result().sources }, 1), false) }
})
test('AI consent rejects extra fields and impossible effective state', () => {
  assert.ok(validSummaryConsent(consent(), 1)); assert.equal(!!validSummaryConsent({ ...consent(), effective: true }, 1), false); assert.equal(!!validSummaryConsent({ ...consent(), public_text: 'private' }, 1), false)
})
test('summary POST sends only empty JSON, no key and uses bounded transport timeout', async () => {
  let request
  const api = createReviewSummaryApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { request = r; r.success({ statusCode: 200, data: { code: 0, data: result() } }) } }) })
  await api.summarize('owner', 1); assert.equal(request.method, 'POST'); assert.deepEqual(request.data, {}); assert.equal(request.header['Idempotency-Key'], undefined); assert.equal(request.timeout, 60000); assert.equal(request.url, 'https://test.invalid/api/service/merchant/1/review-summary')
})
test('wrong purpose and unsafe grant input never requests and response details stay private', async () => {
  let calls = 0; const api = createReviewSummaryApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { calls++; r.success({ statusCode: 503, data: { message: 'private SQL' } }) } }) })
  assert.throws(() => api.update('owner', 1, { consented: true, expected_version: 0, expected_text_version: 2, consent_version: 'R4B2_TEXT_V1' }, key)); assert.equal(calls, 0); await assert.rejects(api.summarize('owner', 1), e => !e.message.includes('private'))
})
test('mismatched approved text and purpose snapshot requires refresh and clears text', async () => {
  const state = initialSummaryConsentState(), flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, api: { detail: async () => consent() }, textApi: { detail: async () => ({ ...text(), version: 4 }) } })
  await flow.load(); assert.equal(state.failureKind, 'conflict'); assert.equal(state.value, null); assert.equal(state.publicText, null)
})
test('purpose is never granted without explicit confirmation', async () => {
  let writes = 0; const state = initialSummaryConsentState(), flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => false, api: { detail: async () => consent(), update: async () => { writes++ } }, textApi: { detail: async () => text() } })
  await flow.load(); await flow.save(true); assert.equal(writes, 0); assert.equal(state.busy, false)
})
test('original key retry binds exact text version and reads current state after write', async () => {
  let writes = 0, confirmations = 0; const calls = [], state = initialSummaryConsentState()
  const flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async (grant, source) => { confirmations++; assert.equal(source, text().public_text); return true }, api: {
    detail: async () => consent(writes > 1 ? 2 : 0, false), update: async (t, o, b, k) => { calls.push([b, k]); if (++writes === 1) throw new ServiceError('network', 'fail'); return consent(1, true) },
  }, textApi: { detail: async () => text() } })
  await flow.load(); await flow.save(true); assert.equal(state.retryConsent, true); await flow.save(true); assert.deepEqual(calls[0], calls[1]); assert.equal(calls[0][0].expected_text_version, 2); assert.equal(confirmations, 1); assert.equal(state.value.effective, false); assert.equal(state.value.version, 2)
})
test('inactive text still allows revoking old AI purpose', async () => {
  let reads = 0; const calls = [], state = initialSummaryConsentState()
  const flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => ({ ...consent(++reads === 1 ? 1 : 2, reads === 1), effective: false, can_consent: false }), update: async (t, o, b) => calls.push(b) }, textApi: { detail: async () => ({ ...text(), visible: false }) } })
  await flow.load(); await flow.save(false); assert.equal(calls[0].consented, false); assert.equal(state.value.consented, false)
})
test('hide during confirmation stops grant and account switch discards loaded source text', async () => {
  const gate = deferred(), state = initialSummaryConsentState(); let writes = 0
  const flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: () => gate.promise, api: { detail: async () => consent(), update: async () => { writes++ } }, textApi: { detail: async () => text() } })
  await flow.load(); const task = flow.save(true); flow.reset(); gate.resolve(true); await task; assert.equal(writes, 0); assert.deepEqual(state, initialSummaryConsentState())
})
test('write success followed by refresh failure never leaves historical authorization visible', async () => {
  let reads = 0; const state = initialSummaryConsentState(), flow = createSummaryConsentFlow({ state, token: () => 'a', order: () => 1, newKey: () => key, confirm: async () => true, api: { detail: async () => { if (++reads > 1) throw new ServiceError('network', 'fail'); return consent() }, update: async () => consent(1, true) }, textApi: { detail: async () => text() } })
  await flow.load(); await flow.save(true); assert.equal(state.value, null); assert.equal(state.publicText, null); assert.equal(state.retryConsent, null)
})
test('refresh immediately removes old summary, and a late generation after hide is ignored', async () => {
  const gate = deferred(), state = initialReviewSummaryState(); state.value = result()
  const flow = createReviewSummaryFlow({ state, token: () => 'a', merchant: () => 1, api: { summarize: () => gate.promise } }); const task = flow.load(); assert.equal(state.value, null); flow.reset(); gate.resolve(result()); await task; assert.deepEqual(state, initialReviewSummaryState())
})
test('token change during generation cannot show prior-account citations', async () => {
  let token = 'a'; const gate = deferred(), state = initialReviewSummaryState(), flow = createReviewSummaryFlow({ state, token: () => token, merchant: () => 1, api: { summarize: () => gate.promise } })
  const task = flow.load(); token = 'b'; flow.reset(); gate.resolve(result()); await task; assert.deepEqual(state, initialReviewSummaryState())
})
test('source-changed and empty results preserve honest status without old references', async () => {
  const state = initialReviewSummaryState(), flow = createReviewSummaryFlow({ state, token: () => 'a', merchant: () => 1, api: { summarize: async () => empty('SOURCE_CHANGED') } })
  state.value = result(); await flow.load(); assert.equal(state.value.status, 'SOURCE_CHANGED'); assert.deepEqual(state.value.sources, [])
})
