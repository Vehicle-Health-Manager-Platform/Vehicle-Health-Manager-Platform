import test from 'node:test'
import assert from 'node:assert/strict'
import {
  ARCHIVE_REPORT_BASE64_ACCEPT, ARCHIVE_REPORT_FILE_NAME, ARCHIVE_REPORT_MAX_BYTES,
  ArchiveReportError, createArchiveReportApi,
} from '../src/services/archive-report.js'

const PDF = Uint8Array.from([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37])
const DISPOSITION = 'attachment; filename="vehicle-archive-report.pdf"'

function directHarness({ size = PDF.length, content = PDF, header = { 'Content-Type': 'application/pdf', 'Content-Disposition': DISPOSITION }, statusCode = 200,
  getFileInfo = options => options.success({ size }), readFile = options => options.success({ data: Uint8Array.from(content).buffer }) } = {}) {
  const calls = [], removed = []
  const fs = {
    getFileInfo,
    readFile,
    unlink: options => { removed.push(options.filePath); options.success() },
    writeFile: options => options.success(),
  }
  const runtime = {
    supports: () => true,
    downloadFile: options => { calls.push(options); options.success({ statusCode, tempFilePath: '/tmp/report.pdf', header }); return { abort() {} } },
    request: () => assert.fail('普通 HTTPS 不能改走 JSON request'),
    getFileSystemManager: () => fs,
    userDataPath: () => '/user/data',
  }
  return { api: createArchiveReportApi({ baseUrl: 'https://api.example', runtime: () => runtime }), calls, removed }
}

test('普通 HTTPS 使用 Bearer downloadFile 并验证固定 MIME、文件名与大小', async () => {
  const h = directHarness()
  assert.equal(await h.api.download('owner-token', 42, { from: '2026-10-01', to: '2026-10-31' }), '/tmp/report.pdf')
  assert.equal(h.calls[0].url, 'https://api.example/api/archive/report?vehicle_id=42&from=2026-10-01&to=2026-10-31')
  assert.equal(h.calls[0].header.Authorization, 'Bearer owner-token')
  assert.equal(h.calls[0].header.Accept, 'application/pdf')
})

test('普通 HTTPS 拒绝错误响应头和超限文件并清理已下载临时文件', async () => {
  const badMime = directHarness({ header: { 'content-type': 'application/json', 'content-disposition': DISPOSITION } })
  await assert.rejects(badMime.api.download('token', 1), { kind: 'protocol' })
  assert.deepEqual(badMime.removed, ['/tmp/report.pdf'])

  const oversized = directHarness({ size: ARCHIVE_REPORT_MAX_BYTES + 1 })
  await assert.rejects(oversized.api.download('token', 1), { kind: 'too-large' })
  assert.deepEqual(oversized.removed, ['/tmp/report.pdf'])

  const invalidPdf = directHarness({ content: Uint8Array.from([0x00, 0x50, 0x44, 0x46, 0x2d]) })
  await assert.rejects(invalidPdf.api.download('token', 1), { kind: 'protocol' })
  assert.deepEqual(invalidPdf.removed, ['/tmp/report.pdf'])
})

test('云托管只经 JSON request，严格验证信封后将 PDF 写入用户临时目录', async () => {
  const calls = [], writes = []
  const envelope = { version: 1, file_name: ARCHIVE_REPORT_FILE_NAME, content_type: 'application/pdf',
    content_length: PDF.length, content_base64: Buffer.from(PDF).toString('base64') }
  const fs = { unlink() {}, getFileInfo() {}, writeFile: options => { writes.push(options); options.success() } }
  const runtime = {
    request: options => { calls.push(options); options.success({ statusCode: 200, data: { code: 0, data: envelope } }); return { abort() {} } },
    downloadFile: () => assert.fail('云托管不使用 cloudrun.invalid 下载文件'),
    getFileSystemManager: () => fs, userDataPath: () => '/user/data',
  }
  const api = createArchiveReportApi({ baseUrl: 'https://cloudrun.invalid', runtime: () => runtime, cloudMode: true })
  const path = await api.download('owner-token', 8, null)
  assert.equal(calls[0].url, 'https://cloudrun.invalid/api/archive/report?vehicle_id=8')
  assert.equal(calls[0].header.Accept, ARCHIVE_REPORT_BASE64_ACCEPT)
  assert.equal(calls[0].header.Authorization, 'Bearer owner-token')
  assert.match(path, /^\/user\/data\/archive-report-[\w-]+\.pdf$/)
  assert.deepEqual(new Uint8Array(writes[0].data), PDF)
})

test('云托管拒绝非规范 Base64、错长、错误文件名和额外信封字段', async () => {
  const base = { version: 1, file_name: ARCHIVE_REPORT_FILE_NAME, content_type: 'application/pdf',
    content_length: PDF.length, content_base64: Buffer.from(PDF).toString('base64') }
  for (const envelope of [
    { ...base, content_base64: '%%%=' },
    { ...base, content_length: PDF.length + 1 },
    { ...base, file_name: '../private.pdf' },
    { ...base, private_url: 'https://files.example/secret' },
  ]) {
    const runtime = { request: options => options.success({ statusCode: 200, data: { code: 0, data: envelope } }),
      getFileSystemManager: () => ({ unlink() {}, writeFile() {} }), userDataPath: () => '/user/data' }
    const api = createArchiveReportApi({ baseUrl: 'https://cloudrun.invalid', runtime: () => runtime, cloudMode: true })
    await assert.rejects(api.download('token', 1), { kind: 'protocol' })
  }
})

test('拒绝无效日期、伪 HTTPS 哨兵直下和服务端 409', async () => {
  const direct = createArchiveReportApi({ baseUrl: 'https://cloudrun.invalid', cloudMode: false, runtime: () => ({}) })
  await assert.rejects(direct.download('token', 1), { kind: 'unconfigured' })
  const h = directHarness()
  await assert.rejects(h.api.download('token', 1, { from: '2026-02-30', to: '2026-03-01' }), { kind: 'invalid' })
  const limited = directHarness({ statusCode: 409 })
  await assert.rejects(limited.api.download('token', 1), { kind: 'too-large' })
})

test('缺少车主会话时不发起传输', async () => {
  const h = directHarness()
  await assert.rejects(h.api.download('', 1), error => error instanceof ArchiveReportError && error.kind === 'unauthorized')
  assert.equal(h.calls.length, 0)
})

test('文件验证期间取消下载会立刻删除临时文件，迟到回调不会复活结果', async () => {
  let readOptions
  const h = directHarness({ readFile: options => { readOptions = options } })
  const listeners = new Set()
  const signal = { cancelled: false, subscribe: fn => listeners.add(fn), unsubscribe: fn => listeners.delete(fn),
    cancel() { this.cancelled = true; for (const fn of [...listeners]) fn() } }
  const pending = h.api.download('token', 1, null, signal)
  await Promise.resolve()
  assert.ok(readOptions, 'downloadFile 完成后应进入 PDF 文件校验')
  signal.cancel()
  await assert.rejects(pending, { kind: 'cancelled' })
  assert.deepEqual(h.removed, ['/tmp/report.pdf'])
  readOptions.success({ data: Uint8Array.from(PDF).buffer })
  await Promise.resolve()
  assert.deepEqual(h.removed, ['/tmp/report.pdf'])
})
