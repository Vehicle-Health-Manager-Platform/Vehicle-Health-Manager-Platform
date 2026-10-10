import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CATEGORIES, REJECT_REASONS, createOnboardingApi, OnboardingError,
  validApplication, validReview, validMine, validSubmit, validSummary, validPendingPage,
  validDetail, validModerate, validAccess, validQuota, validQuotas,
  initialOwnerOnboardingState, initialOperatorOnboardingState,
  createOwnerOnboardingFlow, createOperatorOnboardingFlow, confirmStillHolds,
} from '../src/services/merchant-onboarding.js'

const key = '01234567-89ab-4cde-8fab-0123456789ab'
const future = '2099-01-01T00:00:00Z'
const draft = { merchant_name: '张三汽修', category: 'MAINTENANCE', region_code: '440305', address: '科技园路 1 号', contact_phone: '13800138000', qualification_file_ids: [11, 12] }
const application = { application_id: 7, merchant_name: '张三汽修', category: 'MAINTENANCE', region_code: '440305', address: '科技园路 1 号', contact_phone: '13800138000', status: 'PENDING_REVIEW', revision: 1, merchant_id: null, review_reason: null, qualification_file_ids: [11, 12], created_at: '2026-10-10T02:00:00Z', updated_at: '2026-10-10T02:00:00Z' }
const approved = { ...application, status: 'APPROVED', merchant_id: 21 }
const rejected = { ...application, status: 'REJECTED', revision: 2, review_reason: 'QUALIFICATION_INCOMPLETE' }
const review = { revision: 1, decision: 'REJECTED', reason_code: 'QUALIFICATION_INCOMPLETE', merchant_id: null, decided_at: '2026-10-10T03:00:00Z' }
const approveReview = { revision: 1, decision: 'APPROVED', reason_code: null, merchant_id: 21, decided_at: '2026-10-10T03:00:00Z' }
const summary = { application_id: 7, merchant_name: '张三汽修', category: 'MAINTENANCE', region_code: '440305', revision: 1, submitted_at: '2026-10-10T02:00:00Z' }
const quota = { region_code: '440305', category: 'MAINTENANCE', max_active: 5, active_stores: 1 }
const access = { url: 'https://files.example.com/qualification?sign=abc', expires_at: future }
const apiFor = (data, calls = [], statusCode = 200) => createOnboardingApi({ baseUrl: 'https://local', runtime: () => ({ request(r) { calls.push(r); r.success({ statusCode, data: { code: 0, data } }) } }) })

test('协议校验拒绝未知字段、非法品类、自由文字驳回码与状态字段不一致', () => {
  assert.ok(validApplication(application)); assert.ok(validApplication(approved)); assert.ok(validApplication(rejected))
  assert.ok(validReview(review)); assert.ok(validReview(approveReview))
  for (const bad of [{ ...application, applicant_user_id: 2 }, { ...application, category: 'FREE_TEXT' }, { ...application, region_code: '440' },
    { ...application, contact_phone: '12345' }, { ...application, qualification_file_ids: [] }, { ...application, qualification_file_ids: [11, 11] },
    { ...application, merchant_id: 3 }, { ...application, review_reason: 'QUALIFICATION_INCOMPLETE' }, { ...application, created_at: 'not-a-date' }])
    assert.equal(validApplication(bad), false, JSON.stringify(bad))
  // 驳回码只能是固定枚举，绝不放行运营自由文字。
  assert.equal(validReview({ ...review, reason_code: '文字理由' }), false)
  assert.equal(validReview({ ...review, reason_code: null }), false)
  assert.equal(validReview({ ...approveReview, merchant_id: null }), false)
  assert.equal(validReview({ ...review, merchant_id: 21 }), false)
})

