import test from 'node:test'
import assert from 'node:assert/strict'
import { archiveBody, createArchiveApi } from '../src/services/archives.js'

const fields = { vehicleId: 11, archiveType: 1, recordedDate: '2026-10-04', mileage: '123', title: ' 保养 ', notes: ' 更换机油 ', fileIds: [5, 7] }
test('archive body normalizes common fields and keeps ordered image ids', () => {
  assert.deepEqual(archiveBody(fields), { vehicle_id: 11, archive_type: 1, recorded_date: '2026-10-04',
    mileage: 123, title: '保养', notes: '更换机油', file_ids: [5, 7] })
  assert.throws(() => archiveBody({ ...fields, fileIds: [5, 5] }))
  assert.throws(() => archiveBody({ ...fields, recordedDate: '2026-02-30' }))
  assert.deepEqual(archiveBody({ ...fields, inputType: 1 }).input_type, 1)
  assert.throws(() => archiveBody({ ...fields, inputType: 1, fileIds: [] }))
  assert.throws(() => archiveBody({ ...fields, inputType: 2 }))
})
test('archive client sends bearer and idempotency key and validates list vehicle', async () => {
  const seen = []
  const api = createArchiveApi({ baseUrl: 'https://api.example', runtime: () => ({ request(options) {
    seen.push(options)
    options.success({ statusCode: 200, data: { code: 0, data: options.method === 'POST'
      ? { archive_id: 99, vehicle_id: 11 }
      : { list: [{ archive_id: 99, vehicle_id: 11, archive_type: 1, input_type: 1, recorded_date: '2026-10-04', title: '保养', notes: '', file_ids: [5] }], total: 1, page: 1, page_size: 20 } } })
  } }) })
  const key = '123e4567-e89b-42d3-a456-426614174000'
  assert.equal((await api.add('owner-token', archiveBody(fields), key)).archive_id, 99)
  assert.equal(seen[0].header.Authorization, 'Bearer owner-token')
  assert.equal(seen[0].header['Idempotency-Key'], key)
  assert.equal((await api.list('owner-token', 11)).list.length, 1)
  assert.equal((await api.list('owner-token', 11)).list[0].input_type, 1)
  assert.match(seen[1].url, /vehicle_id=11/)
})
