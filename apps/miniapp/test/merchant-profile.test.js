import test from 'node:test'
import assert from 'node:assert/strict'
import { createMerchantProfileApi, merchantStatusLabel, merchantTypeLabel, profileBody,
  PROFILE_REQUIRED_KEYS, EDITABLE_KEYS, READ_ONLY_KEYS, NEVER_READ_KEYS } from '../src/services/merchant-profile.js'
import { ServiceError } from '../src/services/service-catalog.js'
import { createProfileFlow, initialProfileState } from '../src/services/profile-flow.js'

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const key = '11111111-1111-4111-8111-111111111111'
const store = (over = {}) => ({ merchant_id: 1, name: '演示汽修厂', address: '广州市天河区演示路1号',
  contact_phone: '13800000000', lng: '113.2644', lat: '23.1291', merchant_type: 2, region_code: '',
  status: 1, can_edit: true, ...over })
const writable = (over = {}) => ({ name: '演示汽修厂', address: '广州市天河区演示路1号',
  contact_phone: '13800000000', lng: '113.2644', lat: '23.1291', ...over })

test('资料表单严格校验名称、地址、电话与坐标，空坐标转成 null', () => {
  const base = { name: '演示汽修厂', address: '广州市天河区演示路1号', contactPhone: '13800000000', lng: '', lat: '' }
  for (const broken of [
    { ...base, name: '厂' }, { ...base, name: ' 演示汽修厂' }, { ...base, name: undefined },
    { ...base, address: '' }, { ...base, address: ' 换址' }, { ...base, address: 'x'.repeat(257) },
    { ...base, contactPhone: '1380000000' }, { ...base, contactPhone: '' },
    { ...base, lng: '181' }, { ...base, lng: '-180.1' }, { ...base, lng: 'abc' }, { ...base, lng: '113.2644.5' },
    { ...base, lat: '-91' }, { ...base, lat: ' ' },
  ]) assert.throws(() => profileBody(broken), { kind: 'invalid' }, JSON.stringify(broken))
  // 留空等价于 null；填了就必须是范围内的数字。
  assert.deepEqual(profileBody(base), { name: '演示汽修厂', address: '广州市天河区演示路1号',
    contact_phone: '13800000000', lng: null, lat: null })
  assert.deepEqual(profileBody({ ...base, lng: '113.2644', lat: 23.1291 }).lng, 113.2644)
  assert.equal(profileBody({ ...base, lng: '-180', lat: '90' }).lat, 90)
})

test('资料读响应是十字段白名单，写响应是五字段白名单', async () => {
  let request
  const response = { statusCode: 200, data: { code: 0, data: store() } }
  const api = createMerchantProfileApi({ baseUrl: 'http://test/', runtime: () => ({ request: options => { request = options; options.success(response) } }) })
  const read = await api.read('token')
  assert.equal(request.url, 'http://test/api/merchant/profile')
  assert.equal(request.header.Authorization, 'Bearer token')
  assert.equal(read.can_edit, true)

  for (const broken of [
    { ...store(), qualification: [] },        // 资质不可经本接口读出
    { ...store(), is_deleted: 0 },
    { ...store(), commission_rate: '1.00' },
    { ...store(), can_edit: 'true' },
    { ...store(), merchant_type: 9 },
  ]) {
    response.data.data = broken
    await assert.rejects(api.read('token'), { kind: 'protocol' })
  }
  // 坐标为 null 是合法状态：门店可能还没填经纬度。
  response.data.data = store({ lng: null, lat: null })
  assert.equal((await api.read('token')).lng, null)

  response.data.data = writable()
  await api.update('token', profileBody({ name: '演示汽修厂', address: '广州市天河区演示路1号',
    contactPhone: '13800000000', lng: '', lat: '' }), key)
  assert.equal(request.method, 'PUT')
  assert.equal(request.header['Idempotency-Key'], key)
  assert.deepEqual(request.data, { name: '演示汽修厂', address: '广州市天河区演示路1号',
    contact_phone: '13800000000', lng: null, lat: null })
  // 写完回读若多出不可改字段，说明服务端把白名单放宽了。
  response.data.data = { ...writable(), merchant_type: 2 }
  await assert.rejects(api.update('token', request.data, key), { kind: 'protocol' })
  assert.throws(() => api.update('token', request.data, 'not-a-uuid'), { kind: 'invalid' })
})

test('只有店长的写请求会被服务端放行，店员得到明确的只读提示', async () => {
  const failing = (statusCode, code) => createMerchantProfileApi({ baseUrl: 'http://test',
    runtime: () => ({ request: options => options.success({ statusCode, data: { code } }) }) })
  const body = profileBody({ name: '演示汽修厂', address: '广州市天河区演示路1号', contactPhone: '13800000000', lng: '', lat: '' })
  await assert.rejects(failing(403, 40300).update('token', body, key), { kind: 'forbidden', message: '只有店长可以修改本店资料' })
  await assert.rejects(failing(404, 40400).read('token'), { kind: 'missing' })
  await assert.rejects(failing(401, 40100).read('token'), { kind: 'unauthorized' })
  assert.throws(() => failing(403, 40300).read(''), { kind: 'unauthorized' })
})