test('本人申请在无记录时只允许 application:null，有记录才带不可变审核历史', () => {
  assert.ok(validMine({ application: null }))
  assert.ok(validMine({ application, reviews: [] }))
  assert.ok(validMine({ application: rejected, reviews: [review] }))
  assert.equal(validMine({ application: null, reviews: [] }), false)
  assert.equal(validMine({ application }), false)
  assert.equal(validMine({ application, reviews: [review, review] }), false)
  assert.equal(validMine({ application, reviews: [{ ...review, extra: 1 }] }), false)
  assert.ok(validSubmit({ application, revision: 1 }))
  assert.equal(validSubmit({ application, revision: 2 }), false)
  assert.equal(validSubmit({ application, revision: 1, merchant_id: 1 }), false)
})

test('待审摘要容不下申请人身份、手机号或资质文件，分页与去重严格', () => {
  assert.ok(validSummary(summary))
  for (const bad of [{ ...summary, applicant_user_id: 2 }, { ...summary, contact_phone: '13800138000' },
    { ...summary, qualification_file_ids: [11] }, { ...summary, revision: 0 }]) assert.equal(validSummary(bad), false)
  const page = { items: [summary], total: 1, page: 1, page_size: 20 }
  assert.ok(validPendingPage(page, 1))
  assert.equal(validPendingPage({ ...page, page: 2 }, 1), false)
  assert.equal(validPendingPage({ ...page, page_size: 50 }, 1), false)
  assert.equal(validPendingPage({ ...page, items: [summary, summary], total: 2 }, 1), false)
  assert.equal(validPendingPage({ ...page, total: 0 }, 1), false)
})

test('详情、审核结论与短时访问地址严格对应请求', () => {
  assert.ok(validDetail({ application, reviews: [review] }))
  assert.equal(validDetail({ application, reviews: [{ ...review, revision: 0 }] }), false)
  assert.ok(validModerate({ application: approved, decision: 'APPROVE', reason_code: null, merchant_id: 21 }, 'APPROVE', null))
  assert.ok(validModerate({ application: rejected, decision: 'REJECT', reason_code: 'QUALIFICATION_INCOMPLETE', merchant_id: null }, 'REJECT', 'QUALIFICATION_INCOMPLETE'))
  assert.equal(validModerate({ application: approved, decision: 'APPROVE', reason_code: null, merchant_id: 21 }, 'APPROVE', 'QUALIFICATION_INCOMPLETE'), false)
  assert.equal(validModerate({ application: { ...approved, merchant_id: 22 }, decision: 'APPROVE', reason_code: null, merchant_id: 21 }, 'APPROVE', null), false)
  assert.equal(validModerate({ application: rejected, decision: 'APPROVE', reason_code: null, merchant_id: 21 }, 'APPROVE', null), false)
  assert.ok(validAccess(access))
  assert.equal(validAccess({ ...access, url: 'http://files.example.com/x' }), false)
  assert.equal(validAccess({ ...access, expires_at: '2000-01-01T00:00:00Z' }), false)
  assert.equal(validAccess({ ...access, object_key: 'private/key' }), false)
})

test('配额读写拒绝越界与重复，active_stores 如实回传', () => {
  assert.ok(validQuota(quota))
  assert.equal(validQuota({ ...quota, max_active: 100001 }), false)
  assert.equal(validQuota({ ...quota, active_stores: -1 }), false)
  assert.ok(validQuotas({ items: [quota] }))
  assert.equal(validQuotas({ items: [quota, quota] }), false)
  assert.ok(Object.hasOwn(CATEGORIES, 'SUPPLIES') && Object.hasOwn(REJECT_REASONS, 'REGION_QUOTA_FULL'))
})

test('提交只发送固定六字段并携带 UUID 幂等键，越界输入本地即拦下', async () => {
  const calls = []; await apiFor({ application, revision: 1 }, calls).submit('owner-token', draft, key)
  assert.deepEqual(calls[0].data, draft)
  assert.equal(calls[0].method, 'POST')
  assert.equal(calls[0].header.Authorization, 'Bearer owner-token')
  assert.equal(calls[0].header['Idempotency-Key'], key)
  for (const bad of [{ ...draft, category: 'FREE_TEXT' }, { ...draft, region_code: '44030' }, { ...draft, contact_phone: '1380013800' },
    { ...draft, merchant_name: ' 张三汽修' }, { ...draft, address: '   ' }, { ...draft, qualification_file_ids: [] },
    { ...draft, qualification_file_ids: [11, 11] }, { ...draft, note: '请尽快' }]) assert.throws(() => apiFor({}).submit('o', bad, key), OnboardingError)
  assert.throws(() => apiFor({}).submit('o', draft, 'not-a-uuid'))
  assert.throws(() => apiFor({}).submit('', draft, key))
})

