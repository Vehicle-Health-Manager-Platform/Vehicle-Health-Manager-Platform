// Manual smoke test against the WeChat Devtools automation port.
// Start cli auto with --auto-port 9420 and the current compiled project first.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')

async function main() {
  const ws = new WebSocket('ws://127.0.0.1:9420')
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject })
  let id = 0
  const pending = new Map()
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data)
    const request = pending.get(String(message.id))
    if (!request) return
    clearTimeout(request.timer)
    pending.delete(String(message.id))
    if (message.error) request.reject(new Error(JSON.stringify(message.error)))
    else request.resolve(message.result)
  }
  const rpc = (method, params = {}) => new Promise((resolve, reject) => {
    const requestId = String(++id)
    const timer = setTimeout(() => { pending.delete(requestId); reject(new Error(`Timeout: ${method}`)) }, 10000)
    pending.set(requestId, { resolve, reject, timer })
    ws.send(JSON.stringify({ id: requestId, method, params }))
  })
  const routes = ['index', 'owner', 'merchant', 'technician', 'home', 'service', 'ai', 'archive', 'mine']
  try {
    for (const name of routes) {
      const url = `/pages/${name}/index`
      await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url }] })
      const page = await rpc('App.getCurrentPage')
      assert.equal(page.path, url.slice(1))
      console.log(`PASS simulator route: ${page.path}`)
    }
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/vehicle/manual' }] })
    const manual = await rpc('App.getCurrentPage')
    assert.equal(manual.path, 'pages/vehicle/manual')
    console.log(`PASS simulator route: ${manual.path}`)
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/service/detail?id=9101101' }] })
    const detail = await rpc('App.getCurrentPage')
    assert.equal(detail.path, 'pages/service/detail')
    console.log(`PASS simulator route: ${detail.path}`)
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/merchant/projects' }] })
    const quotes = await rpc('App.getCurrentPage')
    assert.equal(quotes.path, 'pages/merchant/projects')
    console.log(`PASS simulator route: ${quotes.path}`)
    for (const url of ['/pages/merchant/slots', '/pages/order/book?id=9101201', '/pages/order/list', '/pages/order/detail?id=1']) {
      await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url }] })
      const page = await rpc('App.getCurrentPage')
      assert.equal(page.path, url.split('?')[0].slice(1))
      console.log(`PASS simulator route: ${page.path}`)
    }
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/owner/index' }] })
    const screenshot = await rpc('App.captureScreenshot')
    const output = path.resolve('test-results/wechat-owner.png')
    fs.mkdirSync(path.dirname(output), { recursive: true })
    assert.equal(typeof screenshot.data, 'string')
    fs.writeFileSync(output, Buffer.from(screenshot.data.replace(/^data:image\/\w+;base64,/, ''), 'base64'))
    console.log(`Screenshot: ${output}`)
  } finally { ws.close() }
}

main().catch((error) => { console.error(error.message); process.exitCode = 1 })
