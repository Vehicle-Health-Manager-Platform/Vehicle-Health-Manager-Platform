import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  TONE_CLASS, toneClass, ratingHeadline, ratingRow, publicationView, textStatusView,
  draftMeter, textListCopy, consentStatusView, summaryView, summarySampleCopy, quoteSource,
} from '../src/services/public-review-display.js'

test('rating headline never renders null or NaN and states the sample size', () => {
  assert.deepEqual(ratingHeadline({ count: 0, rating_sum: 0, average_rating: null }), { value: '—', unit: '', caption: '暂无有效公开评价', tone: 'muted' })
  assert.deepEqual(ratingHeadline({ count: 3, rating_sum: 12, average_rating: '4.00' }), { value: '4.00', unit: '/ 5', caption: '3 条有效公开评价', tone: 'ok' })
  // 后端校验保证有样本时 average_rating 是字符串；缺失时必须退化而不是显示 undefined。
  assert.equal(ratingHeadline({ count: 1, rating_sum: 5 }).value, '—')
  assert.equal(ratingHeadline({}).value, '—')
  assert.equal(ratingHeadline(undefined).caption, '暂无有效公开评价')
})

test('rating rows clamp the score and expose a bar width', () => {
  assert.deepEqual(ratingRow({ rating: 4, submitted_date: '2026-10-20' }), { score: 4, date: '2026-10-20', bar: '80%', label: '4 / 5 分' })
  assert.equal(ratingRow({ rating: 9, submitted_date: '2026-10-20' }).bar, '100%')
  assert.equal(ratingRow({ rating: -1 }).score, 0)
  assert.equal(ratingRow({}).date, '日期未提供')
  assert.equal(ratingRow(undefined).bar, '0%')
})

test('publication separates test orders, unverified sources and the consented states', () => {
  assert.deepEqual(publicationView({ can_publish: false, unavailable_reason: 'TEST_ORDER', consented: false }),
    { label: '测试订单不可公开', tone: 'muted', copy: '测试评价不计入商家评分，也不会向其他车主展示。', action: null })
  const unverified = publicationView({ can_publish: false, unavailable_reason: 'PUBLIC_SOURCE_UNVERIFIED', consented: true })
  assert.equal(unverified.label, '来源未通过核对'); assert.equal(unverified.tone, 'risk'); assert.equal(unverified.action, '撤销公开授权')
  assert.deepEqual(publicationView({ can_publish: true, consented: true, visible: true }),
    { label: '已公开', tone: 'ok', copy: '评分与评价日期正在向登录车主展示，并计入平均评分。', action: '撤销公开授权' })
  assert.equal(publicationView({ can_publish: true, consented: false }).action, '单独授权公开星级')
  assert.equal(publicationView(null).label, '正在核对')
})

test('public text status is distinguishable before review, after review, rejected and withdrawn', () => {
  const reasons = { PRIVACY: '含隐私或联系信息' }
  assert.equal(textStatusView({ can_submit: true, status: 'NONE' }).label, '尚未投稿')
  assert.equal(textStatusView({ can_submit: true, status: 'PENDING' }).tone, 'warn')
  assert.equal(textStatusView({ can_submit: true, status: 'PENDING' }).action, '撤销文字授权并清除')
  assert.equal(textStatusView({ can_submit: true, status: 'APPROVED', visible: true }).label, '已公开')
  // 获批但来源失效：不能说成"已公开"，也不能说成"未投稿"。
  const stale = textStatusView({ can_submit: true, status: 'APPROVED', visible: false })
  assert.equal(stale.label, '已批准但停止展示'); assert.equal(stale.tone, 'warn')
  assert.match(textStatusView({ can_submit: true, status: 'REJECTED', reason_code: 'PRIVACY' }, reasons).copy, /含隐私或联系信息/)
  assert.match(textStatusView({ can_submit: true, status: 'REJECTED', reason_code: 'UNKNOWN' }, reasons).copy, /可修改后重新投稿/)
  assert.equal(textStatusView({ can_submit: true, status: 'WITHDRAWN' }).action, null)
  assert.equal(textStatusView({ can_submit: false, unavailable_reason: 'DISABLED' }).label, '功能未开启')
  assert.equal(textStatusView({ can_submit: false, unavailable_reason: 'PUBLIC_SOURCE_UNVERIFIED' }).tone, 'risk')
  assert.equal(textStatusView(undefined).label, '正在核对')
})

test('draft meter counts code points and flags overflow separately', () => {
  assert.deepEqual(draftMeter('维修后复检正常'), { count: 7, limit: 300, over: false, text: '7 / 300 字', empty: false })
  assert.equal(draftMeter('😀😀').count, 2)
  assert.equal(draftMeter('').empty, true)
  assert.equal(draftMeter('字'.repeat(301)).over, true)
  assert.equal(draftMeter(undefined).text, '0 / 300 字')
})

