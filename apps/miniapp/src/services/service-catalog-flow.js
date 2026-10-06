import { ServiceError, serviceFailure } from './service-catalog.js'

export const initialServiceListState = () => ({ category: 0, items: [], page: 0, total: 0, busy: false, loaded: false, message: '', failureKind: '' })
export const initialServiceDetailState = () => ({ item: null, busy: false, message: '', failureKind: '' })
export function createServiceListFlow({ state, api, token }) {
  let generation = 0
  let retryMore = false
  const suspend = () => { generation++; state.busy = false }
  const reset = () => { suspend(); Object.assign(state, initialServiceListState()) }
  async function load(more = false) {
    if (state.busy || (more && (!state.loaded || state.items.length >= state.total))) return
    retryMore = more
    const owner = token(), current = ++generation, category = state.category, page = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      if (!owner) throw new ServiceError('unauthorized', '请先登录车主账号')
      const result = await api.list(owner, category, page)
      if (current !== generation || token() !== owner) return
      const items = more ? [...state.items, ...result.items] : result.items
      state.items = [...new Map(items.map(item => [item.id, item])).values()]
      state.page = page; state.total = result.total; state.loaded = true
    } catch (error) {
      if (current !== generation || token() !== owner) return
      const safe = serviceFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  function select(category) {
    if (!Number.isInteger(category) || category < 0 || category > 6) return
    suspend(); Object.assign(state, initialServiceListState(), { category }); return load()
  }
  return { load, retry: () => load(retryMore), select, suspend, reset }
}
export function createServiceDetailFlow({ state, api, token }) {
  let generation = 0
  const suspend = () => { generation++; state.busy = false }
  const reset = () => { suspend(); Object.assign(state, initialServiceDetailState()) }
  async function load(id) {
    if (state.busy) return
    const current = ++generation, owner = token()
    state.busy = true; state.item = null; state.message = ''; state.failureKind = ''
    try {
      if (!owner) throw new ServiceError('unauthorized', '请先登录车主账号')
      const result = await api.detail(owner, id)
      if (current === generation && token() === owner) state.item = result
    } catch (error) {
      if (current !== generation || token() !== owner) return
      const safe = serviceFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }
  return { load, suspend, reset }
}
