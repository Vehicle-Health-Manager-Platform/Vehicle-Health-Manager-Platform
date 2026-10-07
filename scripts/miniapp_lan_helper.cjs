#!/usr/bin/env node
'use strict'

/**
 * 小程序真机（局域网）调试助手。
 *
 * 目标：把「手机真机能连上本机后端」这件事变成一条可复现的命令，
 * 用于微信测试号 真机预览 + 「打开调试」跳过域名校验时的真实微信登录联调。
 *
 * 用法：
 *   node scripts/miniapp_lan_helper.cjs                 # 只探测并打印候选局域网地址
 *   node scripts/miniapp_lan_helper.cjs --check         # 额外验证该地址上的后端是否真的可达
 *   node scripts/miniapp_lan_helper.cjs --apply         # 写入 apps/miniapp/.env.local，并验证后端可达性
 *   node scripts/miniapp_lan_helper.cjs --apply --build # 写入并重新构建 mp-weixin 产物
 *   node scripts/miniapp_lan_helper.cjs --restore       # 仅当没有可用局域网地址时，退回回环地址
 *
 * 注意：局域网地址对开发者工具同样有效（工具运行在电脑上），因此**不需要**在真机与工具之间
 * 来回切换地址。--restore 是应急出口（例如电脑未连 WiFi），不是常规收尾步骤。
 *   node scripts/miniapp_lan_helper.cjs --ip 192.168.1.5 --port 18080 --apply --build
 *
 * 只改写 .env.local 中的 VITE_API_BASE_URL，其余行（例如云托管变量）原样保留。
 * .env.local 与构建产物都受 Git 忽略，不会提交。
 *
 * 构建后还会同步开发者工具的项目私有配置（dist/build/mp-weixin/project.private.config.json）
 * 中的 urlCheck=false。后端地址是 http，工具默认会把它当作非法通讯域名拦掉，表现为所有请求
 * 直接失败——很容易被误判成后端不可用。该文件同样受 Git 忽略，只影响本机调试，不改变线上行为。
 */

const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { spawnSync } = require('node:child_process')

const REPO_ROOT = path.resolve(__dirname, '..')
const MINIAPP_DIR = path.join(REPO_ROOT, 'apps', 'miniapp')
const ENV_LOCAL = path.join(MINIAPP_DIR, '.env.local')
const BUILD_DIR = path.join(MINIAPP_DIR, 'dist', 'build', 'mp-weixin')
const DEVTOOLS_CONFIG = path.join(BUILD_DIR, 'project.config.json')
const DEVTOOLS_PRIVATE_CONFIG = path.join(BUILD_DIR, 'project.private.config.json')

const DEFAULT_PORT = '18080'
const LOOPBACK_ORIGIN = `http://127.0.0.1:${DEFAULT_PORT}`

// 常见虚拟网卡：它们的私有网段通常不是手机能到达的那张网卡。
const VIRTUAL_ADAPTER_HINTS = [
  'virtualbox', 'vmware', 'hyper-v', 'vethernet', 'wsl', 'docker',
  'loopback', 'bluetooth', 'tailscale', 'zerotier', 'radmin', 'tap-windows', 'npcap',
]
// 常见物理网卡名（中英文），命中时优先。
const PHYSICAL_ADAPTER_HINTS = ['wlan', 'wi-fi', 'wifi', 'ethernet', '以太网', '无线', '本地连接']

function isIPv4(value) {
  if (typeof value !== 'string') return false
  const parts = value.split('.')
  if (parts.length !== 4) return false
  return parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255)
}

function isUsableCandidate(address) {
  if (!isIPv4(address)) return false
  if (address.startsWith('127.')) return false
  if (address.startsWith('169.254.')) return false // APIPA 自分配，代表没拿到 DHCP
  if (address === '0.0.0.0') return false
  if (address.endsWith('.255') || address.endsWith('.0')) return false
  return true
}

function segmentRank(address) {
  if (address.startsWith('192.168.')) return 0
  if (address.startsWith('10.')) return 1
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(address)) return 2
  return 3
}

function adapterPenalty(name) {
  const lower = String(name || '').toLowerCase()
  if (PHYSICAL_ADAPTER_HINTS.some((hint) => lower.includes(hint.toLowerCase()))) return -5
  if (VIRTUAL_ADAPTER_HINTS.some((hint) => lower.includes(hint))) return 100
  return 0
}

/**
 * 从 os.networkInterfaces() 形态的对象中挑出可用的局域网 IPv4 候选。
 * 返回按推荐度升序排列的 [{address, name, rank}]。
 */
