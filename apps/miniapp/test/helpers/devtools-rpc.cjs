const DEFAULT_ENDPOINT = 'ws://127.0.0.1:9420'

function connectDevtools({ endpoint = DEFAULT_ENDPOINT, timeoutMs = 10000, WebSocketImpl = WebSocket } = {}) {
  const url = new URL(endpoint)
  if (url.protocol !== 'ws:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) {
    throw new Error('Devtools endpoint must be a local ws:// address')
  }
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) throw new Error('Invalid RPC timeout')
  return new Promise((resolve, reject) => {
    const ws = new WebSocketImpl(endpoint), pending = new Map()
    let opened = false, stopped = false, id = 0
    function stop(error) {
      if (stopped) return
      stopped = true
      clearTimeout(connectionTimer)
      for (const request of pending.values()) { clearTimeout(request.timer); request.reject(error) }
      pending.clear()
      if (!opened) reject(error)
      if (ws.readyState < 2) ws.close()
    }
    const connectionTimer = setTimeout(() => stop(new Error(`Devtools connection timeout: ${endpoint}`)), timeoutMs)
    ws.onerror = () => stop(new Error(`Devtools connection failed: ${endpoint}`))
    ws.onclose = () => stop(new Error('Devtools connection closed'))
    ws.onmessage = event => {
      let message
      try { message = JSON.parse(event.data) }
      catch { stop(new Error('Invalid Devtools JSON response')); return }
      if (!message || typeof message !== 'object') { stop(new Error('Invalid Devtools response')); return }
      const request = pending.get(String(message.id))
      if (!request) return
      clearTimeout(request.timer); pending.delete(String(message.id))
      if (message.error) request.reject(new Error(`Devtools RPC error (${request.method})`))
      else request.resolve(message.result)
    }
    ws.onopen = () => {
      if (stopped) { ws.close(); return }
      opened = true; clearTimeout(connectionTimer)
      resolve({
        rpc(method, params = {}) {
          if (stopped) return Promise.reject(new Error('Devtools connection closed'))
          return new Promise((resolveRpc, rejectRpc) => {
            const requestId = String(++id)
            const timer = setTimeout(() => { pending.delete(requestId); rejectRpc(new Error(`Devtools RPC timeout: ${method}`)) }, timeoutMs)
            pending.set(requestId, { resolve: resolveRpc, reject: rejectRpc, timer, method })
            try { ws.send(JSON.stringify({ id: requestId, method, params })) }
            catch { clearTimeout(timer); pending.delete(requestId); rejectRpc(new Error(`Devtools RPC send failed: ${method}`)) }
          })
        },
        close() { stop(new Error('Devtools connection closed by runner')) },
      })
    }
  })
}

async function waitForPage(rpc, expected, { timeoutMs = 10000, pollMs = 100 } = {}) {
  const deadline = Date.now() + timeoutMs
  let current
  do {
    current = await rpc('App.getCurrentPage')
    if (current?.path === expected) return current
    await new Promise(resolve => setTimeout(resolve, pollMs))
  } while (Date.now() < deadline)
  throw new Error(`Devtools page timeout: expected ${expected}, got ${current?.path || '(none)'}`)
}

module.exports = { connectDevtools, waitForPage, DEFAULT_ENDPOINT }
