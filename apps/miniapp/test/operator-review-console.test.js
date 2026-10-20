import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  moderationCardView, moderationConsoleCopy, reasonPicker, reasonLabel,
  operatorEntryGroups, operatorCapabilitySummary, operatorCardFacts,
} from '../src/services/public-review-display.js'
import { TEXT_REASONS } from '../src/services/public-review-texts.js'
import { REVIEW_REASONS } from '../src/services/experience-cards.js'

const ALL = {
  canReview: true, canOnboard: true, canCatalog: true, canCoupon: true, canInvite: true, canDiagnose: true,
  canDiagnoseManage: true, canPoints: true, canAssessment: true, canAnalytics: true, canPriceMonitor: true,
  canCommunityCurate: true, canCommunityAnswer: true, canCommunityManage: true, canKnowledge: true,
}
const keys = groups => groups.flatMap(group => group.entries.map(entry => entry.key))

test('moderation row says whether it can be approved before the operator taps', () => {
  assert.deepEqual(moderationCardView({ review_id: 1, version: 2, can_approve: true }), {
    approve: true, label: '可批准', tone: 'ok', versionCopy: '第 2 版',
    copy: '批准即公开这段确切文字，不能代改；驳回则车主修改后需重新授权。',
  })
  const blocked = moderationCardView({ review_id: 1, version: 3, can_approve: false })
  assert.equal(blocked.label, '来源已失效'); assert.equal(blocked.tone, 'risk'); assert.equal(blocked.approve, false)
  assert.match(blocked.copy, /只能驳回/)
  // 字段缺失时不能渲染 undefined，也不能误判成"可批准"。
  assert.equal(moderationCardView(undefined).approve, false)
  assert.equal(moderationCardView({}).versionCopy, '版本未知')
})

test('moderation queue distinguishes "nothing pending" from "not loaded yet"', () => {
  assert.deepEqual(moderationConsoleCopy(null), { empty: false, pending: 0, loaded: 0, blocked: 0, emptyTitle: '', emptyCopy: '', footer: '' })
  assert.deepEqual(moderationConsoleCopy({ items: [], total: 0 }), {
    empty: true, pending: 0, loaded: 0, blocked: 0,
    emptyTitle: '当前没有待审文字', emptyCopy: '只有车主单独投稿、且来源核对通过的脱敏文字才会进入这里。', footer: '',
  })
  const mixed = moderationConsoleCopy({ items: [{ can_approve: true }, { can_approve: false }], total: 5 })
  assert.equal(mixed.empty, false)
  assert.equal(mixed.blocked, 1)
  assert.match(mixed.footer, /待审 5 条 · 本页 2 条 · 其中 1 条来源已失效只能驳回/)
  assert.doesNotMatch(moderationConsoleCopy({ items: [{ can_approve: true }], total: 1 }).footer, /来源已失效/)
})

test('rejection reasons keep the code for the request and the text for the operator', () => {
  for (const reasons of [TEXT_REASONS, REVIEW_REASONS]) {
    const picker = reasonPicker(reasons)
    assert.deepEqual(picker.codes, Object.keys(reasons))
    assert.deepEqual(picker.labels, Object.values(reasons))
    assert.equal(picker.placeholder, '请选择驳回理由')
    for (const code of picker.codes) assert.equal(reasonLabel(code, reasons), reasons[code])
    assert.equal(reasonLabel('UNKNOWN', reasons), '')
    assert.equal(reasonLabel(undefined, reasons), '')
  }
  assert.deepEqual(reasonPicker().codes, [])
})

