import test from 'node:test'
import assert from 'node:assert/strict'
import { createArchiveFlow, initialArchiveState } from '../src/services/archive-flow.js'

test('retry preserves body and key until form changes', async () => {
  const state = { ...initialArchiveState(), vehicleId: 11, recordedDate: '2026-10-04', title: '保养', fileIds: [5] }
  const calls = [], api = { add: async (token, body, key) => { calls.push({ token, body, key }); if (calls.length === 1) throw Error('network'); return { archive_id: calls.length, vehicle_id: 11 } } }
  const flow = createArchiveFlow({ state, api, token: () => 'token', newKey: () => `key-${calls.length}` })
  await flow.save(); await flow.save()
  assert.equal(calls[0].key, calls[1].key)
  assert.deepEqual(calls[0].body, calls[1].body)
  assert.equal(state.saved.archive_id, 2)
})
test('reset discards a late result after owner changes', async () => {
  let resolve, token = 'first'
  const state = { ...initialArchiveState(), vehicleId: 11, recordedDate: '2026-10-04', title: '保养' }
  const flow = createArchiveFlow({ state, api: { add: () => new Promise(done => { resolve = done }) }, token: () => token, newKey: () => 'key' })
  const save = flow.save(); token = 'second'; flow.reset(); resolve({ archive_id: 3, vehicle_id: 11 }); await save
  assert.equal(state.saved, null)
  assert.equal(state.vehicleId, 0)
})

test('photo save requires an uploaded image and sends the source', async () => {
  const state = { ...initialArchiveState(), vehicleId: 11, inputType: 1, recordedDate: '2026-10-04', title: '拍照记录' }
  const calls = [], flow = createArchiveFlow({ state, api: { async add(...args) { calls.push(args); return { archive_id: 1, vehicle_id: 11 } } },
    token: () => 'token', newKey: () => 'key' })
  await flow.save()
  assert.equal(calls.length, 0)
  assert.equal(state.failureKind, 'invalid')
  state.fileIds = [5]
  await flow.save()
  assert.equal(calls[0][1].input_type, 1)
})
