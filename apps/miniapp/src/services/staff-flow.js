import { STAFF_ROLES, staffBody, staffFailure, staffRoleLabel } from './merchant-staff.js'

// 员工列表、新增与启停/员工码的状态机。与 quote-flow.js 同一套写法：
// 每一次异步返回都重新核对 generation 与 token，账号切换或离开页面后的迟到响应一律丢弃。

export const initialStaffListState = () => ({ items: [], page: 0, total: 0, busy: false, loaded: false,
  role: '', message: '', failureKind: '' })

export function createStaffListFlow({ state, fetchPage, token }) {
  let generation = 0, retryMore = false
  function suspend() { generation++; state.busy = false }
  function reset() { suspend(); Object.assign(state, initialStaffListState()) }
  async function load(more = false) {
    if (state.busy || (more && (!state.loaded || state.items.length >= state.total))) return
    retryMore = more
    const current = ++generation, actor = token(), n = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      const result = await fetchPage(actor, n, state.role)
      if (current !== generation || actor !== token()) return
      state.items = more ? [...state.items, ...result.items] : result.items
      state.page = n; state.total = result.total; state.loaded = true
    } catch (error) {
      if (current !== generation || actor !== token()) return
      const safe = staffFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  function filter(role) {
    if (role && !STAFF_ROLES.includes(role)) return
    reset(); state.role = role; return load()
  }
  return { load, retry: () => load(retryMore), filter, suspend, reset }
}

export const initialStaffCreateState = () => ({ role: 'STAFF', displayName: '', phone: '', password: '',
  busy: false, saved: null, message: '', failureKind: '' })

export function createStaffCreateFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null
  function suspend() { generation++; state.busy = false }
  function reset() { suspend(); pending = null; Object.assign(state, initialStaffCreateState()) }
  async function save() {
    if (state.busy || state.saved) return
    let body
    try { body = staffBody(state) } catch (error) {
      const safe = staffFailure(error); state.message = safe.message; state.failureKind = safe.kind; return
    }
    // 重试同一份内容必须复用原幂等键；改了内容才换键，否则会拿旧响应当成功。
    const canonical = JSON.stringify(body)
    if (!pending || pending.canonical !== canonical) pending = { canonical, body, key: newKey() }
    const current = ++generation, actor = token()
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      const result = await api.create(actor, pending.body, pending.key)
      if (current !== generation || actor !== token()) return
      state.saved = result
      state.message = `已创建${staffRoleLabel(result.role)}「${result.display_name}」，账号 ${result.account}`
      // 明文密码只在请求里出现，成功后不再留在内存。
      state.password = ''
    } catch (error) {
      if (current !== generation || actor !== token()) return
      const safe = staffFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  return { save, suspend, reset }
}

export const initialStaffActionState = () => ({ busyId: 0, action: '', message: '', failureKind: '', issued: null })

export function createStaffActionFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null
  function suspend() { generation++; state.busyId = 0; state.action = '' }
  function reset() { suspend(); pending = null; Object.assign(state, initialStaffActionState()) }
  // 同一员工的同一意图复用同一个幂等键；换了员工或换了动作才换键。
  function keyFor(intent) {
    if (!pending || pending.intent !== intent) pending = { intent, key: newKey() }
    return pending.key
  }
  async function run(staffId, action, call) {
    if (state.busyId) return null
    const current = ++generation, actor = token()
    state.busyId = staffId; state.action = action; state.message = ''; state.failureKind = ''
    try {
      const result = await call(actor)
      if (current !== generation || actor !== token()) return null
      return result
    } catch (error) {
      if (current !== generation || actor !== token()) return null
      const safe = staffFailure(error); state.message = safe.message; state.failureKind = safe.kind; return null
    } finally { if (current === generation) { state.busyId = 0; state.action = '' } }
  }
  async function toggle(row) {
    const target = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    const result = await run(row.staff_id, target === 'DISABLED' ? 'disable' : 'enable', actor => target === 'DISABLED'
      ? api.disable(actor, row.staff_id, keyFor(`${row.staff_id}:DISABLED`))
      : api.enable(actor, row.staff_id, keyFor(`${row.staff_id}:ACTIVE`)))
    if (result) state.message = target === 'DISABLED'
      ? '员工已停用；原登录会话立即失效，技师微信绑定同时撤销'
      : '员工已启用；技师需重新用员工码绑定微信'
    return result
  }
  async function issue(row) {
    const result = await run(row.staff_id, 'issue', actor => api.issueCode(actor, row.staff_id))
    // 一次性明文码只保存在内存里，方便店长当面转达；离开页面即消失，不落盘。
    if (result) state.issued = { staffId: row.staff_id, code: result.employee_code }
    return result
  }
  async function revoke(row) {
    const result = await run(row.staff_id, 'revoke', actor => api.revokeCode(actor, row.staff_id))
    if (result) { state.issued = null; state.message = '员工码已撤销，原有微信绑定同时失效' }
    return result
  }
  return { toggle, issue, revoke, suspend, reset }
}