test('public text list shows an empty state instead of a zero total footer', () => {
  assert.deepEqual(textListCopy({ total: 0, items: [] }),
    { empty: true, emptyTitle: '暂无公开文字', emptyCopy: '需要车主单独授权、经规则脱敏与人工审核，且当前来源核对通过才会出现在这里。', footer: '' })
  const filled = textListCopy({ total: 2, items: [{}, {}] })
  assert.equal(filled.empty, false)
  assert.match(filled.footer, /共 2 条有效公开文字/)
  assert.match(filled.footer, /UTC/)
  assert.equal(textListCopy(null).footer, '')
})

test('consent shows the stale-authorisation state after the text version changes', () => {
  assert.equal(consentStatusView({ can_consent: true, consented: true, effective: true }).label, '已授权且有效')
  const stale = consentStatusView({ can_consent: true, consented: true, effective: false })
  assert.equal(stale.label, '授权已失效'); assert.equal(stale.tone, 'warn'); assert.equal(stale.action, '按当前文字重新授权')
  assert.equal(consentStatusView({ can_consent: true, consented: false }).action, '授权用于生成要点')
  assert.equal(consentStatusView({ can_consent: false }).label, '无可授权文字')
  assert.equal(consentStatusView(undefined).label, '正在核对')
})

test('summary explains every terminal status without claiming completeness', () => {
  assert.equal(summaryView({ status: 'AI_READY' }).tone, 'ok')
  assert.equal(summaryView({ status: 'FALLBACK' }).title, '原文摘录（未采用 AI 输出）')
  assert.equal(summaryView({ status: 'SOURCE_CHANGED' }).tone, 'risk')
  assert.equal(summaryView({ status: 'DISABLED' }).tone, 'muted')
  assert.equal(summaryView({ status: 'EMPTY' }).title, '暂无可用于要点的文字')
  assert.equal(summaryView(undefined).title, '暂无可用于要点的文字')
  assert.equal(summarySampleCopy({ sample_count: 0, eligible_count: 0 }), '')
  assert.match(summarySampleCopy({ sample_count: 6, eligible_count: 9 }), /候选 6 条.*符合 AI 用途的文字 9 条.*最近 20 条/)
})

test('quote source falls back when the source text is no longer visible', () => {
  const sources = [{ public_id: 'a', public_text: '师傅讲解清楚。', published_date: '2026-10-20' }]
  assert.deepEqual(quoteSource(sources, 'a'), { public_text: '师傅讲解清楚。', published_date: '2026-10-20', missing: false })
  assert.deepEqual(quoteSource(sources, 'b'), { public_text: '', published_date: '来源已不可见', missing: true })
  assert.equal(quoteSource(undefined, 'a').missing, true)
  assert.equal(quoteSource([{ public_id: 'a' }], 'a').published_date, '日期未提供')
})

test('tone classes always map to a documented badge class', () => {
  assert.deepEqual(Object.values(TONE_CLASS).sort(), ['ux-badge-muted', 'ux-badge-ok', 'ux-badge-risk', 'ux-badge-warn'])
  assert.equal(toneClass('warn'), 'ux-badge-warn')
  assert.equal(toneClass('nope'), 'ux-badge-muted')
  assert.equal(toneClass(undefined), 'ux-badge-muted')
})

test('review display pages go through the shared mapping instead of inline nullable text', () => {
  const dir = resolve(import.meta.dirname, '../src/pages')
  const page = name => readFileSync(resolve(dir, name), 'utf8')
  const pages = ['service/ratings.vue', 'service/review-texts.vue', 'service/review-summary.vue',
    'order/public-review-text.vue', 'order/review-publication.vue', 'order/review-summary-consent.vue']
  for (const name of pages) assert.match(page(name), /public-review-display\.js/, `${name} 必须走统一展示映射`)
  // 两个列表页空数据必须有空态分支，不能只靠 v-for 自然消失。
  for (const name of ['service/ratings.vue', 'service/review-texts.vue']) {
    assert.match(page(name), /ux-empty/, `${name} 缺少空态`)
    assert.match(page(name), /data-testid="public-/, `${name} 缺少可核对的展示锚点`)
  }
  // 要点来源必须经 quoteSource 兜底：撤回后来源会缺失，直接取字段会渲染 undefined。
  assert.match(page('service/review-summary.vue'), /quoteSource\(/)
  assert.doesNotMatch(page('service/review-summary.vue'), /source\(h\.public_id\)\?\./)
})
