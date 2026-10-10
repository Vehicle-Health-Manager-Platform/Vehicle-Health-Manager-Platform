import test from 'node:test'
import assert from 'node:assert/strict'
import { createOrderReviewsApi, reviewBody, validReviewDetail, validReview, createOrderReviewFlow, initialOrderReviewState, OrderReviewError } from '../src/services/order-reviews.js'
const key = '01234567-89ab-4cde-8fab-0123456789ab'
const review = () => ({ review_id: 12, rating: 5, content: '实际服务反馈🙂', photo_file_ids: [103, 101], submitted_at: '2026-10-09T02:00:00Z', test_mode: true })
const result = () => ({ order_id: 1, review: review() })
const detail = () => ({ order_id: 1, can_submit: true, unavailable_reason: null, test_mode: true, review: null })
test('评分字数码点与图片 ID 范围严格，emoji 按码点计数', () => {
  assert.deepEqual(reviewBody(1, 5, '  真实反馈  ', [103, 101]), { order_id: 1, rating: 5, content: '真实反馈', photo_file_ids: [103, 101] })
  assert.equal([...reviewBody(1, 1, '🙂'.repeat(500), []).content].length, 500)
  for (const rating of [0, 6, 1.5, '5', null]) assert.throws(() => reviewBody(1, rating, '反馈'))
  for (const text of ['', ' ', '🙂'.repeat(501), 'a\u0000', '\ud800']) assert.throws(() => reviewBody(1, 5, text))
  for (const photos of [[1, 1], [1, 2, 3, 4], [0], [1.5], ['1'], null]) assert.throws(() => reviewBody(1, 5, '反馈', photos))
  for (const order of [0, 1.5, '1', Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => reviewBody(order, 5, '反馈'))
})
test('响应字段最小化且资格与测试来源相互一致', () => {
  assert.equal(validReview(review()), true); assert.equal(validReviewDetail(detail(), 1), true)
  assert.equal(validReviewDetail({ ...detail(), can_submit: false, unavailable_reason: 'PAYMENT_UNVERIFIED', test_mode: null }, 1), true)
  assert.equal(validReviewDetail({ ...detail(), can_submit: false, unavailable_reason: 'ALREADY_REVIEWED', review: review() }, 1), true)
  for (const v of [{ ...detail(), order_id: 2 }, { ...detail(), user_id: 1 }, { ...detail(), review: review() }, { ...detail(), test_mode: null }, { ...detail(), can_submit: false }, { ...detail(), can_submit: false, unavailable_reason: 'ALREADY_REVIEWED' }]) assert.equal(validReviewDetail(v, 1), false)
  assert.equal(validReviewDetail({ ...detail(), can_submit: false, unavailable_reason: 'ALREADY_REVIEWED', review: review(), test_mode: false }, 1), false)
  assert.equal(validReview({ ...review(), payment_id: 1 }), false)
})
test('本人提交使用既定路径、四字段和 UUID，无客户端身份或测试字段', async () => {
  let call; const api = createOrderReviewsApi({ baseUrl: 'http://local/', runtime: () => ({ request: o => { call = o; o.success({ statusCode: 200, data: { code: 0, data: result() } }) } }) })
  const body = reviewBody(1, 5, review().content, [103, 101]); await api.submit('owner', body, key)
  assert.equal(call.url, 'http://local/api/order/review'); assert.equal(call.header.Authorization, 'Bearer owner'); assert.equal(call.header['Idempotency-Key'], key); assert.deepEqual(call.data, body)
  assert.throws(() => api.submit('owner', { ...body, test_mode: false }, key)); assert.throws(() => api.submit('owner', body, '')); assert.throws(() => api.submit('', body, key))
})
test('本人资格查询拒绝串单、额外内部字段及未知资格', async () => {
  let response = detail(), call; const api = createOrderReviewsApi({ baseUrl: 'http://local', runtime: () => ({ request: o => { call = o; o.success({ statusCode: 200, data: { code: 0, data: response } }) } }) })
  await api.detail('owner', 1); assert.equal(call.url, 'http://local/api/order/1/review')
  for (response of [{ ...detail(), order_id: 2 }, { ...detail(), staff_id: 1 }, { ...detail(), unavailable_reason: 'SECRET' }]) await assert.rejects(api.detail('owner', 1), e => e.kind === 'protocol')
})
test('写响应须为本次正文，不能把不同已评内容认作成功', async () => {
  let response = result(); const api = createOrderReviewsApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.success({ statusCode: 200, data: { code: 0, data: response } }) }) })
  for (const change of [{ rating: 1 }, { content: '另一次评价' }, { photo_file_ids: [101, 103] }]) { response = { ...result(), review: { ...review(), ...change } }; await assert.rejects(api.submit('owner', reviewBody(1, 5, review().content, [103, 101]), key), e => e.kind === 'protocol') }
})
test('错误文本不反射后端私密信息，已有评价与图片失败给出可执行提示', async () => {
  let status = 409, code = 44002; const api = createOrderReviewsApi({ baseUrl: 'http://local', runtime: () => ({ request: o => o.success({ statusCode: status, data: { code, message: 'secret SQL' } }) }) })
  const body = reviewBody(1, 5, '评价'); await assert.rejects(api.submit('owner', body, key), e => e.kind === 'conflict' && e.message.includes('不可修改') && !e.message.includes('SQL'))
  status = 422; await assert.rejects(api.submit('owner', body, key), e => e.kind === 'invalidPhoto' && e.message.includes('重新上传'))
  status = 503; await assert.rejects(api.submit('owner', body, key), e => e.kind === 'unavailable' && e.message.includes('原请求'))
})
function fixture(options = {}) {
  let actor = 'owner', order = 1, content = review().content, counter = 0; const calls = [], state = initialOrderReviewState()
  const api = { submit: async (...args) => { calls.push(args); return result() }, ...options.api }
  const flow = createOrderReviewFlow({ state, token: () => actor, body: () => reviewBody(order, 5, content, [103, 101]), api, newKey: () => `key-${++counter}`, confirm: options.confirm || (async () => true), onConflict: options.onConflict })
  flow.resume(); return { state, flow, calls, actor: n => { actor = n }, order: n => { order = n }, content: n => { content = n } }
}
test('取消确认不提交，成功锁定并明确测试来源', async () => {
  const cancel = fixture({ confirm: async () => false }); await cancel.flow.submit(); assert.equal(cancel.calls.length, 0); assert.equal(cancel.state.busy, false)
  const f = fixture(); await f.flow.submit(); assert.match(f.state.message, /未真实扣款/); await f.flow.submit(); assert.equal(f.calls.length, 1)
})
test('确认期间换账号、订单、内容或离页均废弃旧提交', async () => {
  for (const change of [f => f.actor('other'), f => f.order(2), f => f.content('改变'), f => { f.flow.reset(); f.flow.resume() }]) {
    let resolve; const f = fixture({ confirm: () => new Promise(r => { resolve = r }) }); const pending = f.flow.submit(); change(f); resolve(true); await pending; assert.equal(f.calls.length, 0)
  }
})
test('网络失败保留正文和原键，改变正文生成新键', async () => {
  const calls = [], f = fixture({ api: { submit: async (...args) => { calls.push(args); throw new OrderReviewError('network', '网络不可用') } } })
  await f.flow.submit(); await f.flow.submit(); assert.equal(calls[0][2], calls[1][2]); assert.deepEqual(calls[0][1], calls[1][1]); f.content('新反馈'); await f.flow.submit(); assert.notEqual(calls[1][2], calls[2][2])
})
test('并发按钮只发送一次，离页和换号不能污染结果', async () => {
  for (const change of [f => f.actor('other'), f => { f.flow.reset(); f.flow.resume() }]) {
    let resolve, count = 0; const f = fixture({ api: { submit: () => { count++; return new Promise(r => { resolve = r }) } } }); const pending = f.flow.submit(); await Promise.resolve(); await f.flow.submit(); assert.equal(count, 1); change(f); resolve(result()); await pending; assert.equal(f.state.saved, null)
  }
})
test('冲突刷新本人资格，失效图片允许替换且不重用旧键', async () => {
  let refreshed = 0, fail = 'conflict'; const calls = [], f = fixture({ onConflict: () => { refreshed++ }, api: { submit: async (...args) => { calls.push(args); throw new OrderReviewError(fail, '刷新') } } })
  await f.flow.submit(); assert.equal(refreshed, 1); fail = 'invalidPhoto'; await f.flow.submit(); await f.flow.submit(); assert.notEqual(calls[1][2], calls[2][2])
})
test('未激活页面不发送请求，重置清除结果和待提交状态', async () => {
  const f = fixture(); f.flow.reset(); await f.flow.submit(); assert.equal(f.calls.length, 0); f.flow.resume(); await f.flow.submit(); assert.ok(f.state.saved); f.flow.reset(); assert.equal(f.state.saved, null); assert.equal(f.state.message, '')
})
