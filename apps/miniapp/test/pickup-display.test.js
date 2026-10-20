import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  canMarkDamage, damageRowLabel, DAMAGE_LIMIT, photoSlotView, pickupBaselineView, pickupDamageView,
  pickupFuelView, pickupMileageView, pickupOrderFacts, pickupPhotoProgress, pickupReadFailureView,
  pickupSubmitView, pickupWriteView,
} from '../src/services/pickup-display.js'
import { PICKUP_SLOTS } from '../src/services/pickup.js'

const readPage = name => readFileSync(resolve(import.meta.dirname, '../src/pages/merchant', name), 'utf8')
const photos = uploaded => Object.fromEntries(PICKUP_SLOTS.map((slot, index) => [slot, { fileId: index < uploaded ? index + 1 : 0, candidate: null, url: index < uploaded ? 'https://x' : '', message: '' }]))
const form = (extra = {}) => ({ code: '012345', mileage: '1000', mileageReason: '', arrivalReason: '', fuel: 'HALF', damageStatus: 'NONE', damages: [], photos: photos(8), ...extra })

test('each photo slot reads as empty, pending or uploaded with the matching action', () => {
  assert.deepEqual(photoSlotView('FUEL', { fileId: 0, candidate: null }), {
    slot: 'FUEL', label: '油表', uploaded: false, hasCandidate: false, statusLabel: '未上传', tone: 'muted',
    actionLabel: '拍照 / 选图并上传', canPreview: false, canRetryUpload: false, message: '',
  })
  const pending = photoSlotView('FRONT', { fileId: 0, candidate: { path: 'a.png' }, message: '已选择' })
  assert.equal(pending.statusLabel, '待上传'); assert.equal(pending.tone, 'warn')
  assert.equal(pending.canRetryUpload, true); assert.equal(pending.message, '已选择')
  const done = photoSlotView('ROOF', { fileId: 4, url: 'https://x', message: '上传成功' })
  assert.equal(done.statusLabel, '已上传'); assert.equal(done.tone, 'ok')
  assert.equal(done.actionLabel, '替换图片'); assert.equal(done.canPreview, true)
  assert.equal(photoSlotView('WAT', { fileId: 1 }).label, 'WAT')
  assert.equal(photoSlotView('FRONT', undefined).statusLabel, '未上传')
})

test('photo progress names the missing shots instead of leaving a bare count', () => {
  const full = pickupPhotoProgress({ photos: photos(8) })
  assert.equal(full.complete, true); assert.equal(full.uploadedCount, 8)
  assert.equal(full.copy, '已上传 8 / 8 张'); assert.equal(full.blockedCopy, '')
  const partial = pickupPhotoProgress({ photos: photos(6) })
  assert.equal(partial.complete, false); assert.equal(partial.uploadedCount, 6)
  assert.equal(partial.copy, '已上传 6 / 8 张')
  // 缺片按 PICKUP_SLOTS 的顺序列出，不是按用户操作顺序。
  assert.deepEqual(partial.missingLabels, ['内饰', '油表'])
  assert.equal(partial.blockedCopy, '还差 2 张：内饰、油表')
  assert.equal(pickupPhotoProgress(undefined).uploadedCount, 0)
  assert.equal(pickupPhotoProgress(undefined).blockedCopy, '还差 8 张：车前、车后、车左、车右、车顶、仪表盘、内饰、油表')
})

test('mileage delta explains itself and flags a rollback', () => {
  assert.deepEqual(pickupMileageView('', 1000), {
    value: null, valid: false, delta: null, needsReason: false, tone: 'muted', deltaCopy: '', hint: '请输入 0–9999999 的整数里程。',
  })
  const same = pickupMileageView('1000', 1000)
  assert.equal(same.delta, 0); assert.equal(same.needsReason, false); assert.equal(same.tone, 'ok')
  assert.equal(same.deltaCopy, '与当前基线差值 0 km；提交时重新比对')
  assert.match(same.hint, /再次比对/)
  const back = pickupMileageView('900', 1000)
  assert.equal(back.delta, -100); assert.equal(back.needsReason, true); assert.equal(back.tone, 'risk')
  assert.equal(back.deltaCopy, '与当前基线差值 -100 km；提交时重新比对')
  assert.match(back.hint, /回退原因/)
  // 超过 7 位与负数都不是合法里程。
  for (const bad of ['10000000', '-1', '12.5', 'abc']) assert.equal(pickupMileageView(bad, 1000).valid, false, bad)
  assert.equal(pickupMileageView('9999999', 0).delta, 9999999)
})

