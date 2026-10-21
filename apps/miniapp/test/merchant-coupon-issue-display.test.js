import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  COUPON_TYPES, couponDateFields, couponIssueEmptyView, couponIssuePagingView, couponIssueRowView,
  couponIssueWriteView, couponProjectOptionRows, couponTermsFormView, couponTypeLabel, couponTypeRows,
} from '../src/services/merchant-coupon-issue-display.js'

const page = readFileSync(resolve(import.meta.dirname, '../src/pages/merchant/coupons.vue'), 'utf8')

const terms = (over = {}) => ({
  name: '精洗立减', type: 'DISCOUNT', project_id: 4, amount: '5.00', min_spend: '20.00',
  total_quantity: 50, valid_from: '2026-10-21T00:00:00Z', valid_to: '2026-11-21T00:00:00Z', ...over,
})
const row = (over = {}) => ({
  coupon_id: 7, merchant_id: 3, terms: terms(), revision: 1, status: 'DRAFT',
  stock_left: 50, publication_revision: null, pool_selected: false, pool_revision: 1, ...over,
})
const dates = { fromDate: '2026-10-21', fromTime: '08:00', toDate: '2026-11-21', toTime: '20:00' }
const projects = { items: [{ standard_project_id: 4, project_name: '精洗', available: true, status: 1 }] }

test('券类型：顺序与文案固定，未知值不当成默认', () => {
  assert.deepEqual(COUPON_TYPES.map(row => row.key), ['DISCOUNT', 'SERVICE'])
  assert.equal(COUPON_TYPES[0].label, '金额优惠券')
  assert.equal(COUPON_TYPES[1].label, '指定项目服务兑换券')
  assert.equal(couponTypeLabel('DISCOUNT'), '金额优惠券')
  assert.equal(couponTypeLabel('SERVICE'), '指定项目服务兑换券')
  assert.equal(couponTypeLabel('WAT'), '券类型未提供')
  assert.equal(couponTypeLabel(undefined), '券类型未提供')
  // 类型选择器不能按字母排序：DISCOUNT 必须仍在第 0 位。
  const rows = couponTypeRows('SERVICE')
  assert.equal(rows[1].index, 1); assert.equal(rows[1].selected, true); assert.equal(rows[0].selected, false)
  assert.equal(couponTypeRows(undefined).length, 2)
})

test('券行：草稿能改能发，已发布只能停止，已停止没有动作', () => {
  const draft = couponIssueRowView(row())
  assert.equal(draft.statusLabel, '草稿'); assert.equal(draft.tone, 'warn')
  assert.equal(draft.stockCopy, '剩余 50 / 发行 50')
  assert.equal(draft.revisionCopy, '草稿版本 1')
  assert.deepEqual(draft.actions.map(a => [a.key, a.action, a.label]), [
    ['update', 'update', '修改草稿'], ['publish', 'status', '发布并冻结条款'],
  ])
  assert.equal(draft.actions[1].target, 'PUBLISHED')
  assert.match(draft.noteCopy, /发布后条款冻结/)

  const published = couponIssueRowView(row({ status: 'PUBLISHED', revision: 2, publication_revision: 2, stock_left: 31 }))
  assert.equal(published.statusLabel, '已发布'); assert.equal(published.tone, 'ok')
  assert.equal(published.stockCopy, '剩余 31 / 发行 50')
  assert.equal(published.revisionCopy, '发布版本 2')
  assert.deepEqual(published.actions.map(a => [a.key, a.target]), [['stop', 'STOPPED']])
  assert.match(published.noteCopy, /不影响已领取/)

  const stopped = couponIssueRowView(row({ status: 'STOPPED', revision: 3, publication_revision: 2 }))
  assert.equal(stopped.tone, 'muted'); assert.deepEqual(stopped.actions, [])
  assert.match(stopped.noteCopy, /仍按原条款使用/)

  // 未知状态绝不能给出动作，否则会被当成可发布。
  const unknown = couponIssueRowView(row({ status: 'WAT' }))
  assert.deepEqual(unknown.actions, [])
  assert.equal(unknown.statusLabel, '状态未提供')
  assert.match(unknown.noteCopy, /无法在本页操作/)

  // 字段缺失一律退化，不抛错、不出现 NaN。
  const bare = couponIssueRowView({})
  assert.equal(bare.stockCopy, '剩余 0 / 发行 0')
  assert.equal(bare.couponId, 0)
  assert.equal(bare.typeLabel, '券类型未提供')
  assert.equal(couponIssueRowView(undefined).actions.length, 0)
})

