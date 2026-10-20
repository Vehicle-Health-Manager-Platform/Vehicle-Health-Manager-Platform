import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  candidateRowView, dispatchCandidateCopy, dispatchExistingFacts, dispatchStageView,
  dispatchSubmitCopy, shouldLoadCandidates, slotFormFacts, slotListCopy, slotProjectOptions, slotRowView,
} from '../src/services/merchant-scheduling-display.js'

const readPage = name => readFileSync(resolve(import.meta.dirname, '../src/pages/merchant', name), 'utf8')

test('slot rows read as open, closed or full instead of two bare numbers', () => {
  const open = slotRowView({ slot_id: 1, project_name: '小保养', starts_at: '2026-10-21T02:00:00Z', ends_at: '2026-10-21T03:00:00Z', open: true, capacity: 5, capacity_left: 2 })
  assert.equal(open.project, '小保养')
  assert.equal(open.statusLabel, '开放预约')
  assert.equal(open.tone, 'ok')
  assert.equal(open.capacityCopy, '剩余 2 / 5')
  assert.equal(open.full, false)
  assert.equal(open.note, '')
  assert.match(open.window, /至/)
  const full = slotRowView({ open: true, capacity: 3, capacity_left: 0 })
  assert.equal(full.full, true); assert.equal(full.tone, 'warn')
  assert.match(full.note, /已约满/)
  const closed = slotRowView({ open: false, capacity: 3, capacity_left: 3 })
  assert.equal(closed.statusLabel, '已关闭'); assert.equal(closed.tone, 'muted')
  assert.match(closed.note, /不再接受新预约/)
  assert.equal(slotRowView(undefined).project, '项目未提供')
  assert.equal(slotRowView({}).capacityCopy, '剩余 0 / 0')
})

test('slot list empty state and footer do not lie about a zero total', () => {
  assert.deepEqual(slotListCopy({ items: [], total: 0 }), {
    empty: true, emptyTitle: '暂无本店预约时段', emptyCopy: '发布时段后车主才能预约；已关闭与约满的时段仍会列在这里。', footer: '',
  })
  const filled = slotListCopy({ items: [{}, {}], total: 6 })
  assert.equal(filled.empty, false)
  assert.equal(filled.footer, '本店时段 共 6 条 · 已加载 2 条')
  assert.equal(slotListCopy(undefined).empty, true)
})

test('publishable projects are filtered and marked in the mapping', () => {
  const items = [
    { standard_project_id: 11, project_name: '小保养', available: true, status: 1 },
    { standard_project_id: 12, project_name: '大保养', available: false, status: 1 },
    { standard_project_id: 13, project_name: '洗车', available: true, status: 0 },
    { standard_project_id: 14, available: true, status: 1 },
  ]
  assert.deepEqual(slotProjectOptions(items, 11), [
    { id: 11, label: '小保养', selected: true },
    { id: 14, label: '项目未提供', selected: false },
  ])
  assert.deepEqual(slotProjectOptions(undefined, 0), [])
})

test('publish form summary says what is still missing', () => {
  const empty = slotFormFacts({ project: 0, day: '2026-10-21', start: '10:00', end: '11:00', capacity: '1' })
  assert.equal(empty.ready, false); assert.equal(empty.projectLabel, '尚未选择项目')
  assert.match(empty.hint, /请先选择要发布的在售项目/)
  const badCapacity = slotFormFacts({ project: 11, day: '2026-10-21', start: '10:00', end: '11:00', capacity: '0' })
  assert.equal(badCapacity.ready, false); assert.match(badCapacity.capacityCopy, /1–100/)
  assert.match(badCapacity.hint, /1–100 的整数/)
  const ready = slotFormFacts({ project: 11, day: '2026-10-21', start: '10:00', end: '11:00', capacity: '8' })
  assert.equal(ready.ready, true); assert.equal(ready.capacityCopy, '预约名额 8')
  assert.match(ready.windowCopy, /2026-10-21 10:00 至 11:00（北京时间）/)
  assert.match(ready.hint, /不可编辑/)
  // 容量上限 100 通过，101 不通过。
  assert.equal(slotFormFacts({ project: 1, capacity: '100' }).ready, true)
  assert.equal(slotFormFacts({ project: 1, capacity: '101' }).ready, false)
  assert.equal(slotFormFacts(undefined).ready, false)
})

