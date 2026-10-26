import test from 'node:test'
import assert from 'node:assert/strict'
import { createMileageRecognition, pickupMileageResponse } from '../src/services/mileage-recognition.js'
import { createCandidateFlow, initialCandidateState } from '../src/services/input-candidates.js'
import { pickupBody, PICKUP_SLOTS } from '../src/services/pickup.js'

const response = (status = 'CANDIDATE', confidence = .95) => ({ order_id: 11, file_id: 7, review_status: status, requires_confirmation: true,
  recognition: { version: 1, scene: 'archive', status: 'CANDIDATE', fields: [{ key: 'mileage', value: '12345', confidence }] } })
const args = () => ({ token: 'synthetic-merchant', orderId: 11, fileId: 7, consent: true })
const fields = [{ key: 'mileage', kind: 'number', max: 7, maxValue: 9999999 }]
function client() {
  const requests = []; let aborted = 0
  const api = createMileageRecognition({ baseUrl: 'https://api.example', pickup: true, runtime: () => ({ request(o) { requests.push(o); return { abort() { aborted++ } } } }) })
  return { api, requests, get aborted() { return aborted } }
}
test('pickup uses distinct purpose and order endpoint without owner identifiers', async () => {
  const c = client(), p = c.api(args()), request = c.requests[0]
  assert.equal(request.url, 'https://api.example/api/check/pickup/mileage-recognition')
  assert.deepEqual(request.data, { order_id: 11, file_id: 7, consent: true, consent_version: 'PICKUP_MILEAGE_OCR_V1' })
  assert.equal(request.header.Authorization, 'Bearer synthetic-merchant')
  request.success({ statusCode: 200, data: { code: 0, data: response() } })
  assert.equal((await p).fields[0].value, '12345')
})
test('missing permission acknowledgement or invalid order never starts a request', async () => {
  for (const patch of [{ consent: false }, { orderId: 0 }, { orderId: 1.5 }, { orderId: undefined, vehicleId: 11 }, { token: '' }, { fileId: -1 }]) {
    const c = client(); await assert.rejects(c.api({ ...args(), ...patch })); assert.equal(c.requests.length, 0)
  }
})
test('wrong order file owner-shaped or expanded response cannot be applied', () => {
  for (const change of [d => d.order_id++, d => d.file_id++, d => d.vehicle_id = 11, d => d.requires_confirmation = false, d => d.recognition.fields[0].key = 'notes']) {
    const d = response(); change(d); assert.throws(() => pickupMileageResponse(d, 11, 7))
  }
})
test('low confidence stays low and ambiguous candidates are never automatically selected', () => {
  assert.equal(pickupMileageResponse(response('LOW_CONFIDENCE', .2), 11, 7).fields[0].confidence, .2)
  const d = response('AMBIGUOUS'); d.recognition = { version: 1, scene: 'archive', status: 'EMPTY', fields: [] }
  assert.equal(pickupMileageResponse(d, 11, 7).status, 'EMPTY')
})
test('human acknowledgement only fills mileage and keeps original pickup submission validation', async () => {
  const c = client(), state = initialCandidateState(), form = { mileage: '100', mileageReason: '保留原原因', arrivalReason: '', code: '012345', fuel: 'HALF', damageStatus: 'NONE', damages: [], photos: Object.fromEntries(PICKUP_SLOTS.map((slot, i) => [slot, { fileId: i + 1 }])) }
  const flow = createCandidateFlow({ state, actor: () => args().token, scope: () => 'pickup:11:7', scene: () => 'archive', definitions: () => fields, values: () => form, apply: patch => Object.assign(form, patch) })
  const pending = flow.recognize(({ signal }) => c.api({ ...args(), signal })); await Promise.resolve()
  c.requests[0].success({ statusCode: 200, data: { code: 0, data: response() } }); await pending
  assert.equal(form.mileage, '100'); flow.review(); assert.equal(flow.confirm(), false)
  state.ack = true; assert.equal(flow.confirm(), true); assert.equal(form.mileage, '12345'); assert.equal(form.mileageReason, '保留原原因')
  const body = pickupBody(11, form); assert.equal(body.appointment_code, '012345'); assert.equal(body.mileage, 12345)
  form.photos.FUEL.fileId = 0; assert.throws(() => pickupBody(11, form))
})
test('account order and photo changes invalidate a response already in flight', async () => {
  for (const mutate of [o => o.actor = 'other', o => o.order = 12, o => o.file = 8]) {
    const c = client(), state = initialCandidateState(), context = { actor: args().token, order: 11, file: 7 }; let applies = 0
    const flow = createCandidateFlow({ state, actor: () => context.actor, scope: () => `pickup:${context.order}:${context.file}`, scene: () => 'archive', definitions: () => fields, values: () => ({ mileage: '' }), apply: () => applies++ })
    const pending = flow.recognize(({ signal }) => c.api({ ...args(), signal })); await Promise.resolve(); mutate(context)
    c.requests[0].success({ statusCode: 200, data: { code: 0, data: response() } }); await pending
    flow.review(); state.ack = true; assert.equal(flow.confirm(), false); assert.equal(applies, 0); flow.cancel()
  }
})
test('editing original mileage while inference is pending blocks stale overwrite', async () => {
  const c = client(), state = initialCandidateState(), form = { mileage: '100' }; let applies = 0
  const flow = createCandidateFlow({ state, actor: () => args().token, scope: () => 'pickup:11:7', scene: () => 'archive', definitions: () => fields, values: () => form, apply: () => applies++ })
  const pending = flow.recognize(({ signal }) => c.api({ ...args(), signal })); await Promise.resolve(); form.mileage = '101'
  c.requests[0].success({ statusCode: 200, data: { code: 0, data: response() } }); await pending
  flow.review(); state.ack = true; assert.equal(flow.confirm(), false); assert.equal(applies, 0)
})
test('hide cancel and late callbacks cannot populate or submit the form', async () => {
  for (const action of ['cancel', 'suspend']) {
    const c = client(), state = initialCandidateState(); let applies = 0
    const flow = createCandidateFlow({ state, actor: () => args().token, scope: () => 'pickup:11:7', scene: () => 'archive', definitions: () => fields, values: () => ({ mileage: '' }), apply: () => applies++ })
    const pending = flow.recognize(({ signal }) => c.api({ ...args(), signal })); await Promise.resolve(); flow[action](); await pending
    c.requests[0].success({ statusCode: 200, data: { code: 0, data: response() } }); assert.equal(state.phase, 'closed'); assert.equal(c.aborted, 1); assert.equal(applies, 0)
  }
})
test('backend rejection stays an error with no private provider response disclosure', async () => {
  for (const statusCode of [401, 403, 404, 409, 429, 503]) {
    const c = client(), pending = c.api(args()); c.requests[0].success({ statusCode, data: { message: 'private object or provider detail' } })
    await assert.rejects(pending, error => !error.message.includes('private'))
  }
})