function rankLanCandidates(interfaces) {
  const candidates = []
  for (const [name, entries] of Object.entries(interfaces || {})) {
    for (const entry of entries || []) {
      if (!entry) continue
      const family = typeof entry.family === 'string' ? entry.family : `IPv${entry.family}`
      if (family !== 'IPv4') continue
      if (entry.internal) continue
      if (!isUsableCandidate(entry.address)) continue
      candidates.push({
        address: entry.address,
        name,
        rank: segmentRank(entry.address) * 10 + adapterPenalty(name),
      })
    }
  }
  return candidates.sort((a, b) => a.rank - b.rank || a.address.localeCompare(b.address))
}

/** 只替换/追加指定键，保留文件中的其它行与注释。 */
function upsertEnvLine(text, key, value) {
  const lines = String(text || '').split(/\r?\n/)
  let replaced = false
  const next = lines.map((line) => {
    if (new RegExp(`^\\s*${key}\\s*=`).test(line)) {
      replaced = true
      return `${key}=${value}`
    }
    return line
  })
  if (!replaced) {
    while (next.length && next[next.length - 1].trim() === '') next.pop()
    next.push(`${key}=${value}`)
    next.push('')
  }
  return next.join('\n')
}

function readEnvLocal() {
  try {
    return fs.readFileSync(ENV_LOCAL, 'utf8')
  } catch {
    return ''
  }
}

function parseArgs(argv) {
  const options = { apply: false, build: false, restore: false, check: false, ip: '', port: DEFAULT_PORT, help: false }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--apply') options.apply = true
    else if (arg === '--build') options.build = true
    else if (arg === '--restore') options.restore = true
    else if (arg === '--check') options.check = true
    else if (arg === '--help' || arg === '-h') options.help = true
    else if (arg === '--ip') options.ip = argv[++i] || ''
    else if (arg === '--port') options.port = argv[++i] || DEFAULT_PORT
    else if (arg.startsWith('--ip=')) options.ip = arg.slice(5)
    else if (arg.startsWith('--port=')) options.port = arg.slice(7)
    else throw new Error(`未知参数：${arg}`)
  }
  return options
}

function usage() {
  return [
    '用法：node scripts/miniapp_lan_helper.cjs [--ip x.x.x.x] [--port 18080] [--apply] [--build] [--check] [--restore]',
    '',
    '  默认        探测并打印推荐的局域网地址，不修改任何文件',
    '  --check     额外请求 http://<地址>:<端口>/actuator/health 验证手机侧能否连通',
    '  --apply     把 VITE_API_BASE_URL 写入 apps/miniapp/.env.local（其余行保留），并验证后端可达性',
    '  --build     写入后重新构建 mp-weixin 产物，并同步开发者工具的“不校验合法域名”设置',
    '  --restore   退回 http://127.0.0.1:18080 并重新构建（应急用：无可用局域网地址时；真机会失效）',
  ].join('\n')
}

/**
 * 把 urlCheck 写进开发者工具私有项目配置的文本。
 * 解析失败（空文件、非法 JSON、非对象）时返回 null，由调用方决定跳过还是重建。
 */
function setUrlCheck(jsonText, urlCheck = false) {
  let parsed
  try {
    parsed = JSON.parse(jsonText)
  } catch {
    return null
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null
  const setting =
    parsed.setting && typeof parsed.setting === 'object' && !Array.isArray(parsed.setting) ? parsed.setting : {}
  return `${JSON.stringify({ ...parsed, setting: { ...setting, urlCheck } }, null, 2)}\n`
}

/**
 * 产物目录尚未被开发者工具打开过时，按工具自身的格式初始化私有配置。
 * projectname 用 encodeURIComponent 编码，与工具写出的形式一致。
 */
function createPrivateConfig(projectName) {
  return `${JSON.stringify(
    {
      description:
        '项目私有配置文件。此文件中的内容将覆盖 project.config.json 中的相同字段。项目的改动优先同步到此文件中。',
      projectname: encodeURIComponent(projectName || ''),
      setting: { urlCheck: false },
    },
    null,
    2,
  )}\n`
}

function readProjectName() {
  try {
    return JSON.parse(fs.readFileSync(DEVTOOLS_CONFIG, 'utf8')).projectname || ''
  } catch {
    return ''
  }
}

/**
 * 构建后同步开发者工具设置，使导入产物目录即可直连 http 后端，免手动勾“不校验合法域名”。
 * 只改 setting.urlCheck，其余本地设置原样保留；任何异常都不应让构建表现为失败。
 */
function syncDevtoolsUrlCheck() {
  try {
    let current = ''
    try {
      current = fs.readFileSync(DEVTOOLS_PRIVATE_CONFIG, 'utf8')
    } catch {
      current = ''
    }
    const next = current ? setUrlCheck(current, false) : createPrivateConfig(readProjectName())
    if (!next) {
      console.log(`警告：${path.relative(REPO_ROOT, DEVTOOLS_PRIVATE_CONFIG)} 无法解析，已跳过域名校验设置。`)
      return false
    }
    if (next === current) return true
    fs.writeFileSync(DEVTOOLS_PRIVATE_CONFIG, next, 'utf8')
    return true
  } catch (error) {
    console.log(`警告：同步开发者工具设置失败（${error && error.message}），可手动勾选“不校验合法域名”。`)
    return false
  }
}

function runBuild() {
  // 直接调用 uni CLI 入口，避免依赖 npm.cmd（Windows 上 shell:false 无法 spawn .cmd）。
  const uniBin = path.join(MINIAPP_DIR, 'node_modules', '@dcloudio', 'vite-plugin-uni', 'bin', 'uni.js')
  const nodeDir = path.dirname(process.execPath)
  const pathKey = Object.keys(process.env).find((key) => key.toLowerCase() === 'path') || 'PATH'
  const env = { ...process.env, [pathKey]: `${nodeDir}${path.delimiter}${process.env[pathKey] || ''}` }

  const result = fs.existsSync(uniBin)
    ? spawnSync(process.execPath, [uniBin, 'build', '-p', 'mp-weixin'], { cwd: MINIAPP_DIR, stdio: 'inherit', env })
    : spawnSync(process.platform === 'win32' ? 'npm.cmd' : 'npm', ['run', 'build:mp-weixin'], {
        cwd: MINIAPP_DIR,
        stdio: 'inherit',
        shell: true,
        env,
      })

  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`构建失败，退出码 ${result.status}`)
  if (syncDevtoolsUrlCheck()) console.log('已同步开发者工具设置：urlCheck=false，无需手动勾选“不校验合法域名”。')
}

