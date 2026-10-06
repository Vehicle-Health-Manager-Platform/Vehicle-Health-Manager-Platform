import test from 'node:test'
import assert from 'node:assert/strict'
import { AuthError, createAuthApi } from '../src/services/wechat-auth.js'
import { useRoleIdentity } from '../src/services/role-identity.js'
import { ownerSession, clearOwnerSession, setOwnerSession } from '../src/services/owner-session.js'
import { clearMerchantSession } from '../src/services/merchant-session.js'
test.beforeEach(clearMerchantSession)

const session = (role) => ({ access_token: 'offline-access', user: { role, phone_bound: false } })
const response = (data) => ({ statusCode: 200, data: { code: 0, data } })
function harness(baseUrl = 'https://offline.invalid/') {
  const requests = []
  let next = response(session('owner'))
  let loginCount = 0
  let loginResult = { code: 'offline-code' }
  const runtime = {
    login(options) {
      loginCount++
      if (loginResult.errMsg) options.fail(loginResult)
      else options.success(loginResult)
    },
    request(options) {
      requests.push(options)
      if (next.errMsg) options.fail(next)
      else options.success(next)
    },
  }
  return { api: createAuthApi({ baseUrl, runtime: () => runtime }), requests,
    reply(value) { next = value }, loginReply(value) { loginResult = value },
    get loginCount() { return loginCount } }
}

test('unconfigured service rejects before WeChat login or HTTP request', async () => {
  const h = harness('')
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'unconfigured' })
  await assert.rejects(h.api.requestMerchantCode('account', 'password'), { kind: 'unconfigured' })
  assert.equal(h.loginCount, 0)
  assert.equal(h.requests.length, 0)
})

test('public login posts WeChat code and role without business authorization', async () => {
  const h = harness()
  await h.api.requestWechatLogin('owner')
  assert.equal(h.requests[0].url, 'https://offline.invalid/api/auth/wx-login')
  assert.equal(h.requests[0].timeout, 15000)
  assert.deepEqual(h.requests[0].data, { code: 'offline-code', role: 'owner' })
  assert.deepEqual(h.requests[0].header, {})
})

test('WeChat cancellation, missing code and unsupported role never submit HTTP', async () => {
  const h = harness()
  h.loginReply({ errMsg: 'login:fail' })
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'wechat' })
  h.loginReply({})
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'wechat' })
  await assert.rejects(h.api.requestWechatLogin('merchant'), { kind: 'invalid' })
  assert.equal(h.requests.length, 0)
})

test('wrong role, missing token, non-success envelope and null payload never authorize', async () => {
  const h = harness()
  for (const data of [session('merchant'), { user: { role: 'owner' } }, null]) {
    h.reply(response(data))
    await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'protocol' })
  }
  h.reply({ statusCode: 200, data: { code: 40100, data: session('owner') } })
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'protocol' })
})

test('only technician may receive a binding-only login response', async () => {
  const h = harness()
  h.reply(response({ status: 'BIND_REQUIRED', binding_token: 'offline-binding' }))
  assert.equal((await h.api.requestWechatLogin('technician')).status, 'BIND_REQUIRED')
  await assert.rejects(h.api.requestWechatLogin('owner'), { kind: 'protocol' })
  h.reply(response({ status: 'BIND_REQUIRED' }))
  await assert.rejects(h.api.requestWechatLogin('technician'), { kind: 'protocol' })
})

test('HTTP failures have typed safe messages and do not echo response secrets', async () => {
  const h = harness()
  for (const [status, kind] of [[401, 'unauthorized'], [403, 'forbidden'], [429, 'rate-limited'],
    [503, 'unavailable'], [400, 'invalid'], [409, 'invalid'], [500, 'server']]) {
    h.reply({ statusCode: status, data: { message: 'private-password-123456' } })
    await assert.rejects(h.api.requestMerchantCode('account', 'password'), (error) =>
      error.kind === kind && error.status === status && !error.message.includes('private-password'))
  }
  h.reply({ statusCode: 503, data: { message: '短信服务尚未配置' } })
  await assert.rejects(h.api.requestMerchantCode('account', 'password'), /短信服务尚未开通/)
})

