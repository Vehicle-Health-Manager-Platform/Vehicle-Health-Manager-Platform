/** 施工防护的纯展示映射：阶段、勾选、提交前置、已提交记录与报工记录都从这里出。
 * 只读已校验的施工记录与本地表单，不改变"允许什么"——必选项、照片与状态仍由 protectionBody 与后端判定。*/
import { PROTECTION_ITEMS, PROTECTION_LABELS } from './service-work.js'
import { displayTime } from './reservations.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const list = v => (Array.isArray(v) ? v : [])

/** 座椅套与方向盘套是硬性必选（与 service-work.js 的 items() 同源）。 */
export const PROTECTION_REQUIRED = ['SEAT_COVER', 'STEERING_COVER']

/** 报工照片三组，标签沿用页面既有措辞（与 PHOTO_LABELS 不是同一套，别混用）。 */
export const REPORT_GROUPS = [
  { key: 'process_photos', label: '施工照' },
  { key: 'fault_part_photos', label: '故障件照' },
  { key: 'finish_photos', label: '完工照' },
]

const itemLabel = item => {
  const index = PROTECTION_ITEMS.indexOf(item)
  return index >= 0 ? PROTECTION_LABELS[index] : '未知防护项'
}

/** 阶段：已有记录优先，其次可提交状态，再是争议，最后是不可新增。
 * 顺序即业务规则，不能调换——已有记录的订单不该再显示可编辑表单。*/
export function protectionStageView(detail) {
  if (!detail) return { stage: 'no-order', tone: 'muted', title: '施工记录尚未加载', copy: '正在读取订单与施工记录，如长时间无响应请刷新。' }
  if (detail.protection) {
    return { stage: 'existing', tone: 'ok', title: '已提交施工防护', copy: '本次记录只读：下方展示已提交的防护内容与报工记录，表单不再出现。' }
  }
  const status = say(detail.order_status, '')
  if (status === 'RECEIVED' || status === 'IN_SERVICE') {
    return { stage: 'ready', tone: 'warn', title: '可以提交施工防护', copy: '勾选实际完成的防护项并上传 1 张照片；必选项缺一不可。' }
  }
  if (status === 'DISPUTED') {
    return { stage: 'disputed', tone: 'risk', title: '争议中不可提交', copy: '订单存在争议，请在接车单处理并等待车主复核后再防护。' }
  }
  return { stage: 'not-allowed', tone: 'muted', title: '当前状态不能新增防护记录', copy: '只有已接车或施工中的订单可以提交施工防护。' }
}

/** 勾选行：必选项、文案、当前是否选中一次算好，模板不再算下标。 */
export function protectionItemRows(selected) {
  const picked = list(selected)
  return PROTECTION_ITEMS.map((key, index) => ({
    key,
    label: PROTECTION_LABELS[index],
    required: PROTECTION_REQUIRED.includes(key),
    checked: picked.includes(key),
    actionLabel: `${PROTECTION_LABELS[index]}${PROTECTION_REQUIRED.includes(key) ? '（必选）' : ''}`,
  }))
}

/** 提交前置：缺哪几项、照片有没有传上去，合成一句能照着做的提示。 */
export function protectionSelectionState(selected, files) {
  const picked = list(selected)
  const missingRequired = PROTECTION_REQUIRED.filter(key => !picked.includes(key))
  const photoReady = list(files).some(file => file?.kind === 'PROTECTION' && num(file?.fileId) > 0)
  const gaps = []
  if (missingRequired.length) gaps.push(`还需勾选：${missingRequired.map(itemLabel).join('、')}`)
  if (!photoReady) gaps.push('还需 1 张防护照片')
  return {
    selectedCount: picked.length,
    selectedLabels: picked.map(itemLabel),
    missingRequired,
    missingLabels: missingRequired.map(itemLabel),
    photoReady,
    ready: gaps.length === 0,
    blockedCopy: gaps.length ? `${gaps.join('；')}。` : '',
  }
}

