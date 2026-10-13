import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
const decimal = (v, bound) => typeof v === 'string' && /^-?(0|[1-9]\d{0,2})(\.\d{1,7})?$/.test(v) && Math.abs(Number(v)) <= bound
const valid = x => x === null || exact(x, ['lng', 'lat', 'coordinate_system', 'source', 'version']) && decimal(x.lng, 180) && decimal(x.lat, 90) && x.coordinate_system === 'GCJ02' && x.source === 'MERCHANT_CONFIRMED' && Number.isSafeInteger(x.version) && x.version > 0
export function locationBody(state, clear = false) {
  if (clear) return { lng: null, lat: null, coordinate_system: null, source: null }
  if (!state.confirmed || !decimal(state.lng, 180) || !decimal(state.lat, 90)) throw new ServiceError('invalid', '请填写经纬度并确认这是本店的 GCJ02 坐标')
  return { lng: state.lng, lat: state.lat, coordinate_system: 'GCJ02', source: 'MERCHANT_CONFIRMED' }
}
export function createMerchantLocationApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, body, key) {
    if (!token) throw new ServiceError('unauthorized', '请先登录门店账号')
    if (!endpoint) throw new ServiceError('unconfigured', '门店位置服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + '/api/merchant/location', method: body ? 'PUT' : 'GET', data: body, timeout: 15000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          const kinds = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 503: 'unavailable' }
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(kinds[statusCode] || 'server', '门店位置请求未成功，请重试'))
          const r = data?.data
          if (data?.code !== 0 || !exact(r, body ? ['location'] : ['location', 'can_edit']) || !valid(r.location) || !body && typeof r.can_edit !== 'boolean') return reject(new ServiceError('protocol', '门店位置响应异常，请重试'))
          resolve(r)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return { read: token => request(token), update(token, body, key) {
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new ServiceError('invalid', '提交键无效')
    return request(token, body, key)
  } }
}
export const merchantLocationApi = createMerchantLocationApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialLocationState = () => ({ lng: '', lat: '', confirmed: false, loaded: false, canEdit: false, busy: false, saved: false, location: null, message: '', failureKind: '' })
export function createLocationFlow({ state, api = merchantLocationApi, token, newKey }) {
  let generation = 0, pending = null
  function reset() { generation++; pending = null; Object.assign(state, initialLocationState()) }
  function suspend() { reset() }
  const validActor = (g, t) => g === generation && t === token()
  function failure(error) { const safe = serviceFailure(error); state.message = safe.message; state.failureKind = safe.kind; if (['unauthorized', 'forbidden', 'missing'].includes(safe.kind)) { state.loaded = false; state.canEdit = false; state.location = null; state.lng = ''; state.lat = ''; state.confirmed = false; pending = null } }
  async function load() {
    if (state.busy) return
    const g = ++generation, actor = token(); resetView()
    try { const r = await api.read(actor); if (validActor(g, actor)) Object.assign(state, { loaded: true, canEdit: r.can_edit, location: r.location, lng: r.location?.lng ?? '', lat: r.location?.lat ?? '', confirmed: false }) }
    catch (e) { if (validActor(g, actor)) failure(e) }
    finally { if (validActor(g, actor)) state.busy = false }
  }
  function resetView() { Object.assign(state, initialLocationState(), { busy: true }) }
  async function save(clear = false) {
    if (!state.canEdit || !state.loaded || state.busy || state.saved) return
    let body
    try { body = locationBody(state, clear) } catch (e) { failure(e); return }
    const canonical = JSON.stringify(body)
    if (!pending || pending.canonical !== canonical) pending = { body, canonical, key: newKey() }
    const g = ++generation, actor = token(); state.busy = true; state.message = ''; state.failureKind = ''
    try { const r = await api.update(actor, pending.body, pending.key); if (validActor(g, actor)) Object.assign(state, { location: r.location, lng: r.location?.lng ?? '', lat: r.location?.lat ?? '', confirmed: false, saved: true, message: clear ? '已清除门店位置' : '门店位置已保存' }) }
    catch (e) { if (validActor(g, actor)) failure(e) }
    finally { if (validActor(g, actor)) state.busy = false }
  }
  return { load, save, reset, suspend, edit() { state.saved = false; state.message = ''; state.confirmed = false } }
}
