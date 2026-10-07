import test from 'node:test'
import assert from 'node:assert/strict'
import { createAiChatApi, MAX_QUESTION_CHARS } from '../src/services/ai-chat.js'
import { ServiceError } from '../src/services/service-catalog.js'
import { createAiChatFlow, initialAiChatState } from '../src/services/ai-chat-flow.js'

const answer = (overrides = {}) => ({ reply: '建议先检查刹车片厚度。', model: 'deepseek-flash', grounded: true, vehicle_id: 7, ...overrides })
const groundedOk = (data = answer()) => ({ statusCode: 200, data: { code: 0, data } })
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

function stubRuntime(response) {
  const calls = []
  const runtime = { request: options => { calls.push(options); options.success(typeof response === 'function' ? response(options) : response) } }
  return { api: createAiChatApi({ baseUrl: 'http://test/', runtime: () => runtime }), calls }
}

test('chat validates the question, vehicle and history before requesting', () => {
  const { api, calls } = stubRuntime(groundedOk())
  assert.throws(() => api.chat('', { message: '你好' }), { kind: 'unauthorized' })
  assert.throws(() => api.chat('owner', { message: '   ' }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: 'x'.repeat(MAX_QUESTION_CHARS + 1) }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: '你好', vehicleId: 0 }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: '你好', history: {} }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: '你好', history: Array(9).fill({ role: 'user', content: 'a' }) }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: '你好', history: [{ role: 'system', content: 'a' }] }), { kind: 'invalid' })
  assert.throws(() => api.chat('owner', { message: '你好', history: [{ role: 'user', content: '' }] }), { kind: 'invalid' })
  assert.equal(calls.length, 0)
})

test('chat posts an authenticated body and returns a validated answer', async () => {
  const { api, calls } = stubRuntime(groundedOk())
  const result = await api.chat('owner-token', {
    message: '  刹车有异响  ',
    vehicleId: 7,
    history: [{ role: 'user', content: '你好' }, { role: 'assistant', content: '您好' }],
  })
  assert.equal(result.reply, '建议先检查刹车片厚度。')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, 'http://test/api/ai/chat')
  assert.equal(calls[0].method, 'POST')
  assert.equal(calls[0].header.Authorization, 'Bearer owner-token')
  assert.equal(calls[0].timeout, 60000)
  assert.deepEqual(calls[0].data, {
    message: '刹车有异响', vehicle_id: 7,
    history: [{ role: 'user', content: '你好' }, { role: 'assistant', content: '您好' }],
  })
  assert.deepEqual(Object.keys(await api.chat('owner-token', { message: '通用问题' })), ['reply', 'model', 'grounded', 'vehicle_id'])
})

test('chat omits vehicle_id when no car is selected and rejects protocol violations', async () => {
  let body
  const { api } = stubRuntime(options => { body = options.data; return groundedOk({ reply: '通用建议', model: 'deepseek-flash', grounded: false, vehicle_id: null }) })
  assert.deepEqual(await api.chat('owner', { message: '多久换机油' }), { reply: '通用建议', model: 'deepseek-flash', grounded: false, vehicle_id: null })
  assert.equal('vehicle_id' in body, false)

  const invalid = [
    { reply: '', model: 'deepseek-flash', grounded: false, vehicle_id: null },
    { reply: 'x'.repeat(8001), model: 'deepseek-flash', grounded: false, vehicle_id: null },
    { reply: 'ok', model: '', grounded: false, vehicle_id: null },
    { reply: 'ok', model: 'deepseek-flash', grounded: 'no', vehicle_id: null },
    { reply: 'ok', model: 'deepseek-flash', grounded: true, vehicle_id: null },
    { reply: 'ok', model: 'deepseek-flash', grounded: false, vehicle_id: 7 },
    { reply: 'ok', model: 'deepseek-flash', grounded: true, vehicle_id: 0 },
  ]
  for (const data of invalid) {
    const stub = stubRuntime(groundedOk(data)).api
    await assert.rejects(stub.chat('owner', { message: '你好' }), { kind: 'protocol' })
  }
})