test('network failure and timeout have distinct retry guidance', async () => {
  const h = harness()
  h.reply({ errMsg: 'request:fail timeout' })
  await assert.rejects(h.api.requestMerchantCode('account', 'password'), { kind: 'timeout' })
  h.reply({ errMsg: 'request:fail network' })
  await assert.rejects(h.api.requestMerchantCode('account', 'password'), { kind: 'network' })
})

test('protected operations require appropriate token and validate results', async () => {
  const h = harness()
  await assert.rejects(h.api.logoutWechat(), { kind: 'unauthorized' })
  await assert.rejects(h.api.bindTechnician(undefined, 'employee-code'), { kind: 'unauthorized' })
  await assert.rejects(h.api.bindOwnerPhone(undefined, 'phone-code'), { kind: 'unauthorized' })
  assert.equal(h.requests.length, 0)
  h.reply(response(session('technician')))
  await h.api.bindTechnician('binding-token', ' employee-code ')
  assert.deepEqual(h.requests[0].header, { Authorization: 'Bearer binding-token' })
  assert.deepEqual(h.requests[0].data, { employee_code: 'employee-code' })
  h.reply(response({ phone_bound: true }))
  await h.api.bindOwnerPhone('owner-token', 'phone-code')
  assert.deepEqual(h.requests[1].header, { Authorization: 'Bearer owner-token' })
  h.reply(response({}))
  await assert.rejects(h.api.logoutWechat('owner-token'), { kind: 'protocol' })
  h.reply(response({ revoked: true }))
  await h.api.logoutWechat('owner-token')
  assert.equal(h.requests[3].data, undefined)
})

test('duplicate submit is suppressed while loading, failure releases loading for retry', async () => {
  let rejectRequest
  let calls = 0
  const api = { requestMerchantCode: () => { calls++; return new Promise((_, reject) => { rejectRequest = reject }) } }
  const page = useRoleIdentity('merchant', api)
  page.merchantAccount.value = 'account'
  page.merchantPassword.value = 'password'
  const pending = page.tryMerchantCode()
  assert.equal(page.busy.value, true)
  assert.equal(page.phase.value, 'loading')
  await page.tryMerchantLogin()
  await page.tryMerchantCode()
  await page.retry()
  assert.equal(calls, 1)
  rejectRequest(new AuthError('network', '无法连接服务'))
  await pending
  assert.equal(page.busy.value, false)
  assert.equal(page.phase.value, 'error')
  assert.equal(page.canRetry.value, true)
  api.requestMerchantCode = async () => { calls++; return { sent: true } }
  await page.retry()
  assert.equal(calls, 2)
  assert.equal(page.phase.value, 'success')
})

test('merchant invalid inputs never reach API, success clears sensitive inputs', async () => {
  let calls = 0
  const page = useRoleIdentity('merchant', { requestMerchantLogin: async () => { calls++; return session('merchant') } })
  await page.tryMerchantLogin()
  page.merchantAccount.value = 'account'
  page.merchantPassword.value = 'password'
  for (const code of ['123', 'abcdef', '12e456']) {
    page.smsCode.value = code
    await page.tryMerchantLogin()
  }
  assert.equal(calls, 0)
  page.smsCode.value = '123456'
  await page.tryMerchantLogin()
  assert.equal(calls, 1)
  assert.equal(page.accessToken.value, 'offline-access')
  assert.equal(page.merchantPassword.value, '')
  assert.equal(page.smsCode.value, '')
})