test('baseline copy says where the number came from', () => {
  assert.deepEqual(pickupBaselineView({ mileage: 1234, source: 'ARCHIVE' }), { value: 1234, sourceLabel: '最近档案', known: true, copy: '比较里程 1234 km（最近档案）' })
  assert.equal(pickupBaselineView({ mileage: 20, source: 'VEHICLE' }).copy, '比较里程 20 km（车辆里程）')
  const missing = pickupBaselineView(undefined)
  assert.equal(missing.value, 0); assert.equal(missing.known, false)
  assert.equal(missing.copy, '比较里程 0 km（车辆里程）')
})

test('fuel picker gets an index, an unset fuel does not pretend to be empty', () => {
  assert.deepEqual(pickupFuelView('HALF'), { chosen: true, index: 2, label: '½', tone: 'ok' })
  assert.deepEqual(pickupFuelView('FULL'), { chosen: true, index: 4, label: '满', tone: 'ok' })
  const empty = pickupFuelView('')
  assert.equal(empty.chosen, false); assert.equal(empty.label, '请选择油量')
  assert.equal(empty.index, 0); assert.equal(empty.tone, 'muted')
  assert.equal(pickupFuelView('WAT').label, '请选择油量')
})

test('damage choice must be explicit and the 20 mark cap is stated up front', () => {
  assert.equal(DAMAGE_LIMIT, 20)
  const unset = pickupDamageView(form({ damageStatus: '' }))
  assert.equal(unset.chosen, false); assert.equal(unset.statusLabel, '尚未选择'); assert.equal(unset.tone, 'muted')
  assert.equal(unset.canMark, false); assert.match(unset.copy, /必须明确选择/)
  const none = pickupDamageView(form({ damageStatus: 'NONE' }))
  assert.equal(none.tone, 'ok'); assert.equal(none.canMark, false); assert.match(none.copy, /已选择无损伤/)
  const present = pickupDamageView(form({ damageStatus: 'PRESENT', damages: [{ photo_slot: 'LEFT', x: 0.5, y: 0.5, note: '划痕' }, { photo_slot: 'FRONT', x: 0.1, y: 0.2, note: '掉漆' }] }))
  assert.equal(present.tone, 'warn'); assert.equal(present.count, 2); assert.equal(present.remaining, 18)
  assert.equal(present.canMark, true)
  assert.equal(present.copy, '已标注 2 / 20 处；点击车身、仪表盘、内饰照片添加标注，油表照片不参与损伤标注。')
  const capped = pickupDamageView(form({ damageStatus: 'PRESENT', damages: Array.from({ length: 20 }, () => ({ photo_slot: 'LEFT', x: 0.5, y: 0.5, note: 'x' })) }))
  assert.equal(capped.canMark, false); assert.equal(capped.remaining, 0)
  assert.equal(pickupDamageView(undefined).count, 0)
})

test('a mark is only accepted on an uploaded non-fuel photo of a present damage with quota left', () => {
  const ready = form({ damageStatus: 'PRESENT' })
  assert.equal(canMarkDamage('LEFT', ready, { fileId: 3, url: 'https://x' }), true)
  // 油表照片永远不能落损伤点。
  assert.equal(canMarkDamage('FUEL', ready, { fileId: 8, url: 'https://x' }), false)
  assert.equal(canMarkDamage('LEFT', form({ damageStatus: 'NONE' }), { fileId: 3, url: 'https://x' }), false)
  assert.equal(canMarkDamage('LEFT', ready, { fileId: 3, url: '' }), false)
  assert.equal(canMarkDamage('LEFT', ready, { fileId: 0, url: 'https://x' }), false)
  const full = form({ damageStatus: 'PRESENT', damages: Array.from({ length: 20 }, () => ({ photo_slot: 'LEFT', x: 0.5, y: 0.5, note: 'x' })) })
  assert.equal(canMarkDamage('LEFT', full, { fileId: 3, url: 'https://x' }), false)
  assert.equal(canMarkDamage('LEFT', undefined, { fileId: 3, url: 'https://x' }), false)
})

test('damage rows are titled by slot and ordinal', () => {
  assert.equal(damageRowLabel({ photo_slot: 'LEFT' }, 1), '车左 · 标注 2')
  assert.equal(damageRowLabel({ photo_slot: 'DASHBOARD' }, 0), '仪表盘 · 标注 1')
  assert.equal(damageRowLabel({ photo_slot: 'WAT' }, 0), '未知位置 · 标注 1')
  assert.equal(damageRowLabel(undefined, undefined), '未知位置 · 标注 1')
})

