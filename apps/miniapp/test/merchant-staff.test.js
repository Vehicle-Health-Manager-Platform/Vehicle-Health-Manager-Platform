import test from 'node:test'
import assert from 'node:assert/strict'
import { createMerchantStaffApi, STAFF_ROW_KEYS, staffBody, staffRoleLabel, staffStatusLabel } from '../src/services/merchant-staff.js'
import { ServiceError } from '../src/services/service-catalog.js'
import { createStaffActionFlow, createStaffCreateFlow, createStaffListFlow,
  initialStaffActionState, initialStaffCreateState, initialStaffListState } from '../src/services/staff-flow.js'

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const key = '11111111-1111-4111-8111-111111111111'
// 与 OpenAPI 的 MerchantStaff 逐字段对齐；创建响应与列表行共用这一份形状。
const row = (over = {}) => ({ staff_id: 1, account: 's1-1', role: 'STAFF', display_name: '前台小李',
  phone_masked: '138****0000', status: 'ACTIVE', employee_code_issued: false, wechat_bound: false,
  created_at: '2026-10-11T00:00:00Z', ...over })
const page = (items, n = 1) => ({ items, total: items.length, page: n, page_size: 20 })

test('创建员工请求体严格校验角色、姓名、密码与店员手机号', () => {
  const base = { role: 'STAFF', displayName: '前台小李', password: 'passw0rd1', phone: '13800000000' }
  for (const broken of [
    { ...base, role: 'MERCHANT' }, { ...base, role: 'OWNER' }, { ...base, role: undefined },
    { ...base, displayName: '李' }, { ...base, displayName: ' 前台小李' }, { ...base, displayName: '前'.repeat(33) },
    { ...base, password: 'short1' }, { ...base, password: 'onlyletters' }, { ...base, password: '12345678' },
    { ...base, password: 'pass w0rd1' },
    { ...base, phone: '' }, { ...base, phone: '1380000000' }, { ...base, phone: '23800000000' },
  ]) assert.throws(() => staffBody(broken), { kind: 'invalid' }, JSON.stringify(broken))
  // 店员的短信登录依赖本人手机号，因此必填。
  assert.deepEqual(staffBody(base), { role: 'STAFF', display_name: '前台小李', password: 'passw0rd1', phone: '13800000000' })
  // 技师手机号可选：不填时请求体里不出现该字段。
  assert.deepEqual(staffBody({ ...base, role: 'TECHNICIAN', phone: '' }),
    { role: 'TECHNICIAN', display_name: '前台小李', password: 'passw0rd1' })
})

test('员工行是九字段白名单投影：少字段、越界字段与重复行都拒绝', async () => {
  let request
  const response = { statusCode: 200, data: { code: 0, data: page([row()]) } }
  const api = createMerchantStaffApi({ baseUrl: 'http://test/', runtime: () => ({ request: options => { request = options; options.success(response) } }) })
  assert.deepEqual(Object.keys(row()).sort(), [...STAFF_ROW_KEYS].sort())
  await api.list('token')
  assert.equal(request.url, 'http://test/api/merchant/staff?page=1&page_size=20')
  assert.equal(request.header.Authorization, 'Bearer token')

  const missing = row(); delete missing.created_at
  for (const broken of [
    missing,
    { ...row(), phone: '13800000000' },        // 手机号原文越界
    { ...row(), password_hash: '$2a$10$hash' }, // 口令哈希越界
    { ...row(), merchant_id: 1 },               // 归属字段越界（由服务端按令牌决定）
  ]) {
    response.data.data = page([broken])
    await assert.rejects(api.list('token'), { kind: 'protocol' })
  }
  // 同一页出现重复员工同样是契约被破坏。
  response.data.data = page([row(), row()])
  await assert.rejects(api.list('token'), { kind: 'protocol' })
  response.data.data = page([row({ staff_id: 2 })], 2)
  const result = await api.list('token', 'TECHNICIAN', 2)
  assert.equal(result.page, 2)
  assert.match(request.url, /role=TECHNICIAN/)
})

test('新增响应与列表行同形，且不回显密码', async () => {
  let request
  const response = { statusCode: 200, data: { code: 0, data: row() } }
  const api = createMerchantStaffApi({ baseUrl: 'http://test', runtime: () => ({ request: options => { request = options; options.success(response) } }) })
  const body = staffBody({ role: 'STAFF', displayName: '前台小李', password: 'passw0rd1', phone: '13800000000' })
  await api.create('token', body, key)
  assert.equal(request.method, 'POST')
  assert.equal(request.header['Idempotency-Key'], key)
  // 创建响应少字段（老后端只回六个字段）会让客户端把合法响应判成协议错误。
  response.data.data = { staff_id: 1, account: 's1-1', role: 'STAFF', display_name: '前台小李', phone_masked: '138****0000', status: 'ACTIVE' }
  await assert.rejects(api.create('token', body, key), { kind: 'protocol' })
  response.data.data = { ...row(), password: 'passw0rd1' }
  await assert.rejects(api.create('token', body, key), { kind: 'protocol' })
})

