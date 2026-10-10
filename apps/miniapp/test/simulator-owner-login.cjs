// Manual native UI acceptance using the Devtools user's actual wx.login.
// No token, wx code, API response, or login method is injected or logged.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { connectDevtools, waitForPage } = require('./helpers/devtools-rpc.cjs')
const root = path.resolve(__dirname, '../../..')
const results = [], output = path.join(root, 'test-results/wechat-native-owner-login.json')
const report = { startedAt: new Date().toISOString(), completed: false, results, scope: 'real native owner login/read/logout; no phone, merchant SMS, payment, or device acceptance' }
function pass(name) { results.push({ name, passed: true }); console.log(`PASS ${name}`) }

async function elements(rpc, page, selector) {
  // The native automation tree exposes uni-app custom tags as "component".
  // A page-level selector does not cross that boundary, even with >>>.
  const found = [], queue = [null], visited = new Set()
  while (queue.length) {
    const elementId = queue.shift(), params = { pageId: page.pageId, ...(elementId ? { elementId } : {}) }
    const method = elementId ? 'Element.getElements' : 'Page.getElements'
    const result = await rpc(method, { ...params, selector })
    found.push(...result.elements)
    const children = await rpc(method, { ...params, selector: 'component' })
    for (const child of children.elements) {
      if (!visited.has(child.elementId)) { visited.add(child.elementId); queue.push(child.elementId) }
    }
    assert.ok(visited.size <= 50, 'Unexpected native component tree')
  }
  return Promise.all(found.map(async element => {
    const { properties } = await rpc('Element.getDOMProperties', { pageId: page.pageId, elementId: element.elementId, names: ['innerText'] })
    return { ...element, text: properties[0] }
  }))
}
async function waitButton(rpc, page, label) {
  const deadline = Date.now() + 15000
  do {
    const button = (await elements(rpc, page, 'button')).find(element => element.text === label)
    if (button) return button
    await new Promise(resolve => setTimeout(resolve, 150))
  } while (Date.now() < deadline)
  throw new Error(`Native button timeout: ${label}`)
}
async function tap(rpc, page, label) {
  const button = await waitButton(rpc, page, label)
  await rpc('Element.tap', { pageId: page.pageId, elementId: button.elementId })
}
async function main() {
  const config = require(path.join(root, 'apps/miniapp/dist/build/mp-weixin/services/api-config.js'))
  assert.ok(config.apiOrigin === 'http://127.0.0.1:18080' && config.cloudRunEnabled === false, 'Build must target the isolated local backend')
  const client = await connectDevtools({ timeoutMs: 15000 }), { rpc } = client
  let loginAttempted = false, loggedOut = false
  try {
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/owner/index' }] })
    let page = await waitForPage(rpc, 'pages/owner/index')
    await waitButton(rpc, page, '微信登录')
    pass('fresh native owner page requires login')
    loginAttempted = true
    await tap(rpc, page, '微信登录')
    await waitButton(rpc, page, '进入车主首页')
    pass('native owner button completes actual wx.login and backend session')
    await tap(rpc, page, '进入车主首页')
    page = await waitForPage(rpc, 'pages/home/index')
    pass('native owner enters home tab')
    const deadline = Date.now() + 15000
    let loaded = false
    do {
      const rows = await elements(rpc, page, '.vehicles .vehicle')
      const copies = await elements(rpc, page, '.vehicles .copy')
      loaded = rows.length > 0 || copies.some(element => element.text === '还没有车辆，先添加一辆开始记录养护。')
      if (loaded) break
      await new Promise(resolve => setTimeout(resolve, 150))
    } while (Date.now() < deadline)
    assert.ok(loaded, 'Native vehicle list did not reach loaded or empty state')
    pass('native home reads real owner vehicles without response mocks')
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/owner/index' }] })
    page = await waitForPage(rpc, 'pages/owner/index')
    await tap(rpc, page, '退出登录')
    await waitButton(rpc, page, '微信登录')
    loggedOut = true
    pass('native logout clears owner session')
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/order/list' }] })
    page = await waitForPage(rpc, 'pages/order/list')
    await waitButton(rpc, page, '前往车主登录')
    pass('after logout native order page requires owner login')
    report.completed = true
  } finally {
    try { if (loginAttempted && !loggedOut) {
      try {
        await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/owner/index' }] })
        const page = await waitForPage(rpc, 'pages/owner/index')
        const buttons = await elements(rpc, page, 'button')
        if (buttons.some(button => button.text === '退出登录')) {
          await tap(rpc, page, '退出登录'); await waitButton(rpc, page, '微信登录')
        }
      } catch { throw new Error('Native owner cleanup failed; inspect local session before continuing') }
    } } finally { client.close() }
  }
}
main().catch(error => { report.failure = error.message; console.error(error.message); process.exitCode = 1 }).finally(() => {
  report.finishedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.writeFileSync(output, JSON.stringify(report, null, 2))
  console.log(`Native real owner ${report.completed ? 'COMPLETE' : 'INCOMPLETE'}: ${results.length} passed`)
})
