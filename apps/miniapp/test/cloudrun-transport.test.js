import test from 'node:test'
import assert from 'node:assert/strict'
import { createApiRuntime } from '../src/services/api-runtime.js'
import { createAuthApi } from '../src/services/wechat-auth.js'
import { CLOUD_RUN_ORIGIN, apiOrigin, cloudRunEnabled } from '../src/services/api-config.js'

const ok = (data) => ({ statusCode: 200, data: { code: 0, data } })
const noCloud = () => { throw new Error('uni.request must not be used in cloud-run mode') }

test('without cloud-run configuration the transport passes through to uni.request', () => {
  const seen = []
  const runtime = { request: (options) => { seen.push(options); options.success(ok({})) } }
  const api = createApiRuntime({ origin: 'https://api.test/', cloud: { env: '', service: '' }, runtime: () => runtime })
  api.request({ url: 'https://api.test/api/vehicle/list?page=1', method: 'GET', success() {}, fail() {} })
  assert.equal(seen.length, 1)
  assert.equal(seen[0].url, 'https://api.test/api/vehicle/list?page=1')
})

test('cloud-run transport keeps a logical origin for service modules only', () => {
  // 哨兵只在云托管模式下作为逻辑原点，供各 service 拼接路径与通过未配置校验。
  assert.match(CLOUD_RUN_ORIGIN, /^https:\/\/cloudrun\.invalid$/)
  assert.equal(apiOrigin, cloudRunEnabled ? CLOUD_RUN_ORIGIN : apiOrigin)
})

test('cloud-run transport strips the sentinel origin and sends X-WX-SERVICE', () => {
  const initialised = []
  const calls = []
  const cloudApi = { init: (options) => initialised.push(options), callContainer: (options) => calls.push(options) }
  const api = createApiRuntime({ origin: CLOUD_RUN_ORIGIN, cloud: { env: 'env-1', service: 'svc-a' },
    runtime: noCloud, cloudApi })
  api.request({ url: `${CLOUD_RUN_ORIGIN}/api/vehicle/list?page=2`, method: 'GET', timeout: 15000,
    header: { Authorization: 'Bearer offline' }, success() {}, fail() {} })
  api.request({ url: `${CLOUD_RUN_ORIGIN}/api/auth/cloud-login`, method: 'POST', data: { role: 'owner' },
    header: {}, success() {}, fail() {} })
  assert.equal(calls[0].path, '/api/vehicle/list?page=2')
  assert.equal(calls[0].config.env, 'env-1')
  assert.equal(calls[0].header['X-WX-SERVICE'], 'svc-a')
  assert.equal(calls[0].header.Authorization, 'Bearer offline')
  assert.equal(calls[0].data, undefined)
  assert.equal(calls[1].path, '/api/auth/cloud-login')
  assert.equal(calls[1].method, 'POST')
  assert.equal(calls[1].header['content-type'], 'application/json')
  assert.deepEqual(calls[1].data, { role: 'owner' })
  // 云环境只初始化一次，即使连续调用。
  assert.equal(initialised.length, 1)
  assert.equal(initialised[0].env, 'env-1')
})

test('an explicit content type is not overwritten and cloud failures stay typed', () => {
  const calls = []
  const failures = []
  const cloudApi = { init() {}, callContainer: (options) => calls.push(options) }
  const api = createApiRuntime({ origin: CLOUD_RUN_ORIGIN, cloud: { env: 'e', service: 's' },
    runtime: noCloud, cloudApi })
  api.request({ url: `${CLOUD_RUN_ORIGIN}/api/upload`, method: 'POST', data: {},
    header: { 'content-type': 'application/octet-stream' }, success() {}, fail() {} })
  assert.equal(calls[0].header['content-type'], 'application/octet-stream')
  // 运行时拿不到云能力时以 fail 回调报错，调用方无需分支处理。
  const broken = createApiRuntime({ origin: CLOUD_RUN_ORIGIN, cloud: { env: 'e', service: 's' },
    runtime: noCloud, cloudApi: null })
  broken.request({ url: `${CLOUD_RUN_ORIGIN}/x`, success() {}, fail: (error) => failures.push(error) })
  assert.equal(failures.length, 1)
  assert.match(failures[0].errMsg, /不支持云托管调用/)
})

function harness({ cloud, reply = ok({ access_token: 'offline-access', refresh_token: 'offline-refresh',
  user: { role: 'owner', phone_bound: false } }) }) {
  const requests = []
  let loginCount = 0
  let next = reply
  const runtime = {
    login(options) { loginCount++; options.success({ code: 'offline-code' }) },
    request(options) { requests.push(options); options.success(next) },
  }
  return { api: createAuthApi({ baseUrl: CLOUD_RUN_ORIGIN, runtime: () => runtime, cloud }),
    requests, reply(value) { next = value }, get loginCount() { return loginCount } }
}

test('cloud-run login never calls wx.login and posts only the role', async () => {
  const h = harness({ cloud: true })
  const result = await h.api.requestWechatLogin('owner')
  assert.equal(h.loginCount, 0)
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].url, `${CLOUD_RUN_ORIGIN}/api/auth/cloud-login`)
  assert.deepEqual(h.requests[0].data, { role: 'owner' })
  assert.deepEqual(h.requests[0].header, {})
  assert.equal(result.access_token, 'offline-access')
})

test('cloud-run login still enforces role and technician binding contract', async () => {
  const h = harness({ cloud: true })
  await assert.rejects(h.api.requestWechatLogin('merchant'), { kind: 'invalid' })
  assert.equal(h.requests.length, 0)
  h.reply(ok({ status: 'BIND_REQUIRED', binding_token: 'offline-binding' }))
  assert.equal((await h.api.requestWechatLogin('technician')).status, 'BIND_REQUIRED')
  h.reply(ok({ status: 'BIND_REQUIRED' }))
  await assert.rejects(h.api.requestWechatLogin('technician'), { kind: 'protocol' })
  h.reply(ok({ status: 'BIND_REQUIRED', binding_token: 'offline-binding' }))
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'protocol' })
})

test('non cloud-run builds keep the wx.login code exchange path unchanged', async () => {
  const h = harness({ cloud: false })
  await h.api.requestWechatLogin('owner')
  assert.equal(h.loginCount, 1)
  assert.equal(h.requests[0].url, `${CLOUD_RUN_ORIGIN}/api/auth/wx-login`)
  assert.deepEqual(h.requests[0].data, { code: 'offline-code', role: 'owner' })
})

test('refresh rotates through the previously unused endpoint and validates the pair', async () => {
  const h = harness({ cloud: true })
  await assert.rejects(h.api.refreshSession(''), { kind: 'unauthorized' })
  assert.equal(h.requests.length, 0)
  h.reply(ok({ access_token: 'next-access', refresh_token: 'next-refresh' }))
  const rotated = await h.api.refreshSession('offline-refresh')
  assert.equal(h.requests[0].url, `${CLOUD_RUN_ORIGIN}/api/auth/refresh`)
  assert.deepEqual(h.requests[0].data, { refresh_token: 'offline-refresh' })
  assert.equal(h.requests[0].header.Authorization, undefined)
  assert.equal(rotated.refresh_token, 'next-refresh')
  // 只回一个凭证时视为协议错误，避免半个会话被当作成功。
  h.reply(ok({ access_token: 'next-access' }))
  await assert.rejects(h.api.refreshSession('offline-refresh'), { kind: 'protocol' })
})
