/** 商家时段与派工的纯展示映射：容量、状态、阶段与候选都从这里出，页面不再写条件链。
 * 只吃已校验过的响应对象；服务端仍是唯一权威——这里决定"显示什么/解释什么"，不决定"允许什么"。 */
import { assignmentStatusLabel, ASSIGNMENT_STATUSES } from './technician-dispatch.js'
import { stateLabel } from './order-status.js'
import { displayTime } from './reservations.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const count = list => (Array.isArray(list) ? list.length : 0)

/** 时段行：开放/关闭/约满三种可读状态，容量用"剩余/总数"而不是两个裸数字。 */
export function slotRowView(row) {
  const open = row?.open === true
  const capacity = num(row?.capacity), left = num(row?.capacity_left)
  const full = open && left <= 0
  return {
    project: say(row?.project_name, '项目未提供'),
    window: `${displayTime(row?.starts_at)} 至 ${displayTime(row?.ends_at)}`,
    statusLabel: open ? '开放预约' : '已关闭',
    tone: open ? (full ? 'warn' : 'ok') : 'muted',
    capacityCopy: `剩余 ${left} / ${capacity}`,
    full,
    note: full ? '已约满，车主端不再显示可预约；如需继续接单请另发时段。' : open ? '' : '已关闭：不再接受新预约，已有订单保持原约定。',
  }
}

/** 时段列表空态与页脚。 */
export function slotListCopy(list) {
  const items = Array.isArray(list?.items) ? list.items : []
  if (items.length === 0) {
    return { empty: true, emptyTitle: '暂无本店预约时段', emptyCopy: '发布时段后车主才能预约；已关闭与约满的时段仍会列在这里。', footer: '' }
  }
  return { empty: false, emptyTitle: '', emptyCopy: '', footer: `本店时段 共 ${num(list?.total)} 条 · 已加载 ${items.length} 条` }
}

/** 可发布项目：只列在售且可用的项目，选中态一并算好，模板不再做 filter。 */
export function slotProjectOptions(items, selectedId) {
  return (Array.isArray(items) ? items : [])
    .filter(row => row?.available === true && row?.status === 1)
    .map(row => ({
      id: row.standard_project_id,
      label: say(row?.project_name, '项目未提供'),
      selected: row.standard_project_id === selectedId,
    }))
}

/** 发布表单的摘要与提示。ready 只用于提示，不阻止提交——真正校验在服务端与既有流程里。 */
export function slotFormFacts(form) {
  const project = num(form?.project)
  const capacity = say(form?.capacity, '')
  const capacityOk = /^([1-9]|[1-9]\d|100)$/.test(capacity)
  const hasProject = project > 0
  return {
    projectLabel: hasProject ? '已选择在售项目' : '尚未选择项目',
    windowCopy: `${say(form?.day, '日期未选')} ${say(form?.start, '--:--')} 至 ${say(form?.end, '--:--')}（北京时间）`,
    capacityCopy: `预约名额 ${capacityOk ? capacity : '需填 1–100'}`,
    ready: hasProject && capacityOk,
    hint: !hasProject ? '请先选择要发布的在售项目。' : capacityOk ? '发布后时间与容量不可编辑；同项目时段不可重叠。' : '预约名额需为 1–100 的整数。',
  }
}

/** 派工页阶段：把模板里 4 层 v-if/v-else-if 收成一个可断言的枚举。
 * 顺序不能改：先看已有派工，再看争议与未接车，最后才是"派工结果还没读到"。 */
export function dispatchStageView(order, assignment) {
  if (!order) return { stage: 'no-order', tone: 'muted', title: '订单尚未加载', copy: '正在读取订单；如长时间无响应请刷新。' }
  if (assignment?.assignment) return { stage: 'existing', tone: 'ok', title: '已有派工', copy: '一单仅一位技师；如需换人请联系管理员按后续流程处理。' }
  const status = say(order?.status, '')
  if (status === 'DISPUTED') return { stage: 'disputed', tone: 'risk', title: '争议中不可派工', copy: '车主已对接车单提出异议，派工与施工已阻断。请先在接车单中处理争议。' }
  if (status !== 'RECEIVED') return { stage: 'not-ready', tone: 'muted', title: `当前状态：${stateLabel(status)}`, copy: '尚未满足派工条件（需已接车且车主已确认）。' }
  if (assignment?.loaded !== true) return { stage: 'loading', tone: 'muted', title: '正在读取派工结果', copy: '读到派工结果之前不显示候选，避免对已有派工的订单重复提交。' }
  return { stage: 'ready', tone: 'warn', title: '可以派工', copy: '候选范围是已在本店完成微信绑定的在职技师；选择后需二次确认。' }
}

/** 候选技师行。 */
export function candidateRowView(row, selectedId) {
  const id = num(row?.technician_id)
  const selected = id > 0 && id === selectedId
  return { id, label: say(row?.label, `#${id}`), selected, actionLabel: selected ? '已选择该技师' : '选择该技师' }
}

/** 候选列表空态与页脚。 */
export function dispatchCandidateCopy(list) {
  const items = Array.isArray(list?.items) ? list.items : []
  if (items.length === 0) {
    return { empty: true, emptyTitle: '暂无候选技师', emptyCopy: '暂无已绑定微信的有效技师，请联系管理员维护技师账号。', footer: '' }
  }
  return { empty: false, emptyTitle: '', emptyCopy: '', footer: `候选技师 共 ${num(list?.total)} 条 · 已加载 ${items.length} 条` }
}

/** 已有派工的事实：状态用派工自己的标签表，不用订单状态。 */
export function dispatchExistingFacts(assignment) {
  const row = assignment?.assignment || null
  if (!row) return { technician: '', statusLabel: '', tone: 'muted', assignedCopy: '', acceptedCopy: '', pendingCopy: '' }
  const status = say(row.status, '')
  return {
    technician: `技师 ${say(row.technician_label, '未提供')}`,
    statusLabel: assignmentStatusLabel(status),
    tone: !ASSIGNMENT_STATUSES.includes(status) ? 'muted' : status === 'ACCEPTED' ? 'ok' : 'warn',
    assignedCopy: `派工时间 ${displayTime(row.assigned_at)}`,
    acceptedCopy: row.accepted_at ? `接单时间 ${displayTime(row.accepted_at)}` : '',
    pendingCopy: row.accepted_at ? '' : '技师尚未接单；接单前不能开始施工。',
  }
}

/** 提交按钮与提示：选中谁、能不能点，一句话说清。 */
export function dispatchSubmitCopy(selectedId, label) {
  const ready = num(selectedId) > 0
  return {
    ready,
    label: ready ? `派工给「${say(label, `#${num(selectedId)}`)}」` : '',
    hint: ready ? '确认后由该技师本人接单并开始施工，商家不能代接。' : '请先选择一位技师，再进行二次确认。',
  }
}

/** 候选列表是否需要加载：只有"可派工且还没有派工"时才拉，避免白跑一次请求。 */
export const shouldLoadCandidates = (stage, list) => stage === 'ready' && count(list?.items) === 0 && list?.loaded !== true && list?.busy !== true
