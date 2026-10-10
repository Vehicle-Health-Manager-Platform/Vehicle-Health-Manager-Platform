import test from 'node:test'
import assert from 'node:assert/strict'
import { createPublicationApi, validPublic, validPending, createPublicationFlow, initialPublicationState, PublicationError } from '../src/services/experience-publication.js'
import { validExperienceCard, createExperienceCardsApi } from '../src/services/experience-cards.js'
import { createApiRuntime } from '../src/services/api-runtime.js'
const key = '01234567-89ab-4cde-8fab-0123456789ab'
const facts = { version: 1, work_minutes: 40, part_kinds: 0, no_parts: true, recorded_month: '2026-10' }
const pending = { card_id: 4, revision: 1, title: '施工经验摘要', summary: facts, model_id: 3 }
const item = { experience_id: key, title: '施工经验摘要', summary: facts, model_id: 3, published_at: '2026-10-09T00:00:00Z' }
const login = { access_token: 'operator-token', token_type: 'Bearer', expires_in: 900, user: { id: 1, role: 'operator', can_review: true } }
const apiFor = (data, calls = [], statusCode = 200) => createPublicationApi({ baseUrl: 'https://local', runtime: () => ({ request(r) { calls.push(r); r.success({ statusCode, data: { code: 0, data } }) } }) })
test('公共与待审协议拒绝原文、私有来源ID、错误车型与不可信摘要', () => {
  assert.ok(validPublic(item)); assert.ok(validPending(pending)); assert.ok(validPending({ ...pending, model_id: null }))
  for (const x of [{ ...item, order_id: 2 }, { ...item, experience_id: '4' }, { ...item, model_id: 0 }, { ...item, summary: { ...facts, notes: 'private' } }, { ...item, published_at: null }]) assert.equal(validPublic(x), false)
  for (const x of [{ ...pending, user_id: 2 }, { ...pending, revision: 0 }, { ...pending, model_id: Number.MAX_SAFE_INTEGER + 1 }]) assert.equal(validPending(x), false)
})
test('运营密码短信登录严格核对独立身份，原生请求不传车主令牌', async () => {
  const calls = []; await apiFor(login, calls).login('reviewer', 'private password', '123456')
  assert.equal(calls[0].header.Authorization, undefined); assert.deepEqual(calls[0].data, { account: 'reviewer', password: 'private password', sms_code: '123456' })
  for (const result of [{ ...login, refresh_token: 'x' }, { ...login, user: { ...login.user, role: 'owner' } }, { ...login, expires_in: 0 }]) await assert.rejects(apiFor(result).login('reviewer', 'p', '123456'), e => e.kind === 'protocol')
  assert.throws(() => apiFor(login).login('reviewer', 'p', '123')); assert.throws(() => apiFor(login).code('reviewer', '密'.repeat(25)))
})
test('短信失败、限流、登录失效和错误响应不会当作成功', async () => {
  for (const status of [400, 401, 403, 409, 429, 503]) await assert.rejects(apiFor({}, [], status).code('reviewer', 'p'), PublicationError)
  await apiFor({ sent: true, expires_in: 300 }).code('reviewer', 'p')
  await assert.rejects(apiFor({ sent: true, expires_in: 300, sms_code: '123456' }).code('reviewer', 'p'), e => e.kind === 'protocol')
})
test('批准与驳回固定载荷、UUID和预期revision；禁止无车型批准', async () => {
  const calls = [], result = { card_id: 4, revision: 2, status: 'PUBLISHED', decision: 'APPROVE', reason_code: null }
  await apiFor(result, calls).moderate('operator', pending, 'APPROVE', null, key)
  assert.deepEqual(calls[0].data, { revision: 1, decision: 'APPROVE', reason_code: null }); assert.equal(calls[0].header['Idempotency-Key'], key)
  assert.equal(calls[0].header.Authorization, 'Bearer operator')
  assert.throws(() => apiFor(result).moderate('o', { ...pending, model_id: null }, 'APPROVE', null, key))
  assert.throws(() => apiFor(result).moderate('o', pending, 'REJECT', 'free text', key))
  await apiFor({ ...result, status: 'REJECTED', decision: 'REJECT', reason_code: 'NOT_SUITABLE' }).moderate('o', pending, 'REJECT', 'NOT_SUITABLE', key)
  await assert.rejects(apiFor({ ...result, revision: 3 }).moderate('o', pending, 'APPROVE', null, key), e => e.kind === 'protocol')
})
test('同款读取仅传本人车辆，空候选页继续游标；拒绝重复ID及游标倒退失败', async () => {
  const calls = []; await apiFor({ items: [], next_cursor: 20 }, calls).experiences('owner', 2, 30)
  assert.match(calls[0].url, /vehicle_id=2&cursor=30$/)
  for (const result of [{ items: [item, item], next_cursor: null }, { items: [], next_cursor: 30 }, { items: [], next_cursor: null, total: 0 }]) await assert.rejects(apiFor(result).experiences('owner', 2, 30), e => e.kind === 'protocol')
  assert.throws(() => apiFor({}).experiences('', 2)); assert.throws(() => apiFor({}).experiences('o', 0))
})
test('发布与驳回本人状态仍严格校验授权，已展示必须先撤回', () => {
  const c = { card_id: 1, vehicle_id: 2, order_id: 3, archive_id: 4, title: '施工经验摘要', status: 'PUBLISHED', revision: 2, test_mode: false, summary: facts, consent_version: 'experience-v1', consented_at: '2026-10-09T00:00:00Z', withdrawn_at: null }
  assert.ok(validExperienceCard(c)); assert.ok(validExperienceCard({ ...c, status: 'REJECTED', review_reason: 'NOT_SUITABLE' }))
  assert.equal(validExperienceCard({ ...c, status: 'REJECTED' }), false); assert.equal(validExperienceCard({ ...c, test_mode: true }), false)
  assert.throws(() => createExperienceCardsApi({ baseUrl: 'x', runtime: () => ({}) }).change('o', c, 'consent', key))
})
function fixture(operator = false, methods = {}) {
  const state = initialPublicationState(); let actor = 'actor', vehicle = 2, count = 0
  const flow = createPublicationFlow({ state, operator, token: () => actor, vehicle: () => vehicle, newKey: () => key.slice(0,-1) + ++count,
    api: { experiences: async () => ({ items: [item], next_cursor: null }), pending: async () => ({ items: [pending], total: 1 }), ...methods } })
  flow.resume(); return { flow, state, actor: n => { actor = n }, vehicle: n => { vehicle = n } }
}
test('离页、换车、换账号丢弃迟到响应并清空摘要', async () => {
  for (const change of ['hide', 'vehicle', 'actor']) {
    let finish; const f = fixture(false, { experiences: () => new Promise(r => { finish = r }) }); const request = f.flow.load()
    if (change === 'hide') f.flow.reset(); else f[change](9)
    finish({ items: [item], next_cursor: null }); await request; assert.deepEqual(f.state.rows, [])
  }
  const f = fixture(); await f.flow.load(); f.flow.reset(); assert.equal(f.state.loaded, false); assert.deepEqual(f.state.rows, [])
})
test('空页继续查询、跨页去重，刷新删除此前已展示摘要', async () => {
  let n = 0; const cursors = [], f = fixture(false, { experiences: async (_, __, cursor) => { cursors.push(cursor); return ++n === 1 ? { items: [], next_cursor: 20 } : n < 4 ? { items: [item], next_cursor: n === 2 ? 10 : null } : { items: [], next_cursor: null } } })
  await f.flow.load(); await f.flow.load(true); await f.flow.load(true); assert.equal(f.state.rows.length, 1); assert.deepEqual(cursors, [null, 20, 10])
  await f.flow.load(); assert.deepEqual(f.state.rows, [])
})
test('审核网络或协议失败保留幂等键，明确冲突与刷新重新建键', async () => {
  const keys = []; let kind = 'network'
  const f = fixture(true, { moderate: async (...args) => { keys.push(args[4]); throw new PublicationError(kind, 'retry') } })
  await f.flow.load(); await f.flow.moderate(pending, 'APPROVE', null); kind = 'protocol'; await f.flow.moderate(pending, 'APPROVE', null)
  assert.equal(keys[0], keys[1]); kind = 'conflict'; await f.flow.moderate(pending, 'APPROVE', null); await f.flow.moderate(pending, 'APPROVE', null); assert.notEqual(keys[2], keys[3])
  await f.flow.load(); await f.flow.moderate(pending, 'APPROVE', null); assert.notEqual(keys[3], keys[4])
})
test('审核成功移除待审且要求刷新分页；离页不回写，双击只发送一次', async () => {
  let finish, writes = 0; const f = fixture(true, { moderate: () => { writes++; return new Promise(r => { finish = r }) } })
  await f.flow.load(); const p = f.flow.moderate(pending, 'APPROVE', null); await f.flow.moderate(pending, 'APPROVE', null); assert.equal(writes, 1)
  finish({}); await p; assert.deepEqual(f.state.rows, []); assert.equal(f.state.loaded, false)
  await f.flow.load(); const late = f.flow.moderate(pending, 'APPROVE', null); f.flow.reset(); finish({}); await late; assert.deepEqual(f.state.rows, [])
})
test('运营权限或会话失效时清除已加载摘要，分页失败也不残留', async () => {
  for (const kind of ['unauthorized','forbidden']) {
    const f = fixture(true, { moderate: async () => { throw new PublicationError(kind, 'denied') } })
    await f.flow.load(); await f.flow.moderate(pending, 'APPROVE', null); assert.deepEqual(f.state.rows, []); assert.equal(f.state.loaded, false)
    let failed=false;const page=fixture(true,{pending:async()=>{if(failed)throw new PublicationError(kind,'denied');return {items:[pending],total:50}}})
    await page.flow.load(); failed=true;await page.flow.load(true);assert.deepEqual(page.state.rows, [])
  }
})
for (const cloud of [false, true]) test(`${cloud ? '微信云托管' : 'uni.request'}运输登录、审核和同款请求，不混用身份`, async () => {
  const requests = [], send = r => { requests.push(r); const path = r.path || r.url
    r.success({ statusCode: 200, data: { code: 0, data: path.includes('/login') ? login : path.includes('/moderate') ? { card_id: 4, revision: 2, status: 'PUBLISHED', decision: 'APPROVE', reason_code: null } : { items: [item], next_cursor: null } } }) }
  const runtime = createApiRuntime({ origin: 'https://cloudrun.invalid', cloud: cloud ? { env: 'test', service: 'api' } : {}, runtime: () => ({ request: send }), cloudApi: { init() {}, callContainer: send } })
  const api = createPublicationApi({ baseUrl: 'https://cloudrun.invalid', runtime: () => runtime })
  await api.login('reviewer', 'p', '123456'); await api.moderate('operator', pending, 'APPROVE', null, key); await api.experiences('owner', 2)
  assert.equal(requests[0].header.Authorization, undefined); assert.equal(requests[1].header.Authorization, 'Bearer operator'); assert.equal(requests[2].header.Authorization, 'Bearer owner')
  if (cloud) { assert.equal(requests[1].path, '/api/admin/experience-cards/4/moderate'); assert.equal(requests[1].header['X-WX-SERVICE'], 'api') }
})