test('列表空态：忙碌与未加载都不算空', () => {
  assert.equal(couponIssueEmptyView({ busy: true, loaded: true, rows: [] }).visible, false)
  assert.equal(couponIssueEmptyView({ busy: false, loaded: false, rows: [] }).visible, false)
  assert.equal(couponIssueEmptyView({ busy: false, loaded: true, rows: [row()] }).visible, false)
  const empty = couponIssueEmptyView({ busy: false, loaded: true, rows: [] })
  assert.equal(empty.visible, true)
  assert.equal(empty.title, '本店还没有新发行券')
  assert.match(empty.copy, /创建券草稿/)
  assert.equal(couponIssueEmptyView(undefined).visible, false)
})

test('分页：一页时不出现翻页区，写入挂起时不能翻页', () => {
  const one = couponIssuePagingView({ page: 1, total: 5, rows: [row()] })
  assert.equal(one.hidden, true); assert.equal(one.canPrev, false); assert.equal(one.canNext, false)
  assert.equal(one.pageCopy, '第 1 页 · 共 5 张')

  const many = couponIssuePagingView({ page: 1, total: 45, rows: [row()] })
  assert.equal(many.hidden, false); assert.equal(many.canPrev, false); assert.equal(many.canNext, true)

  const second = couponIssuePagingView({ page: 2, total: 45, rows: [row()] })
  assert.equal(second.canPrev, true); assert.equal(second.canNext, true)
  const last = couponIssuePagingView({ page: 3, total: 45, rows: [row()] })
  assert.equal(last.canNext, false)

  // 原请求未确认时不能翻页（翻页会清空 rows 并丢掉现场）。
  const locked = couponIssuePagingView({ page: 2, total: 45, rows: [row()], retryWrite: true })
  assert.equal(locked.canPrev, false); assert.equal(locked.canNext, false)
  assert.equal(locked.hidden, false, '锁只影响能不能点，不能把翻页区藏掉')
  assert.equal(couponIssuePagingView({}).hidden, true)
})

test('写入结果：成功与失败语气分开，越权只给重登', () => {
  const ok = couponIssueWriteView({ message: '已完成并重新读取当前券状态', kind: '', retryWrite: false })
  assert.equal(ok.tone, 'ok'); assert.equal(ok.canRelogin, false); assert.equal(ok.retryCopy, '')
  const denied = couponIssueWriteView({ message: '当前账号没有券管理权限', kind: 'forbidden' })
  assert.equal(denied.tone, 'risk'); assert.equal(denied.canRelogin, true)
  const conflict = couponIssueWriteView({ message: '券状态或库存已变化，请刷新', kind: 'conflict' })
  assert.equal(conflict.canRelogin, false)
  const pending = couponIssueWriteView({ message: '网络问题', kind: 'network', retryWrite: true })
  assert.equal(pending.retryWrite, true)
  assert.match(pending.retryCopy, /不要改动表单另建一张券/)
  assert.equal(couponIssueWriteView({}).message, '')
  assert.equal(couponIssueWriteView(undefined).tone, 'muted')
})

test('表单缺口：一次列全，口径与 validCouponTerms 一致', () => {
  const empty = couponTermsFormView({}, {}, { items: [] })
  assert.equal(empty.ready, false)
  assert.deepEqual(empty.missing, ['券名称', '在售标准项目', '优惠金额（两位小数，且大于 0）', '发行库存（正整数）', '生效时间', '截止时间'])
  assert.match(empty.hint, /^还需填写：券名称、在售标准项目/)
  assert.equal(empty.amountLocked, false)
  assert.match(empty.projectHint, /暂无可选的在售项目/)

  const good = couponTermsFormView(terms(), dates, projects)
  assert.equal(good.ready, true); assert.deepEqual(good.missing, [])
  assert.equal(good.hint, '信息齐了，可以保存草稿。')
  assert.equal(good.dateCopy, '2026-10-21 08:00 → 2026-11-21 20:00（北京时间）')
  assert.equal(good.projectOptions, 1); assert.equal(good.projectHint, '')

  // 服务券的优惠与门槛恒为 0.00，页面据此隐藏两个输入框，也不要求填金额。
  const service = couponTermsFormView(terms({ type: 'SERVICE', amount: '', min_spend: '' }), dates, projects)
  assert.equal(service.amountLocked, true); assert.equal(service.isService, true)
  assert.equal(service.ready, true)
  assert.equal(service.typeLabel, '指定项目服务兑换券')

  // 金额口径：两位小数、必须大于 0、门槛不得小于优惠。
  assert.ok(couponTermsFormView(terms({ amount: '5.0' }), dates, projects).missing.some(m => m.startsWith('优惠金额')))
  assert.ok(couponTermsFormView(terms({ amount: '0.00' }), dates, projects).missing.some(m => m.startsWith('优惠金额')))
  assert.ok(couponTermsFormView(terms({ min_spend: '3.00' }), dates, projects).missing.some(m => m.startsWith('最低消费')))
  assert.ok(couponTermsFormView(terms({ min_spend: '20.0' }), dates, projects).missing.some(m => m.startsWith('最低消费')))

  // 库存：正整数；0、小数、空都不行，但 10 位整数可以。
  for (const quantity of ['0', '1.5', '', '-1', '0.5']) {
    assert.ok(couponTermsFormView(terms({ total_quantity: quantity }), dates, projects).missing.includes('发行库存（正整数）'), `库存 ${quantity} 应无效`)
  }
  assert.equal(couponTermsFormView(terms({ total_quantity: '1234567890' }), dates, projects).ready, true)

  // 有效期顺序：截止必须晚于生效。
  const reversed = couponTermsFormView(terms({
    valid_from: '2026-11-21T00:00:00Z', valid_to: '2026-10-21T00:00:00Z',
  }), dates, projects)
  assert.ok(reversed.missing.some(m => m.includes('截止须晚于生效')))
  assert.equal(couponTermsFormView(terms(), {}, projects).dateCopy, '有效期按北京时间填写，两项都要选')

  // 名称过长单独提示，不与"没填"混为一谈。
  const longName = couponTermsFormView(terms({ name: '券'.repeat(129) }), dates, projects)
  assert.deepEqual(longName.missing, ['券名称（不超过 128 字）'])
})

