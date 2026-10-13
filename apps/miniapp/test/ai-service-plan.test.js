import test from 'node:test'
import assert from 'node:assert/strict'
import { createAiServicePlanApi, validPlan, createAiServicePlanFlow, initialAiServicePlanState } from '../src/services/ai-service-plan.js'
const project = n => ({ project_id: n, project_name: `项目${n}`, category: 1, service_content: '公开内容' })
const catalog = () => ({ items: [1, 2, 3, 4].map(project), total: 4, limit: 40 })
const row = (id, tier = 'RECOMMENDED') => ({ project_id: id, project_name: `项目${id}`, category: 1, tier, reason_code: tier === 'REQUESTED' ? 'OWNER_SELECTED' : 'MODEL_MATCH', evidence_quote: '异响' })
const result = () => ({ status: 'AI_READY', question: '异响', candidate_count: 4, items: [row(1, 'REQUESTED'), row(2)] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
test('plan protocols reject prices, fabricated necessity and altered evidence', () => {
  assert.ok(validPlan(result(), [1])); for (const bad of [{ ...result(), items: [{ ...row(1, 'REQUESTED'), price: '100.00' }] }, { ...result(), items: [row(1, 'REQUESTED'), row(2, 'MUST')] }, { ...result(), items: [{ ...row(1, 'REQUESTED'), evidence_quote: '故障确定' }] }, { ...result(), status: 'MANUAL' }, { ...result(), items: [row(1, 'REQUESTED'), row(1)] }]) assert.equal(!!validPlan(bad, [1]), false)
  assert.ok(validPlan({ status: 'SOURCE_CHANGED', question: '异响', candidate_count: 0, items: [] }, [1]))
})
test('POST requires explicit purpose and only question with public project ids', async () => {
  let request; const api = createAiServicePlanApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { request = r; r.success({ statusCode: 200, data: { code: 0, data: result() } }) } }) })
  await api.generate('owner', '异响', [1]); assert.equal(request.method, 'POST'); assert.deepEqual(request.data, { question: '异响', requested_project_ids: [1], consent_to_provider: true }); assert.equal(request.header['Idempotency-Key'], undefined); assert.equal(request.url, 'https://test.invalid/api/ai/service-plan')
  assert.throws(() => api.generate('owner', '异响', [1, 1])); assert.throws(() => api.generate('owner', 'a'.repeat(301), []))
})
test('catalog rejects leaked data and malformed item before rendering', async () => {
  const api = createAiServicePlanApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: { ...catalog(), items: [{ ...project(1), user_id: 9 }] } } }) }) }); await assert.rejects(api.catalog('owner'), { kind: 'protocol' })
})
test('explicit selection is capped at three and removing one invalidates result', async () => {
  const state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', api: { catalog: async () => catalog() } }); await flow.load(); for (const id of [1, 2, 3, 4]) flow.toggle(id); assert.deepEqual(state.requested, [1, 2, 3]); state.value = result(); flow.toggle(1); assert.equal(state.value, null); assert.deepEqual(state.requested, [2, 3])
})
test('cancel purpose confirmation makes no model request', async () => {
  let calls = 0; const state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', confirm: async () => false, api: { catalog: async () => catalog(), generate: async () => { calls++ } } }); await flow.load(); flow.edit('异响'); await flow.generate(); assert.equal(calls, 0); assert.equal(state.busy, false)
})
test('project route carries only current standard id, never question or quotes', async () => {
  const state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', confirm: async () => true, api: { catalog: async () => catalog(), generate: async () => result() } }); await flow.load(); flow.edit('异响'); flow.toggle(1); await flow.generate(); assert.equal(flow.projectRoute(state.value.items[1]), '/pages/service/detail?id=2'); assert.equal(flow.projectRoute(row(99)), null); flow.edit('新问题'); assert.equal(state.value, null)
})
test('editing during an in-flight generation ignores the old result', async () => {
  const gate = deferred(), state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', confirm: async () => true, api: { catalog: async () => catalog(), generate: () => gate.promise } }); await flow.load(); flow.edit('异响'); const task = flow.generate(); await Promise.resolve(); flow.edit('新问题'); gate.resolve(result()); await task; assert.equal(state.value, null); assert.equal(state.draft, '新问题')
})
test('hide while confirming clears question and prevents any request', async () => {
  let calls = 0; const gate = deferred(), state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', confirm: () => gate.promise, api: { catalog: async () => catalog(), generate: async () => { calls++ } } }); await flow.load(); flow.edit('异响'); const task = flow.generate(); flow.reset(); gate.resolve(true); await task; assert.equal(calls, 0); assert.deepEqual(state, initialAiServicePlanState())
})
test('account change during catalog or model response discards all old state', async () => {
  let token = 'a'; const gate = deferred(), state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => token, api: { catalog: () => gate.promise } }); const task = flow.load(); token = 'b'; flow.reset(); gate.resolve(catalog()); await task; assert.deepEqual(state, initialAiServicePlanState())
})
test('failed refresh cannot leave an old result available for navigation', async () => {
  const state = initialAiServicePlanState(), flow = createAiServicePlanFlow({ state, token: () => 'a', api: { catalog: async () => { throw Error('private transport') } } }); state.value = result(); await flow.load(); assert.equal(state.value, null); assert.equal(flow.projectRoute(row(1)), null); assert.equal(state.message.includes('private'), false)
})
