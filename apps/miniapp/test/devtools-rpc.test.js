import test from 'node:test'
import assert from 'node:assert/strict'
import rpcHelpers from './helpers/devtools-rpc.cjs'
const { connectDevtools, waitForPage } = rpcHelpers

function socketClass({ open = true, send } = {}) {
  return class {
    static last
    readyState = 0
    constructor() {
      this.constructor.last = this
      if (open) queueMicrotask(() => { this.readyState = 1; this.onopen?.() })
    }
    send(raw) { send?.(this, JSON.parse(raw)) }
    close() { this.readyState = 3; this.onclose?.() }
  }
}

test('Devtools connection rejects remote endpoints and invalid timeouts', () => {
  for (const endpoint of ['wss://localhost:9420', 'ws://example.com:9420']) assert.throws(() => connectDevtools({ endpoint }), /local/)
  assert.throws(() => connectDevtools({ timeoutMs: 0 }), /timeout/)
})

test('Devtools connection timeout closes a stalled socket', async () => {
  const Socket = socketClass({ open: false })
  await assert.rejects(connectDevtools({ WebSocketImpl: Socket, timeoutMs: 15 }), /connection timeout/)
  assert.equal(Socket.last.readyState, 3)
})

test('Devtools matches out of order RPC responses and ignores events', async () => {
  const Socket = socketClass(), client = await connectDevtools({ WebSocketImpl: Socket })
  try {
    const first = client.rpc('first'), second = client.rpc('second')
    Socket.last.onmessage({ data: JSON.stringify({ method: 'event' }) })
    Socket.last.onmessage({ data: JSON.stringify({ id: '2', result: 'second-result' }) })
    Socket.last.onmessage({ data: JSON.stringify({ id: 1, result: 'first-result' }) })
    assert.deepEqual(await Promise.all([first, second]), ['first-result', 'second-result'])
  } finally { client.close() }
})

test('Devtools RPC rejects errors without exposing response payload', async () => {
  const Socket = socketClass({ send: (ws, request) => ws.onmessage({ data: JSON.stringify({ id: request.id, error: { secret: 'private-payload' } }) }) })
  const client = await connectDevtools({ WebSocketImpl: Socket })
  try { await assert.rejects(client.rpc('failing'), error => /RPC error \(failing\)/.test(error.message) && !error.message.includes('private-payload')) }
  finally { client.close() }
})

test('Devtools RPC timeout rejects instead of hanging', async () => {
  const client = await connectDevtools({ WebSocketImpl: socketClass(), timeoutMs: 15 })
  try { await assert.rejects(client.rpc('stalled'), /RPC timeout: stalled/) }
  finally { client.close() }
})

test('Devtools close rejects pending and subsequent RPCs', async () => {
  const client = await connectDevtools({ WebSocketImpl: socketClass() })
  const pending = client.rpc('pending')
  client.close()
  await assert.rejects(pending, /closed/)
  await assert.rejects(client.rpc('later'), /closed/)
})

test('Malformed Devtools responses terminate the connection', async () => {
  const Socket = socketClass(), client = await connectDevtools({ WebSocketImpl: Socket })
  const pending = client.rpc('pending')
  Socket.last.onmessage({ data: 'not-json' })
  await assert.rejects(pending, /Invalid Devtools JSON/)
  assert.equal(Socket.last.readyState, 3)
})

test('Native page polling waits through redirect and fails on wrong route', async () => {
  const pages = [{ path: 'old' }, { path: 'expected', pageId: 7 }]
  assert.equal((await waitForPage(async () => pages.shift(), 'expected', { pollMs: 1 })).pageId, 7)
  await assert.rejects(waitForPage(async () => ({ path: 'wrong' }), 'expected', { timeoutMs: 5, pollMs: 1 }), /expected expected, got wrong/)
})
