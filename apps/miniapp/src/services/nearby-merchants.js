import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const id = n => Number.isSafeInteger(n) && n > 0
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
export const validPoint = p => exact(p, ['lng', 'lat', 'coordinate_system']) && typeof p.lng === 'number' && Number.isFinite(p.lng) && Math.abs(p.lng) <= 180 && typeof p.lat === 'number' && Number.isFinite(p.lat) && Math.abs(p.lat) <= 90 && p.coordinate_system === 'GCJ02'
const validDistance = r => r.distance_meters === null ? r.location_source === null : Number.isSafeInteger(r.distance_meters) && r.distance_meters >= 0 && r.distance_meters <= 20015115 && r.location_source === 'MERCHANT_CONFIRMED'
export function distanceLabel(row) {
  if (!row || row.distance_meters === null) return '距离未知（门店尚未确认位置）'
  return row.distance_meters < 1000 ? `直线距离约 ${row.distance_meters} 米` : `直线距离约 ${(row.distance_meters / 1000).toFixed(1)} 公里`
}
export function requestNativeLocation(runtime = () => globalThis.uni, timeoutMs = 12000) {
  return new Promise((resolve, reject) => {
    let settled = false
    const done = (error, result) => { if (settled) return; settled = true; clearTimeout(timer); error ? reject(error) : resolve(result) }
    const fail = () => done(new ServiceError('location', '未取得位置，可选择省市区或按价格查看门店'))
    const timer = setTimeout(() => done(new ServiceError('location', '定位超时，可选择省市区或按价格查看门店')), timeoutMs)
    try {
      const api = runtime()
      if (typeof api?.getLocation !== 'function') return fail()
      api.getLocation({ type: 'gcj02', success: r => {
        const point = { lng: r.longitude, lat: r.latitude, coordinate_system: 'GCJ02' }
        validPoint(point) ? done(null, point) : fail()
      }, fail })
    } catch { fail() }
  })
}
export function createNearbyApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, body, validate) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '距离服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: 'POST', data: body, timeout: 15000, header: { Authorization: `Bearer ${token}` },
        success: ({ statusCode, data }) => {
          const kinds = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 503: 'unavailable' }
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(kinds[statusCode] || 'server', '距离查询未成功，请重试或选择区域'))
          try { if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '距离响应异常，请重试')) }
          catch { return reject(new ServiceError('protocol', '距离响应异常，请重试')) }
          resolve(data.data)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    list(token, project, point, sort = 'distance_asc', page = 1, region = null) {
      if (!id(project) || !validPoint(point) || !['distance_asc', 'price_asc', 'price_desc'].includes(sort) || !id(page) || page > 1000000 || region !== null && (typeof region !== 'string' || !/^\d{6}$/.test(region))) throw new ServiceError('invalid', '位置或筛选条件无效')
      return request(token, `/api/service/project/${project}/nearby-merchants`, { ...point, sort, page, page_size: 20, region_code: region }, r => exact(r, ['items', 'total', 'page', 'page_size']) && r.page === page && r.page_size === 20 && Number.isSafeInteger(r.total) && r.total >= 0 && Array.isArray(r.items) && r.items.length <= 20 && r.items.length <= r.total
        && new Set(r.items.map(x => x.merchant_project_id)).size === r.items.length && r.items.every(x => exact(x, ['merchant_project_id', 'merchant_id', 'merchant_name', 'address', 'price', 'version_id', 'version', 'distance_meters', 'location_source'])
          && id(x.merchant_project_id) && id(x.merchant_id) && id(x.version_id) && id(x.version) && typeof x.merchant_name === 'string' && typeof x.address === 'string' && typeof x.price === 'string' && /^(0|[1-9]\d{0,7})\.\d{2}$/.test(x.price) && Number(x.price) > 0 && validDistance(x)))
    },
    distance(token, merchant, point) {
      if (!id(merchant) || !validPoint(point)) throw new ServiceError('invalid', '门店或位置无效')
      return request(token, `/api/service/merchant/${merchant}/distance`, point, r => exact(r, ['merchant_id', 'distance_meters', 'location_source']) && r.merchant_id === merchant && validDistance(r))
    },
  }
}
export const nearbyApi = createNearbyApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })

export const initialDistanceState = () => ({ busy: false, result: null, message: '', failureKind: '' })
export function createDistanceFlow({ state, token, merchant, api = nearbyApi, locate = requestNativeLocation, invalidate = () => {} }) {
  let generation = 0
  function reset() { generation++; Object.assign(state, initialDistanceState()) }
  async function load() {
    if (state.busy || !token() || !id(merchant())) return
    const current = ++generation, actor = token(), shop = merchant()
    Object.assign(state, initialDistanceState(), { busy: true })
    const valid = () => current === generation && actor === token() && shop === merchant()
    try {
      const point = await locate()
      if (!valid()) return
      const result = await api.distance(actor, shop, point)
      if (valid()) state.result = result
    } catch (e) {
      if (!valid()) return
      const safe = e?.kind === 'location' ? e : serviceFailure(e)
      state.message = safe.message; state.failureKind = safe.kind
      if (['unauthorized', 'forbidden', 'missing'].includes(safe.kind)) invalidate()
    } finally { if (valid()) state.busy = false }
  }
  return { load, reset }
}
