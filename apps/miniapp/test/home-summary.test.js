import test from 'node:test'
import assert from 'node:assert/strict'
import { ownerSession } from '../src/services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle, selectionFromPage } from '../src/services/owner-vehicle-selection.js'
import { createHomeSummaryFlow, initialHomeSummaryState } from '../src/services/home-summary-flow.js'
import { ArchiveError } from '../src/services/archives.js'

const a = { vehicle_id: 1, model_name: '车型一' }, b = { vehicle_id: 2, model_name: '车型二' }
test('a selection from a later page survives refresh of the first page and clears on identity change', () => {
  ownerSession.accessToken = 'owner-one'
  selectOwnerVehicle(b)
  assert.equal(selectionFromPage([a], selectedOwnerVehicle.value.vehicle_id), null)
  assert.equal(selectedOwnerVehicle.value.vehicle_id, 2)
  assert.equal(selectionFromPage([a], 0).vehicle_id, 1)
  ownerSession.accessToken = 'owner-two'
  assert.equal(selectedOwnerVehicle.value, null)
  ownerSession.accessToken = ''
})

function harness() {
  const state = initialHomeSummaryState(), calls = []
  let token = 'owner-one'
  const api = { list: async (...args) => { calls.push(args); return { list: [], total: 0 } } }
  const flow = createHomeSummaryFlow({ state, api, token: () => token })
  return { state, calls, api, flow, token(value) { token = value; flow.reset() } }
}
test('summary uses the server total and newest record; empty records are explicit', async () => {
  const h = harness()
  h.api.list = async (...args) => { h.calls.push(args); return { total: 14, list: [{ archive_id: 9, title: '保养' }] } }
  await h.flow.select(a)
  assert.deepEqual(h.calls[0], ['owner-one', 1, 1])
  assert.equal(h.state.total, 14)
  assert.equal(h.state.latest.title, '保养')
  await h.flow.select(b)
  assert.equal(h.calls[1][1], 2)
  h.api.list = async () => ({ total: 0, list: [] })
  await h.flow.load()
  assert.equal(h.state.latest, null)
  assert.equal(h.state.total, 0)
})
test('failure can retry without showing the previous vehicle summary', async () => {
  const h = harness()
  h.api.list = async () => ({ total: 1, list: [{ archive_id: 1, title: '旧车记录' }] })
  await h.flow.select(a)
  h.api.list = async () => { throw new ArchiveError('unavailable', '档案服务暂不可用') }
  await h.flow.select(b)
  assert.equal(h.state.latest, null)
  assert.equal(h.state.total, 0)
  assert.equal(h.state.failureKind, 'unavailable')
  h.api.list = async () => ({ total: 2, list: [{ archive_id: 2, title: '新车记录' }] })
  await h.flow.load()
  assert.equal(h.state.latest.title, '新车记录')
  assert.equal(h.state.message, '')
})
test('late response after vehicle switch or logout cannot overwrite the current summary', async () => {
  const h = harness(); let finish
  h.api.list = () => new Promise(resolve => { finish = resolve })
  const old = h.flow.select(a)
  h.api.list = async () => ({ total: 3, list: [{ archive_id: 3, title: '新车' }] })
  await h.flow.select(b)
  finish({ total: 1, list: [{ archive_id: 1, title: '旧车' }] }); await old
  assert.equal(h.state.latest.title, '新车')
  let finishLogout
  h.api.list = () => new Promise(resolve => { finishLogout = resolve })
  const pending = h.flow.load(); h.token('owner-two')
  finishLogout({ total: 99, list: [{ archive_id: 99, title: '他人' }] }); await pending
  assert.equal(h.state.vehicle, null)
  assert.equal(h.state.latest, null)
})
test('hidden page ignores an old response and refreshes on return', async () => {
  const h = harness(); let finish
  h.api.list = () => new Promise(resolve => { finish = resolve })
  const pending = h.flow.select(a)
  h.flow.suspend(); finish({ total: 8, list: [{ archive_id: 8, title: '隐藏后返回' }] }); await pending
  assert.equal(h.state.loaded, false)
  h.api.list = async () => ({ total: 1, list: [{ archive_id: 1, title: '最新' }] })
  await h.flow.load()
  assert.equal(h.state.latest.title, '最新')
})
