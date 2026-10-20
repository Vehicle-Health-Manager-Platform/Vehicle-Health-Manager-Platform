import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  PROTECTION_REQUIRED, REPORT_GROUPS, protectionExistingFacts, protectionItemRows, protectionPhotoStatus,
  protectionReadFailureView, protectionReportView, protectionSelectionState, protectionStageView,
  protectionSubmitView, protectionWriteView,
} from '../src/services/merchant-protection-display.js'

const readPage = name => readFileSync(resolve(import.meta.dirname, '../src/pages/merchant', name), 'utf8')
const photo = fileId => [{ kind: 'PROTECTION', fileId }]

test('protection stage keeps the original guard order', () => {
  assert.equal(protectionStageView(null).stage, 'no-order')
  // 已有记录优先于状态：提交过的订单不该再出现可编辑表单。
  assert.equal(protectionStageView({ order_status: 'DISPUTED', protection: { items: ['SEAT_COVER', 'STEERING_COVER'] } }).stage, 'existing')
  assert.equal(protectionStageView({ order_status: 'RECEIVED' }).stage, 'ready')
  assert.equal(protectionStageView({ order_status: 'IN_SERVICE' }).stage, 'ready')
  const disputed = protectionStageView({ order_status: 'DISPUTED' })
  assert.equal(disputed.stage, 'disputed'); assert.equal(disputed.tone, 'risk')
  assert.match(disputed.copy, /接车单/)
  const blocked = protectionStageView({ order_status: 'PAID' })
  assert.equal(blocked.stage, 'not-allowed'); assert.equal(blocked.tone, 'muted')
  assert.match(blocked.copy, /已接车或施工中/)
  for (const stage of ['no-order', 'existing', 'ready', 'disputed', 'not-allowed']) {
    const detail = stage === 'no-order' ? null
      : stage === 'existing' ? { order_status: 'RECEIVED', protection: {} }
        : { order_status: stage === 'disputed' ? 'DISPUTED' : stage === 'ready' ? 'RECEIVED' : 'PAID' }
    const view = protectionStageView(detail)
    assert.equal(view.stage, stage)
    assert.ok(view.copy.length > 5)
    assert.ok(['ok', 'warn', 'risk', 'muted'].includes(view.tone))
  }
  // 未知状态不能当成可提交。
  assert.equal(protectionStageView({ order_status: 'WAT' }).stage, 'not-allowed')
  assert.equal(protectionStageView({}).stage, 'not-allowed')
})

test('required items are exactly seat cover and steering cover', () => {
  assert.deepEqual(PROTECTION_REQUIRED, ['SEAT_COVER', 'STEERING_COVER'])
  const rows = protectionItemRows(['SEAT_COVER', 'FLOOR_MAT'])
  assert.deepEqual(rows.map(row => row.key), ['SEAT_COVER', 'STEERING_COVER', 'FLOOR_MAT', 'FENDER_COVER'])
  assert.deepEqual(rows.map(row => row.label), ['座椅套', '方向盘套', '一次性脚垫', '翼子板布'])
  assert.deepEqual(rows.map(row => row.required), [true, true, false, false])
  assert.deepEqual(rows.map(row => row.checked), [true, false, true, false])
  assert.equal(rows[1].actionLabel, '方向盘套（必选）')
  assert.equal(rows[3].actionLabel, '翼子板布')
  assert.deepEqual(protectionItemRows(undefined).map(row => row.checked), [false, false, false, false])
})

test('selection gaps are named, not counted', () => {
  const none = protectionSelectionState([], [])
  assert.equal(none.ready, false)
  assert.deepEqual(none.missingLabels, ['座椅套', '方向盘套'])
  assert.equal(none.photoReady, false)
  assert.equal(none.blockedCopy, '还需勾选：座椅套、方向盘套；还需 1 张防护照片。')
  const half = protectionSelectionState(['SEAT_COVER'], photo(0))
  assert.deepEqual(half.missingLabels, ['方向盘套'])
  assert.equal(half.blockedCopy, '还需勾选：方向盘套；还需 1 张防护照片。')
  const photoOnly = protectionSelectionState(['SEAT_COVER', 'STEERING_COVER'], [{ kind: 'PROTECTION', fileId: 0 }])
  assert.equal(photoOnly.photoReady, false)
  assert.equal(photoOnly.blockedCopy, '还需 1 张防护照片。')
  const ready = protectionSelectionState(['STEERING_COVER', 'SEAT_COVER', 'FLOOR_MAT'], photo(9))
  assert.equal(ready.ready, true); assert.equal(ready.blockedCopy, '')
  assert.equal(ready.selectedCount, 3)
  assert.deepEqual(ready.selectedLabels, ['方向盘套', '座椅套', '一次性脚垫'])
  // 其它类别的图不能顶替防护照片。
  assert.equal(protectionSelectionState(['SEAT_COVER', 'STEERING_COVER'], [{ kind: 'PROCESS', fileId: 3 }]).photoReady, false)
})

