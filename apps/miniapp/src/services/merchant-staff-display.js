/** 商家员工管理页的纯展示映射：筛选、行事实、行内动作、新增表单缺口、一次性员工码。
 *
 * 权限不在这里判定——`canManage` 来自服务端资料读接口的 `can_edit`，本模块只决定
 * "当前身份能看到的入口怎么显示"。启停/签发的合法性仍由 merchant-staff.js 与后端判定。 */
import { STAFF_ROLES, staffRoleLabel, staffStatusLabel } from './merchant-staff.js'

const say = (v, fallback) => (typeof v === 'string' && v.trim() ? v : fallback)
const num = (v, fallback = 0) => (Number.isFinite(v) ? v : fallback)
const list = v => (Array.isArray(v) ? v : [])
const bool = v => v === true

/** 三段筛选与 merchant-staff.js 的 STAFF_ROLES 同源（'' 表示全部）。 */
export const STAFF_FILTERS = [
  { role: '', label: '全部' },
  { role: 'STAFF', label: '店员' },
  { role: 'TECHNICIAN', label: '技师' },
]

export function staffFilterRows(activeRole) {
  // 只认服务端会返回的两个角色；未知/缺失一律落到"全部"，否则三个筛选一个都不高亮。
  const active = STAFF_ROLES.includes(activeRole) ? activeRole : ''
  return STAFF_FILTERS.map(row => ({ ...row, selected: row.role === active }))
}

/** 行事实：姓名 + 角色、账号 + 状态、手机与两个绑定标记。 */
export function staffRowView(item) {
  const role = say(item?.role, '')
  const status = say(item?.status, '')
  const phone = say(item?.phone_masked, '')
  return {
    staffId: num(item?.staff_id),
    titleCopy: `${say(item?.display_name, '姓名未提供')} · ${staffRoleLabel(role)}`,
    accountCopy: `账号 ${say(item?.account, '未提供')} · ${staffStatusLabel(status)}`,
    detailCopy: `${phone ? `手机 ${phone}` : '未登记手机号'} · 员工码${bool(item?.employee_code_issued) ? '已签发' : '未签发'} · 微信${bool(item?.wechat_bound) ? '已绑定' : '未绑定'}`,
    tone: status === 'ACTIVE' ? 'ok' : status === 'DISABLED' ? 'muted' : 'warn',
    isTechnician: role === 'TECHNICIAN',
    codeIssued: bool(item?.employee_code_issued),
    // 停用后原会话立即失效；技师还会连带撤销微信绑定，这句话必须按角色说准。
    disabledCopy: status === 'DISABLED'
      ? `已停用：原登录会话立即失效${role === 'TECHNICIAN' ? '，微信绑定同时撤销' : ''}。`
      : '',
  }
}

/** 行内动作：启停对所有角色可用，员工码只对技师且有签发/撤销两种形态。 */
export function staffRowActions(item, options = {}) {
  const busyId = num(options.busyId)
  const action = say(options.action, '')
  const staffId = num(item?.staff_id)
  const disabled = busyId !== 0
  const mine = busyId === staffId
  const actions = [{
    key: 'toggle',
    label: say(item?.status, '') === 'ACTIVE' ? '停用' : '启用',
    disabled,
    loading: mine && ['disable', 'enable'].includes(action),
  }]
  if (say(item?.role, '') === 'TECHNICIAN') {
    actions.push({
      key: 'issue',
      label: bool(item?.employee_code_issued) ? '重新签发员工码' : '签发员工码',
      disabled,
      loading: mine && action === 'issue',
    })
    if (bool(item?.employee_code_issued)) {
      actions.push({ key: 'revoke', label: '撤销员工码', disabled, loading: mine && action === 'revoke' })
    }
  }
  return actions
}

/** 一次性员工码卡：只在本行刚签发时出现，明示"只显示这一次"。 */
export function staffIssuedView(issued, staffId) {
  if (!issued || num(issued.staffId) !== num(staffId) || !say(issued.code, '')) {
    return { visible: false, label: '', code: '', hint: '' }
  }
  return {
    visible: true,
    label: '一次性员工码（只显示这一次）',
    code: say(issued.code, ''),
    hint: '请当面转达给技师；技师绑定后此码立即失效，刷新或离开页面后不再显示。',
  }
}

