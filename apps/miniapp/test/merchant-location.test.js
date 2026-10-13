import test from 'node:test'
import assert from 'node:assert/strict'
import { createNearbyApi, requestNativeLocation, validPoint, distanceLabel, createDistanceFlow, initialDistanceState } from '../src/services/nearby-merchants.js'
import { initialRegionalQuoteState, createRegionalQuoteFlow } from '../src/services/merchant-discovery-flow.js'
import { createMerchantLocationApi, locationBody, initialLocationState, createLocationFlow } from '../src/services/merchant-location.js'
import { ServiceError } from '../src/services/service-catalog.js'
const point = { lng: 113, lat: 22, coordinate_system: 'GCJ02' }
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j }); return { promise, resolve, reject } }
const row = { merchant_project_id: 1, merchant_id: 1, merchant_name: '合成门店', address: '合成地址', price: '100.00', version_id: 1, version: 1, distance_meters: 0, location_source: 'MERCHANT_CONFIRMED' }
const page = (items = [row], n = 1, total = items.length) => ({ items, page: n, page_size: 20, total })
const location = { lng: '113', lat: '22', coordinate_system: 'GCJ02', source: 'MERCHANT_CONFIRMED', version: 1 }
const key = '11111111-1111-4111-8111-111111111111'
test('native location is explicit GCJ02 and handles denial, unsupported runtime and timeout', async () => {
  const p = await requestNativeLocation(() => ({ getLocation: r => { assert.equal(r.type, 'gcj02'); r.success({ longitude: 113, latitude: 22 }) } }))
  assert.deepEqual(p, point)
  await assert.rejects(requestNativeLocation(() => ({ getLocation: r => r.fail({ errMsg: 'private' }) })), e => e.kind === 'location' && !e.message.includes('private'))
  await assert.rejects(requestNativeLocation(() => ({})), { kind: 'location' })
  await assert.rejects(requestNativeLocation(() => ({ getLocation() {} }), 5), { kind: 'location' })
})
test('invalid native coordinates never become a usable point and late success after timeout is ignored', async () => {
  for (const bad of [{ ...point, lng: Infinity }, { ...point, lat: 91 }, { ...point, coordinate_system: 'WGS84' }, { ...point, lng: '113' }]) assert.equal(validPoint(bad), false)
  let callback
  await assert.rejects(requestNativeLocation(() => ({ getLocation: r => { callback = r.success } }), 5), { kind: 'location' })
  callback({ longitude: 113, latitude: 22 })
  assert.match(distanceLabel(row), /0 米/); assert.match(distanceLabel({ distance_meters: null }), /距离未知/)
})
test('nearby requests never put precise position in URL or headers and reject private response fields', async () => {
  const requests = []
  const api = createNearbyApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => { requests.push(r); r.success({ statusCode: 200, data: { code: 0, data: page() } }) } }) })
  await api.list('owner', 11, point, 'distance_asc', 1, '440305')
  assert.equal(requests[0].method, 'POST'); assert.equal(requests[0].url, 'https://test.invalid/api/service/project/11/nearby-merchants'); assert.deepEqual(requests[0].data, { ...point, sort: 'distance_asc', page: 1, page_size: 20, region_code: '440305' })
  const invalid = createNearbyApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: page([{ ...row, lng: 113 }]) } }) }) })
  await assert.rejects(invalid.list('owner', 11, point), { kind: 'protocol' })
  const malformed = createNearbyApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: page([null]) } }) }) })
  await assert.rejects(malformed.list('owner', 11, point), { kind: 'protocol' })
  assert.throws(() => api.list('owner', 11, { ...point, lat: NaN }), { kind: 'invalid' }); assert.equal(requests.length, 1)
})
test('distance protocol binds merchant and unknown/source fields consistently', async () => {
  for (const result of [{ merchant_id: 2, distance_meters: 0, location_source: 'MERCHANT_CONFIRMED' }, { merchant_id: 1, distance_meters: null, location_source: 'MERCHANT_CONFIRMED' }, { merchant_id: 1, distance_meters: -1, location_source: 'MERCHANT_CONFIRMED' }]) {
    const api = createNearbyApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: result } }) }) })
    await assert.rejects(api.distance('owner', 1, point), { kind: 'protocol' })
  }
})
test('nearby keeps current region and price sorting, clears position on restore and hide', async () => {
  const state = initialRegionalQuoteState(), calls = []
  const flow = createRegionalQuoteFlow({ state, token: () => 'owner', project: () => 11, locate: async () => point, api: { ownerList: async () => page() }, nearbyApi: { list: async (...args) => { calls.push(args); return page() } } })
  flow.show(); flow.beginRegion(); await flow.selectRegion({ value: ['广东', '深圳', '南山'], code: ['440000', '440300', '440305'] }); await flow.nearby()
  assert.equal(calls[0][3], 'distance_asc'); assert.equal(calls[0][5], '440305')
  await flow.sort('price_desc'); assert.equal(calls[1][3], 'price_desc'); assert.deepEqual(state.position, point)
  await flow.allRegions(); assert.equal(state.position, null); assert.equal(state.regionCode, null)
  await flow.nearby(); flow.suspend(); assert.equal(state.position, null); assert.deepEqual(state.items, [])
})
test('denial falls back to ordinary regional quotes without losing the selected region', async () => {
  const state = initialRegionalQuoteState(); let calls = 0
  const flow = createRegionalQuoteFlow({ state, token: () => 'owner', project: () => 11, locate: async () => { throw new ServiceError('location', '使用区域回退') }, api: { ownerList: async () => { calls++; return page() } } })
  flow.show(); await flow.nearby(); assert.equal(state.position, null); assert.equal(state.locating, false); assert.equal(calls, 1); assert.equal(state.locationMessage, '使用区域回退')
})
test('hide, account change and newer sort invalidate native location callbacks before API request', async () => {
  for (const action of ['hide', 'account', 'sort']) {
    const wait = deferred(), state = initialRegionalQuoteState(); let owner = 'one', count = 0
    const flow = createRegionalQuoteFlow({ state, token: () => owner, project: () => 11, locate: () => wait.promise, nearbyApi: { list: async () => { count++; return page() } }, api: { ownerList: async () => page() } })
    flow.show(); const locating = flow.nearby()
    if (action === 'hide') flow.suspend(); else if (action === 'account') { owner = 'two'; flow.reset() } else await flow.sort('price_desc')
    wait.resolve(point); await locating; assert.equal(state.position, null); assert.equal(count, 0)
  }
})
test('nearby permission loss clears old coordinates and pages', async () => {
  const state = initialRegionalQuoteState()
  const flow = createRegionalQuoteFlow({ state, token: () => 'owner', project: () => 11, locate: async () => point, nearbyApi: { list: async (t, p, pos, sort, n) => { if (n === 2) throw new ServiceError('unauthorized', 'expired'); return page([row], 1, 30) } }, api: {} })
  flow.show(); await flow.nearby(); await flow.load(true); assert.equal(state.position, null); assert.deepEqual(state.items, [])
})
test('detail distance has its own active location request and cannot restore after reset', async () => {
  const state = initialDistanceState(), wait = deferred(); let called = 0
  const flow = createDistanceFlow({ state, token: () => 'owner', merchant: () => 1, locate: () => wait.promise, api: { distance: async () => { called++; return row } } })
  const loading = flow.load(); flow.reset(); wait.resolve(point); await loading; assert.equal(called, 0); assert.equal(state.result, null)
})
test('store coordinates require explicit confirmation and preserve seven-decimal values without scientific notation', () => {
  assert.throws(() => locationBody({ lng: '113', lat: '22', confirmed: false }), { kind: 'invalid' })
  assert.equal(locationBody({ lng: '0.0000001', lat: '-0.0000001', confirmed: true }).lng, '0.0000001')
  assert.deepEqual(locationBody({}, true), { lng: null, lat: null, coordinate_system: null, source: null })
  for (const lng of ['181', '1e2', '1.12345678', ' 113']) assert.throws(() => locationBody({ lng, lat: '22', confirmed: true }), { kind: 'invalid' })
})
test('store response whitelist separates read permission and write envelope', async () => {
  const api = createMerchantLocationApi({ baseUrl: 'https://test.invalid', runtime: () => ({ request: r => r.success({ statusCode: 200, data: { code: 0, data: r.method === 'GET' ? { location, can_edit: true } : { location } } }) }) })
  assert.equal((await api.read('manager')).can_edit, true); assert.deepEqual((await api.update('manager', locationBody({ ...location, confirmed: true }), key)).location, location)
})
test('failed store write retries with original key and disabled account clears editable fields', async () => {
  const state = initialLocationState(), keys = []; let first = true
  const flow = createLocationFlow({ state, token: () => 'manager', newKey: () => key, api: { read: async () => ({ can_edit: true, location: null }), update: async (t, b, k) => { keys.push(k); if (first) { first = false; throw new ServiceError('network', 'retry') } return { location } } } })
  await flow.load(); Object.assign(state, { lng: '113', lat: '22', confirmed: true }); await flow.save(); await flow.save(); assert.deepEqual(keys, [key, key]); assert.equal(state.saved, true)
  flow.edit(); const denied = createLocationFlow({ state, token: () => 'manager', newKey: () => key, api: { update: async () => { throw new ServiceError('unauthorized', 'expired') } } })
  state.confirmed = true; await denied.save(); assert.equal(state.canEdit, false); assert.equal(state.lng, ''); assert.equal(state.loaded, false)
})
test('late store read or write after logout cannot populate a new account', async () => {
  const wait = deferred(), state = initialLocationState(); let actor = 'one'
  const flow = createLocationFlow({ state, token: () => actor, newKey: () => key, api: { read: () => wait.promise } })
  const reading = flow.load(); actor = 'two'; flow.reset(); wait.resolve({ location, can_edit: true }); await reading; assert.equal(state.loaded, false); assert.equal(state.location, null)
})