test('SMS unavailable and rejected credentials do not report sent or create a session', async () => {
  const h = harness()
  const page = useRoleIdentity('merchant', h.api)
  page.merchantAccount.value = 'account'
  page.merchantPassword.value = 'password'
  page.smsCode.value = '123456'
  h.reply({ statusCode: 503, data: { message: '短信服务尚未配置' } })
  await page.tryMerchantCode()
  assert.equal(page.phase.value, 'error')
  assert.match(page.message.value, /尚未开通/)
  assert.equal(page.accessToken.value, '')
  h.reply({ statusCode: 401, data: {} })
  await page.tryMerchantLogin()
  assert.equal(page.canRetry.value, false)
  assert.equal(page.accessToken.value, '')
  assert.equal(page.merchantPassword.value, 'password')
})

test('owner session is shared across pages; failed logout retains it, successful retry clears it', async () => {
  clearOwnerSession()
  const h = harness()
  const first = useRoleIdentity('owner', h.api)
  const second = useRoleIdentity('owner', h.api)
  await first.tryLogin()
  assert.equal(second.accessToken.value, 'offline-access')
  h.reply({ errMsg: 'request:fail' })
  await first.tryLogout()
  assert.equal(ownerSession.accessToken, 'offline-access')
  h.reply(response({ revoked: true }))
  await first.retry()
  assert.equal(ownerSession.accessToken, '')
  assert.equal(second.accessToken.value, '')
})

test('phone cancellation never submits; failure needs a new code and success updates shared status', async () => {
  setOwnerSession(session('owner'))
  const h = harness()
  const page = useRoleIdentity('owner', h.api)
  await page.tryBindPhone({ detail: {} })
  assert.equal(h.requests.length, 0)
  h.reply({ errMsg: 'request:fail' })
  await page.tryBindPhone({ detail: { code: 'first-code' } })
  assert.equal(page.canRetry.value, false)
  h.reply(response({ phone_bound: true }))
  await page.tryBindPhone({ detail: { code: 'fresh-code' } })
  assert.equal(page.phoneBound.value, true)
  assert.equal(h.requests[1].data.code, 'fresh-code')
  clearOwnerSession()
})

test('rejected protected session retains state until explicit re-login recovery', async () => {
  setOwnerSession(session('owner'))
  const h = harness()
  const page = useRoleIdentity('owner', h.api)
  h.reply({ statusCode: 401, data: {} })
  await page.tryLogout()
  assert.equal(page.accessToken.value, 'offline-access')
  assert.equal(page.requiresLogin.value, true)
  page.restartLogin()
  assert.equal(ownerSession.accessToken, '')
  assert.equal(page.requiresLogin.value, false)
  assert.equal(page.phase.value, 'idle')
})

test('unexpected runtime errors do not display sensitive exception text', async () => {
  clearOwnerSession()
  const page = useRoleIdentity('owner', { requestWechatLogin: async () => { throw new Error('secret-password') } })
  await page.tryLogin()
  assert.equal(page.phase.value, 'error')
  assert.doesNotMatch(page.message.value, /secret-password/)
})

test('technician binding rejects permission then requires new login; success clears code and binding token', async () => {
  const h = harness()
  const page = useRoleIdentity('technician', h.api)
  h.reply(response({ status: 'BIND_REQUIRED', binding_token: 'binding-token' }))
  await page.tryLogin()
  page.employeeCode.value = 'employee-code'
  h.reply({ statusCode: 403, data: {} })
  await page.tryBind()
  assert.equal(page.bindingToken.value, '')
  assert.equal(page.accessToken.value, '')
  assert.match(page.message.value, /重新微信登录/)
  h.reply(response({ status: 'BIND_REQUIRED', binding_token: 'new-binding-token' }))
  await page.tryLogin()
  page.employeeCode.value = 'employee-code'
  h.reply(response(session('technician')))
  await page.tryBind()
  assert.equal(page.accessToken.value, 'offline-access')
  assert.equal(page.employeeCode.value, '')
  assert.equal(page.bindingToken.value, '')
})