test('项目选项：只有本店有效且在售的能选', () => {
  const rows = couponProjectOptionRows({
    items: [
      { standard_project_id: 4, project_name: '精洗', available: true, status: 1 },
      { standard_project_id: 5, project_name: '打蜡', available: false, status: 1 },
      { standard_project_id: 6, project_name: '轮胎', available: true, status: 0 },
      { standard_project_id: 7, available: true, status: 1 },
    ],
  })
  assert.deepEqual(rows.map(r => [r.index, r.projectId, r.selectable]), [
    [0, 4, true], [1, 5, false], [2, 6, false], [3, 7, true],
  ])
  assert.equal(rows[0].label, '精洗')
  assert.equal(rows[3].label, '项目未命名')
  assert.deepEqual(couponProjectOptionRows(undefined), [])
})

test('编辑态初值：UTC 条款时间拆回四个本地输入框', () => {
  const local = value => (value === '2026-10-21T00:00:00Z' ? '2026-10-21 08:00' : value === '2026-11-21T00:00:00Z' ? '2026-11-21 20:00' : '')
  assert.deepEqual(couponDateFields(terms(), local), {
    fromDate: '2026-10-21', fromTime: '08:00', toDate: '2026-11-21', toTime: '20:00',
  })
  // 时间非法时 couponLocalTime 返回空串，四个格子一起空，不产生 'undefined' 字样。
  assert.deepEqual(couponDateFields(terms(), () => ''), { fromDate: '', fromTime: '', toDate: '', toTime: '' })
  assert.deepEqual(couponDateFields(null, local), { fromDate: '', fromTime: '', toDate: '', toTime: '' })
  assert.deepEqual(couponDateFields(terms(), undefined), { fromDate: '', fromTime: '', toDate: '', toTime: '' })
})

test('券发行页使用映射，并保住发行契约', () => {
  for (const fn of ['couponTypeRows', 'couponTermsFormView', 'couponProjectOptionRows', 'couponIssueEmptyView', 'couponIssuePagingView', 'couponIssueWriteView', 'couponIssueRowView', 'couponDateFields']) {
    assert.match(page, new RegExp(`${fn}\\(`), `coupons.vue 必须使用 ${fn}`)
  }
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  for (const id of ['coupon-issue-form', 'coupon-issue-login', 'coupon-issue-create', 'coupon-terms-card', 'coupon-name', 'coupon-type', 'coupon-project', 'coupon-amount', 'coupon-min-spend', 'coupon-quantity', 'coupon-valid-from', 'coupon-valid-to', 'coupon-save', 'coupon-close', 'coupon-board', 'coupon-issue-empty', 'coupon-issue-message', 'coupon-issue-retry']) {
    assert.match(page, new RegExp(`data-testid="${id}"`), `缺少锚点 ${id}`)
  }
  // 发行仍走既有服务：条款校验、幂等键、UTC 时间互转、条款展示组件都不能绕开。
  assert.match(page, /createCouponFlow\(/)
  assert.match(page, /newKey: imageRequestKey/)
  assert.match(page, /couponUtcTime\(/)
  assert.match(page, /couponLocalTime\)/)
  assert.match(page, /<CouponTerms/)
  assert.match(page, /quotesApi\.ownList/)
  // 旧模板 inline 判断不应残留。
  assert.doesNotMatch(page, /v-if="row\.status==='DRAFT'"/)
  assert.doesNotMatch(page, /state\.rows\.length/)
  assert.doesNotMatch(page, /:range="\['金额优惠券'/)
  assert.doesNotMatch(page, /v-if="state\.page>1"/)
})
