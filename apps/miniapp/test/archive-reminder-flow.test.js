import test from 'node:test'
import assert from 'node:assert/strict'
import { createReminderListFlow, createReminderEditFlow, initialReminderListState, initialReminderEditState } from '../src/services/archive-reminder-flow.js'
import { ArchiveReminderError } from '../src/services/archive-reminders.js'
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const context = () => ({ vehicle_id: 101, archive_id: 11, archive_type: 1, setting: { revision: 0, active: false, archive_id: null, due_date: null, due_mileage: null } })
function editor({ save = async () => ({}), confirm = async () => true, initial = context() } = {}) {
  let token = 'jwt', key = 0
  const state = initialReminderEditState()
  const api = { context: async () => initial, save }
  const flow = createReminderEditFlow({ state, api, token: () => token, newKey: () => String(++key), confirmReplacement: confirm, confirmDisable: confirm })
  flow.route(11, 101)
  return { flow, state, api, changeToken(value) { token = value } }
}
test('列表切车、隐藏和账号变化忽略迟到数据', async () => {
  const waiting = deferred(), state = initialReminderListState(); let owner = 'one'
  const flow = createReminderListFlow({ state, token: () => owner, api: { list: () => waiting.promise } })
  const loading = flow.resume(101); flow.suspend(); owner = 'two'; waiting.resolve({ list: ['private'], current_mileage: 1000, today: '2026-10-12' }); await loading
  assert.deepEqual(state.rows, []); assert.equal(state.loaded, false)
})
test('弱网相同目标复用键，修改目标更换键，重复点击不并发', async () => {
  const calls = []; let count = 0
  const h = editor({ save: async (token, source, body, key) => { calls.push(key); count++; throw new ArchiveReminderError('network', 'retry') } })
  await h.flow.resume(); h.state.mileage = '1000'
  await h.flow.save(); await h.flow.save(); h.state.mileage = '2000'; await h.flow.save()
  assert.deepEqual(calls, ['1', '1', '2'])
  const pending = deferred(); h.api.save = () => { count++; return pending.promise }
  const sending = h.flow.save(); await h.flow.save(); assert.equal(count, 4); pending.resolve({}); await sending
})
test('冲突须刷新复核，不能直接继续提交旧版本', async () => {
  let writes = 0
  const h = editor({ save: async () => { writes++; throw new ArchiveReminderError('conflict', 'refresh') } })
  await h.flow.resume(); h.state.mileage = '1000'; await h.flow.save(); await h.flow.save()
  assert.equal(writes, 1); assert.equal(h.state.kind, 'conflict')
  await h.flow.load(); assert.equal(h.state.kind, ''); assert.equal(h.state.mileage, '')
})
test('替换来源或停用取消不写入，确认后目标来源明确', async () => {
  let accepted = false; const bodies = []
  const old = context(); old.setting = { revision: 1, active: true, archive_id: 14, due_date: '2026-12-01', due_mileage: null }
  const h = editor({ initial: old, confirm: async () => accepted, save: async (token, source, body) => { bodies.push(body) } })
  await h.flow.resume(); assert.equal(h.state.date, ''); h.state.date = '2026-12-02'
  await h.flow.save(); assert.equal(bodies.length, 0)
  accepted = true; await h.flow.save(); assert.equal(bodies[0].replace_source, true); assert.equal(bodies[0].due_date, '2026-12-02')
  const own = context(); own.setting = { revision: 2, active: true, archive_id: 11, due_date: '2026-12-02', due_mileage: null }
  accepted = false
  const disabling = editor({ initial: own, confirm: async () => accepted, save: async (token, source, body) => bodies.push(body) })
  await disabling.flow.resume(); await disabling.flow.save(false); assert.equal(bodies.length, 1)
  accepted = true; await disabling.flow.save(false); assert.deepEqual(bodies[1], { expected_revision: 2, active: false })
})
test('待确认时退出账号不会写入；隐藏时迟到成功不恢复表单', async () => {
  const waiting = deferred(); let writes = 0
  const old = context(); old.setting = { revision: 1, active: true, archive_id: 14, due_date: '2026-12-01', due_mileage: null }
  const h = editor({ initial: old, confirm: () => waiting.promise, save: async () => { writes++ } })
  await h.flow.resume(); h.state.date = '2026-12-02'; const pending = h.flow.save()
  h.changeToken('other'); h.flow.reset(); waiting.resolve(true); await pending
  assert.equal(writes, 0); assert.equal(h.state.context, null)
  const response = deferred(), second = editor({ save: () => response.promise }); await second.flow.resume(); second.state.mileage = '1000'
  const sending = second.flow.save(); second.flow.suspend(); response.resolve({}); await sending
  assert.equal(second.state.context, null); assert.equal(second.state.mileage, ''); assert.equal(second.state.busy, false)
})
