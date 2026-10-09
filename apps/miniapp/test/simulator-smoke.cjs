// Native anonymous baseline; CLI HTTP port 11927, automation WebSocket port 9420.
// Run against a fresh compiled project. No responses or identities are mocked.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { createHash } = require('node:crypto')
const { execFileSync } = require('node:child_process')
const { connectDevtools, waitForPage, DEFAULT_ENDPOINT } = require('./helpers/devtools-rpc.cjs')
const root = path.resolve(__dirname, '../../..'), options = new Map()
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i], value = process.argv[i + 1]
  if (!['--endpoint', '--backend-origin', '--timeout-ms'].includes(key) || !value) throw new Error('Invalid smoke arguments')
  options.set(key, value)
}
const endpoint = options.get('--endpoint') || DEFAULT_ENDPOINT
const timeoutMs = Number(options.get('--timeout-ms') || 10000), backend = options.get('--backend-origin')
if (backend) {
  const url = new URL(backend)
  assert.ok(url.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) && url.pathname === '/' && !url.username && !url.password && !url.search && !url.hash, 'Only a local isolated backend origin is allowed')
}
const report = { startedAt: new Date().toISOString(), endpoint, backend: backend || null, completed: false, checks: [], scope: 'anonymous native routes, login gates and real local HTTP; not authenticated business or device acceptance' }
const output = path.join(root, 'test-results/wechat-native-smoke.json')
function pass(name) { report.checks.push({ name, passed: true }); console.log(`PASS ${name}`) }
async function buttons(rpc, page) {
  const { elements } = await rpc('Page.getElements', { pageId: page.pageId, selector: 'button' })
  return Promise.all(elements.map(async element => {
    const { properties } = await rpc('Element.getDOMProperties', { pageId: page.pageId, elementId: element.elementId, names: ['innerText'] })
    return properties[0]
  }))
}
async function main() {
  const dist = path.join(root, 'apps/miniapp/dist/build/mp-weixin')
  const app = JSON.parse(fs.readFileSync(path.join(dist, 'app.json'), 'utf8'))
  const project = JSON.parse(fs.readFileSync(path.join(dist, 'project.config.json'), 'utf8'))
  assert.ok(fs.existsSync(path.join(dist, 'app.js')) && fs.existsSync(path.join(dist, 'common/vendor.js')), 'Complete build required')
  const source = JSON.parse(fs.readFileSync(path.join(root, 'apps/miniapp/src/pages.json'), 'utf8'))
  assert.deepEqual(app.pages, source.pages.map(page => page.path), 'Built routes differ from source')
  report.commit = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim()
  report.appId = project.appid
  report.vendorSha256 = createHash('sha256').update(fs.readFileSync(path.join(dist, 'common/vendor.js'))).digest('hex')
  report.domainCheck = project.setting.urlCheck
  const privateFile = path.join(dist, 'project.private.config.json')
  report.privateDomainException = fs.existsSync(privateFile) && JSON.parse(fs.readFileSync(privateFile, 'utf8').replace(/^\uFEFF/, '')).setting?.urlCheck === false
  pass('complete build and source route inventory match')
  const client = await connectDevtools({ endpoint, timeoutMs }), { rpc } = client
  try {
    pass('native WebSocket handshake and RPC connection')
    for (const route of app.pages) {
      await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: `/${route}` }] })
      const expected = route === 'pages/operator/review' ? 'pages/operator/login' : route
      await waitForPage(rpc, expected, { timeoutMs })
      pass(`native route ${route}${expected !== route ? ' -> operator login' : ''}`)
    }
    for (const [route, text] of [
      ['pages/merchant/orders', '前往商家登录'], ['pages/technician/orders', '前往技师登录'],
      ['pages/order/list', '前往车主登录'], ['pages/community/my-cards', '前往车主登录'],
      ['pages/operator/review', '登录审核工作区'],
    ]) {
      await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: `/${route}` }] })
      const page = await waitForPage(rpc, route === 'pages/operator/review' ? 'pages/operator/login' : route, { timeoutMs })
      const deadline = Date.now() + timeoutMs
      let found
      do {
        found = (await buttons(rpc, page)).includes(text)
        if (found) break
        await new Promise(resolve => setTimeout(resolve, 100))
      } while (Date.now() < deadline)
      assert.ok(found, `Missing native login gate: ${route}`)
      pass(`anonymous login gate ${route}`)
    }
    if (backend) {
      for (const [route, expected] of [['/actuator/health', 200], ['/api/vehicle/list', 401], ['/api/merchant/orders', 401], ['/api/tech/orders', 401], ['/api/admin/experience-cards', 401]]) {
        const { result } = await rpc('App.callWxMethod', { method: 'request', args: [{ url: `${backend.replace(/\/$/, '')}${route}`, method: 'GET', timeout: timeoutMs }] })
        assert.equal(result?.statusCode, expected, `Unexpected native HTTP status: ${route}`)
        if (route === '/actuator/health') assert.equal(result.data?.status, 'UP')
        pass(`real native HTTP ${route} -> ${expected}`)
      }
    }
    await rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/pages/operator/login' }] })
    await waitForPage(rpc, 'pages/operator/login', { timeoutMs })
    const styledPage = await rpc('App.getCurrentPage')
    const { elements: containers } = await rpc('Page.getElements', { pageId: styledPage.pageId, selector: '.reservation-page' })
    assert.ok(containers.length, 'Native page container missing')
    const { styles } = await rpc('Element.getStyles', { pageId: styledPage.pageId, elementId: containers[0].elementId, names: ['padding-left', 'padding-top'] })
    assert.ok(styles.every(value => Number.parseFloat(value) > 0), 'Native shared page spacing missing')
    pass('native shared layout computed spacing')
    const screenshot = await rpc('App.captureScreenshot')
    assert.equal(typeof screenshot.data, 'string')
    fs.mkdirSync(path.dirname(output), { recursive: true })
    fs.writeFileSync(path.join(root, 'test-results/wechat-native-operator-login.png'), Buffer.from(screenshot.data.replace(/^data:image\/\w+;base64,/, ''), 'base64'))
    pass('anonymous native screenshot captured')
    report.completed = true
  } finally { client.close() }
}
main().catch(error => { report.failure = error.message; console.error(error.message); process.exitCode = 1 }).finally(() => {
  report.finishedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.writeFileSync(output, JSON.stringify(report, null, 2))
  console.log(`Native baseline ${report.completed ? 'COMPLETE' : 'INCOMPLETE'}: ${report.checks.length} passed; ${output}`)
})