test('submit card explains why the button is not ready', () => {
  const blocked = protectionSubmitView([], [])
  assert.equal(blocked.ready, false); assert.equal(blocked.label, '确认提交施工防护')
  assert.match(blocked.blockedCopy, /座椅套/)
  assert.match(blocked.note, /技师无法报工/)
  const ready = protectionSubmitView(['SEAT_COVER', 'STEERING_COVER'], photo(4))
  assert.equal(ready.ready, true); assert.equal(ready.blockedCopy, '')
  assert.equal(protectionSubmitView(['SEAT_COVER', 'STEERING_COVER'], photo(4), { busy: true }).ready, false)
  const saved = protectionSubmitView(['SEAT_COVER', 'STEERING_COVER'], photo(4), { saved: {} })
  assert.equal(saved.ready, false)
  assert.equal(saved.blockedCopy, '防护记录已提交，不能重复提交。')
})

test('existing protection reads as labels plus a read-only note', () => {
  const facts = protectionExistingFacts({ items: ['SEAT_COVER', 'STEERING_COVER', 'FENDOR'], photo_file_id: 12, uploaded_at: '2026-10-20T02:00:00Z' })
  assert.deepEqual(facts.itemLabels, ['座椅套', '方向盘套', '未知防护项'])
  assert.equal(facts.uploadedCopy, '提交时间 2026-10-20 10:00（北京时间）')
  assert.equal(facts.photoFileId, 12); assert.equal(facts.canPreview, true)
  assert.match(facts.note, /不可修改/)
  const broken = protectionExistingFacts({ items: [], photo_file_id: 0 })
  assert.deepEqual(broken.itemLabels, [])
  assert.equal(broken.canPreview, false)
  assert.deepEqual(protectionExistingFacts(undefined).itemLabels, [])
})

test('report view separates submitted from signed', () => {
  const base = {
    status: 'SUBMITTED', submitted_at: '2026-10-20T03:00:00Z', repair_plan: '更换机油', fault_analysis: '机油劣化',
    no_fault_parts: true, fault_part_photos: [], no_parts: false, work_hours: 45,
    parts_used: [{ name: '机油', model: '5W-30', brand: '示例', quantity: 4 }],
    process_photos: [1, 2], finish_photos: [3], signature_file_id: 0,
  }
  const submitted = protectionReportView(base)
  assert.equal(submitted.exists, true)
  assert.equal(submitted.statusLabel, '待质检签字'); assert.equal(submitted.tone, 'warn')
  assert.equal(submitted.signed, false); assert.equal(submitted.signedCopy, '')
  assert.equal(submitted.submittedCopy, '已提交施工报工 · 2026-10-20 11:00（北京时间）')
  assert.equal(submitted.planCopy, '施工方案：更换机油')
  assert.equal(submitted.faultCopy, '故障分析：机油劣化')
  assert.equal(submitted.noFaultPartsCopy, '本次声明无故障件')
  assert.deepEqual(submitted.partsLines, ['机油 · 5W-30 · 示例 × 4'])
  assert.equal(submitted.noPartsCopy, '')
  assert.equal(submitted.hoursCopy, '实际工时 45 分钟')
  assert.deepEqual(submitted.groups.map(group => [group.key, group.files.length]), [['process_photos', 2], ['fault_part_photos', 0], ['finish_photos', 1]])

  const signed = protectionReportView({ ...base, status: 'SIGNED', signed_at: '2026-10-20T04:00:00Z', signature_file_id: 7, parts_used: [], no_parts: true, no_fault_parts: false, fault_part_photos: [5], work_hours: 0 })
  assert.equal(signed.statusLabel, '已质检签字'); assert.equal(signed.tone, 'ok')
  assert.equal(signed.signedCopy, '已质检签字 · 2026-10-20 12:00（北京时间）')
  assert.equal(signed.signatureFileId, 7)
  assert.deepEqual(signed.partsLines, [])
  assert.equal(signed.noPartsCopy, '本次声明未使用配件')
  assert.equal(signed.noFaultPartsCopy, '')
  assert.equal(signed.hoursCopy, '实际工时 0 分钟')

  const missing = protectionReportView(undefined)
  assert.equal(missing.exists, false); assert.deepEqual(missing.groups, [])
  assert.equal(missing.submittedCopy, '')
  // 未知状态当待签字，不能默认宣称已签字。
  assert.equal(protectionReportView({ status: 'WAT' }).signed, false)
  assert.deepEqual(REPORT_GROUPS.map(group => group.label), ['施工照', '故障件照', '完工照'])
  // 照片 id 非正数被过滤，避免把 0 当文件。
  assert.deepEqual(protectionReportView({ ...base, process_photos: [1, 0, -2] }).groups[0].files, [1])
})