test('operator entries are grouped and only appear with the matching permission', () => {
  assert.deepEqual(operatorEntryGroups({}), [])
  assert.deepEqual(operatorEntryGroups(null), [])
  const reviewOnly = operatorEntryGroups({ canReview: true })
  assert.deepEqual(reviewOnly.map(group => group.key), ['content'])
  assert.deepEqual(keys(reviewOnly), ['public-images', 'public-questions', 'review-texts'])
  // 入驻审核以前对所有运营可见；现在必须由 can_onboard 收口。
  assert.deepEqual(keys(operatorEntryGroups({ canOnboard: true })), ['onboarding'])
  // 诊断工作区两种权限任一即可，其它入口不跟着出现。
  assert.deepEqual(keys(operatorEntryGroups({ canDiagnose: true })), ['diagnosis-cases'])
  assert.deepEqual(keys(operatorEntryGroups({ canDiagnoseManage: true })), ['diagnosis-cases', 'diagnosis-service'])
  const full = operatorEntryGroups(ALL)
  assert.deepEqual(full.map(group => group.key), ['content', 'queue', 'operate', 'read'])
  assert.equal(keys(full).length, 21)
  assert.equal(new Set(keys(full)).size, 21)
  for (const entry of full.flatMap(group => group.entries)) {
    assert.match(entry.url, /^\/pages\//, `${entry.key} 必须指向页面`)
    assert.ok(entry.label.length > 0 && entry.note.length > 0, `${entry.key} 缺少可读文案`)
  }
})

test('every capability badge in the summary has at least one entry behind it', () => {
  // 权限摘要不能声称一项能力却没有对应入口——两边由同一条清单推导。
  for (const capability of Object.keys(ALL)) {
    assert.ok(operatorEntryGroups({ [capability]: true }).length >= 1, `${capability} 在权限摘要里出现，却没有入口`)
    assert.match(operatorCapabilitySummary({ [capability]: true }).copy, /1 项权限/)
  }
})

test('capability summary explains an account with no operator permission', () => {
  assert.deepEqual(operatorCapabilitySummary({}), { granted: [], tone: 'risk', copy: '当前账号没有运营权限，请联系管理员开通后再使用审核台。' })
  const partial = operatorCapabilitySummary({ canReview: true, canOnboard: true })
  assert.deepEqual(partial.granted, ['内容审核', '商家入驻'])
  assert.equal(partial.tone, 'ok')
  assert.match(partial.copy, /2 项权限/)
  assert.equal(operatorCapabilitySummary(ALL).granted.length, 15)
  assert.deepEqual(operatorCapabilitySummary(undefined).granted, [])
})

test('pending experience cards separate the approval fact from the summary facts', () => {
  const approvable = operatorCardFacts({ card_id: 7, revision: 2, title: '施工经验摘要', model_id: 12, summary: { recorded_month: '2026-09', work_minutes: 90, part_kinds: 2, no_parts: false } })
  assert.deepEqual(approvable, {
    title: '施工经验摘要', revisionCopy: '第 2 次修订', month: '2026-09', work: '实际工时 90 分钟',
    parts: '使用 2 种配件', model: '有效车型编号 12', canApprove: true, tone: 'ok',
  })
  const noModel = operatorCardFacts({ revision: 1, model_id: null, summary: { recorded_month: '2026-09', work_minutes: 45, part_kinds: 0, no_parts: true } })
  assert.equal(noModel.canApprove, false); assert.equal(noModel.tone, 'risk')
  assert.equal(noModel.parts, '本次未使用配件')
  assert.match(noModel.model, /不能批准/)
  assert.equal(operatorCardFacts(undefined).title, '未命名经验卡片')
  assert.equal(operatorCardFacts({}).month, '月份未提供')
  // 0 不是有效车型：不能因为 Number.isSafeInteger(0) 就允许批准。
  assert.equal(operatorCardFacts({ model_id: 0 }).canApprove, false)
})

test('operator console pages render the shared mapping instead of a hardcoded button wall', () => {
  const dir = resolve(import.meta.dirname, '../src/pages/operator')
  const page = name => readFileSync(resolve(dir, name), 'utf8')
  for (const name of ['review.vue', 'review-texts.vue']) {
    assert.match(page(name), /public-review-display\.js/, `${name} 必须走统一展示映射`)
    assert.match(page(name), /ux-hero/, `${name} 缺少页面头部`)
    assert.match(page(name), /ux-card/, `${name} 缺少卡片原语`)
    assert.match(page(name), /toneClass\(/, `${name} 必须用统一徽标色调`)
  }
  // 入口清单搬进映射后，页面不应再出现散落的页面路径与按钮墙。
  assert.match(page('review.vue'), /operatorEntryGroups\(/)
  assert.match(page('review.vue'), /data-testid="operator-entry"/)
  assert.match(page('review.vue'), /ux-empty/)
  assert.doesNotMatch(page('review.vue'), /\/pages\/operator\/knowledge/)
  assert.doesNotMatch(page('review.vue'), /\/pages\/community\/public-images/)
  // 待审文字页：空态与行内能力状态都要在。
  assert.match(page('review-texts.vue'), /ux-empty/)
  assert.match(page('review-texts.vue'), /data-testid="moderation-(empty|row)"/)
  assert.match(page('review-texts.vue'), /reasonPicker\(/)
  assert.match(page('review-texts.vue'), /moderationCardView\(/)
})
