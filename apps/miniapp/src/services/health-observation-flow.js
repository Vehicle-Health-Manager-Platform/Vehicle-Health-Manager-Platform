import { observationBody, observationForm, HealthObservationError } from './health-observations.js'

export function initialObservationState() { return { context: null, form: {}, busy: false, loaded: false, message: '', kind: '' } }
export function createObservationFlow({ state, api, token, newKey, confirmClear, confirmReplacement, confirmCorrection = async () => false }) {
  let generation = 0, visible = false, archive = 0, vehicle = 0, metric = '', pending = null
  const valid = (current, owner) => current === generation && owner === token() && visible
  function suspend() { generation++; visible = false; state.context = null; state.loaded = false; state.busy = false; state.form = {} }
  async function load() {
    if (state.busy || !visible) return
    const current = ++generation, owner = token()
    state.busy = true; state.context = null; state.loaded = false; state.message = ''; state.kind = ''; state.form = {}
    try {
      const context = await api.context(owner, archive, vehicle, metric)
      if (!valid(current, owner)) return
      state.context = context; state.loaded = true
      state.form = observationForm(context)
    } catch (e) { if (valid(current, owner)) { state.message = e.message || '读数加载失败，请重试'; state.kind = e.kind || 'server' } }
    finally { if (current === generation) state.busy = false }
  }
  async function save(clear = false, operation = clear ? 'CLEAR' : 'RECORD') {
    if (state.busy || !visible || !state.context || state.kind === 'conflict') return
    const current = ++generation, owner = token(), context = state.context
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const body = observationBody(context, state.form, clear, operation)
      if (body.replace_source && !(await confirmReplacement())) return
      if (operation === 'CORRECT' && !(await confirmCorrection())) return
      if (clear && !(await confirmClear())) return
      if (!valid(current, owner)) return
      const canonical = JSON.stringify(body)
      if (!pending || pending.owner !== owner || pending.archive !== archive || pending.canonical !== canonical) pending = { owner, archive, canonical, key: newKey() }
      const attempt = pending
      await api.save(owner, context, body, attempt.key)
      if (!valid(current, owner)) return
      if (pending === attempt) pending = null
      state.busy = false
      await load()
      if (visible && owner === token() && !state.kind) state.message = clear ? '本类当前读数已清空，历史与原档案保留' : operation === 'CORRECT' ? '修正已保存，原读数仍在历史中' : '新增测量已保存，尚未核验'
    } catch (e) {
      if (valid(current, owner)) { state.message = e.message || '保存未成功，请重试'; state.kind = e.kind || 'server'; if (e instanceof HealthObservationError && e.kind === 'conflict') pending = null }
    } finally { if (current === generation) state.busy = false }
  }
  return {
    load, save,
    route(nextArchive, nextVehicle, nextMetric) { archive = nextArchive; vehicle = nextVehicle; metric = nextMetric },
    resume() { visible = true; return load() },
    suspend,
    reset() { suspend(); pending = null; state.message = ''; state.kind = '' },
  }
}

export function initialObservationListState() { return { vehicleId: 0, rows: [], today: '', busy: false, loaded: false, message: '', kind: '' } }
export function createObservationListFlow({ state, api, token }) {
  let generation = 0, visible = false
  function clear() { generation++; Object.assign(state, initialObservationListState()) }
  async function load() {
    if (state.busy || !visible || !state.vehicleId) return
    const current = ++generation, owner = token(), vehicle = state.vehicleId
    state.busy = true; state.rows = []; state.loaded = false; state.message = ''; state.kind = ''
    try {
      const result = await api.list(owner, vehicle)
      if (current !== generation || owner !== token() || vehicle !== state.vehicleId || !visible) return
      state.rows = result.list; state.today = result.today; state.loaded = true
    } catch (e) { if (current === generation && owner === token()) { state.message = e.message || '读数加载失败，请重试'; state.kind = e.kind || 'server' } }
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
