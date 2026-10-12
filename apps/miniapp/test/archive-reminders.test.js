import test from 'node:test'
import assert from 'node:assert/strict'
import { reminderBody, validReminderSetting, createArchiveReminderApi, canReturnToReminder } from '../src/services/archive-reminders.js'

const setting = (type = 1) => ({ archive_type: type, revision: 0, active: false, archive_id: null, recorded_date: null, due_date: null, due_mileage: null, status: 'NOT_SET', triggered_by: [] })
const context = () => ({ vehicle_id: 101, archive_id: 11, archive_type: 1, recorded_date: '2026-10-01', current_mileage: 999, today: '2026-10-12', setting: setting() })
function harness(data, statusCode = 200) {
  const calls = []
  const api = createArchiveReminderApi({ baseUrl: 'https://api.example', runtime: () => ({ request(options) { calls.push(options); options.success({ statusCode, data: { code: 0, data } }) } }) })
  return { api, calls }
}
test('个人目标不设默认日期或周期，日期与里程严格校验，保险仅日期', () => {
  assert.throws(() => reminderBody(context(), { date: '', mileage: '' }))
  assert.throws(() => reminderBody(context(), { date: '2026-02-30', mileage: '' }))
  assert.throws(() => reminderBody(context(), { date: '', mileage: '-1' }))
  assert.throws(() => reminderBody(context(), { date: '', mileage: '2147483648' }))
  assert.deepEqual(reminderBody(context(), { date: '', mileage: '1000' }), { expected_revision: 0, active: true, due_date: null, due_mileage: 1000, replace_source: false })
  const insurance = context(); insurance.archive_type = 3
  assert.throws(() => reminderBody(insurance, { date: '2026-12-01', mileage: '1' }))
  assert.deepEqual(reminderBody(insurance, {}, false), { expected_revision: 0, active: false })
})
test('到目标首尾包含且任一触发，缺少里程不能显示正常', () => {
  const row = { ...setting(), revision: 1, active: true, archive_id: 11, recorded_date: '2026-10-01', due_date: '2026-10-13', due_mileage: 1000, status: 'PLANNED' }
  assert.ok(validReminderSetting(row, '2026-10-12', 999))
  assert.ok(validReminderSetting({ ...row, status: 'DUE', triggered_by: ['MILEAGE'] }, '2026-10-12', 1000))
  assert.ok(validReminderSetting({ ...row, status: 'DUE', triggered_by: ['DATE'] }, '2026-10-13', 999))
  assert.ok(validReminderSetting({ ...row, status: 'UNKNOWN' }, '2026-10-12', null))
  assert.equal(validReminderSetting(row, '2026-10-12', null), false)
  assert.equal(validReminderSetting({ ...row, plate_no: 'private' }, '2026-10-12', 999), false)
})
test('读取必须匹配车辆、来源与固定三类列表，不接受越界响应', async () => {
  const h = harness(context()); await h.api.context('jwt', 11, 101)
  assert.equal(h.calls[0].header.Authorization, 'Bearer jwt')
  await assert.rejects(h.api.context('jwt', 11, 201), { kind: 'protocol' })
  const list = harness({ vehicle_id: 101, today: '2026-10-12', current_mileage: 999, list: [setting(1), setting(3), setting(7)] })
  await list.api.list('jwt', 101)
  const invalid = harness({ vehicle_id: 101, today: '2026-10-12', current_mileage: 999, list: [setting(3), setting(1), setting(7)] })
  await assert.rejects(invalid.api.list('jwt', 101), { kind: 'protocol' })
})
test('PUT使用幂等UUID及版本，回执版本必须严格递增', async () => {
  const key = '12345678-1234-1234-1234-123456789012', body = reminderBody(context(), { date: '', mileage: '1000' })
  const h = harness({ vehicle_id: 101, archive_id: 11, archive_type: 1, revision: 1, active: true })
  await h.api.save('jwt', context(), body, key)
  assert.equal(h.calls[0].method, 'PUT'); assert.equal(h.calls[0].header['Idempotency-Key'], key); assert.deepEqual(h.calls[0].data, body)
  await assert.rejects(harness({ vehicle_id: 101, archive_id: 11, archive_type: 1, revision: 2, active: true }).api.save('jwt', context(), body, key), { kind: 'protocol' })
})
test('版本冲突、会话失效和源不可用分开处理；登录返回识别提醒页', async () => {
  for (const [status, kind] of [[401, 'unauthorized'], [404, 'missing'], [409, 'conflict'], [503, 'unavailable']])
    await assert.rejects(harness({}, status).api.context('jwt', 11, 101), { kind })
  assert.ok(canReturnToReminder([{ route: 'pages/archive/reminder' }, { route: 'pages/owner/index' }]))
  assert.equal(canReturnToReminder([{ route: 'pages/archive/shared' }, { route: 'pages/owner/index' }]), false)
})
