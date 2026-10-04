import { vehicleBody, vehicleFailure } from './vehicles.js'

export function initialVehicleState() {
  return { brand: [], series: [], model: [], brandId: 0, seriesId: 0, year: '', modelId: 0,
    mileage: '0', plate: '', vin: '', busy: false, message: '', failureKind: '', saved: null, catalogRetry: null,
    pages: { brand: 0, series: 0, model: 0 }, totals: { brand: 0, series: 0, model: 0 } }
}
// State is supplied by Vue in the page and by ordinary objects in offline tests.
export function createVehicleFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null
  const fail = error => { const safe = vehicleFailure(error); state.message = safe.message; state.failureKind = safe.kind }
  function suspend() { generation++; state.busy = false }
  function reset() { suspend(); pending = null; Object.assign(state, initialVehicleState()) }
  async function load(kind, more = false) {
    if (state.busy || state.saved || !token()) return
    const parent = kind === 'series' ? state.brandId : kind === 'model' ? state.seriesId : 0
    const page = more ? state.pages[kind] + 1 : 1
    const owner = token(), current = ++generation
    state.busy = true; state.message = ''; state.failureKind = ''; state.catalogRetry = null
    try {
      const data = await api.catalog(owner, kind, parent, page)
      if (current !== generation || token() !== owner) return
      state[kind] = more ? [...state[kind], ...data.list] : data.list
      state.pages[kind] = page; state.totals[kind] = data.total
      if (!state[kind].length) state.message = '暂无可选车型，请稍后再试'
    } catch (error) { if (current === generation && token() === owner) { fail(error); state.catalogRetry = { kind, more } } }
    finally { if (current === generation) state.busy = false }
  }
  async function selectBrand(id) {
    suspend(); pending = null
    Object.assign(state, { brandId: id, seriesId: 0, year: '', modelId: 0, series: [], model: [], saved: null })
    state.pages.series = state.pages.model = state.totals.series = state.totals.model = 0
    await load('series')
  }
  async function selectSeries(id) {
    suspend(); pending = null
    Object.assign(state, { seriesId: id, year: '', modelId: 0, model: [], saved: null })
    state.pages.model = state.totals.model = 0
    await load('model')
  }
  async function save() {
    if (state.busy || state.saved || !token()) return
    let body
    try { body = vehicleBody(state) } catch (error) { fail(error); return }
    const canonical = JSON.stringify(body)
    if (!pending || pending.canonical !== canonical) pending = { canonical, key: newKey(), body }
    const owner = token(), current = ++generation
    state.busy = true; state.message = ''; state.failureKind = ''; state.catalogRetry = null
    try {
      const saved = await api.add(owner, pending.body, pending.key)
      if (current !== generation || token() !== owner) return
      state.saved = saved; state.message = '车辆已保存，可返回档案查看'
    } catch (error) { if (current === generation && token() === owner) fail(error) }
    finally { if (current === generation) state.busy = false }
  }
  return { load, selectBrand, selectSeries, save, suspend, reset }
}