test('运营接口固定载荷：审核只发 revision/decision/reason_code，配额用 PUT', async () => {
  const calls = []
  await apiFor({ items: [summary], total: 1, page: 1, page_size: 20 }, calls).pending('operator-token', 1)
  assert.match(calls[0].url, /\/api\/admin\/merchant-applications\?page=1&page_size=20$/)
  assert.equal(calls[0].header.Authorization, 'Bearer operator-token')
  await apiFor({ application: approved, decision: 'APPROVE', reason_code: null, merchant_id: 21 }, calls).moderate('operator-token', 7, 1, 'APPROVE', null, key)
  assert.deepEqual(calls[1].data, { revision: 1, decision: 'APPROVE', reason_code: null })
  assert.equal(calls[1].header['Idempotency-Key'], key)
  await apiFor(quota, calls).setQuota('operator-token', { region_code: '440305', category: 'MAINTENANCE', max_active: 5 }, key)
  assert.equal(calls[2].method, 'PUT')
  assert.deepEqual(calls[2].data, { region_code: '440305', category: 'MAINTENANCE', max_active: 5 })
  await apiFor(access, calls).fileAccess('operator-token', 7, 11)
  assert.match(calls[3].url, /\/api\/admin\/merchant-applications\/7\/files\/11\/access$/)
  assert.throws(() => apiFor({}).moderate('o', 7, 1, 'REJECT', '自由文字', key))
  assert.throws(() => apiFor({}).moderate('o', 7, 1, 'APPROVE', 'QUOTA_FULL', key))
  assert.throws(() => apiFor({}).setQuota('o', { region_code: '440305', category: 'MAINTENANCE', max_active: 100001 }, key))
  assert.throws(() => apiFor({}).pending('o', 0))
})

test('失败响应按状态归类，绝不当成成功', async () => {
  for (const [status, kind] of [[400, 'invalid'], [401, 'unauthorized'], [403, 'forbidden'], [404, 'missing'], [409, 'conflict'], [429, 'limited'], [503, 'unavailable']]) {
    await assert.rejects(apiFor({}, [], status).mine('owner-token'), e => e instanceof OnboardingError && e.kind === kind)
  }
  await assert.rejects(apiFor({ application: null, reviews: [] }).mine('owner-token'), e => e.kind === 'protocol')
})

function ownerFixture(methods = {}) {
  const state = initialOwnerOnboardingState(); let actor = 'owner', count = 0
  const flow = createOwnerOnboardingFlow({ state, token: () => actor, newKey: () => key.slice(0, -1) + ++count,
    api: { mine: async () => ({ application, reviews: [] }), submit: async () => ({ application, revision: 1 }), ...methods } })
  flow.resume(); return { flow, state, actor: n => { actor = n } }
}
function operatorFixture(methods = {}) {
  const state = initialOperatorOnboardingState(); let actor = 'operator', count = 0
  const flow = createOperatorOnboardingFlow({ state, token: () => actor, newKey: () => key.slice(0, -1) + ++count,
    api: { pending: async () => ({ items: [summary], total: 1, page: 1, page_size: 20 }),
      detail: async () => ({ application, reviews: [] }),
      moderate: async () => ({ application: approved, decision: 'APPROVE', reason_code: null, merchant_id: 21 }),
      quotas: async () => ({ items: [quota] }), setQuota: async () => quota, ...methods } })
  flow.resume(); return { flow, state, actor: n => { actor = n } }
}