/** 提交卡：按钮文案、能否点、不能点时的原因。 */
export function protectionSubmitView(selected, files, flags = {}) {
  const state = protectionSelectionState(selected, files)
  const busy = flags?.busy === true
  const saved = !!flags?.saved
  return {
    state,
    ready: state.ready && !busy && !saved,
    label: '确认提交施工防护',
    blockedCopy: saved ? '防护记录已提交，不能重复提交。' : state.blockedCopy,
    note: '未上传防护照片，技师无法报工。车主尚未确认或有未解决争议时，提交会被拒绝。',
  }
}

/** 已提交记录：勾了哪些项、什么时候传的、照片 id 与不可修改的说明。 */
export function protectionExistingFacts(protection) {
  const items = list(protection?.items)
  const fileId = num(protection?.photo_file_id)
  return {
    itemLabels: items.map(itemLabel),
    uploadedCopy: `提交时间 ${displayTime(protection?.uploaded_at)}`,
    photoFileId: fileId,
    canPreview: fileId > 0,
    note: '防护记录不可修改。技师报工时仍会重新校验防护与车主确认。',
  }
}

/** 报工记录：状态、签字、方案/分析、配件与工时，字段缺失只隐藏那一行。 */
export function protectionReportView(report) {
  if (!report) {
    return {
      exists: false, submittedCopy: '', statusLabel: '', tone: 'muted', signed: false, signedCopy: '',
      signatureFileId: 0, planCopy: '', faultCopy: '', noFaultPartsCopy: '', partsLines: [], noPartsCopy: '', hoursCopy: '',
      groups: [],
    }
  }
  const signed = say(report.status, '') === 'SIGNED'
  return {
    exists: true,
    submittedCopy: `已提交施工报工 · ${displayTime(report.submitted_at)}`,
    statusLabel: signed ? '已质检签字' : '待质检签字',
    tone: signed ? 'ok' : 'warn',
    signed,
    signedCopy: signed ? `已质检签字 · ${displayTime(report.signed_at)}` : '',
    signatureFileId: num(report.signature_file_id),
    planCopy: `施工方案：${say(report.repair_plan, '未提供')}`,
    faultCopy: `故障分析：${say(report.fault_analysis, '未提供')}`,
    noFaultPartsCopy: report.no_fault_parts === true ? '本次声明无故障件' : '',
    partsLines: list(report.parts_used).map(part => `${say(part?.name, '未命名')} · ${say(part?.model, '—')} · ${say(part?.brand, '—')} × ${num(part?.quantity)}`),
    noPartsCopy: report.no_parts === true ? '本次声明未使用配件' : '',
    hoursCopy: `实际工时 ${num(report.work_hours)} 分钟`,
    groups: REPORT_GROUPS.map(group => ({ ...group, files: list(report[group.key]).map(id => num(id)).filter(id => id > 0) })),
  }
}

/** 读取失败：能不能重登、能不能重试。 */
export function protectionReadFailureView(read) {
  const kind = say(read?.failureKind, '')
  return {
    message: say(read?.message, ''),
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    canRetry: kind !== 'unauthorized' && kind !== 'forbidden',
    tone: 'risk',
    title: '施工记录未能加载',
  }
}

/** 提交失败与重试：原请求重试不使用当前编辑内容，说清楚才不会误点。 */
export function protectionWriteView(write) {
  const kind = say(write?.failureKind, '')
  return {
    message: say(write?.message, ''),
    saved: !!write?.saved,
    retry: !!write?.retry,
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    retryLabel: '重试原防护提交请求（不使用当前编辑内容）',
  }
}

/** 照片区状态：上传中/失败要说清楚，权限类失败另给开启入口。 */
export function protectionPhotoStatus(photos) {
  return {
    message: say(photos?.message, ''),
    busy: photos?.busy === true,
    needsPermission: say(photos?.failureKind, '') === 'permission',
  }
}