test('read failure decides between relogin and retry', () => {
  const auth = protectionReadFailureView({ message: '登录已失效，请重新登录', failureKind: 'unauthorized' })
  assert.equal(auth.canRelogin, true); assert.equal(auth.canRetry, false)
  assert.equal(auth.title, '施工记录未能加载')
  assert.equal(protectionReadFailureView({ message: 'x', failureKind: 'forbidden' }).canRelogin, true)
  const retry = protectionReadFailureView({ message: '施工服务暂不可用', failureKind: 'unavailable' })
  assert.equal(retry.canRelogin, false); assert.equal(retry.canRetry, true)
  assert.equal(protectionReadFailureView(undefined).message, '')
})

test('photo status surfaces permission failures separately', () => {
  assert.deepEqual(protectionPhotoStatus({ message: '正在安全上传…', busy: true, failureKind: '' }), { message: '正在安全上传…', busy: true, needsPermission: false })
  assert.equal(protectionPhotoStatus({ failureKind: 'permission' }).needsPermission, true)
  assert.equal(protectionPhotoStatus(undefined).message, '')
  assert.equal(protectionPhotoStatus(undefined).busy, false)
})

test('write state keeps the retry wording honest', () => {
  const failed = protectionWriteView({ message: '车主尚未确认', failureKind: 'rejected', retry: true })
  assert.equal(failed.canRelogin, false); assert.equal(failed.retry, true)
  assert.equal(failed.message, '车主尚未确认')
  assert.match(failed.retryLabel, /不使用当前编辑内容/)
  assert.equal(protectionWriteView({ failureKind: 'forbidden' }).canRelogin, true)
  assert.equal(protectionWriteView({ saved: { order_id: 1 } }).saved, true)
  assert.equal(protectionWriteView(undefined).retry, false)
})

test('protection page renders the mapping and keeps its submit contract', () => {
  const page = readPage('protection.vue')
  for (const fn of ['protectionStageView', 'protectionItemRows', 'protectionSelectionState', 'protectionSubmitView', 'protectionExistingFacts', 'protectionReportView']) {
    assert.match(page, new RegExp(`${fn}\\(`), `protection.vue 必须使用 ${fn}`)
  }
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  // 阶段锚点与表单锚点。
  assert.match(page, /data-testid="protection-form"/)
  assert.match(page, /data-testid="protection-stage"/)
  assert.match(page, /data-testid="protection-items"/)
  assert.match(page, /data-testid="protection-submit"/)
  assert.match(page, /data-testid="protection-existing"/)
  assert.match(page, /data-testid="protection-report"/)
  // 提交仍走 protectionBody + confirmStillHolds 的二次确认与幂等。
  assert.match(page, /protectionBody\(order,\s*selected\.value/)
  assert.match(page, /confirmStillHolds\(/)
  assert.match(page, /save\.save\(\)/)
  assert.match(page, /save\.retry/)
  assert.match(page, /photoFlow\.choose/)
  // 旧模板的条件链与内联标签表不应残留。
  assert.doesNotMatch(page, /v-else-if="\['RECEIVED','IN_SERVICE'\]\.includes\(read\.value\.order_status\)"/)
  assert.doesNotMatch(page, /PROTECTION_LABELS\[index\]/)
  assert.doesNotMatch(page, /index<2\?'（必选）'/)
  assert.doesNotMatch(page, /process_photos:'施工照'/)
})