test('员工码是轮换语义：签发与撤销都不带幂等键', async () => {
  const calls = []
  const api = createMerchantStaffApi({ baseUrl: 'http://test', runtime: () => ({ request: options => {
    calls.push(options)
    options.success(options.method === 'DELETE'
      ? { statusCode: 200, data: { code: 0, data: { staff_id: 7, employee_code_revoked: true } } }
      : { statusCode: 200, data: { code: 0, data: { staff_id: 7, employee_code: 'one-time-code', single_use: true } } })
  } }) })
  const issued = await api.issueCode('token', 7)
  assert.equal(issued.employee_code, 'one-time-code')
  assert.equal(calls[0].method, 'POST')
  assert.equal('Idempotency-Key' in calls[0].header, false)
  assert.equal(calls[0].data, undefined)
  await api.revokeCode('token', 7)
  assert.equal(calls[1].method, 'DELETE')
  assert.equal('Idempotency-Key' in calls[1].header, false)
  // 伪造的响应（明码为空、single_use 非 true）必须被拒绝。
  const broken = createMerchantStaffApi({ baseUrl: 'http://test', runtime: () => ({ request: options =>
    options.success({ statusCode: 200, data: { code: 0, data: { staff_id: 7, employee_code: '', single_use: false } } }) }) })
  await assert.rejects(broken.issueCode('token', 7), { kind: 'protocol' })
})

test('停用与启用发送空对象正文，并翻译服务端拒绝码', async () => {
  const calls = []
  const api = createMerchantStaffApi({ baseUrl: 'http://test', runtime: () => ({ request: options => {
    calls.push(options)
    options.success({ statusCode: 200, data: { code: 0, data: options.url.endsWith('/disable')
      ? { staff_id: 5, status: 'DISABLED', sessions_revoked: 2 } : { staff_id: 5, status: 'ACTIVE' } } })
  } }) })
  const disabled = await api.disable('token', 5, key)
  assert.equal(disabled.sessions_revoked, 2)
  assert.deepEqual(calls[0].data, {})
  assert.equal(calls[0].method, 'POST')
  await api.enable('token', 5, key)
  assert.equal(calls[1].url.endsWith('/enable'), true)

  const failing = (statusCode, code) => createMerchantStaffApi({ baseUrl: 'http://test', runtime: () => ({ request: options => options.success({ statusCode, data: { code } }) }) })
  await assert.rejects(failing(403, 40300).list('token'), { kind: 'forbidden', message: '只有店长可以维护本店员工与员工码' })
  await assert.rejects(failing(404, 40400).enable('token', 5, key), { kind: 'missing', message: '员工不存在或不属于本店' })
  await assert.rejects(failing(409, 40900).issueCode('token', 5), { kind: 'conflict', message: '员工已停用不能签发员工码，或本店账号序号已用尽' })
  await assert.rejects(failing(503, 50300).list('token'), { kind: 'unavailable' })
  assert.throws(() => api.disable('token', 5, 'not-a-uuid'), { kind: 'invalid' })
  // 未登录与未配置属于同步前置校验，不发请求。
  assert.throws(() => api.list(''), { kind: 'unauthorized' })
  assert.throws(() => createMerchantStaffApi({ baseUrl: '', runtime: () => ({}) }).list('token'), { kind: 'unconfigured' })
})

test('停用并发只发一次；同目标同意图复用原幂等键，换目标才换键', async () => {
  const state = initialStaffActionState(), calls = []
  let n = 0, pending = deferred()
  const flow = createStaffActionFlow({ state, token: () => 'merchant', newKey: () => `key-${++n}`,
    api: { disable: (_token, id, idempotencyKey) => { calls.push({ id, key: idempotencyKey }); return pending.promise },
      enable: (_token, id, idempotencyKey) => { calls.push({ id, key: idempotencyKey }); return pending.promise },
      issueCode: async () => ({ employee_code: 'code' }), revokeCode: async () => ({ employee_code_revoked: true }) } })
  const first = { staff_id: 5, status: 'ACTIVE' }, second = { staff_id: 6, status: 'ACTIVE' }
  const running = flow.toggle(first)
  await flow.toggle(first)
  assert.equal(calls.length, 1)
  assert.equal(state.busyId, 5)
  pending.reject(new ServiceError('network', '重试')); await running
  assert.equal(state.failureKind, 'network')

  pending = deferred(); const retry = flow.toggle(first)
  pending.reject(new ServiceError('network', '重试')); await retry
  assert.equal(calls[0].key, calls[1].key)
  pending = deferred(); const other = flow.toggle(second)
  pending.reject(new ServiceError('network', '重试')); await other
  assert.notEqual(calls[1].key, calls[2].key)
  // 目标是员工 6，键必须与员工 5 的不同，否则会把一次停用记成另一次。
  assert.equal(calls[2].id, 6)
})

