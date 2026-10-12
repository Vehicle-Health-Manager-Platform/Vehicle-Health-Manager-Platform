import test from 'node:test'
import assert from 'node:assert/strict'
import { archiveShareInput, validShareSummary, createArchiveShareApi, createArchiveShareMutation, canReturnToArchiveShare } from '../src/services/archive-shares.js'

const capability = 'a'.repeat(64)
const uuid = '12345678-1234-1234-1234-123456789012'
const summary = () => ({ version: 1, generated_at: '2026-10-12T00:00:00Z', from: null, to: null, registered_mileage: null,
  undated_count: 0, entries: [{ archive_type: 1, input_type: 3, recorded_date: null, mileage: 123, work_minutes: 0, test_mode: false }] })
const metadata = { share_id: 1, vehicle_id: 101, created_at: '2026-10-12T00:00:00Z', expires_at: '2099-10-12T00:00:00Z', status: 'ACTIVE' }
function harness(data, statusCode = 200) {
  const calls = []
  const api = createArchiveShareApi({ baseUrl: 'https://api.example', runtime: () => ({ request(options) {
    calls.push(options); options.success({ statusCode, data: { code: 0, data } })
  } }) })
  return { api, calls }
}
test('固定摘要严格拒绝多余敏感字段和非法数值', () => {
  assert.ok(validShareSummary(summary()))
  assert.equal(validShareSummary({ ...summary(), plate_no: '粤B12345' }), false)
  const bad = summary(); bad.entries[0].notes = 'private'; assert.equal(validShareSummary(bad), false)
  const invalid = summary(); invalid.entries[0].mileage = -1; assert.equal(validShareSummary(invalid), false)
  const unsupported = summary(); unsupported.entries[0].input_type = 2; assert.equal(validShareSummary(unsupported), false)
})
test('有效期、日期与安全整数校验；默认24小时', () => {
  assert.deepEqual(archiveShareInput(101), { vehicle_id: 101, expires_in_hours: 24 })
  for (const hours of [1, 24, 168]) assert.equal(archiveShareInput(101, hours).expires_in_hours, hours)
  for (const args of [[101, 2], [101, 24, '2026-02-30', '2026-03-01'], [101, 24, '2026-10-12', ''], [Number.MAX_SAFE_INTEGER + 1]]) assert.throws(() => archiveShareInput(...args))
})
test('接收者令牌只进正文，受控路径和元信息都校验', async () => {
  const h = harness({ summary: summary(), expires_at: '2099-10-12T00:00:00Z' })
  await h.api.view('owner-jwt', capability)
  assert.equal(h.calls[0].url, 'https://api.example/api/archive/share/view')
  assert.deepEqual(h.calls[0].data, { share_token: capability })
  assert.equal(h.calls[0].header.Authorization, 'Bearer owner-jwt')
  assert.equal(h.calls[0].header['Idempotency-Key'], undefined)
  assert.throws(() => h.api.view('owner-jwt', 'invalid'), { kind: 'gone' })
  const bad = harness({ share_id: 1, path: 'https://untrusted.example', expires_at: '2099-10-12T00:00:00Z' })
  await assert.rejects(bad.api.link('owner-jwt', 1), { kind: 'protocol' })
  const good = harness({ share_id: 1, path: `/pages/archive/shared?token=${capability}`, expires_at: '2099-10-12T00:00:00Z' })
  assert.equal((await good.api.link('owner-jwt', 1)).share_id, 1)
})
test('创建与撤销幂等请求及撤销回执必须符合状态', async () => {
  const h = harness(metadata)
  await h.api.create('jwt', archiveShareInput(101), uuid)
  assert.equal(h.calls[0].header['Idempotency-Key'], uuid)
  await assert.rejects(h.api.revoke('jwt', 1, uuid), { kind: 'protocol' })
  const revoke = harness({ ...metadata, status: 'REVOKED' }); await revoke.api.revoke('jwt', 1, uuid)
  assert.deepEqual(revoke.calls[0].data, {})
})
test('失败重试复用 UUID，修改范围或切账号更换 UUID，成功后清理', async () => {
  const keys = []; let count = 0, fails = true
  const mutation = createArchiveShareMutation({ newKey: () => String(++count), api: { async create(token, body, key) {
    keys.push(key); if (fails) throw new Error('network'); return metadata
  } } })
  await assert.rejects(mutation.create('jwt', archiveShareInput(101)))
  await assert.rejects(mutation.create('jwt', archiveShareInput(101)))
  await assert.rejects(mutation.create('jwt', archiveShareInput(101, 1)))
  await assert.rejects(mutation.create('other', archiveShareInput(101, 1)))
  fails = false; await mutation.create('other', archiveShareInput(101, 1)); await mutation.create('other', archiveShareInput(101, 1))
  assert.deepEqual(keys, ['1', '1', '2', '3', '3', '4'])
})
test('登录返回仅识别前一页面为接收页，失效与服务错误区分', async () => {
  assert.equal(canReturnToArchiveShare([{ route: 'pages/archive/shared' }, { route: 'pages/owner/index' }]), true)
  assert.equal(canReturnToArchiveShare([{ route: 'pages/home/index' }, { route: 'pages/owner/index' }]), false)
  for (const [status, kind] of [[401, 'unauthorized'], [403, 'forbidden'], [410, 'gone'], [503, 'unavailable']])
    await assert.rejects(harness({}, status).api.view('jwt', capability), { kind })
})
test('旧账号迟到成功不会清除新账号失败请求的幂等键', async () => {
  let resolveOld, count = 0; const keys = []
  const mutation = createArchiveShareMutation({ newKey: () => String(++count), api: { create(token, body, key) {
    keys.push(key)
    if (token === 'old') return new Promise(resolve => { resolveOld = resolve })
    return Promise.reject(new Error('network'))
  } } })
  const old = mutation.create('old', archiveShareInput(101)); mutation.reset()
  await assert.rejects(mutation.create('new', archiveShareInput(201)))
  resolveOld(metadata); await old
  await assert.rejects(mutation.create('new', archiveShareInput(201)))
  assert.deepEqual(keys, ['1', '2', '2'])
})
