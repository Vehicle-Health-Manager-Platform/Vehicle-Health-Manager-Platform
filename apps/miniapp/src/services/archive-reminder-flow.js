import { reminderBody, ArchiveReminderError } from './archive-reminders.js'

export function initialReminderListState() { return { vehicleId: 0, rows: [], currentMileage: null, today: '', busy: false, loaded: false, message: '', kind: '' } }
export function createReminderListFlow({ state, api, token }) {
  let generation = 0, visible = false
  function clear() { generation++; Object.assign(state, initialReminderListState()) }
  async function load() {
    if (state.busy || !visible || !state.vehicleId) return
    const current = ++generation, owner = token(), vehicle = state.vehicleId
    state.busy = true; state.rows = []; state.loaded = false; state.message = ''; state.kind = ''
    try {
      const result = await api.list(owner, vehicle)
      if (current !== generation || owner !== token() || vehicle !== state.vehicleId || !visible) return
      state.rows = result.list; state.currentMileage = result.current_mileage; state.today = result.today; state.loaded = true
    } catch (e) { if (current === generation && owner === token()) { state.message = e.message || '提醒加载失败，请重试'; state.kind = e.kind || 'server' } }
    finally { if (current === generation) state.busy = false }
  }
  return {
    load,
    select(vehicle) { clear(); state.vehicleId = vehicle || 0; if (visible) return load() },
    resume(vehicle) { visible = true; return this.select(vehicle) },
    suspend() { visible = false; clear() },
    reset() { clear() },
  }
}
export function initialReminderEditState() { return { context: null, date: '', mileage: '', busy: false, loaded: false, message: '', kind: '' } }
export function createReminderEditFlow({ state, api, token, newKey, confirmReplacement, confirmDisable }) {
  let generation = 0, visible = false, archive = 0, vehicle = 0, pending = null
  const valid = (current, owner) => current === generation && owner === token() && visible
  function suspend() { generation++; visible = false; state.context = null; state.loaded = false; state.busy = false; state.date = ''; state.mileage = '' }
  async function load() {
    if (state.busy || !visible) return
    const current = ++generation, owner = token()
    state.busy = true; state.context = null; state.loaded = false; state.message = ''; state.kind = ''; state.date = ''; state.mileage = ''
    try {
      const context = await api.context(owner, archive, vehicle)
      if (!valid(current, owner)) return
      state.context = context; state.loaded = true
      if (context.setting.archive_id === archive) { state.date = context.setting.due_date || ''; state.mileage = context.setting.due_mileage === null ? '' : String(context.setting.due_mileage) }
    } catch (e) { if (valid(current, owner)) { state.message = e.message || '设置加载失败，请重试'; state.kind = e.kind || 'server' } }
    finally { if (current === generation) state.busy = false }
  }
  async function save(active = true) {
    if (state.busy || !visible || !state.context || state.kind === 'conflict') return
    const current = ++generation, owner = token(), context = state.context
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const replacing = active && context.setting.revision > 0 && context.setting.archive_id !== archive
      let body = reminderBody(context, { date: state.date, mileage: state.mileage }, active, replacing)
      if (replacing && !(await confirmReplacement())) return
      if (!active && !(await confirmDisable())) return
      if (!valid(current, owner)) return
      const canonical = JSON.stringify(body)
      if (!pending || pending.owner !== owner || pending.archive !== archive || pending.canonical !== canonical) pending = { owner, archive, canonical, key: newKey() }
      const operation = pending
      await api.save(owner, context, body, operation.key)
      if (!valid(current, owner)) return
      if (pending === operation) pending = null
      state.busy = false
      await load()
      if (visible && owner === token() && !state.kind) state.message = active ? '目标已保存；到目标才提示。' : '提醒已停用'
    } catch (e) {
      if (valid(current, owner)) { state.message = e.message || '保存未成功，请重试'; state.kind = e.kind || 'server'; if (e instanceof ArchiveReminderError && e.kind === 'conflict') pending = null }
    } finally { if (current === generation) state.busy = false }
  }
  return {
    load, save,
    route(nextArchive, nextVehicle) { archive = nextArchive; vehicle = nextVehicle },
    resume() { visible = true; return load() },
    suspend,
    reset() { suspend(); pending = null; state.message = ''; state.kind = '' },
  }
}