test('资料读回映射到表单，坐标 null 显示为空串', async () => {
  const state = initialProfileState()
  const flow = createProfileFlow({ state, token: () => 'merchant', newKey: () => key,
    api: { read: async () => store({ lng: null, lat: null, contact_phone: null, can_edit: false }),
      update: async () => writable() } })
  await flow.load()
  assert.equal(state.merchantId, 1)
  assert.equal(state.contactPhone, '')
  assert.equal(state.lng, '')
  assert.equal(state.lat, '')
  // can_edit=false 来自服务端，页面据此隐藏编辑入口。
  assert.equal(state.canEdit, false)
  assert.equal(state.loaded, true)
  assert.equal(state.saved, null)
})

test('保存复用原幂等键、回写服务端结果，切账号后丢弃迟到响应', async () => {
  const state = initialProfileState()
  const calls = []
  let n = 0, pending = deferred(), actor = 'merchant'
  const flow = createProfileFlow({ state, token: () => actor, newKey: () => `key-${++n}`,
    api: { read: async () => store(), update: (_token, body, idempotencyKey) => { calls.push({ body, key: idempotencyKey }); return pending.promise } } })
  await flow.load()
  assert.equal(state.canEdit, true)
  state.name = '新店名'
  let saving = flow.save()
  pending.reject(new ServiceError('network', '重试')); await saving
  assert.equal(state.saved, null)
  pending = deferred(); saving = flow.save()
  pending.resolve(writable({ name: '新店名' })); await saving
  assert.equal(calls[0].key, calls[1].key)
  assert.equal(state.saved.name, '新店名')
  assert.equal(state.saveMessage, '门店资料已保存')
  // 已保存后表单只读：继续改要显式回到编辑态。
  await flow.save()
  assert.equal(calls.length, 2)
  flow.edit()
  assert.equal(state.saved, null)
  assert.equal(state.saveMessage, '')

  // 切账号后旧响应必须作废，且新账号不能看到上一账号的资料。
  const late = deferred()
  const other = createProfileFlow({ state, token: () => actor, newKey: () => key,
    api: { read: async () => store({ merchant_id: 2 }), update: () => late.promise } })
  state.name = '新店名'
  const writing = other.save()
  actor = 'other-merchant'; other.reset()
  late.resolve(writable({ merchant_id: 2 }))
  await writing
  assert.equal(state.merchantId, 0)
  assert.equal(state.saved, null)
})

test('验证失败不发请求，也不占用幂等键', async () => {
  const state = initialProfileState(), calls = []
  const flow = createProfileFlow({ state, token: () => 'merchant', newKey: () => key,
    api: { read: async () => store(), update: async (_token, body, idempotencyKey) => { calls.push({ body, key: idempotencyKey }); return writable() } } })
  await flow.load()
  state.contactPhone = '1380000000'
  await flow.save()
  assert.equal(calls.length, 0)
  assert.equal(state.saveFailureKind, 'invalid')
  assert.equal(state.saved, null)
})

test('类型与状态展示文案在未知取值下不臆断', () => {
  assert.equal(merchantTypeLabel(2), '维修保养')
  assert.equal(merchantTypeLabel(9), '类型未提供')
  assert.equal(merchantStatusLabel(1), '在营')
  assert.equal(merchantStatusLabel(7), '状态未提供')
})

test('读写白名单恰好是契约字段，且不掺入运营侧字段', () => {
  assert.equal(new Set(PROFILE_REQUIRED_KEYS).size, PROFILE_REQUIRED_KEYS.length)
  assert.equal(new Set(EDITABLE_KEYS).size, EDITABLE_KEYS.length)
  // 写白名单必须是读白名单的子集，否则会出现「能写但读不回来」的字段。
  assert.deepEqual(EDITABLE_KEYS, PROFILE_REQUIRED_KEYS.filter(f => EDITABLE_KEYS.includes(f)))
  // 只读字段读得到、可写字段之外一个都不能写；两者必须分开——曾把 region_code
  // 同时放进「不可写」和「读响应白名单」，导致所有合法读响应被判协议错误。
  assert.deepEqual(READ_ONLY_KEYS, ['merchant_type', 'region_code', 'status'])
  for (const field of READ_ONLY_KEYS) {
    assert.ok(PROFILE_REQUIRED_KEYS.includes(field), `${field} 应可读`)
    assert.ok(!EDITABLE_KEYS.includes(field), `${field} 不该可写`)
  }
  for (const field of NEVER_READ_KEYS) {
    assert.ok(!PROFILE_REQUIRED_KEYS.includes(field), `${field} 不得进入读白名单`)
    assert.ok(!EDITABLE_KEYS.includes(field), `${field} 不得进入写白名单`)
  }
})
