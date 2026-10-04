import { archiveBody, archiveFailure } from './archives.js'

export function initialArchiveState() {
  return { vehicleId: 0, archiveType: 1, recordedDate: '', mileage: '', title: '', notes: '', fileIds: [],
    busy: false, message: '', failureKind: '', saved: null }
}
export function createArchiveFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null
  function suspend() { generation++; state.busy = false }
  function reset() { suspend(); pending = null; Object.assign(state, initialArchiveState()) }
  async function save() {
    if (state.busy || state.saved || !token()) return
    let body
    try { body = archiveBody(state) }
    catch (error) { const safe = archiveFailure(error); state.message = safe.message; state.failureKind = safe.kind; return }
    const canonical = JSON.stringify(body)
    if (!pending || pending.canonical !== canonical) pending = { canonical, body, key: newKey() }
    const owner = token(), current = ++generation
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      const result = await api.add(owner, pending.body, pending.key)
      if (current !== generation || token() !== owner) return
      state.saved = result; state.message = '档案已保存'
    } catch (error) {
      if (current !== generation || token() !== owner) return
      const safe = archiveFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  return { save, suspend, reset }
}
