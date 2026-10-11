export function initialCatalogState() { return { rows: [], total: 0, page: 0, busy: false, writing: false, loaded: false, message: '', kind: '' } }
export function createCatalogFlow({ state, api, token, selectedKind, newKey }) {
  let epoch = 0, pending = null
  const current = (v, actor, kind) => v === epoch && actor === token() && kind === selectedKind()
  function reset() { epoch++; pending = null; Object.assign(state, initialCatalogState()) }
  async function load(more = false) {
    if (state.busy || state.writing) return
    const v = ++epoch, actor = token(), kind = selectedKind(), page = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.kind = ''
    if (!more) { state.rows = []; state.loaded = false }
    try { const r = await api.list(actor, kind, page); if (!current(v, actor, kind)) return
      state.rows = more ? [...state.rows, ...r.items.filter(x => !state.rows.some(y => y.id === x.id))] : r.items
      state.total = r.total; state.page = page; state.loaded = true
    } catch (e) { if (current(v, actor, kind)) { state.message = e.message; state.kind = e.kind } }
    finally { if (current(v, actor, kind)) state.busy = false }
  }
  async function save(body, target) {
    if (state.busy || state.writing) return null
    const v = epoch, actor = token(), kind = selectedKind(), stamp = JSON.stringify([actor, kind, body, target])
    if (!pending || pending.stamp !== stamp) pending = { stamp, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try { const result = await api.save(actor, kind, body, pending.key, target); if (!current(v, actor, kind)) return null
      pending = null; state.message = '已保存，请刷新目录'; return result
    } catch (e) { if (current(v, actor, kind)) { state.message = e.message; state.kind = e.kind; if (!['network', 'unavailable', 'server', 'protocol'].includes(e.kind)) pending = null } return null }
    finally { if (current(v, actor, kind)) state.writing = false }
  }
  return { reset, load, save }
}
