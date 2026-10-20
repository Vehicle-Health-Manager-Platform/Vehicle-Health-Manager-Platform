/** 接车检查的纯展示映射：照片进度、里程比对、损伤上限与提交前置都从这里出。
 * 只读 form 与已校验的上下文，不改变"允许什么"——八张独立图片、损伤坐标范围等硬规则仍在 pickup.js 与后端。*/
import { PICKUP_SLOTS, PICKUP_LABELS, FUEL_VALUES, FUEL_LABELS } from './pickup.js'
import { displayTime } from './reservations.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const list = v => (Array.isArray(v) ? v : [])

/** 与 pickup.js 的 damages>20 判定同源，这里只用于提前提示。 */
export const DAMAGE_LIMIT = 20

/** 单张照片槽：未上传 / 已选待上传 / 已上传 三态，按钮文案与徽标一起给出。 */
export function photoSlotView(slot, photo) {
  const uploaded = num(photo?.fileId) > 0
  const hasCandidate = !!photo?.candidate
  return {
    slot,
    label: PICKUP_LABELS[slot] || slot,
    uploaded,
    hasCandidate,
    statusLabel: uploaded ? '已上传' : hasCandidate ? '待上传' : '未上传',
    tone: uploaded ? 'ok' : hasCandidate ? 'warn' : 'muted',
    actionLabel: uploaded ? '替换图片' : '拍照 / 选图并上传',
    canPreview: uploaded,
    canRetryUpload: hasCandidate,
    message: say(photo?.message, ''),
  }
}

/** 八张的总体进度。缺哪几张要列出来，否则用户只能靠数。 */
export function pickupPhotoProgress(form) {
  const photos = form?.photos || {}
  const missing = PICKUP_SLOTS.filter(slot => num(photos[slot]?.fileId) <= 0)
  const total = PICKUP_SLOTS.length
  const uploadedCount = total - missing.length
  const missingLabels = missing.map(slot => PICKUP_LABELS[slot] || slot)
  return {
    total,
    uploadedCount,
    complete: missing.length === 0,
    copy: `已上传 ${uploadedCount} / ${total} 张`,
    missingLabels,
    blockedCopy: missing.length ? `还差 ${missing.length} 张：${missingLabels.join('、')}` : '',
  }
}

/** 基线里程：来源决定措辞，数值缺省按 0 显示但会标出来源缺失。 */
export function pickupBaselineView(baseline) {
  const value = num(baseline?.mileage)
  const sourceLabel = baseline?.source === 'ARCHIVE' ? '最近档案' : '车辆里程'
  return { value, sourceLabel, known: Number.isFinite(baseline?.mileage), copy: `比较里程 ${value} km（${sourceLabel}）` }
}

/** 手填里程与基线的差值。低于基线必须写原因——这里只提示，服务端提交时重新比对。 */
export function pickupMileageView(mileage, baselineMileage) {
  const text = typeof mileage === 'string' ? mileage.trim() : String(num(mileage, ''))
  const valid = /^\d{1,7}$/.test(text)
  const base = num(baselineMileage)
  const value = valid ? Number(text) : null
  const delta = valid ? value - base : null
  const needsReason = valid && delta < 0
  return {
    value,
    valid,
    delta,
    needsReason,
    tone: needsReason ? 'risk' : valid ? 'ok' : 'muted',
    deltaCopy: valid ? `与当前基线差值 ${delta} km；提交时重新比对` : '',
    hint: !valid
      ? '请输入 0–9999999 的整数里程。'
      : needsReason
        ? '里程低于基线，请在下方填写回退原因。'
        : '提交时服务端会再次比对基线里程。',
  }
}

/** 油量：picker 只吃下标，文案与下标一起算好，模板不再做 indexOf。 */
export function pickupFuelView(fuel) {
  const index = FUEL_VALUES.indexOf(fuel)
  const chosen = index >= 0
  return {
    chosen,
    index: chosen ? index : 0,
    label: chosen ? FUEL_LABELS[index] : '请选择油量',
    tone: chosen ? 'ok' : 'muted',
  }
}

