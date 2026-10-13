import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { exact, safeId } from './experience-publication.js'
import { validVehicleEvidence } from './vehicle-plan-evidence.js'
import { quotesApi } from './merchant-quotes.js'
import { reservationsApi, chinaDate } from './reservations.js'
import { ratedQuotesApi } from './rated-quotes.js'
const validDay = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s)) && new Date(s).toISOString().slice(0,10) === s
const validInput = b => exact(b, ['merchant_project_id', 'standard_project_id', 'vehicle_id', 'date', 'page', 'selected_slot_id']) && [b.merchant_project_id, b.standard_project_id, b.vehicle_id].every(safeId) && validDay(b.date) && safeId(b.page) && b.page <= 1000000 && (b.selected_slot_id === null || safeId(b.selected_slot_id))
const stamp = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(s) && Number.isFinite(Date.parse(s))
function validSlot(s, b) { return exact(s, ['slot_id', 'standard_project_id', 'starts_at', 'ends_at', 'capacity', 'capacity_left', 'open', 'project_name']) && safeId(s.slot_id) && s.standard_project_id === b.standard_project_id && stamp(s.starts_at) && stamp(s.ends_at) && s.starts_at < s.ends_at && chinaDate(new Date(s.starts_at)) === b.date && safeId(s.capacity) && safeId(s.capacity_left) && s.capacity_left <= s.capacity && s.open === true && typeof s.project_name === 'string' }
export function validPlanBookingContext(r, b) {
  if (!exact(r, ['quote', 'evidence', 'slots', 'selected_slot'])) return false
  const q = r.quote, s = r.slots
  if (!exact(q, ['merchant_project_id', 'quote_version_id', 'version', 'merchant_id', 'standard_project_id', 'merchant_name', 'address', 'project_name', 'price']) || q.merchant_project_id !== b.merchant_project_id || q.standard_project_id !== b.standard_project_id || ![q.quote_version_id, q.version, q.merchant_id].every(safeId) || !['merchant_name', 'address', 'project_name'].every(k => typeof q[k] === 'string') || typeof q.price !== 'string' || !/^(0|[1-9]\d{0,7})\.\d{2}$/.test(q.price) || Number(q.price) <= 0 || !validVehicleEvidence(r.evidence, b.vehicle_id, [b.standard_project_id])) return false
  return exact(s, ['items', 'total', 'page', 'page_size']) && s.page === b.page && s.page_size === 20 && Number.isSafeInteger(s.total) && s.total >= 0 && Array.isArray(s.items) && s.items.length <= 20 && s.items.length <= s.total && new Set(s.items.map(x => x?.slot_id)).size === s.items.length && s.items.every(x => validSlot(x, b)) && (r.selected_slot === null || b.selected_slot_id !== null && r.selected_slot.slot_id === b.selected_slot_id && validSlot(r.selected_slot, b))
}
export function createPlanBookingApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  return { read(token, body) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '方案预约服务尚未配置')
    if (!validInput(body)) throw new ServiceError('invalid', '方案预约参数无效，请重新选择')
    return new Promise((resolve, reject) => { try { runtime().request({ url: endpoint + '/api/order/plan-booking-context', method: 'POST', data: body, timeout: 15000, header: { Authorization: `Bearer ${token}` }, success: ({ statusCode, data }) => {
      if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 503: 'unavailable' })[statusCode] || 'server', '方案预约信息暂不可用，请重新核对'))
      try { if (data?.code !== 0 || !validPlanBookingContext(data.data, body)) return reject(new ServiceError('protocol', '方案预约响应异常，请重试')) } catch { return reject(new ServiceError('protocol', '方案预约响应异常，请重试')) }
      resolve(data.data)
    }, fail: () => reject(serviceFailure()) }) } catch { reject(serviceFailure()) } })
  } }
}
export const planBookingApi = createPlanBookingApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialPlanBookingState = () => ({ busy: false, uncertain: false, saved: null, quotes: [], total: 0, page: 0, loaded: false, sort: 'price_asc', quoteId: 0, day: chinaDate(), context: null, slot: 0, confirmed: false, message: '', kind: '' })
function signature(c) { const q = c.quote, v = c.evidence.vehicle, a = c.evidence.applicability[0]; return JSON.stringify([q.merchant_project_id, q.quote_version_id, q.version, q.merchant_id, q.standard_project_id, q.price, q.merchant_name, q.address, q.project_name, v.vehicle_id, v.model_id, v.model_name, v.power_type, a.verdict, a.rule_version, a.source_reference]) }
export function createPlanBookingFlow({ state, token, selection, newKey, api = planBookingApi, quotes = quotesApi, create = reservationsApi.create, ratedApi = ratedQuotesApi }) {
  let epoch = 0, pending = null
  const capture = () => { const g = ++epoch, actor = token(), selected = JSON.stringify(selection()); return { actor, valid: () => g === epoch && actor === token() && selected === JSON.stringify(selection()) } }
  function clearChoice() { state.context = null; state.slot = 0; state.confirmed = false }
  function reset() { epoch++; pending = null; Object.assign(state, initialPlanBookingState()) }
  function suspend() { epoch++; state.busy = false; state.quotes = []; state.loaded = false; clearChoice() }
  function failure(e) { const safe = e instanceof ServiceError ? e : serviceFailure(); state.message = safe.message; state.kind = safe.kind; if (['unauthorized', 'forbidden', 'missing'].includes(safe.kind)) { clearChoice(); state.quotes = []; state.loaded = false } return safe }
  const blocked = () => state.busy || state.uncertain || !!state.saved
  function input(page = 1, selected = null) { const s = selection(); return { merchant_project_id: state.quoteId, standard_project_id: s.project, vehicle_id: s.vehicle, date: state.day, page, selected_slot_id: selected } }
  async function loadQuotes(more = false, sort = state.sort) {
    if (blocked()) return; const task = capture(), s = selection(), page = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.kind = ''; if (!more) { state.quotes = []; state.loaded = false; state.quoteId = 0; clearChoice(); state.sort = sort }
    try { const r = sort==='rating_desc'?await ratedApi.list(task.actor,s.project,page):await quotes.ownerList(task.actor, s.project, sort, page); if (!task.valid()) return; state.quotes = more ? [...state.quotes, ...r.items.filter(x => !state.quotes.some(y => y.merchant_project_id === x.merchant_project_id))] : r.items; state.total = r.total; state.page = page; state.loaded = true } catch (e) { if (task.valid()) failure(e) } finally { if (task.valid()) state.busy = false }
  }
  async function loadContext(more = false) {
    if (blocked() || !state.quoteId) return; const task = capture(), old = state.context, page = more && old ? old.slots.page + 1 : 1
    state.busy = true; state.message = ''; state.kind = ''; if (!more) clearChoice()
    try { let r = await api.read(task.actor, input(page)); if (!task.valid()) return
      if (more && old && signature(old) !== signature(r)) { clearChoice(); r = await api.read(task.actor, input()); if (!task.valid()) return; state.message = '报价、车型或规则已变化，已重新读取；请重新选择时段并确认' }
      else if (more && old) r = { ...r, slots: { ...r.slots, items: [...old.slots.items, ...r.slots.items.filter(x => !old.slots.items.some(y => y.slot_id === x.slot_id))] } }
      state.context = r
    } catch (e) { if (task.valid()) { clearChoice(); failure(e) } } finally { if (task.valid()) state.busy = false }
  }
  function chooseQuote(row) { if (blocked() || !state.quotes.includes(row)) return; state.quoteId = row.merchant_project_id; return loadContext() }
  function chooseDay(day) { if (blocked() || !validDay(day)) return; state.day = day; clearChoice(); return loadContext() }
  function chooseSlot(row) { if (blocked() || !state.context?.slots.items.includes(row)) return; state.slot = row.slot_id }
  function confirm() { if (!blocked() && state.context && state.context.evidence.applicability[0].verdict !== 'NOT_APPLICABLE') state.confirmed = !state.confirmed }
  async function save() {
    if (state.busy || state.saved) return
    if (!pending && (!state.context || !state.confirmed || !state.slot || state.context.evidence.applicability[0].verdict === 'NOT_APPLICABLE')) { state.message = '请核对本车适用性、选择时段并确认当前报价'; state.kind = 'invalid'; return }
    const task = capture(); state.busy = true; state.message = ''; state.kind = ''
    try {
      if (!pending) { const expected = signature(state.context), selected = state.slot, fresh = await api.read(task.actor, input(1, selected)); if (!task.valid()) return; state.context = fresh
        if (signature(fresh) !== expected || !fresh.selected_slot || fresh.evidence.applicability[0].verdict === 'NOT_APPLICABLE') { state.slot = 0; state.confirmed = false; state.kind = 'conflict'; state.message = '报价、车型规则或时段已变化，请按当前信息重新选择并确认'; return }
        pending = { body: { merchant_project_id: fresh.quote.merchant_project_id, quote_version_id: fresh.quote.quote_version_id, vehicle_id: selection().vehicle, slot_id: selected }, key: newKey() }
      }
      state.uncertain = true; const result = await create(task.actor, pending.body, pending.key); if (!task.valid()) return; pending = null; state.uncertain = false; state.saved = result; state.message = '预约已创建'
    } catch (e) {
      if (!task.valid()) return; const safe = failure(e)
      if (pending && ['network', 'unavailable', 'server', 'protocol'].includes(safe.kind)) { state.uncertain = true; state.message = '创建结果尚未确定；请用原请求重试取得回执，离开后先核对我的订单' }
      else { pending = null; state.uncertain = false; state.slot = 0; state.confirmed = false
        if (safe.kind === 'conflict') { state.context = null; try { const fresh = await api.read(task.actor, input()); if (task.valid()) state.context = fresh } catch (refresh) { if (task.valid()) failure(refresh) } }
      }
    } finally { if (task.valid()) state.busy = false }
  }
  return { reset, suspend, loadQuotes, loadContext, chooseQuote, chooseDay, chooseSlot, confirm, save }
}