test('一次性员工码只留在内存，撤销后立即清空', async () => {
  const state = initialStaffActionState()
  const flow = createStaffActionFlow({ state, token: () => 'merchant', newKey: () => key,
    api: { issueCode: async () => ({ employee_code: 'one-time-code' }), revokeCode: async () => ({ employee_code_revoked: true }),
      disable: async () => ({}), enable: async () => ({}) } })
  await flow.issue({ staff_id: 9 })
  assert.deepEqual(state.issued, { staffId: 9, code: 'one-time-code' })
  assert.equal(state.message, '')
  await flow.revoke({ staff_id: 9 })
  assert.equal(state.issued, null)
  assert.equal(state.message, '员工码已撤销，原有微信绑定同时失效')
  // 换员工签发时旧码必须消失：不能把 A 的码留在 B 的行上。
  await flow.issue({ staff_id: 9 })
  await flow.issue({ staff_id: 10 })
  assert.deepEqual(state.issued, { staffId: 10, code: 'one-time-code' })
})

test('新增成功后清空内存中的明文密码，失败重试复用原幂等键', async () => {
  const state = { ...initialStaffCreateState(), displayName: '前台小李', phone: '13800000000', password: 'passw0rd1' }
  const calls = []
  let n = 0, pending = deferred()
  const created = row()
  const flow = createStaffCreateFlow({ state, token: () => 'merchant', newKey: () => `key-${++n}`,
    api: { create: (_token, body, idempotencyKey) => { calls.push({ body, key: idempotencyKey }); return pending.promise } } })
  const saving = flow.save()
  pending.reject(new ServiceError('network', '重试')); await saving
  assert.equal(state.saved, null)
  pending = deferred(); const retry = flow.save()
  pending.resolve(created); await retry
  assert.equal(calls[0].key, calls[1].key)
  assert.equal(calls[0].body.password, 'passw0rd1')
  assert.equal(state.saved, created)
  assert.equal(state.password, '')
  assert.equal(state.message, '已创建店员「前台小李」，账号 s1-1')
  await flow.save()
  assert.equal(calls.length, 2)
  // 校验失败不发请求，也不占用幂等键。
  state.saved = null; state.displayName = '李'
  await flow.save()
  assert.equal(calls.length, 2)
  assert.equal(state.failureKind, 'invalid')
})

test('切换账号、筛选或离页后丢弃迟到响应', async () => {
  const state = initialStaffListState(), calls = []
  const pending = deferred()
  let actor = 'one'
  const flow = createStaffListFlow({ state, token: () => actor, fetchPage: () => pending.promise })
  const loading = flow.load()
  actor = 'two'; flow.reset()
  pending.resolve(page([row()])); await loading
  assert.equal(state.items.length, 0)
  assert.equal(state.loaded, false)

  const filtered = createStaffListFlow({ state, token: () => 'merchant',
    fetchPage: async (_token, n, role) => { calls.push(role); return page([row({ role: role || 'STAFF' })], n) } })
  await filtered.filter('TECHNICIAN')
  assert.deepEqual(calls, ['TECHNICIAN'])
  assert.equal(state.role, 'TECHNICIAN')
  await filtered.filter('')
  assert.deepEqual(calls, ['TECHNICIAN', ''])
  // 店长角色不能当筛选值：接口只接受 STAFF/TECHNICIAN。
  filtered.filter('MERCHANT')
  assert.deepEqual(calls, ['TECHNICIAN', ''])
})

test('角色与状态展示文案在未知取值下不臆断', () => {
  assert.equal(staffRoleLabel('STAFF'), '店员')
  assert.equal(staffRoleLabel('TECHNICIAN'), '技师')
  assert.equal(staffRoleLabel('MERCHANT'), '角色未提供')
  assert.equal(staffStatusLabel('ACTIVE'), '已启用')
  assert.equal(staffStatusLabel('DISABLED'), '已停用')
  assert.equal(staffStatusLabel(''), '状态未提供')
})