test('chat maps failures to safe kinds without leaking server text', async () => {
  const kinds = [[400, 'invalid'], [401, 'unauthorized'], [403, 'forbidden'], [404, 'missing'], [429, 'limited'], [503, 'unavailable'], [500, 'server']]
  for (const [status, kind] of kinds) {
    const { api } = stubRuntime({ statusCode: status, data: { code: 50301, message: 'AI 管家尚未配置' } })
    await assert.rejects(api.chat('owner', { message: '你好' }), { kind })
  }
  const { api } = stubRuntime({ statusCode: 200, data: { code: 50301, message: 'AI 管家尚未配置' } })
  await assert.rejects(api.chat('owner', { message: '你好' }), { kind: 'protocol' })
})

test('flow keeps the question, retries the same turn and never duplicates it', async () => {
  const state = initialAiChatState()
  let fail = true
  const api = { chat: async (_token, { message }) => { if (fail) throw new ServiceError('unavailable', 'AI 管家暂时不可用'); return answer({ reply: `回答：${message}` }) } }
  const flow = createAiChatFlow({ state, api, token: () => 'owner', vehicleId: () => 7 })
  await flow.send('刹车有异响')
  assert.equal(state.messages.length, 1)
  assert.equal(state.messages[0].role, 'user')
  assert.equal(state.failureKind, 'unavailable')
  assert.equal(state.retryable, true)
  assert.equal(state.busy, false)
  fail = false
  await flow.retry()
  assert.deepEqual(state.messages.map(item => item.role), ['user', 'assistant'])
  assert.equal(state.messages[1].content, '回答：刹车有异响')
  assert.equal(state.failureKind, '')
  assert.equal(state.retryable, false)
  await flow.retry()
  assert.equal(state.messages.length, 2)
})

test('flow blocks invalid input, concurrent sends and marks unauthenticated as final', async () => {
  const state = initialAiChatState()
  let calls = 0
  const pending = deferred()
  const flow = createAiChatFlow({ state, api: { chat: () => { calls++; return pending.promise } }, token: () => 'owner' })
  await flow.send('   ')
  assert.equal(state.failureKind, 'invalid'); assert.equal(state.messages.length, 0); assert.equal(calls, 0)
  const first = flow.send('第一个问题')
  await flow.send('第二个问题')
  assert.equal(calls, 1)
  assert.deepEqual(state.messages.map(item => item.content), ['第一个问题'])
  pending.resolve(answer()); await first
  assert.equal(state.messages.length, 2)

  const anonymous = { ...initialAiChatState() }
  const guest = createAiChatFlow({ state: anonymous, api: { chat: async () => { throw new ServiceError('unauthorized', '请先登录车主账号') } }, token: () => '' })
  await guest.send('刹车有异响')
  assert.equal(anonymous.failureKind, 'unauthorized'); assert.equal(anonymous.retryable, false)
})

test('flow sends capped history and drops stale responses after reset or identity change', async () => {
  const state = initialAiChatState()
  const payloads = []
  let owner = 'first'
  const pending = deferred()
  const flow = createAiChatFlow({ state, api: { chat: (token, payload) => { payloads.push({ token, payload }); return pending.promise } }, token: () => owner })
  for (let i = 1; i <= 10; i++) state.messages.push({ role: i % 2 ? 'user' : 'assistant', content: `第${i}条` })
  const loading = flow.send('最新问题')
  assert.equal(payloads[0].payload.history.length, 8)
  assert.deepEqual(payloads[0].payload.history.map(item => item.content), ['第3条', '第4条', '第5条', '第6条', '第7条', '第8条', '第9条', '第10条'])
  owner = 'second'
  flow.reset()
  pending.resolve(answer())
  await loading
  assert.equal(state.messages.length, 0)
  assert.equal(state.loaded, false)
})

test('flow drops a late answer after suspend', async () => {
  const state = initialAiChatState()
  const pending = deferred()
  const flow = createAiChatFlow({ state, api: { chat: () => pending.promise }, token: () => 'owner' })
  const loading = flow.send('刹车有异响')
  flow.suspend()
  pending.resolve(answer())
  await loading
  assert.equal(state.messages.filter(item => item.role === 'assistant').length, 0)
  assert.equal(state.busy, false)
})
