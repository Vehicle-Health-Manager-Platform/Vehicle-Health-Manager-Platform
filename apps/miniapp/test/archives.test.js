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

const service = { archive_id: 99, vehicle_id: 11, archive_type: 2, input_type: 4, recorded_date: '2026-10-09',
  title: '施工记录', notes: '实际维修方案', mileage: null, file_ids: [101, 102],
  source: { order_id: 7, report_id: 8, review_id: 9, redemption_id: 10 }, test_mode: true,
  parts_used: [], no_parts: true, work_minutes: 40, submitted_at: '2026-10-09T00:50:00Z',
  signed_at: '2026-10-09T01:00:00Z', redeemed_at: '2026-10-09T01:10:00Z' }
function listApi(row) {
  return createArchiveApi({ baseUrl: 'https://api.example', runtime: () => ({ request: o => o.success({ statusCode: 200,
    data: { code: 0, data: { list: [row], total: 1, page: 1, page_size: 20 } } }) }) })
}
test('automatic service archives retain traceability test status actual minutes and private files', async () => {
  assert.deepEqual((await listApi(service).list('owner', 11)).list[0], service)
  assert.throws(() => archiveBody({ ...fields, inputType: 4 }))
})
test('automatic archives reject missing source test marker fake mileage and malformed report fields', async () => {
  for (const row of [{ ...service, source: {} }, { ...service, test_mode: undefined }, { ...service, mileage: 0 },
    { ...service, work_minutes: 0 }, { ...service, no_parts: false, parts_used: [null] }, { ...service, parts_used: [{ name: '油' }] }, { ...service, signed_at: 'bad' }])
    await assert.rejects(listApi(row).list('owner', 11), error => error.kind === 'protocol')
})
test('service evidence uses archive scoped bearer route and requires fresh HTTPS signatures', async () => {
  let sent, payload = { url: 'https://files.example/private?signature=temporary', expires_at: new Date(Date.now()+60000).toISOString() }
  const api = createArchiveApi({ baseUrl: 'https://api.example', runtime: () => ({ request(o) { sent=o; o.success({statusCode:200,data:{code:0,data:payload}}) } }) })
  assert.deepEqual(await api.access('owner',99,101),payload)
  assert.equal(sent.url,'https://api.example/api/archive/99/files/101/access')
  assert.equal(sent.header.Authorization,'Bearer owner')
  for (const bad of [{...payload,url:'http://files.example/private'}, {...payload,expires_at:'2020-01-01T00:00:00Z'}]) {
    payload=bad; await assert.rejects(api.access('owner',99,101),e=>e.kind==='protocol')
  }
  assert.throws(()=>api.access('owner',0,101)); assert.throws(()=>api.access('',99,101))
})
