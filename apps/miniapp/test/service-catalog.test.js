import test from 'node:test'
import assert from 'node:assert/strict'
import { createServiceCatalogApi, ServiceError } from '../src/services/service-catalog.js'
import { initialServiceListState, createServiceListFlow, initialServiceDetailState, createServiceDetailFlow } from '../src/services/service-catalog-flow.js'
const item = (id = 1, category = 1) => ({ id, category, project_name: '本地合成项目', base_price_low: '0.10', base_price_high: '199.99' })
const page = (items, total = items.length, n = 1) => ({ items, total, page: n, page_size: 20 })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
test('request validates exact response, category, money and authenticated URL', async () => {
  let response = { statusCode: 200, data: { code: 0, data: page([item(1,2)]) } }, request
  const api = createServiceCatalogApi({ baseUrl: 'http://test/', runtime: () => ({ request: options => { request=options; options.success(response) } }) })
  await api.list('owner',2)
  assert.equal(request.url,'http://test/api/service/projects?page=1&page_size=20&category=2')
  assert.equal(request.header.Authorization,'Bearer owner')
  for (const invalid of [item(1,1), { ...item(1,2), base_price_low: 0.1 }, { ...item(1,2), base_price_high: '0.01' }]) {
    response.data.data = page([invalid]); await assert.rejects(api.list('owner',2), { kind: 'protocol' })
  }
  assert.throws(() => api.list('',2), { kind: 'unauthorized' })
  assert.throws(() => api.list('owner',7), { kind: 'invalid' })
})
test('detail requires matching id, content and handles safe statuses', async () => {
  let response = { statusCode: 200, data: { code: 0, data: { ...item(), service_content: '内容', quality_standard: null } } }
  const api = createServiceCatalogApi({ baseUrl: 'http://test', runtime: () => ({ request: options => options.success(response) }) })
  assert.equal((await api.detail('owner',1)).quality_standard,null)
  await assert.rejects(api.detail('owner',2), { kind: 'protocol' })
  response.statusCode=404; await assert.rejects(api.detail('owner',1), { kind: 'missing' })
  response.statusCode=503; await assert.rejects(api.detail('owner',1), { kind: 'unavailable' })
})
test('category switch discards late response', async () => {
  const first=deferred(), second=deferred(), state=initialServiceListState()
  const flow=createServiceListFlow({ state, token: () => 'owner', api: { list: (_token, category) => category ? second.promise : first.promise } })
  const a=flow.load(), b=flow.select(2)
  second.resolve(page([item(2,2)])); await b
  first.resolve(page([item(1)])); await a
  assert.deepEqual(state.items,[item(2,2)]); assert.equal(state.category,2)
})
test('pagination failure retains rows and retries same page', async () => {
  const state=initialServiceListState(), calls=[]
  let fail=true
  const flow=createServiceListFlow({ state, token: () => 'owner', api: { list: async (_t,_c,n) => {
    calls.push(n); if(n===2 && fail) throw new ServiceError('network','重试'); return page([item(n)],2,n)
  } } })
  await flow.load(); await flow.load(true)
  assert.equal(state.page,1); assert.deepEqual(state.items,[item(1)])
  fail=false; await flow.retry()
  assert.deepEqual(calls,[1,2,2]); assert.equal(state.items.length,2)
})
test('failed refresh of a complete list retries first page', async () => {
  const state=initialServiceListState(); let fail=false; const calls=[]
  const flow=createServiceListFlow({ state, token: () => 'owner', api: { list: async (_t,_c,n) => {
    calls.push(n); if(fail) throw new ServiceError('network','重试'); return page([item()])
  } } })
  await flow.load(); fail=true; await flow.load(); fail=false; await flow.retry()
  assert.deepEqual(calls,[1,1,1]); assert.equal(state.message,'')
})
test('hide and identity reset reject late list updates', async () => {
  const state=initialServiceListState(); let owner='first', pending=deferred()
  const flow=createServiceListFlow({ state, token: () => owner, api: { list: () => pending.promise } })
  let loading=flow.load(); flow.suspend(); pending.resolve(page([item()])); await loading
  assert.equal(state.items.length,0); assert.equal(state.busy,false)
  pending=deferred(); loading=flow.load(); owner='second'; flow.reset(); pending.resolve(page([item()])); await loading
  assert.equal(state.loaded,false); assert.equal(state.items.length,0)
})
test('detail reload removes stale content and revoked identity never restores it', async () => {
  const state=initialServiceDetailState(); let owner='first', pending=deferred()
  const flow=createServiceDetailFlow({ state, token: () => owner, api: { detail: () => pending.promise } })
  let loading=flow.load(1); pending.resolve(item()); await loading; assert.equal(state.item.id,1)
  pending=deferred(); loading=flow.load(1); assert.equal(state.item,null)
  owner=''; flow.reset(); pending.resolve(item()); await loading; assert.equal(state.item,null)
  await flow.load(1); assert.equal(state.failureKind,'unauthorized')
})
