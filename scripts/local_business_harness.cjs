// Opt-in local acceptance harness. Real WeChat code and backend responses;
// no mocked business data, credentials printed, or production integration.
const http = require('node:http')
const https = require('node:https')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { createHash } = require('node:crypto')

if (!process.argv.includes('--allow-local-test-writes')) {
  console.error('Requires --allow-local-test-writes; only the isolated local database is supported.')
  process.exit(2)
}
const root = path.resolve(__dirname, '..'), publicRoot = path.join(root, 'apps/miniapp/dist/build/h5')
const origin = 'http://127.0.0.1:4317', backend = 'http://127.0.0.1:18080'
const container = 'vehicle-auth-local-mysql-1'
function docker(args, input) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true })
  if (result.status !== 0) throw new Error('Local database operation failed')
  return result.stdout.trim()
}
function sql(query) {
  return docker(['exec', '-i', container, 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'], query)
}
function prepare() {
  const project = docker(['inspect', container, '--format', '{{index .Config.Labels "com.docker.compose.project"}}'])
  if (project !== 'vehicle-auth-local') throw new Error('Refusing non-test Docker project')
  if (!fs.existsSync(path.join(publicRoot, 'index.html'))) throw new Error('Build H5 first')
  // Reserve IDs only if absent or already marked synthetic. Reject collisions.
  const collisions = sql("SELECT (SELECT COUNT(*) FROM brand WHERE id=9100601 AND name<>'本地合成测试品牌')+(SELECT COUNT(*) FROM series WHERE id=9100601 AND (name<>'本地合成测试车系' OR brand_id<>9100601))+(SELECT COUNT(*) FROM model WHERE id IN (9100601,9100602) AND (series_id<>9100601 OR config_name NOT IN ('合成配置A','合成配置B')))+(SELECT COUNT(*) FROM user WHERE id=9100690 AND openid<>'local-business-synthetic-foreign-owner')+(SELECT COUNT(*) FROM vehicle WHERE id=9100690 AND (user_id<>9100690 OR model_id<>9100601));")
  if (collisions !== '0') throw new Error('Synthetic fixture IDs conflict')
  sql(fs.readFileSync(path.join(__dirname, 'local_business_fixture.sql'), 'utf8'))
}
let socket, nextId = 0
const pending = new Map()
async function wechatCode() {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    socket = new WebSocket('ws://127.0.0.1:9420')
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('DevTools unavailable')) })
    socket.onmessage = event => {
      const message = JSON.parse(event.data), item = pending.get(String(message.id))
      if (!item) return
      clearTimeout(item.timer); pending.delete(String(message.id))
      message.error ? item.reject(new Error('WeChat login failed')) : item.resolve(message.result)
    }
  }
  const result = await new Promise((resolve, reject) => {
    const id = String(++nextId), timer = setTimeout(() => { pending.delete(id); reject(new Error('WeChat login timeout')) }, 15000)
    pending.set(id, { resolve, reject, timer })
    socket.send(JSON.stringify({ id, method: 'App.callWxMethod', params: { method: 'login', args: [{ provider: 'weixin' }] } }))
  })
  const code = result?.code ?? result?.result?.code ?? result?.data?.code
  if (typeof code !== 'string' || !code) throw new Error('Missing WeChat code')
  return code
}
const writes = [], checks = []
let dropOnce = ''
const json = (res, status, data) => { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(data)) }
async function read(req, limit = 65536) {
  const chunks = []; let bytes = 0
  for await (const chunk of req) { bytes += chunk.length; if (bytes > limit) throw Object.assign(new Error('Request too large'), { localBodyLimit:true }); chunks.push(chunk) }
  return Buffer.concat(chunks)
}
async function handler(req, res) {
  const url = new URL(req.url, origin)
  if (req.headers.origin && req.headers.origin !== origin) return json(res, 403, { error: 'Local origin required' })
  if (url.pathname.startsWith('/__local/')) {
    if (req.headers.origin !== origin || req.headers['x-local-business'] !== '1') return json(res, 403, { error: 'Local acceptance header required' })
    if (url.pathname === '/__local/code' && req.method === 'POST') return json(res, 200, { code: await wechatCode() })
    if (url.pathname === '/__local/image' && req.method === 'POST') {
      const body = JSON.parse((await read(req)).toString()), target = new URL(body.url)
      if (target.origin !== 'https://127.0.0.1:9443' || !/^\/owner-archives-local\/uploads\/[a-f0-9-]+$/.test(target.pathname)) return json(res, 400, { error:'Local signed image required' })
      // Trust only the generated certificate, never disable TLS verification.
      return new Promise(resolve => {
        const upstream = https.get(target, { ca:fs.readFileSync(path.join(root,'test-results/local-upload-cert.pem')) }, response => {
          res.writeHead(response.statusCode, { 'Content-Type':response.headers['content-type'] || 'application/octet-stream', 'Cache-Control':'no-store' })
          response.pipe(res); response.on('end',resolve)
          response.on('error',()=>{res.destroy();resolve()})
        })
        upstream.setTimeout(10000,()=>upstream.destroy())
        upstream.on('error',()=>{if(!res.headersSent)json(res,503,{error:'Local signed download unavailable'});else res.destroy();resolve()})
      })
    }
    if (url.pathname === '/__local/drop-once' && req.method === 'POST') {
      const body = JSON.parse((await read(req)).toString())
      if (!['/api/vehicle/add', '/api/archive/add', '/api/file/upload'].includes(body.path)) return json(res, 400, { error: 'Unsupported failure point' })
      dropOnce = body.path; return json(res, 200, { armed: true })
    }
    if (url.pathname === '/__local/evidence' && req.method === 'POST') {
      return json(res, 200, { writes: writes.map(({route,key,hash,status,dropped}) => ({route,key,hash,status,dropped})), checks })
    }
    if (url.pathname === '/__local/database' && req.method === 'POST') {
      const aggregate = sql("SELECT (SELECT COUNT(*) FROM vehicle WHERE model_id IN (9100601,9100602) AND user_id<>9100690),(SELECT COUNT(*) FROM vehicle_archive a JOIN vehicle v ON v.id=a.vehicle_id WHERE v.model_id IN (9100601,9100602) AND v.user_id<>9100690),(SELECT COUNT(*) FROM audit_log a JOIN vehicle v ON a.resource_type='vehicle' AND a.resource_id=v.id WHERE v.model_id IN (9100601,9100602) AND v.user_id<>9100690),(SELECT COUNT(*) FROM audit_log a JOIN vehicle_archive r ON a.resource_type='vehicle_archive' AND a.resource_id=r.id JOIN vehicle v ON v.id=r.vehicle_id WHERE v.model_id IN (9100601,9100602) AND v.user_id<>9100690);" ).split('\t').map(Number)
      return json(res, 200, { vehicles:aggregate[0], archives:aggregate[1], vehicleAudits:aggregate[2], archiveAudits:aggregate[3] })
    }
    return json(res, 404, { error: 'Unknown local action' })
  }
  if (url.pathname.startsWith('/api/') || url.pathname === '/actuator/health') {
    const body = await read(req, url.pathname === '/api/file/upload' ? 11 * 1024 * 1024 : 65536), headers = {}
    for (const name of ['authorization','content-type','idempotency-key']) if (req.headers[name]) headers[name] = req.headers[name]
    const upstream = await fetch(backend + url.pathname + url.search, { method:req.method, headers, body:body.length ? body : undefined, redirect:'manual', signal:AbortSignal.timeout(20000) })
    const bytes = Buffer.from(await upstream.arrayBuffer())
    const dropping = url.pathname === dropOnce && upstream.ok
    if (['/api/vehicle/add','/api/archive/add','/api/file/upload'].includes(url.pathname) && req.method === 'POST') writes.push({route:url.pathname,key:headers['idempotency-key'],hash:createHash('sha256').update(body).digest('hex'),status:upstream.status,dropped:dropping})
    if (dropping) {
      dropOnce=''
      // Send a partial response so Chromium cannot silently replay a connection
      // failure before the application observes the committed write.
      res.writeHead(200, { 'Content-Type':'application/json', 'Content-Length':'10000' })
      res.write('{'); setTimeout(() => res.destroy(), 100); return
    }
    checks.push({ route:url.pathname, status:upstream.status })
    res.writeHead(upstream.status, { 'Content-Type':upstream.headers.get('content-type') || 'application/json', 'Cache-Control':'no-store' }); return res.end(bytes)
  }
  if (!['GET','HEAD'].includes(req.method)) return json(res, 405, { error:'Method not allowed' })
  let file = path.resolve(publicRoot, '.' + decodeURIComponent(url.pathname))
  if (!file.startsWith(publicRoot + path.sep) && file !== publicRoot) return json(res, 403, { error:'Invalid path' })
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(publicRoot, 'index.html')
  const types={'.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2','.ttf':'font/ttf'}
  res.writeHead(200, { 'Content-Type':types[path.extname(file)] || 'application/octet-stream', 'Cache-Control':'no-store' })
  if (req.method === 'HEAD') return res.end()
  fs.createReadStream(file).pipe(res)
}
try {
  prepare()
  const server = http.createServer((req,res) => handler(req,res).catch(error => { if (!res.headersSent) json(res,error.localBodyLimit === true ? 413 : 503,{error:'Local acceptance operation failed'}); else res.destroy() }))
  server.listen(4317,'127.0.0.1',() => console.log('Local acceptance ready at '+origin+'; synthetic fixtures only; credentials withheld'))
  const close=()=>{ socket?.close(); server.close(()=>process.exit(0)) }
  process.on('SIGINT',close); process.on('SIGTERM',close)
} catch { console.error('Local acceptance setup failed; check isolated container, fixtures and H5 build'); process.exitCode=1 }