/** 损伤选择与标注额度：必须明确二选一，最多 20 处，油表不参与标注。 */
export function pickupDamageView(form) {
  const status = form?.damageStatus
  const total = list(form?.damages).length
  const chosen = status === 'NONE' || status === 'PRESENT'
  return {
    status,
    chosen,
    statusLabel: status === 'NONE' ? '已选：无损伤' : status === 'PRESENT' ? '已选：有损伤' : '尚未选择',
    tone: status === 'PRESENT' ? 'warn' : status === 'NONE' ? 'ok' : 'muted',
    count: total,
    limit: DAMAGE_LIMIT,
    remaining: Math.max(0, DAMAGE_LIMIT - total),
    canMark: status === 'PRESENT' && total < DAMAGE_LIMIT,
    copy: status === 'PRESENT'
      ? `已标注 ${total} / ${DAMAGE_LIMIT} 处；点击车身、仪表盘、内饰照片添加标注，油表照片不参与损伤标注。`
      : status === 'NONE'
        ? '已选择无损伤；如需标注请改为"有损伤"，最多 20 处。'
        : '必须明确选择有无损伤，选择"有损伤"后才能点击照片标注位置。',
  }
}

/** 某张照片此刻能不能落标注：油表不行、无损伤不行、超额不行、图片未上传不行。 */
export function canMarkDamage(slot, form, photo) {
  if (slot === 'FUEL') return false
  if (form?.damageStatus !== 'PRESENT') return false
  if (list(form?.damages).length >= DAMAGE_LIMIT) return false
  return num(photo?.fileId) > 0 && typeof photo?.url === 'string' && photo.url.length > 0
}

/** 损伤行的标题："车左 · 标注 2"，不靠模板拼字符串。 */
export const damageRowLabel = (damage, index) => `${PICKUP_LABELS[damage?.photo_slot] || '未知位置'} · 标注 ${num(index) + 1}`

/** 上下文事实：项目、预约时间与基线一次算好。 */
export function pickupOrderFacts(context) {
  const order = context?.order || {}
  const startsAt = order?.appointment_snapshot?.starts_at
  return {
    project: say(order?.project_snapshot?.project_name, '项目未提供'),
    appointmentCopy: startsAt ? `预约 ${displayTime(startsAt)}` : '预约时间未提供',
    baseline: pickupBaselineView(context?.mileage_baseline),
    guide: '逐项拍摄或选择真实照片，八张全部上传后提交，油表照片须独立于仪表盘。',
  }
}

/** 读取失败：冲突要去查看已提交接车单，其余给重试。 */
export function pickupReadFailureView(read) {
  const message = say(read?.message, '')
  return {
    message,
    conflict: read?.failureKind === 'conflict',
    tone: 'risk',
    title: read?.failureKind === 'conflict' ? '接车单已存在' : '订单未能加载',
    retryLabel: '重试加载',
  }
}

/** 提交按钮：缺片先报缺口，提交态与已保存态各自收口。 */
export function pickupSubmitView(form, flags = {}) {
  const progress = pickupPhotoProgress(form)
  const busy = flags?.busy === true
  const saved = !!flags?.saved
  return {
    progress,
    busy,
    saved,
    ready: progress.complete && !busy && !saved,
    label: '提交完整接车单',
    disabledCopy: saved ? '接车单已提交，不能重复提交。' : progress.blockedCopy,
    note: '提交后仍需车主确认和派工，本步不能开始施工。',
  }
}

/** 提交失败与重试：原请求重试不使用当前编辑内容，说清楚才不会误点。 */
export function pickupWriteView(write) {
  const kind = say(write?.failureKind, '')
  return {
    message: say(write?.message, ''),
    saved: !!write?.saved,
    retry: !!write?.retry,
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    retryLabel: '重试原接车提交请求（不使用当前编辑内容）',
    savedLabel: '查看接车单',
  }
}