async function checkReachable(origin) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 4000)
  try {
    const response = await fetch(`${origin}/actuator/health`, { signal: controller.signal })
    const body = await response.text()
    return { ok: response.ok, status: response.status, body: body.slice(0, 200) }
  } catch (error) {
    return { ok: false, status: 0, body: error && error.name === 'AbortError' ? '请求超时' : String(error && error.message) }
  } finally {
    clearTimeout(timer)
  }
}

const BACKEND_CONTAINER = 'vehicle-auth-local-backend'

function defaultDockerRun(args) {
  const result = spawnSync('docker', args, { encoding: 'utf8' })
  if (result.error || typeof result.stdout !== 'string') return { ok: false, stdout: '' }
  if (result.status !== 0) return { ok: false, stdout: result.stdout }
  return { ok: true, stdout: result.stdout }
}

/**
 * 判断本机后端容器对外发布的是「所有网卡」还是「仅回环」。
 * 只读docker，不改动任何容器；docker 不可用时返回 unknown，不影响主流程。
 *
 * 返回 { state, detail }，state ∈ lan | loopback | absent | unknown。
 */
function inspectBackendPublish(port, run = defaultDockerRun) {
  const result = run(['ps', '--filter', `name=^/${BACKEND_CONTAINER}$`, '--format', '{{.Ports}}'])
  if (!result.ok) return { state: 'unknown', detail: 'docker 命令不可用或执行失败' }
  const line = String(result.stdout || '').trim()
  if (!line) return { state: 'absent', detail: `未发现运行中的容器 ${BACKEND_CONTAINER}` }
  const match = line.match(new RegExp(`(0\\.0\\.0\\.0|\\[::\\]|\\*|127\\.0\\.0\\.1):${port}->`))
  if (!match) return { state: 'unknown', detail: `容器在运行，但未见 ${port} 端口映射（${line}）` }
  return match[1] === '127.0.0.1'
    ? { state: 'loopback', detail: line }
    : { state: 'lan', detail: line }
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  if (options.help) {
    console.log(usage())
    return
  }

  if (options.restore) {
    const next = upsertEnvLine(readEnvLocal(), 'VITE_API_BASE_URL', LOOPBACK_ORIGIN)
    fs.writeFileSync(ENV_LOCAL, next, 'utf8')
    console.log(`已还原 ${path.relative(REPO_ROOT, ENV_LOCAL)} → ${LOOPBACK_ORIGIN}`)
    runBuild()
    console.log('已按回环地址重新构建，开发者工具可继续本地调试。')
    return
  }

  const candidates = rankLanCandidates(os.networkInterfaces())
  if (candidates.length) {
    console.log('候选局域网地址（越靠前越推荐）：')
    candidates.slice(0, 8).forEach((item, index) => {
      console.log(`  ${index === 0 ? '*' : ' '} ${item.address}  (网卡 ${item.name})`)
    })
  } else {
    console.log('未找到可用的局域网 IPv4 地址，请确认已连接 WiFi 或网线。')
  }

  const address = options.ip || (candidates[0] && candidates[0].address) || ''
  if (!address) {
    console.log('无法确定地址，可用 --ip 手动指定。')
    return
  }
  if (!isIPv4(address)) throw new Error(`地址不合法：${address}`)

  const origin = `http://${address}:${options.port}`
  console.log('')
  console.log(`选用地址：${origin}`)

  if (options.check) {
    const result = await checkReachable(origin)
    if (result.ok) {
      console.log(`连通性检查：通过（HTTP ${result.status}）${result.body ? ` ${result.body}` : ''}`)
      console.log('该地址从本机可达。若手机仍连不上，请检查手机与电脑是否同一网络、以及系统防火墙入站规则。')
    } else {
      console.log(`连通性检查：失败（HTTP ${result.status}）${result.body ? ` ${result.body}` : ''}`)
      const publish = inspectBackendPublish(options.port)
      if (publish.state === 'loopback') {
        console.log(`后端容器当前只发布在回环地址（${publish.detail}），手机一定连不上；`)
        console.log(`需按 docs/operations/LAN_DEVICE_LOGIN_RUNBOOK.md 用 -p 0.0.0.0:${options.port}:8080 重建容器。`)
      } else if (publish.state === 'absent') {
        console.log(`${publish.detail}，请先启动后端。`)
      } else {
        console.log('常见原因：后端端口只发布在回环地址（需用 -p 0.0.0.0:18080:8080 重新运行），或防火墙未放行。')
      }
      console.log('本机可达不代表手机可达；本机不可达时手机一定不可达。')
    }
  }

  if (!options.apply) {
    console.log('')
    console.log('如需写入并构建，请追加 --apply --build。')
    return
  }

  const next = upsertEnvLine(readEnvLocal(), 'VITE_API_BASE_URL', origin)
  fs.writeFileSync(ENV_LOCAL, next, 'utf8')
  console.log(`已写入 ${path.relative(REPO_ROOT, ENV_LOCAL)}（VITE_API_BASE_URL=${origin}）`)

  if (options.build) runBuild()

  console.log('')
  console.log('真机联调还需：')
  // 用「本机能否经局域网地址访问到后端」作为判据，比读 docker 端口映射更直接
  // （Windows 上 Node 直接 spawn docker 会 EBUSY，读不到端口信息）。
  const probe = await checkReachable(origin)
  const publish = probe.ok ? null : inspectBackendPublish(options.port)
  if (probe.ok) {
    console.log(`  1. 后端已就绪：${origin}/actuator/health 返回 HTTP ${probe.status}（本机可达）`)
  } else {
    console.log(`  1. 本机经该地址访问不到后端（HTTP ${probe.status}）${probe.body ? ` ${probe.body}` : ''}`)
    if (publish && publish.state === 'loopback') {
      console.log(`     容器只发布在回环地址（${publish.detail}），手机一定连不上`)
    } else if (publish && publish.state === 'absent') {
      console.log(`     ${publish.detail}`)
    }
    console.log(`     按 docs/operations/LAN_DEVICE_LOGIN_RUNBOOK.md 用 -p 0.0.0.0:${options.port}:8080 重建容器，或检查防火墙`)
  }
  console.log(`  2. 确认入站端口 ${options.port} 未被防火墙拦截（Docker Desktop 的入站规则需覆盖当前网络配置文件）`)
  console.log(`  3. 用手机浏览器打开 http://${address}:${options.port}/actuator/health 确认返回 UP`)
  console.log('     这一步不通过就别继续：先解决网络。校园网/公共 WiFi 常见客户端隔离，可改用电脑移动热点')
  console.log('  4. 开发者工具导入 apps/miniapp/dist/build/mp-weixin')
  console.log('     · 模拟器：点「编译」即可（构建已自动关闭域名校验，无需手动勾选）')
  console.log('     · 预览：手机扫码后，在右上角「…」里选择「打开调试」')
  console.log('     · 真机调试：手机与电脑须在同一局域网，该模式不校验域名')
  console.log(`  5. 此地址对开发者工具同样有效（工具在电脑上，访问 ${address} 正常），**无需切回回环**；`)
  console.log('     仅当电脑未连 WiFi、局域网地址不可用时，才需要 --restore')
  console.log('     （历史上这里是“用完执行 --restore”，会让人把地址切回 127.0.0.1，真机随即使失效）')
}

module.exports = {
  rankLanCandidates,
  upsertEnvLine,
  isUsableCandidate,
  segmentRank,
  adapterPenalty,
  inspectBackendPublish,
  setUrlCheck,
  createPrivateConfig,
  BACKEND_CONTAINER,
  DEFAULT_PORT,
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`执行失败：${error && error.message}`)
    process.exitCode = 1
  })
}
