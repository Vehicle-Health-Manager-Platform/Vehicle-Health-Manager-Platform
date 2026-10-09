import test from 'node:test'
import assert from 'node:assert/strict'
import { createRedemptionApi, redemptionBody, validRedemption, createRedemptionFlow, initialRedemptionState, RedemptionError } from '../src/services/order-redemption.js'
const key = '01234567-89ab-4cde-8fab-0123456789ab'
const result = () => ({ order_id: 1, order_status: 'COMPLETED', redeemed_at: '2026-10-09T01:00:00Z', test_mode: true, changed: true })
test('六位码必须为字符串，拒绝全角、空白、数字型和错误订单', () => {
  assert.deepEqual(redemptionBody(1, '001234'), { order_id: 1, code: '001234' })
  for (const code of ['12345', '１２３４５６', '123456 ', 123456, null]) assert.throws(() => redemptionBody(1, code))
  for (const id of [0, 1.5, '1']) assert.throws(() => redemptionBody(id, '123456'))
})
test('响应拒绝串单、码泄露、身份字段与伪完成状态', () => {
  assert.equal(validRedemption(result(), 1), true)
  for (const value of [{ ...result(), order_id: 2 }, { ...result(), code: '123456' }, { ...result(), staff_id: 1 }, { ...result(), order_status: 'PENDING_VERIFY' }, { ...result(), test_mode: 'true' }, { ...result(), redeemed_at: null }]) assert.equal(validRedemption(value, 1), false)
})
test('核销请求仅发送码，保留独立商家身份和原 UUID', async () => {
  let call; const api = createRedemptionApi({ baseUrl: 'http://local/', runtime: () => ({ request: o => { call = o; o.success({ statusCode: 200, data: { code: 0, data: result() } }) } }) })
  await api.redeem('merchant', 1, '123456', key); assert.equal(call.url, 'http://local/api/merchant/orders/1/redeem'); assert.deepEqual(call.data, { code: '123456' }); assert.equal(call.header.Authorization, 'Bearer merchant'); assert.equal(call.header['Idempotency-Key'], key)
  assert.throws(() => api.redeem('merchant', 1, '123456', '')); assert.throws(() => api.redeem('', 1, '123456', key))
})
test('本人和本店查询区分路径，历史无记录不推定已核销', async () => {
  let call; const api = createRedemptionApi({ baseUrl: 'http://local', runtime: () => ({ request: o => { call = o; o.success({ statusCode: 200, data: { code: 0, data: { order_id: 1, redemption: null } } }) } }) })
  assert.equal((await api.detail('owner', 'owner', 1)).redemption, null); assert.equal(call.url, 'http://local/api/order/1/redemption'); await api.detail('shop', 'merchant', 1); assert.equal(call.url, 'http://local/api/merchant/orders/1/redemption'); assert.throws(() => api.detail('tech', 'tech', 1)); await assert.rejects(api.detail('owner', 'owner', 2), e => e.kind === 'protocol')
})
test('拒绝反射后端错误，付款与争议给出明确指引', async () => {
  let code = 43009; const api = createRedemptionApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.success({ statusCode: 409, data: { code, message: 'secret SQL/code' } }) }) })
  await assert.rejects(api.redeem('shop', 1, '123456', key), e => e.kind === 'conflict' && e.message.includes('付款') && !e.message.includes('SQL')); code = 43007; await assert.rejects(api.redeem('shop', 1, '123456', key), e => e.message.includes('争议'))
})
test('429 读取大小写 Retry-After，并限制不可信等待值', async () => {
  let header = { 'retry-after': '37' }; const api = createRedemptionApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.success({ statusCode: 429, data: { code: 42900 }, header }) }) })
  await assert.rejects(api.redeem('shop', 1, '123456', key), e => e.retryAfter === 37); header = { 'Retry-After': '-1' }; await assert.rejects(api.redeem('shop', 1, '123456', key), e => e.retryAfter === 600)
})
function fixture(options = {}) {
  let actor = 'shop', id = 1, count = 0, time = 1000; const calls = [], state = initialRedemptionState(); state.code = '123456'
  const api = { redeem: async (...args) => { calls.push(args); return result() }, ...options.api }
  const flow = createRedemptionFlow({ state, token: () => actor, order: () => id, api, newKey: () => `key-${++count}`, confirm: options.confirm || (async () => true), now: () => time, onChanged: options.onChanged })
  flow.resume(); return { flow, state, calls, actor: n => { actor = n }, order: n => { id = n }, time: n => { time = n } }
}
test('未确认不会提交，成功清除码并展示测试核销', async () => {
  const cancel = fixture({ confirm: async () => false }); await cancel.flow.submit(); assert.equal(cancel.calls.length, 0)
  const f = fixture(); await f.flow.submit(); assert.equal(f.state.code, ''); assert.match(f.state.message, /未真实扣款/); await f.flow.submit(); assert.equal(f.calls.length, 1)
})
test('确认弹窗期间切账号、换订单或隐藏后不提交旧码', async () => {
  for (const change of [f => f.actor('other'), f => f.order(2), f => { f.flow.suspend(); f.flow.resume() }]) {
    let resolve; const f = fixture({ confirm: () => new Promise(r => { resolve = r }) }); const pending = f.flow.submit(); change(f); resolve(true); await pending; assert.equal(f.calls.length, 0)
  }
})
test('断网或503重试使用原码原键，改码才换键', async () => {
  const calls = []; const f = fixture({ api: { redeem: async (...args) => { calls.push(args); throw new RedemptionError('unavailable', '重试') } } }); await f.flow.submit(); await f.flow.submit(); assert.deepEqual(calls[0], calls[1]); f.state.code = '654321'; await f.flow.submit(); assert.notEqual(calls[1][3], calls[2][3])
})
test('隐藏或身份变化后的成功回包丢弃且页面不留敏感码', async () => {
  let resolve; const f = fixture({ api: { redeem: () => new Promise(r => { resolve = r }) } }); const pending = f.flow.submit(); await Promise.resolve(); f.flow.suspend(); f.flow.resume(); resolve(result()); await pending; assert.equal(f.state.saved, null); assert.equal(f.state.code, '')
})
test('共享限额响应到期前不再次请求，到期后可用原请求重试', async () => {
  let calls = 0; const f = fixture({ api: { redeem: async () => { calls++; throw new RedemptionError('rateLimited', '等待', 42900, 37) } } }); await f.flow.submit(); assert.equal(f.state.waitUntil, 38000); await f.flow.submit(); assert.equal(calls, 1); f.time(38000); await f.flow.submit(); assert.equal(calls, 2)
})
test('前置冲突刷新订单并清除旧码，恢复后须重新输入', async () => {
  let refresh = 0; const f = fixture({ api: { redeem: async () => { throw new RedemptionError('conflict', '付款异常', 43009) } }, onChanged: async () => { refresh++ } }); await f.flow.submit(); assert.equal(refresh, 1); assert.equal(f.state.code, ''); assert.equal(f.state.saved, null)
})