test('dispatch stage keeps the original guard order', () => {
  assert.equal(dispatchStageView(null, null).stage, 'no-order')
  // 已有派工优先于争议：服务端已定的事实不能被争议面板盖掉。
  assert.equal(dispatchStageView({ status: 'DISPUTED' }, { assignment: { status: 'ASSIGNED' } }).stage, 'existing')
  assert.equal(dispatchStageView({ status: 'DISPUTED' }, { assignment: null, loaded: true }).stage, 'disputed')
  const notReady = dispatchStageView({ status: 'PAID' }, { assignment: null, loaded: true })
  assert.equal(notReady.stage, 'not-ready'); assert.match(notReady.title, /已支付待接车/)
  // 派工结果还没读到：不给候选，避免对已有派工的订单重复提交。
  assert.equal(dispatchStageView({ status: 'RECEIVED' }, { assignment: null, loaded: false }).stage, 'loading')
  assert.equal(dispatchStageView({ status: 'RECEIVED' }, undefined).stage, 'loading')
  assert.equal(dispatchStageView({ status: 'RECEIVED' }, { assignment: null, loaded: true }).stage, 'ready')
  for (const stage of ['no-order', 'existing', 'disputed', 'not-ready', 'loading', 'ready']) {
    const view = dispatchStageView(
      stage === 'no-order' ? null : { status: stage === 'disputed' ? 'DISPUTED' : stage === 'not-ready' ? 'PAID' : 'RECEIVED' },
      { assignment: stage === 'existing' ? { status: 'ASSIGNED' } : null, loaded: stage !== 'loading' && stage !== 'existing' })
    assert.ok(view.copy.length > 5)
    assert.ok(['ok', 'warn', 'risk', 'muted'].includes(view.tone))
    assert.equal(view.stage, stage)
  }
})

test('candidate rows and their empty state', () => {
  assert.deepEqual(candidateRowView({ technician_id: 7, label: '张师傅' }, 7), { id: 7, label: '张师傅', selected: true, actionLabel: '已选择该技师' })
  assert.equal(candidateRowView({ technician_id: 7 }, 0).actionLabel, '选择该技师')
  assert.equal(candidateRowView({ technician_id: 7 }, 0).label, '#7')
  assert.equal(candidateRowView(undefined, 0).selected, false)
  assert.deepEqual(dispatchCandidateCopy({ items: [], total: 0 }), {
    empty: true, emptyTitle: '暂无候选技师', emptyCopy: '暂无已绑定微信的有效技师，请联系管理员维护技师账号。', footer: '',
  })
  assert.equal(dispatchCandidateCopy({ items: [{}, {}], total: 3 }).footer, '候选技师 共 3 条 · 已加载 2 条')
  assert.equal(dispatchCandidateCopy(undefined).empty, true)
})

test('existing assignment facts use the assignment status table, not the order one', () => {
  const accepted = dispatchExistingFacts({ assignment: { status: 'ACCEPTED', technician_label: '张师傅', assigned_at: '2026-10-20T02:00:00Z', accepted_at: '2026-10-20T02:10:00Z' } })
  assert.equal(accepted.statusLabel, '已接单'); assert.equal(accepted.tone, 'ok')
  assert.match(accepted.technician, /张师傅/)
  assert.match(accepted.acceptedCopy, /接单时间/)
  assert.equal(accepted.pendingCopy, '')
  const pending = dispatchExistingFacts({ assignment: { status: 'ASSIGNED', technician_label: '李师傅', assigned_at: '2026-10-20T02:00:00Z', accepted_at: null } })
  assert.equal(pending.statusLabel, '待接单'); assert.equal(pending.tone, 'warn')
  assert.match(pending.pendingCopy, /尚未接单/)
  assert.equal(pending.acceptedCopy, '')
  const broken = dispatchExistingFacts({ assignment: { status: 'WAT', technician_label: '王师傅' } })
  assert.equal(broken.statusLabel, '派工状态未提供'); assert.equal(broken.tone, 'muted')
  assert.deepEqual(dispatchExistingFacts(null), { technician: '', statusLabel: '', tone: 'muted', assignedCopy: '', acceptedCopy: '', pendingCopy: '' })
})

