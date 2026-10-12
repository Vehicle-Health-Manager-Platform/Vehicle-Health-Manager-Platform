import { structuredBody, structuredForm, ArchiveStructuredError } from './archive-structured.js'

export function initialStructuredState() { return { context: null, form: {}, busy: false, loaded: false, message: '', kind: '' } }
export function createStructuredFlow({ state, api, token, newKey, confirmClear }) {
  let generation = 0, visible = false, archive = 0, vehicle = 0, pending = null
  const valid = (current, owner) => current === generation && owner === token() && visible
  function suspend() { generation++; visible = false; state.context = null; state.loaded = false; state.busy = false; state.form = {} }
  async function load() {
    if (state.busy || !visible) return
    const current = ++generation, owner = token()
    state.busy = true; state.context = null; state.loaded = false; state.message = ''; state.kind = ''; state.form = {}
    try {
      const context = await api.context(owner, archive, vehicle)
      if (!valid(current, owner)) return
      state.context = context; state.loaded = true
      state.form = structuredForm(context)
    } catch (e) { if (valid(current, owner)) { state.message = e.message || '资料加载失败，请重试'; state.kind = e.kind || 'server' } }
    finally { if (current === generation) state.busy = false }
  }
  async function save(clear = false) {
    if (state.busy || !visible || !state.context || state.kind === 'conflict') return
    const current = ++generation, owner = token(), context = state.context
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const body = structuredBody(context, state.form, clear)
      if (clear && !(await confirmClear())) return
      if (!valid(current, owner)) return
      const canonical = JSON.stringify(body)
      if (!pending || pending.owner !== owner || pending.archive !== archive || pending.canonical !== canonical) pending = { owner, archive, canonical, key: newKey() }
      const operation = pending
      await api.save(owner, context, body, operation.key)
      if (!valid(current, owner)) return
      if (pending === operation) pending = null
      state.busy = false
      await load()
      if (visible && owner === token() && !state.kind) state.message = clear ? '结构化资料已清空，原档案保留' : '本人资料已保存，尚未核验'
    } catch (e) {
      if (valid(current, owner)) { state.message = e.message || '保存未成功，请重试'; state.kind = e.kind || 'server'; if (e instanceof ArchiveStructuredError && e.kind === 'conflict') pending = null }
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
