import { archiveFailure } from './archives.js'

export function initialHomeSummaryState() {
  return { vehicle: null, total: 0, latest: null, busy: false, loaded: false, message: '', failureKind: '' }
}

export function createHomeSummaryFlow({ state, api, token }) {
  let generation = 0
  function suspend() { generation++; state.busy = false }
  function reset() { suspend(); Object.assign(state, initialHomeSummaryState()) }
  function select(row) {
    if (!row) { reset(); return }
    const changed = state.vehicle?.vehicle_id !== row.vehicle_id
    state.vehicle = row
    if (changed) {
      suspend(); state.total = 0; state.latest = null; state.loaded = false; state.message = ''; state.failureKind = ''
      return load()
    }
  }
  async function load() {
    if (state.busy || !state.vehicle || !token()) return
    const current = ++generation, owner = token(), vehicleId = state.vehicle.vehicle_id
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      const result = await api.list(owner, vehicleId, 1)
      if (current !== generation || owner !== token() || state.vehicle?.vehicle_id !== vehicleId) return
      state.total = result.total; state.latest = result.list[0] || null; state.loaded = true
    } catch (error) {
      if (current !== generation || owner !== token() || state.vehicle?.vehicle_id !== vehicleId) return
      const safe = archiveFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  return { select, load, suspend, reset }
}