test('order facts fall back instead of printing nulls', () => {
  const facts = pickupOrderFacts({
    order: { project_snapshot: { project_name: '小保养' }, appointment_snapshot: { starts_at: '2026-10-21T02:00:00Z' } },
    mileage_baseline: { mileage: 1234, source: 'ARCHIVE' },
  })
  assert.equal(facts.project, '小保养')
  assert.equal(facts.appointmentCopy, '预约 2026-10-21 10:00（北京时间）')
  assert.equal(facts.baseline.copy, '比较里程 1234 km（最近档案）')
  assert.match(facts.guide, /八张/)
  assert.match(facts.guide, /油表照片须独立/)
  const empty = pickupOrderFacts(undefined)
  assert.equal(empty.project, '项目未提供')
  assert.equal(empty.appointmentCopy, '预约时间未提供')
  assert.equal(empty.baseline.value, 0)
})

test('read failure separates the already-submitted case from a plain retry', () => {
  const conflict = pickupReadFailureView({ message: '接车单状态已变化，请刷新查看', failureKind: 'conflict' })
  assert.equal(conflict.conflict, true); assert.equal(conflict.title, '接车单已存在'); assert.equal(conflict.tone, 'risk')
  const other = pickupReadFailureView({ message: '接车服务暂不可用', failureKind: 'unavailable' })
  assert.equal(other.conflict, false); assert.equal(other.title, '订单未能加载')
  assert.equal(other.retryLabel, '重试加载')
  const silent = pickupReadFailureView(undefined)
  assert.equal(silent.message, ''); assert.equal(silent.conflict, false)
})

test('submit stays disabled until all eight photos exist, and says why', () => {
  const partial = pickupSubmitView(form({ photos: photos(7) }), {})
  assert.equal(partial.ready, false); assert.equal(partial.saved, false)
  assert.equal(partial.label, '提交完整接车单')
  assert.equal(partial.disabledCopy, '还差 1 张：油表')
  assert.match(partial.note, /仍需车主确认和派工/)
  const ready = pickupSubmitView(form(), {})
  assert.equal(ready.ready, true); assert.equal(ready.disabledCopy, '')
  assert.equal(pickupSubmitView(form(), { busy: true }).ready, false)
  const saved = pickupSubmitView(form(), { saved: {} })
  assert.equal(saved.saved, true); assert.equal(saved.ready, false)
  assert.equal(saved.disabledCopy, '接车单已提交，不能重复提交。')
})

test('write state keeps "retry the original request" honest about not using edits', () => {
  const failed = pickupWriteView({ message: '登录已失效，请重新登录', failureKind: 'unauthorized', retry: true, saved: null })
  assert.equal(failed.canRelogin, true); assert.equal(failed.retry, true); assert.equal(failed.saved, false)
  assert.match(failed.retryLabel, /不使用当前编辑内容/)
  assert.equal(failed.message, '登录已失效，请重新登录')
  const rejected = pickupWriteView({ message: '预约码无效', failureKind: 'rejected' })
  assert.equal(rejected.canRelogin, false)
  assert.equal(pickupWriteView(undefined).retry, false)
  assert.equal(pickupWriteView({ saved: { pickup_check_id: 1 } }).saved, true)
})

test('pickup page renders the mapping and keeps its anchors and submit contract', () => {
  const page = readPage('pickup.vue')
  // 展示走映射，模板不再自己算三态与文案。
  for (const fn of ['photoSlotView', 'pickupPhotoProgress', 'pickupMileageView', 'pickupFuelView', 'pickupDamageView', 'pickupSubmitView']) {
    assert.match(page, new RegExp(`${fn}\\(`), `pickup.vue 必须使用 ${fn}`)
  }
  assert.match(page, /canMarkDamage\(/)
  assert.match(page, /damageRowLabel\(/)
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  // 既有锚点与照片元素类名是端到端流程的依赖，必须保留。
  assert.match(page, /data-testid="pickup-form"/)
  assert.match(page, /data-testid="`pickup-slot-\$\{row\.slot\}`"/)
  assert.match(page, /:class="`pickup-photo-\$\{row\.slot\}`"/)
  // 提交仍先过 pickupBody 校验，再走原有幂等提交流程。
  assert.match(page, /pickupBody\(order,\s*form\)/)
  assert.match(page, /save\.save\(\)/)
  assert.match(page, /save\.retry/)
  assert.match(page, /flow\.load/)
  // 旧模板里的条件链与文案搬走后不应残留。
  assert.doesNotMatch(page, /form\.damages\.length >= 20/)
  assert.doesNotMatch(page, /form\.photos\[slot\]\.fileId \? '替换图片'/)
  assert.doesNotMatch(page, /点击上方车身\/仪表盘\/内饰照片标注损伤位置/)
})