test('submit copy names the technician or tells you to pick one', () => {
  assert.deepEqual(dispatchSubmitCopy(0, ''), { ready: false, label: '', hint: '请先选择一位技师，再进行二次确认。' })
  const picked = dispatchSubmitCopy(7, '张师傅')
  assert.equal(picked.ready, true); assert.equal(picked.label, '派工给「张师傅」')
  assert.match(picked.hint, /本人接单/)
  assert.equal(dispatchSubmitCopy(7, '').label, '派工给「#7」')
})

test('candidates are only fetched when the stage is ready and nothing is loaded', () => {
  assert.equal(shouldLoadCandidates('ready', { items: [], loaded: false, busy: false }), true)
  assert.equal(shouldLoadCandidates('loading', { items: [], loaded: false, busy: false }), false)
  assert.equal(shouldLoadCandidates('existing', { items: [], loaded: false, busy: false }), false)
  assert.equal(shouldLoadCandidates('ready', { items: [], loaded: true, busy: false }), false)
  assert.equal(shouldLoadCandidates('ready', { items: [], loaded: false, busy: true }), false)
  assert.equal(shouldLoadCandidates('ready', { items: [{}], loaded: false, busy: false }), false)
  assert.equal(shouldLoadCandidates('ready', undefined), true)
})

test('slot and dispatch pages render the shared mapping instead of inline branches', () => {
  for (const name of ['slots.vue', 'dispatch.vue']) {
    const page = readPage(name)
    assert.match(page, /merchant-scheduling-display\.js/, `${name} 必须走时段/派工展示映射`)
    assert.match(page, /ux-hero/, `${name} 缺少页面头部`)
    assert.match(page, /ux-card/, `${name} 缺少卡片原语`)
    assert.match(page, /toneClass\(/, `${name} 必须用统一徽标色调`)
  }
  const slots = readPage('slots.vue')
  assert.match(slots, /slotProjectOptions\(/)
  assert.match(slots, /slotRowView\(/)
  assert.match(slots, /data-testid="`slot-project-\$\{option\.id\}`"/)
  assert.match(slots, /data-testid="`merchant-slot-\$\{row\.item\.slot_id\}`"/)
  assert.match(slots, /data-testid="slots-empty"/)
  assert.doesNotMatch(slots, /projects\.items\.filter/)
  const dispatch = readPage('dispatch.vue')
  assert.match(dispatch, /dispatchStageView\(/)
  assert.match(dispatch, /shouldLoadCandidates\(/)
  assert.match(dispatch, /data-testid="dispatch-order-status"/)
  assert.match(dispatch, /data-testid="dispatch-existing-status"/)
  assert.match(dispatch, /data-testid="dispatch-submit"/)
  assert.match(dispatch, /data-testid="dispatch-message"/)
  assert.match(dispatch, /data-testid="`candidate-\$\{row\.item\.technician_id\}`"/)
  assert.match(dispatch, /data-testid="candidate-selected"/)
  // 阶段链与状态字面量不该再回到模板里。
  assert.doesNotMatch(dispatch, /v-else-if="order\.value\?\.status === 'DISPUTED'"/)
  assert.doesNotMatch(dispatch, /order\.value\.status !== 'RECEIVED'/)
})

test('both pages keep the retry affordances for the original failed request', () => {
  assert.match(readPage('slots.vue'), /publish\.retry/)
  assert.match(readPage('slots.vue'), /close\.retry/)
  assert.match(readPage('dispatch.vue'), /assign\.retry/)
})