/** 新增表单：与 staffBody 的口径一致（店员必填手机号、密码含字母与数字）。 */
export function staffCreateFormView(form) {
  const role = say(form?.role, 'STAFF')
  const displayName = String(form?.displayName ?? '')
  const phone = String(form?.phone ?? '')
  const password = String(form?.password ?? '')
  const saved = form?.saved != null
  const missing = []
  if (!STAFF_ROLES.includes(role)) missing.push('身份')
  if ([...displayName].length < 2 || [...displayName].length > 32) missing.push('姓名（2–32 字）')
  if (role === 'STAFF' && !/^1[3-9][0-9]{9}$/.test(phone)) missing.push('本人手机号（店员必填，用于短信登录）')
  else if (phone && !/^1[3-9][0-9]{9}$/.test(phone)) missing.push('手机号（格式不正确）')
  if (password.length < 8 || password.length > 64 || !/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
    missing.push('初始密码（8–64 位，含字母与数字）')
  }
  return {
    role,
    roleLabel: staffRoleLabel(role),
    phonePlaceholder: role === 'STAFF' ? '本人手机号（店员必填）' : '手机号（可选）',
    phoneRequired: role === 'STAFF',
    missing,
    hint: missing.length ? `还需填写：${missing.join('、')}` : '',
    ready: missing.length === 0 && !saved,
    locked: form?.busy === true || saved,
    canSubmit: missing.length === 0 && form?.busy !== true && !saved,
    submitLabel: '创建员工',
    closeLabel: saved ? '返回本店员工' : '取消',
    message: say(form?.message, ''),
    messageTone: say(form?.message, '') ? (saved ? 'ok' : 'risk') : 'muted',
    passwordNotice: '账号由系统生成；短信登录需要本人手机号，因此店员必须填写。密码只在本次请求里出现，不会回显、不写日志。',
  }
}

/** 列表失败：越权给重新登录，其余给重试（只重试上一次的加载意图，不重置筛选）。 */
export function staffListFailureView(list_ = {}) {
  const kind = say(list_?.failureKind, '')
  const message = say(list_?.message, '')
  return {
    visible: message.length > 0,
    message,
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    retryLabel: '重试加载',
    tone: 'risk',
  }
}

/** 空态：店长看到"可先新增"，店员看到"请联系店长"。 */
export function staffEmptyView(list_ = {}, canManage) {
  const visible = list_?.loaded === true && list_?.busy !== true && list(list_?.items).length === 0
  return {
    visible,
    copy: visible
      ? `本店还没有店员或技师；${canManage === true ? '可先新增。' : '请联系店长添加。'}`
      : '',
    tone: 'muted',
  }
}

/** 只读提示：只有非店长才需要这句话。 */
export function staffPermissionView(canManage) {
  return {
    canManage: canManage === true,
    readOnlyNotice: canManage === true ? '' : '只有店长可以新增、启停员工或签发员工码；本页对店员只读。',
    emptyHint: canManage === true ? '可先新增。' : '请联系店长添加。',
  }
}

/** 分页：还有更多才显示按钮。 */
export function staffPagingView(list_ = {}) {
  return {
    visible: list(list_?.items).length < num(list_?.total) && list(list_?.items).length > 0,
    label: '加载更多员工',
    disabled: list_?.busy === true,
  }
}

/** 操作结果：成功与失败共用一处 message，语气按 failureKind 判断。 */
export function staffActionView(actions = {}) {
  const message = say(actions?.message, '')
  const kind = say(actions?.failureKind, '')
  return {
    visible: message.length > 0,
    message,
    tone: kind ? 'risk' : 'ok',
    canRelogin: kind === 'unauthorized' || kind === 'forbidden',
    busy: num(actions?.busyId) !== 0,
  }
}
