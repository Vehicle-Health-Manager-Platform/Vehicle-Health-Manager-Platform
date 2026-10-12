import test from 'node:test'
import assert from 'node:assert/strict'
import { createArchiveShareReader, initialArchiveShareReaderState } from '../src/services/archive-share-reader.js'
const capability = 'a'.repeat(64)
const data = { summary: { version: 1 }, expires_at: '2099-01-01T00:00:00Z' }
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function harness(view) {
  let token = 'jwt', expiry
  const state = initialArchiveShareReaderState()
  const reader = createArchiveShareReader({ state, api: { view }, token: () => token,
    schedule: callback => { expiry = callback; return 1 }, cancel: () => { expiry = null } })
  reader.setCapability(capability)
  return { state, reader, changeToken(value) { token = value }, expire() { expiry() } }
}
test('隐藏页面清空摘要，忽略迟到响应，重新显示须重新读取', async () => {
  const waiting = deferred(); let calls = 0
  const h = harness(() => { calls++; return calls === 1 ? waiting.promise : Promise.resolve(data) })
  const loading = h.reader.resume(); h.reader.suspend(); waiting.resolve(data); await loading
  assert.equal(h.state.summary, null); assert.equal(h.state.busy, false)
  await h.reader.resume(); assert.deepEqual(h.state.summary, data.summary); assert.equal(calls, 2)
  h.reader.suspend(); assert.equal(h.state.summary, null)
})
test('退出账号后旧响应不恢复摘要，重新登录继续校验；卸载不再请求', async () => {
  const waiting = deferred(); let calls = 0
  const h = harness(() => { calls++; return calls === 1 ? waiting.promise : Promise.resolve(data) })
  const loading = h.reader.resume(); h.changeToken(''); await h.reader.invalidate(); waiting.resolve(data); await loading
  assert.equal(h.state.summary, null); assert.equal(h.state.kind, 'unauthorized')
  h.changeToken('other'); await h.reader.invalidate(); assert.deepEqual(h.state.summary, data.summary)
  h.reader.dispose(); assert.equal(h.state.summary, null); await h.reader.resume(); assert.equal(calls, 2)
})
test('过期计时器清空已显示摘要；撤销重试不保留旧内容', async () => {
  let revoked = false
  const h = harness(async () => { if (revoked) throw Object.assign(new Error('链接失效'), { kind: 'gone' }); return data })
  await h.reader.resume(); h.expire(); assert.equal(h.state.summary, null); assert.equal(h.state.kind, 'gone')
  await h.reader.load(); revoked = true; await h.reader.load(); assert.equal(h.state.summary, null); assert.equal(h.state.kind, 'gone')
})
