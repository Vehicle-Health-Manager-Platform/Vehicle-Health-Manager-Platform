import test from 'node:test'
import assert from 'node:assert/strict'
import { createExperienceCardsApi, validExperienceCard, ExperienceCardError, createExperienceFlow, initialExperienceState } from '../src/services/experience-cards.js'
const uuid = '01234567-89ab-4cde-8fab-0123456789ab'
const card = (fields = {}) => ({ card_id: 1, vehicle_id: 2, order_id: 3, archive_id: 4, title: '施工经验摘要', status: 'DRAFT', revision: 0, test_mode: false,
  summary: { version: 1, work_minutes: 40, part_kinds: 0, no_parts: true, recorded_month: '2026-10' }, consent_version: null, consented_at: null, withdrawn_at: null, ...fields })
const sent = () => card({ status: 'PENDING_REVIEW', revision: 1, consent_version: 'experience-v1', consented_at: '2026-10-09T02:00:00Z' })
const removed = () => card({ status: 'WITHDRAWN', revision: 1, withdrawn_at: '2026-10-09T02:00:00Z' })
const list = () => ({ items: [card()], total: 1, page: 1, page_size: 20 })

test('卡片协议只接受结构化摘要和一致的授权状态，拒绝泄露字段', () => {
  assert.equal(validExperienceCard(card()), true); assert.equal(validExperienceCard(sent()), true); assert.equal(validExperienceCard(removed()), true)
  for (const c of [card({ user_id: 1 }), card({ title: '<script>' }), card({ status: 'PUBLISHED' }), card({ revision: 1 }), sentWithTest(), card({ summary: { ...card().summary, notes: 'VIN' } }),
    card({ summary: { ...card().summary, no_parts: false } }), card({ summary: { ...card().summary, recorded_month: '2026-13' } }), card({ summary: { ...card().summary, work_minutes: 0 } }),
    card({ summary: { ...card().summary, part_kinds: 21 } }), card({ card_id: Number.MAX_SAFE_INTEGER + 1 }), card({ consented_at: 'invalid' })]) assert.equal(validExperienceCard(c), false)
  function sentWithTest() { return { ...sent(), test_mode: true } }
})
test('本人分页与声明/撤回请求使用固定路径和载荷，不传身份或自由原文', async () => {
  const calls = [], api = createExperienceCardsApi({ baseUrl: 'http://local/', runtime: () => ({ request: r => { calls.push(r); r.success({ statusCode: 200, data: { code: 0, data: r.method === 'GET' ? list() : { card: r.url.endsWith('consent') ? sent() : removed() } } }) } }) })
  await api.list('owner', 2); await api.change('owner', card(), 'consent', uuid); await api.change('owner', card(), 'withdraw', uuid)
  assert.equal(calls[0].url, 'http://local/api/experience-cards?vehicle_id=2&page=1&page_size=20')
  assert.deepEqual(calls[1].data, { agree: true, consent_version: 'experience-v1' }); assert.deepEqual(calls[2].data, {})
  assert.equal(calls[1].header['Idempotency-Key'], uuid); assert.equal(calls[1].header.Authorization, 'Bearer owner')
  assert.throws(() => api.change('owner', card({ test_mode: true }), 'consent', uuid)); assert.throws(() => api.change('owner', card(), 'consent', ''))
  assert.throws(() => api.list('owner', 0)); assert.throws(() => api.list('', 2)); assert.throws(() => api.list('owner', 2, 1000001))
})
test('协议异常和身份/数据库/网络错误不接受为成功', async () => {
  for (const status of [400, 401, 403, 404, 409, 503]) {
    const api = createExperienceCardsApi({ baseUrl: 'http://local', runtime: () => ({ request: r => r.success({ statusCode: status, data: { code: 1 } }) }) })
    await assert.rejects(api.list('owner', 2), ExperienceCardError)
  }
  for (const data of [{ ...list(), items: [card({ vehicle_id: 7 })] }, { ...list(), items: [card(), card()], total: 2 }, { ...list(), total: -1 }, { ...list(), extra: 'identity' }, { card: { ...sent(), order_id: 8 } }]) {
    const api = createExperienceCardsApi({ baseUrl: 'http://local', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data } }) }) })
    await assert.rejects(data.card ? api.change('owner', card(), 'consent', uuid) : api.list('owner', 2), e => e.kind === 'protocol')
  }
})
function fixture(custom = {}) {
  const state = initialExperienceState(), calls = []; let actor = 'owner', car = 2, number = 0
  const flow = createExperienceFlow({ state, token: () => actor, vehicle: () => car, newKey: () => `${uuid.slice(0, -1)}${++number}`,
    api: { list: async () => list(), change: async (...args) => { calls.push(args); return { card: sent() } }, ...custom } })
  flow.resume(); state.rows = [card()]; state.loaded = true; state.total = 1; state.page = 1
  return { state, flow, calls, actor: v => { actor = v }, vehicle: v => { car = v } }
}
test('明确未预选授权与测试阻断；提交成功只替换当前卡片', async () => {
  const f = fixture(); await f.flow.change(card(), 'consent'); assert.equal(f.calls.length, 0)
  f.state.rows = [card({ test_mode: true })]; await f.flow.change(f.state.rows[0], 'consent', true); assert.equal(f.calls.length, 0)
  f.state.rows = [card()]; await f.flow.change(card(), 'consent', true); assert.equal(f.state.rows[0].status, 'PENDING_REVIEW'); assert.match(f.state.message, /尚未公开/)
})
test('网络及数据库失败保留原操作键，明确冲突后新键', async () => {
  const calls = []; let kind = 'network'
  const f = fixture({ change: async (...a) => { calls.push(a); throw new ExperienceCardError(kind, '重试') } })
  await f.flow.change(card(), 'consent', true); await f.flow.change(card(), 'consent', true); assert.equal(calls[0][3], calls[1][3])
  kind = 'unavailable'; await f.flow.change(card(), 'consent', true); assert.equal(calls[1][3], calls[2][3])
  kind = 'conflict'; await f.flow.change(card(), 'consent', true); await f.flow.change(card(), 'consent', true); assert.notEqual(calls[3][3], calls[4][3])
})
test('双击只发送一次，换账号/换车/离页忽略迟到写响应', async () => {
  for (const steer of [f => f.actor('other'), f => f.vehicle(7), f => { f.flow.reset(); f.flow.resume() }]) {
    let resolve, count = 0; const f = fixture({ change: () => { count++; return new Promise(r => { resolve = r }) } })
    const pending = f.flow.change(card(), 'consent', true); await f.flow.change(card(), 'consent', true); assert.equal(count, 1)
    steer(f); resolve({ card: sent() }); await pending
    assert.equal(f.state.rows.some(c => c.status === 'PENDING_REVIEW'), false)
  }
})
test('离页读取和重新进入不接收旧响应；分页去重', async () => {
  let resolve; const f = fixture({ list: () => new Promise(r => { resolve = r }) }); const old = f.flow.load(); f.flow.reset(); f.flow.resume(); resolve(list()); await old; assert.equal(f.state.rows.length, 0)
  const g = fixture({ list: async () => ({ items: [card(), card({ card_id: 6 })], total: 2, page: 2, page_size: 20 }) }); await g.flow.load(true); assert.equal(g.state.rows.length, 2)
})
test('撤回和重新授权使用各自的新操作键，旧版本不能提交', async () => {
  const calls = []; const f = fixture({ change: async (...a) => { calls.push(a); return { card: a[2] === 'withdraw' ? removed() : { ...sent(), revision: 2 } } } })
  await f.flow.change(card(), 'withdraw'); assert.equal(f.state.rows[0].status, 'WITHDRAWN')
  await f.flow.change(card(), 'consent', true); assert.equal(calls.length, 1)
  await f.flow.change(f.state.rows[0], 'consent', true); assert.equal(calls.length, 2); assert.notEqual(calls[0][3], calls[1][3])
})
