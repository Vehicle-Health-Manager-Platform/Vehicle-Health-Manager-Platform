'use strict'

/**
 * 离线单元测试：node --test scripts/test_miniapp_lan_helper.cjs
 * 覆盖地址挑选与 .env.local 改写规则，不触碰网络与真实文件。
 */

const test = require('node:test')
const assert = require('node:assert/strict')

const {
  rankLanCandidates,
  upsertEnvLine,
  isUsableCandidate,
  inspectBackendPublish,
  DEFAULT_PORT,
} = require('./miniapp_lan_helper.cjs')

// 用假的 docker 输出来测端口发布判定，不依赖本机是否装了 Docker。
const fakeDocker = (stdout, ok = true) => () => ({ ok, stdout })

test('默认端口为 18080', () => {
  assert.equal(DEFAULT_PORT, '18080')
})

test('候选地址排除回环、链路本地与广播形态', () => {
  assert.equal(isUsableCandidate('127.0.0.1'), false)
  assert.equal(isUsableCandidate('169.254.10.20'), false)
  assert.equal(isUsableCandidate('192.168.1.255'), false)
  assert.equal(isUsableCandidate('10.66.1.251'), true)
  assert.equal(isUsableCandidate('not-an-ip'), false)
})

test('优先物理网卡的局域网地址，虚拟网卡靠后', () => {
  const interfaces = {
    'vEthernet (WSL (Hyper-V firewall))': [{ family: 'IPv4', address: '172.21.224.1', internal: false }],
    WLAN: [{ family: 'IPv4', address: '10.66.1.251', internal: false }],
    'Loopback Pseudo-Interface 1': [{ family: 'IPv4', address: '127.0.0.1', internal: true }],
  }
  const ranked = rankLanCandidates(interfaces)
  assert.deepEqual(ranked.map((item) => item.address), ['10.66.1.251', '172.21.224.1'])
})

test('同为物理网卡时 192.168 网段排在 10 网段之前', () => {
  const interfaces = {
    以太网: [{ family: 'IPv4', address: '10.0.0.9', internal: false }],
    WLAN: [{ family: 'IPv4', address: '192.168.137.1', internal: false }],
  }
  const ranked = rankLanCandidates(interfaces)
  assert.equal(ranked[0].address, '192.168.137.1')
})

test('IPv6 与非 IPv4 条目被忽略', () => {
  const interfaces = {
    WLAN: [
      { family: 'IPv6', address: 'fe80::1', internal: false },
      { family: 4, address: '192.168.1.20', internal: false },
    ],
  }
  const ranked = rankLanCandidates(interfaces)
  assert.deepEqual(ranked.map((item) => item.address), ['192.168.1.20'])
})

test('改写 VITE_API_BASE_URL 时保留其它行与注释', () => {
  const before = [
    '# 云托管变量保持不动',
    'VITE_API_BASE_URL=http://127.0.0.1:18080',
    'VITE_WECHAT_CLOUD_ENV_ID=env-demo',
    '',
  ].join('\n')
  const after = upsertEnvLine(before, 'VITE_API_BASE_URL', 'http://10.66.1.251:18080')
  assert.match(after, /VITE_API_BASE_URL=http:\/\/10\.66\.1\.251:18080/)
  assert.match(after, /VITE_WECHAT_CLOUD_ENV_ID=env-demo/)
  assert.match(after, /# 云托管变量保持不动/)
  assert.equal(after.includes('127.0.0.1'), false)
})

test('键不存在时追加到末尾', () => {
  const after = upsertEnvLine('VITE_WECHAT_CLOUD_ENV_ID=env-demo\n', 'VITE_API_BASE_URL', 'http://10.0.0.2:18080')
  assert.match(after, /VITE_WECHAT_CLOUD_ENV_ID=env-demo/)
  assert.match(after, /VITE_API_BASE_URL=http:\/\/10\.0\.0\.2:18080/)
})

test('空文件也能生成合法配置', () => {
  const after = upsertEnvLine('', 'VITE_API_BASE_URL', 'http://192.168.1.5:18080')
  assert.equal(after.trim(), 'VITE_API_BASE_URL=http://192.168.1.5:18080')
})

test('重复执行结果稳定（幂等）', () => {
  const once = upsertEnvLine('VITE_API_BASE_URL=http://127.0.0.1:18080\n', 'VITE_API_BASE_URL', 'http://10.66.1.251:18080')
  const twice = upsertEnvLine(once, 'VITE_API_BASE_URL', 'http://10.66.1.251:18080')
  assert.equal(twice, once)
})

test('发布到所有网卡时判定为 lan', () => {
  const result = inspectBackendPublish('18080', fakeDocker('0.0.0.0:18080->8080/tcp\n'))
  assert.equal(result.state, 'lan')
})

test('IPv6 全接口发布同样判定为 lan', () => {
  const result = inspectBackendPublish('18080', fakeDocker('[::]:18080->8080/tcp\n'))
  assert.equal(result.state, 'lan')
})

test('只发布在回环时判定为 loopback 并保留原始端口串', () => {
  const result = inspectBackendPublish('18080', fakeDocker('127.0.0.1:18080->8080/tcp\n'))
  assert.equal(result.state, 'loopback')
  assert.match(result.detail, /127\.0\.0\.1:18080->8080/)
})

test('容器未运行时判定为 absent', () => {
  const result = inspectBackendPublish('18080', fakeDocker('\n'))
  assert.equal(result.state, 'absent')
})

test('docker 不可用时判定为 unknown 且不抛错', () => {
  const result = inspectBackendPublish('18080', fakeDocker('', false))
  assert.equal(result.state, 'unknown')
})

test('端口映射存在但端口不匹配时判定为 unknown', () => {
  const result = inspectBackendPublish('18080', fakeDocker('0.0.0.0:9000->9000/tcp\n'))
  assert.equal(result.state, 'unknown')
})
