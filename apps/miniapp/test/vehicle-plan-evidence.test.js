import test from 'node:test'
import assert from 'node:assert/strict'
import { createVehiclePlanEvidenceApi, validVehicleEvidence, createVehicleEvidenceFlow, initialVehicleEvidenceState, createApplicabilityFlow, initialApplicabilityState } from '../src/services/vehicle-plan-evidence.js'
import { healthMetrics } from '../src/services/health-observations.js'
const key = '11111111-1111-4111-8111-111111111111'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const rule = (version = 0, value = null) => ({ project_id: 1, model_id: 2, version, rule: value })
const known = { verdict: 'NOT_APPLICABLE', source_reference: '合成来源' }
const evidence = () => ({ vehicle: { vehicle_id: 1, model_id: 2, model_name: '合成车型', model_source: 'OWNER_REGISTERED', power_type: 'EV' }, observations: { vehicle_id: 1, today: '2026-10-13', list: healthMetrics.map(metric => ({ metric, revision: 0, archive_id: null, recorded_date: null, measurement: {}, status: 'NOT_SET' })) }, experiences: { items: [], next_cursor: null }, applicability: [{ project_id: 1, verdict: 'UNKNOWN', rule_version: 0, source_reference: null }] })
const apiFor = reply => createVehiclePlanEvidenceApi({ baseUrl: 'https://synthetic.invalid', runtime: () => ({ request: request => reply(request) }) })
test('evidence request sends only selected vehicle and project IDs', async () => {
  const api = apiFor(r => { assert.equal(r.method, 'POST'); assert.equal(r.url, 'https://synthetic.invalid/api/ai/service-plan/vehicle-evidence'); assert.deepEqual(r.data, { vehicle_id: 1, project_ids: [1] }); assert.deepEqual(r.header, { Authorization: 'Bearer owner' }); r.success({ statusCode: 200, data: { code: 0, data: evidence() } }) })
  assert.deepEqual(await api.evidence('owner', 1, [1]), evidence())
  assert.throws(() => api.evidence('owner', 1, [1, 1]), /有效项目/)
})
test('evidence refuses extra private fields, mismatched projects and model experiences', () => {
  assert.equal(validVehicleEvidence(evidence(), 1, [1]), true)
  for (const mutate of [r => { r.vehicle.vin = 'private' }, r => { r.applicability[0].project_id = 2 }, r => { r.applicability[0].source_reference = 'invented unknown' }, r => { r.observations.list[0].measurement = { value: '90' } }, r => { r.experiences.items = [{ experience_id: key, title: '施工经验摘要', model_id: 3, published_at: '2026-10-13T00:00:00Z', summary: { version: 1, work_minutes: 10, part_kinds: 0, no_parts: true, recorded_month: '2026-10' } }] }]) { const r = evidence(); mutate(r); assert.equal(validVehicleEvidence(r, 1, [1]), false) }
  const r = evidence(); r.vehicle.model_id = null; r.vehicle.model_name = null; assert.equal(validVehicleEvidence(r, 1, [1]), true)
})
test('evidence maps network, protocol and permission errors safely', async () => {
  for (const code of [401, 403, 404, 503]) await assert.rejects(apiFor(r => r.success({ statusCode: code })).evidence('owner', 1, [1]), e => ['unauthorized', 'forbidden', 'missing', 'unavailable'].includes(e.kind))
  await assert.rejects(apiFor(r => r.fail()).evidence('owner', 1, [1]), e => e.kind === 'network')
  await assert.rejects(apiFor(r => r.success({ statusCode: 200, data: { code: 0, data: {} } })).evidence('owner', 1, [1]), e => e.kind === 'protocol')
})
test('vehicle or account switch discards late private evidence', async () => {
  let actor = 'owner', selected = { vehicle: 1, projects: [1] }; const wait = deferred(), state = initialVehicleEvidenceState()
  const flow = createVehicleEvidenceFlow({ state, token: () => actor, selection: () => selected, api: { evidence: () => wait.promise } })
  const p = flow.load(); actor = 'other'; selected = { vehicle: 2, projects: [1] }; flow.reset(); wait.resolve(evidence()); await p; assert.equal(state.value, null); assert.equal(state.busy, false)
})
test('selected vehicle requires loaded evidence and blocks explicit not applicable', async () => {
  let selected = { vehicle: 1, projects: [1] }; const state = initialVehicleEvidenceState(), flow = createVehicleEvidenceFlow({ state, token: () => 'owner', selection: () => selected, api: { evidence: async () => evidence() } })
  assert.equal(flow.canChoose(1), false); await flow.load(); assert.equal(flow.canChoose(1), true); assert.equal(flow.canChoose(2), false)
  state.value.applicability[0].verdict = 'NOT_APPLICABLE'; assert.equal(flow.canChoose(1), false); flow.reset(); selected = { vehicle: 0, projects: [1] }; assert.equal(flow.canChoose(1), true)
})
test('rule API validates source, expected version and idempotency header', async () => {
  const body = { verdict: known.verdict, source_reference: known.source_reference, expected_version: 0 }
  const api = apiFor(r => { assert.equal(r.method, 'PUT'); assert.equal(r.header['Idempotency-Key'], key); assert.deepEqual(r.data, body); r.success({ statusCode: 200, data: { code: 0, data: rule(1, known) } }) })
  assert.equal((await api.saveRule('operator', 1, 2, body, key)).version, 1)
  for (const change of [{ source_reference: ' leading' }, { source_reference: 'a\u200db' }, { verdict: 'MUST' }, { expected_version: Number.MAX_SAFE_INTEGER }, { unexpected: true }]) assert.throws(() => api.saveRule('operator', 1, 2, { ...body, ...change }, key))
})
test('cancelled confirmation does not write rules', async () => {
  let calls = 0; const state = initialApplicabilityState(); state.value = rule()
  const flow = createApplicabilityFlow({ state, token: () => 'operator', selection: () => ({ project: 1, model: 2 }), newKey: () => key, confirm: async () => false, api: { saveRule: () => { calls++ } } })
  await flow.save('APPLICABLE', '合成来源'); assert.equal(calls, 0); assert.equal(state.busy, false)
})
test('uncertain retry uses same key and rereads live rule after historical success', async () => {
  const keys = []; let count = 0; const state = initialApplicabilityState(); state.value = rule()
  const flow = createApplicabilityFlow({ state, token: () => 'operator', selection: () => ({ project: 1, model: 2 }), newKey: () => { count++; return key }, confirm: async () => true, api: { saveRule: async (t, p, m, b, k) => { keys.push(k); if (keys.length === 1) throw Object.assign(new Error('network'), { kind: 'network' }); return rule(1, known) }, rule: async () => rule(2) } })
  await flow.save('NOT_APPLICABLE', '合成来源'); await flow.save('NOT_APPLICABLE', '合成来源'); assert.deepEqual(keys, [key, key]); assert.equal(count, 1); assert.equal(state.value.version, 2); assert.equal(state.value.rule, null)
})
test('version conflict clears rule and requires fresh read before retry', async () => {
  const state = initialApplicabilityState(); state.value = rule(1, known); let calls = 0
  const flow = createApplicabilityFlow({ state, token: () => 'operator', selection: () => ({ project: 1, model: 2 }), newKey: () => key, confirm: async () => true, api: { saveRule: async () => { calls++; throw Object.assign(new Error('changed'), { kind: 'conflict' }) } } })
  await flow.save('WITHDRAW', null); await flow.save('WITHDRAW', null); assert.equal(calls, 1); assert.equal(state.value, null)
})
test('hide during write or reread cannot repopulate private state', async () => {
  const state = initialApplicabilityState(); state.value = rule(); const wait = deferred()
  const flow = createApplicabilityFlow({ state, token: () => 'operator', selection: () => ({ project: 1, model: 2 }), newKey: () => key, confirm: async () => true, api: { saveRule: async () => rule(1, known), rule: () => wait.promise } })
  const p = flow.save('NOT_APPLICABLE', '合成来源'); await new Promise(r => setImmediate(r)); flow.reset(); wait.resolve(rule(1, known)); await p; assert.equal(state.value, null); assert.equal(state.busy, false)
})
