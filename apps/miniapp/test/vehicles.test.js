import test from 'node:test'
import assert from 'node:assert/strict'
import { createVehicleApi, vehicleBody, VehicleError } from '../src/services/vehicles.js'
import { createVehicleFlow, initialVehicleState } from '../src/services/vehicle-flow.js'
const key = '11111111-1111-4111-8111-111111111111'
const saved = { vehicle_id: 1, model_name: '测试车型', need_archive: true }
function transport(baseUrl = 'https://test.invalid') {
  let response = { statusCode: 200, data: { code: 0, data: saved } }
  const calls = []
  const api = createVehicleApi({ baseUrl, runtime: () => ({ request(options) { calls.push(options); response.errMsg ? options.fail(response) : options.success(response) } }) })
  return { api, calls, reply(value) { response = value } }
}
function harness() {
  let owner = 'first', keys = 0
  const calls = [], state = initialVehicleState()
  const api = { catalog: async (...args) => ({ list: [], total: 0, page: args[3], page_size: 100 }),
    add: async (...args) => { calls.push(args); return saved } }
  const flow = createVehicleFlow({ state, api, token: () => owner, newKey: () => `key-${++keys}` })
  return { state, api, flow, calls, owner(value) { owner = value; flow.reset() } }
}
test('unconfigured service and missing token never invoke transport', () => {
  const h = transport('')
  assert.throws(() => h.api.list(''), { kind: 'unauthorized' })
  assert.throws(() => h.api.list('owner'), { kind: 'unconfigured' }); assert.equal(h.calls.length, 0)
})
test('write carries formal authorization and original idempotency key', async () => {
  const h = transport(), body = { add_type: 4, model_id: 1 }
  assert.deepEqual(await h.api.add('owner', body, key), saved)
  assert.equal(h.calls[0].header.Authorization, 'Bearer owner'); assert.equal(h.calls[0].header['Idempotency-Key'], key)
  assert.equal(h.calls[0].data, body)
})
test('list rejects malformed rows and mismatched pagination', async () => {
  const h = transport()
  const row = { vehicle_id: 1, current_mileage: 0, model_name: '车型', plate_no_masked: '', vin_masked: '' }
  h.reply({ statusCode: 200, data: { code: 0, data: { list: [row], page: 1, page_size: 20, total: 1 } } })
  assert.equal((await h.api.list('owner')).list.length, 1)
  for (const value of [{ ...row, vehicle_id: '1' }, { ...row, current_mileage: -1 }]) {
    h.reply({ statusCode: 200, data: { code: 0, data: { list: [value], page: 1, page_size: 20, total: 1 } } })
    await assert.rejects(h.api.list('owner'), { kind: 'protocol' })
  }
  h.reply({ statusCode: 200, data: { code: 0, data: { list: [], page: 2, page_size: 100, total: 0 } } })
  await assert.rejects(h.api.list('owner'), { kind: 'protocol' })
})
test('catalog queries use validated parent and expose empty pages', async () => {
  const h = transport()
  h.reply({ statusCode: 200, data: { code: 0, data: { list: [], page: 2, page_size: 100, total: 0 } } })
  assert.equal((await h.api.catalog('owner', 'model', 8, 2)).list.length, 0)
  assert.ok(h.calls[0].url.endsWith('/api/model/list?page=2&page_size=100&series_id=8'))
  assert.throws(() => h.api.catalog('owner', 'series', 0), { kind: 'invalid' })
})
test('HTTP failures are fixed safe messages', async () => {
  const h = transport()
  for (const status of [400, 401, 403, 404, 409, 429, 503, 500]) {
    h.reply({ statusCode: status, data: { message: 'private owner secret' } })
    await assert.rejects(h.api.add('owner', {}, key), error => error instanceof VehicleError && !error.message.includes('private'))
  }
  h.reply({ errMsg: 'private network detail' }); await assert.rejects(h.api.list('owner'), { kind: 'network' })
})
test('input normalizes identifiers and enforces mileage and VIN boundaries', () => {
  const fields = { modelId: 1, mileage: '0', plate: ' 粤b12345 ', vin: 'lsvnv2180h2123456' }
  assert.equal(vehicleBody(fields).plate_no, '粤B12345')
  for (const change of [{ modelId: 0 }, { mileage: '-1' }, { mileage: '1.5' }, { mileage: '2147483648' }, { vin: 'I123' }, { plate: 'wrong' }])
    assert.throws(() => vehicleBody({ ...fields, ...change }), { kind: 'invalid' })
})
test('network retry retains key while edited payload gets a new key', async () => {
  const h = harness(); h.state.modelId = 1
  h.api.add = async (...args) => { h.calls.push(args); throw new VehicleError('unavailable', '稍后重试') }
  await h.flow.save(); await h.flow.save()
  assert.equal(h.calls[0][2], h.calls[1][2])
  h.state.mileage = '2'; await h.flow.save(); assert.notEqual(h.calls[2][2], h.calls[1][2])
  h.api.add = async () => saved; await h.flow.save(); assert.deepEqual(h.state.saved, saved)
})
test('duplicate taps do not start concurrent writes', async () => {
  const h = harness(); h.state.modelId = 1
  let finish; h.api.add = (...args) => { h.calls.push(args); return new Promise(resolve => { finish = resolve }) }
  const first = h.flow.save(); await h.flow.save(); assert.equal(h.calls.length, 1)
  finish(saved); await first; assert.equal(h.state.saved.vehicle_id, 1)
})
test('account change clears form and ignores a late write response', async () => {
  const h = harness(); h.state.modelId = 1; h.state.plate = '粤B12345'
  let finish; h.api.add = () => new Promise(resolve => { finish = resolve })
  const pending = h.flow.save(); h.owner('second'); finish(saved); await pending
  assert.equal(h.state.saved, null); assert.equal(h.state.plate, ''); assert.equal(h.state.modelId, 0)
})
test('hidden page ignores old result and preserves pending key for retry', async () => {
  const h = harness(); h.state.modelId = 1
  let finish; h.api.add = (...args) => { h.calls.push(args); return new Promise(resolve => { finish = resolve }) }
  const first = h.flow.save(); h.flow.suspend(); finish(saved); await first
  assert.equal(h.state.saved, null)
  h.api.add = async (...args) => { h.calls.push(args); return saved }; await h.flow.save()
  assert.equal(h.calls[0][2], h.calls[1][2])
})
test('parent changes clear descendants and discard late catalog responses', async () => {
  const h = harness(); let finish
  h.api.catalog = () => new Promise(resolve => { finish = resolve })
  const pending = h.flow.selectBrand(1); h.flow.suspend()
  h.api.catalog = async () => ({ list: [{ id: 3, name: '新车系' }], total: 1, page: 1, page_size: 100 })
  await h.flow.selectBrand(2); finish({ list: [{ id: 9, name: '旧车系' }], total: 1, page: 1, page_size: 100 }); await pending
  assert.equal(h.state.brandId, 2); assert.equal(h.state.series[0].id, 3); assert.equal(h.state.modelId, 0)
})
test('catalog pagination and failed next page retain rows and retry position', async () => {
  const h = harness()
  h.api.catalog = async (_, __, ___, page) => ({ list: [{ id: page, name: '测试' }], total: 2, page, page_size: 100 })
  await h.flow.load('brand'); h.api.catalog = async () => { throw new VehicleError('network', '网络失败') }
  await h.flow.load('brand', true); assert.equal(h.state.brand.length, 1); assert.deepEqual(h.state.catalogRetry, { kind: 'brand', more: true })
  h.api.catalog = async (_, __, ___, page) => ({ list: [{ id: page, name: '测试' }], total: 2, page, page_size: 100 })
  await h.flow.load('brand', true); assert.deepEqual(h.state.brand.map(row => row.id), [1, 2])
})
