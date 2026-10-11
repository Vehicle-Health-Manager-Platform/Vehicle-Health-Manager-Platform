import test from 'node:test'
import assert from 'node:assert/strict'
import { createArchiveReportFlow } from '../src/services/archive-report-flow.js'
import { ArchiveReportError } from '../src/services/archive-report.js'

const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function harness(apiOverrides = {}) {
  let owner = 'owner-one', vehicle = 7
  const removed = [], opened = [], changes = []
  const api = {
    download: async () => '/tmp/report.pdf',
    openDocument: async path => { opened.push(path) },
    remove: path => removed.push(path),
    ...apiOverrides,
  }
  const flow = createArchiveReportFlow({ api, token: () => owner, vehicleId: () => vehicle,
    onChange: state => changes.push(state) })
  return { flow, api, removed, opened, changes, owner: value => { owner = value }, vehicle: value => { vehicle = value } }
}

test('请求期间切车或退出时会取消下载，迟到路径不会打开并会清理', async () => {
  const pending = deferred(), h = harness({ download: (token, vehicle, range, signal) => { hSignal = signal; return pending.promise } })
  let hSignal
  const run = h.flow.export({ from: '2026-10-01', to: '2026-10-31' })
  await Promise.resolve()
  h.vehicle(8)
  h.flow.invalidate()
  assert.equal(hSignal.cancelled, true)
  pending.resolve('/tmp/late-report.pdf')
  await run
  assert.deepEqual(h.opened, [])
  assert.deepEqual(h.removed, ['/tmp/late-report.pdf'])
})

test('页面隐藏会中止未完成下载并提示返回后重试', async () => {
  const pending = deferred()
  let requestSignal
  const h = harness({ download: (_token, _vehicle, _range, signal) => { requestSignal = signal; return pending.promise } })
  const run = h.flow.export()
  await Promise.resolve()
  h.flow.suspend()
  assert.equal(requestSignal.cancelled, true)
  pending.resolve('/tmp/hidden-report.pdf')
  await run
  assert.deepEqual(h.opened, [])
  assert.deepEqual(h.removed, ['/tmp/hidden-report.pdf'])
  assert.equal(h.flow.state.message, '导出已暂停，返回后可以重新生成')
})

test('打开系统 PDF 阅读器时保留文件，返回小程序后删除临时副本', async () => {
  const opening = deferred(), h = harness({ openDocument: path => { h.opened.push(path); return opening.promise } })
  const run = h.flow.export()
  await Promise.resolve(); await Promise.resolve()
  assert.deepEqual(h.opened, ['/tmp/report.pdf'])
  h.flow.suspend()
  assert.deepEqual(h.removed, [])
  opening.resolve()
  await run
  assert.deepEqual(h.removed, [])
  h.flow.resume()
  assert.deepEqual(h.removed, ['/tmp/report.pdf'])
  assert.equal(h.flow.state.kind, 'success')
})

test('若页面已在 openDocument 回调前恢复，成功后马上清理文件', async () => {
  const opening = deferred(), h = harness({ openDocument: () => opening.promise })
  const run = h.flow.export()
  await Promise.resolve(); await Promise.resolve()
  h.flow.suspend(); h.flow.resume()
  opening.resolve()
  await run
  assert.deepEqual(h.removed, ['/tmp/report.pdf'])
})

test('新导出会取消旧任务；PDF 打开失败时清理并给出可重试提示', async () => {
  const first = deferred(), h = harness({ download: (() => { let calls = 0; return () => ++calls === 1 ? first.promise : Promise.resolve('/tmp/next-report.pdf') })(),
    openDocument: async () => { throw new ArchiveReportError('open', 'PDF 未能打开，请重试') } })
  const old = h.flow.export()
  await Promise.resolve()
  await h.flow.export()
  first.resolve('/tmp/old-report.pdf')
  await old
  assert.deepEqual(h.removed, ['/tmp/next-report.pdf', '/tmp/old-report.pdf'])
  assert.equal(h.flow.state.kind, 'open')
  assert.equal(h.flow.state.message, 'PDF 未能打开，请重试')
})