test('车主流程在离页或换账号后丢弃迟到响应', async () => {
  for (const change of ['hide', 'actor']) {
    let finish; const f = ownerFixture({ mine: () => new Promise(r => { finish = r }) }); const request = f.flow.load()
    if (change === 'hide') f.flow.reset(); else f.actor('other')
    finish({ application, reviews: [] }); await request
    assert.equal(f.state.application, null); assert.equal(f.state.loaded, false)
  }
})

test('车主流程重提成功写入最新 revision，失败保留可读原因', async () => {
  const f = ownerFixture(); await f.flow.load(); assert.equal(f.state.application.revision, 1)
  await f.flow.submit(draft); assert.equal(f.state.message, '已提交，等待运营审核')
  const g = ownerFixture({ submit: async () => { const e = new OnboardingError('conflict', '已存在待审或已通过的入驻申请，请勿重复提交'); throw e } })
  await g.flow.submit(draft); assert.equal(g.state.kind, 'conflict'); assert.match(g.state.message, /重复提交/)
})

test('运营流程审核后移除该行、清空详情并要求重新加载分页', async () => {
  const f = operatorFixture(); await f.flow.load(); assert.equal(f.state.rows.length, 1)
  await f.flow.open(summary); assert.equal(f.state.detail.application_id, 7)
  await f.flow.decide(summary, 'APPROVE', null)
  assert.equal(f.state.rows.length, 0); assert.equal(f.state.total, 0)
  assert.equal(f.state.detail, null); assert.equal(f.state.loaded, false)
  assert.equal(f.state.message, '已批准，门店与店长待激活账号已创建')
})

test('运营流程只对列表内同版本行操作，配额保存按区域品类合并', async () => {
  const f = operatorFixture(); await f.flow.load()
  await f.flow.decide({ ...summary, revision: 9 }, 'APPROVE', null)
  assert.equal(f.state.rows.length, 1)
  await f.flow.loadQuotas(); assert.equal(f.state.quotasLoaded, true); assert.equal(f.state.quotas[0].active_stores, 1)
  const g = operatorFixture({ setQuota: async () => ({ ...quota, max_active: 8 }) })
  await g.flow.loadQuotas(); await g.flow.saveQuota({ region_code: '440305', category: 'MAINTENANCE', max_active: 8 })
  assert.equal(g.state.quotas.length, 1); assert.equal(g.state.quotas[0].max_active, 8)
  assert.equal(g.state.message, '配额已保存')
  const h = operatorFixture({ setQuota: async () => ({ region_code: '110108', category: 'TIRE', max_active: 2, active_stores: 0 }) })
  await h.flow.saveQuota({ region_code: '110108', category: 'TIRE', max_active: 2 })
  assert.equal(h.state.quotas.length, 1)
})

test('运营流程在换账号后丢弃迟到响应并清空列表', async () => {
  let finish; const f = operatorFixture({ pending: () => new Promise(r => { finish = r }) }); const request = f.flow.load()
  f.actor('other'); finish({ items: [summary], total: 1, page: 1, page_size: 20 }); await request
  assert.deepEqual(f.state.rows, []); assert.equal(f.state.loaded, false)
})

test('弹窗确认在换账号、离开页面或换目标后作废', () => {
  assert.equal(confirmStillHolds({ actor: 'a', target: 7 }, { visible: true, token: 'a', target: 7 }), true)
  assert.equal(confirmStillHolds({ actor: 'a', target: 7 }, { visible: true, token: 'b', target: 7 }), false)
  assert.equal(confirmStillHolds({ actor: 'a', target: 7 }, { visible: false, token: 'a', target: 7 }), false)
  assert.equal(confirmStillHolds({ actor: 'a', target: 7 }, { visible: true, token: 'a', target: 8 }), false)
  assert.equal(confirmStillHolds({ actor: 'a' }, { visible: true, token: 'a' }), true)
  assert.equal(confirmStillHolds({ actor: 'a' }, { visible: true, token: '' }), false)
})
