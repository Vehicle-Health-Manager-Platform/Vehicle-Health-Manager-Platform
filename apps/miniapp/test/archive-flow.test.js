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
