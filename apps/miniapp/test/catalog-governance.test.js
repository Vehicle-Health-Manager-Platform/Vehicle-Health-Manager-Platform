import test from 'node:test'
import assert from 'node:assert/strict'
import { createCatalogApi, validCatalogRow, catalogBody } from '../src/services/catalog-governance.js'
import { createCatalogFlow, initialCatalogState } from '../src/services/catalog-flow.js'
import { createPublicationApi } from '../src/services/experience-publication.js'
const key = '01234567-89ab-4cde-8fab-0123456789ab'
const brand = { id: 1, name: '授权品牌', status: 0, revision: 1, sort_order: 0, source_note: '授权测试来源' }
const fixture = (result, requests = [], status = 200) => createCatalogApi({ baseUrl: 'https://test', runtime: () => ({ request(r) { requests.push(r); r.success({ statusCode: status, data: { code: 0, data: result } }) } }) })
test('catalog protocols reject unknown identity and malformed states', () => {
  assert.equal(validCatalogRow('brands', brand), true)
  for (const row of [{ ...brand, operator_id: 2 }, { ...brand, status: 2 }, { ...brand, revision: 0 }, { ...brand, id: '1' }, { ...brand, sort_order: -1 }]) assert.equal(validCatalogRow('brands', row), false)
})
test('catalog writes send exact body, scoped identity and UUID key, single envelope', async () => {
  const requests = [], body = catalogBody('brands', { ...brand, id: 'must-not-send', can_catalog: true })
  assert.deepEqual(body, { name: brand.name, sort_order: 0, source_note: brand.source_note })
  await fixture(brand, requests).save('operator-token', 'brands', body, key)
  assert.equal(requests[0].header.Authorization, 'Bearer operator-token'); assert.equal(requests[0].header['Idempotency-Key'], key)
  assert.equal(requests[0].method, 'POST'); assert.deepEqual(requests[0].data, body)
  await assert.rejects(fixture({ data: brand }).save('token', 'brands', body, key), e => e.kind === 'protocol')
})
test('invalid directory choices and anonymous calls never send requests', async () => {
  const requests = [], api = fixture(brand, requests)
  await assert.rejects(api.list('', 'brands'), e => e.kind === 'unauthorized')
  await assert.rejects(api.list('token', 'unknown'), e => e.kind === 'invalid')
  await assert.rejects(api.save('token', 'brands', {}, 'bad'), e => e.kind === 'invalid')
  assert.equal(requests.length, 0)
  assert.throws(() => catalogBody('models', { series_id: 1, year: '2026', config_name: '配置', power_type: 'guess', source_note: '授权来源', sort_order: 0 }))
})
test('failed pagination preserves rows for retry and maps revoked permission', async () => {
  const state = initialCatalogState(); let fail = false
  const api = { async list(_, __, page) { if (fail) throw Object.assign(new Error('网络失败'), { kind: 'network' }); return { items: [brand], total: 21, page, page_size: 20 } } }
  const flow = createCatalogFlow({ state, api, token: () => 'token', selectedKind: () => 'brands', newKey: () => key })
  await flow.load(); fail = true; await flow.load(true); assert.deepEqual(state.rows, [brand]); assert.equal(state.page, 1)
  await assert.rejects(fixture(null, [], 403).list('token', 'brands'), e => e.kind === 'forbidden')
})
test('network retry reuses write key but edited payload gets a new key', async () => {
  const state = initialCatalogState(), keys = []; let count = 0
  const api = { async save(_, __, body, key) { keys.push(key); throw Object.assign(new Error('断网'), { kind: 'network' }) } }
  const flow = createCatalogFlow({ state, api, token: () => 'token', selectedKind: () => 'brands', newKey: () => String(++count) })
  await flow.save({ name: '甲' }, null); await flow.save({ name: '甲' }, null); await flow.save({ name: '乙' }, null)
  assert.deepEqual(keys, ['1', '1', '2'])
})
test('leaving or changing identity discards late read and write results', async () => {
  const state = initialCatalogState(); let resolve, token = 'first'
  const flow = createCatalogFlow({ state, api: { list: () => new Promise(r => resolve = r) }, token: () => token, selectedKind: () => 'brands', newKey: () => key })
  const pending = flow.load(); token = 'second'; flow.reset(); resolve({ items: [brand], total: 1, page: 1 }); await pending
  assert.deepEqual(state, initialCatalogState())
})
test('operator login accepts new boolean catalog permission and old server without widening unknown fields', async () => {
  const base = { access_token: 'operator', token_type: 'Bearer', expires_in: 900, user: { id: 1, role: 'operator', can_review: false, can_onboard: false } }
  const api = result => createPublicationApi({ baseUrl: 'https://test', runtime: () => ({ request(r) { r.success({ statusCode: 200, data: { code: 0, data: result } }) } }) })
  await api(base).login('catalog', 'password', '123456'); await api({ ...base, user: { ...base.user, can_catalog: true } }).login('catalog', 'password', '123456')
  await assert.rejects(api({ ...base, user: { ...base.user, can_catalog: 'true' } }).login('catalog', 'password', '123456'), e => e.kind === 'protocol')
  await assert.rejects(api({ ...base, user: { ...base.user, can_catalog: true, can_admin: true } }).login('catalog', 'password', '123456'), e => e.kind === 'protocol')
})
