import { vehicleBody, vehicleFailure } from './vehicles.js'

export function initialVehicleState() {
  return { brand: [], series: [], model: [], brandId: 0, seriesId: 0, year: '', modelId: 0,
    mileage: '0', plate: '', vin: '', busy: false, message: '', failureKind: '', saved: null, catalogRetry: null,
    pages: { brand: 0, series: 0, model: 0 }, totals: { brand: 0, series: 0, model: 0 } }
}
// State is supplied by Vue in the page and by ordinary objects in offline tests.
export function createVehicleFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null, candidateEpoch=0
  const fail = error => { const safe = vehicleFailure(error); state.message = safe.message; state.failureKind = safe.kind }
  function suspend(keepCandidate=false) { generation++; state.busy = false;if(keepCandidate!==true)candidateEpoch++ }
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
  async function selectBrand(id,fromCandidate=false) {
    suspend(fromCandidate); pending = null
    Object.assign(state, { brandId: id, seriesId: 0, year: '', modelId: 0, series: [], model: [], saved: null })
    state.pages.series = state.pages.model = state.totals.series = state.totals.model = 0
    await load('series')
  }
  async function selectSeries(id,fromCandidate=false) {
    suspend(fromCandidate); pending = null
    Object.assign(state, { seriesId: id, year: '', modelId: 0, model: [], saved: null })
    state.pages.model = state.totals.model = 0
    await load('model')
  }
  function resumeCatalog() {
    if (state.saved) return
    if (!state.brand.length) return load('brand')
    if (state.brandId && !state.series.length) return load('series')
    if (state.seriesId && !state.model.length) return load('model')
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
  async function selectCandidate(candidate) {
    const actor=token();if(!actor||state.busy||state.saved)return
    const epoch=++candidateEpoch,active=()=>token()===actor&&epoch===candidateEpoch
    state.message='';state.failureKind='';state.catalogRetry=null
    // Re-read current catalog after explicit user confirmation; recognition never saves a model.
    const find=async(kind,id)=>{
      if(!active())return false
      if(!state[kind].length)await load(kind)
      for(let n=0;!state[kind].some(row=>row.id===id)&&state[kind].length<state.totals[kind]&&n<50;n++){
        if(state.failureKind||!active())return false
        await load(kind,true)
      }
      return active()&&!state.failureKind&&state[kind].some(row=>row.id===id)
    }
    const unavailable=()=>{if(active()){state.message='候选已不可用，请重试加载或手动选择';state.failureKind=state.failureKind||'invalid'}}
    if(!candidate||['brand_id','series_id','model_id'].some(k=>!Number.isSafeInteger(candidate[k])||candidate[k]<1))return unavailable()
    Object.assign(state,{brandId:0,seriesId:0,modelId:0,year:'',series:[],model:[]})
    state.pages.series=state.pages.model=state.totals.series=state.totals.model=0
    await load('brand')
    if(!await find('brand',candidate.brand_id))return unavailable()
    await selectBrand(candidate.brand_id,true)
    if(!await find('series',candidate.series_id))return unavailable()
    await selectSeries(candidate.series_id,true)
    if(!await find('model',candidate.model_id))return unavailable()
    const brand=state.brand.find(r=>r.id===candidate.brand_id),series=state.series.find(r=>r.id===candidate.series_id),model=state.model.find(r=>r.id===candidate.model_id)
    if(brand?.name!==candidate.brand_name||series?.name!==candidate.series_name||model?.year!==candidate.year||model?.config_name!==candidate.config_name){state.message='候选车型已变更，请重新查询或手动选择';state.failureKind='invalid';return}
    state.year=model.year;state.modelId=model.id;state.message='已选入当前车型目录，请核对里程和车辆资料后保存';state.failureKind=''
  }
  return { load, selectBrand, selectSeries, selectCandidate, resumeCatalog, save, suspend, reset }
}
