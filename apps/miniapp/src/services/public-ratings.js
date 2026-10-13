import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
const id = x => Number.isSafeInteger(x) && x > 0
export const validPublication = (r, order) => exact(r, ['order_id', 'version', 'consented', 'visible', 'can_publish', 'unavailable_reason']) && r.order_id === order && Number.isSafeInteger(r.version) && r.version >= 0
  && [r.consented, r.visible, r.can_publish].every(x => typeof x === 'boolean') && r.visible === (r.consented && r.can_publish)
  && (r.can_publish ? r.unavailable_reason === null : ['TEST_ORDER', 'PUBLIC_SOURCE_UNVERIFIED'].includes(r.unavailable_reason))
export function validRatings(r, merchant, page) {
  if (!exact(r, ['merchant_id', 'page', 'page_size', 'items', 'summary']) || r.merchant_id !== merchant || r.page !== page || r.page_size !== 20 || !exact(r.summary, ['count', 'rating_sum', 'average_rating'])) return false
  const s = r.summary
  if (!Number.isSafeInteger(s.count) || s.count < 0 || !Number.isSafeInteger(s.rating_sum) || s.rating_sum < s.count || s.rating_sum > s.count * 5) return false
  if (s.count === 0 ? s.average_rating !== null || s.rating_sum !== 0 : typeof s.average_rating !== 'string' || !/^[1-5]\.\d{2}$/.test(s.average_rating) || Math.abs(Number(s.average_rating) - s.rating_sum / s.count) > 0.00501) return false
  return Array.isArray(r.items) && r.items.length <= 20 && r.items.length <= s.count && r.items.every(x => exact(x, ['rating', 'submitted_date']) && Number.isInteger(x.rating) && x.rating >= 1 && x.rating <= 5 && /^\d{4}-\d{2}-\d{2}$/.test(x.submitted_date))
}
export function createPublicRatingsApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, body, key) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '公开星级服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body ? 'PUT' : 'GET', data: body, timeout: 15000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' })[statusCode] || 'server', '公开星级请求未成功，请刷新核对'))
          try { if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '公开星级响应异常，请重试')) } catch { return reject(new ServiceError('protocol', '公开星级响应异常，请重试')) }
          resolve(data.data)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    detail(token, order) { if (!id(order)) throw new ServiceError('invalid', '订单无效'); return call(token, `/api/order/${order}/review-publication`, r => validPublication(r, order)) },
    update(token, order, body, key) {
      if (!id(order) || !exact(body, ['publish', 'expected_version']) || typeof body.publish !== 'boolean' || !Number.isSafeInteger(body.expected_version) || body.expected_version < 0 || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new ServiceError('invalid', '授权请求无效')
      return call(token, `/api/order/${order}/review-publication`, r => validPublication(r, order), body, key)
    },
    list(token, merchant, page = 1) { if (!id(merchant) || !id(page) || page > 1000000) throw new ServiceError('invalid', '分页条件无效'); return call(token, `/api/service/merchant/${merchant}/ratings?page=${page}&page_size=20`, r => validRatings(r, merchant, page)) },
  }
}
export const publicRatingsApi = createPublicRatingsApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialPublicationState = () => ({ busy: false, value: null, message: '', failureKind: '', retryPublish: null })
export function createPublicationFlow({ state, token, order, api = publicRatingsApi, newKey, confirm }) {
  let generation = 0, pending = null
  function reset() { generation++; pending = null; Object.assign(state, initialPublicationState()) }
  function failure(e) { state.message = e?.kind === 'conflict' ? '授权状态已变化，请刷新后重新确认' : serviceFailure(e).message; state.failureKind = e?.kind || 'network'; if (['unauthorized', 'forbidden', 'missing'].includes(state.failureKind)) { state.value = null; pending = null; state.retryPublish = null } }
  async function load() {
    if (state.busy) return
    const g = ++generation, actor = token(), id = order(); pending = null; Object.assign(state, initialPublicationState(), { busy: true })
    const valid = () => g === generation && actor === token() && id === order()
    try { const r = await api.detail(actor, id); if (valid()) state.value = r } catch (e) { if (valid()) failure(e) } finally { if (valid()) state.busy = false }
  }
  async function save(publish) {
    if (state.busy || !state.value || publish && !state.value.can_publish) return
    const g = ++generation, actor = token(), id = order(), valid = () => g === generation && actor === token() && id === order()
    state.busy = true; state.message = ''; state.failureKind = ''
    let written = false
    try {
      if (!pending || pending.body.publish !== publish || pending.body.expected_version !== state.value.version) {
        if (!await confirm(publish) || !valid()) return
        pending = { body: { publish, expected_version: state.value.version }, key: newKey() }
      }
      await api.update(actor, id, pending.body, pending.key)
      if (!valid()) return
      written = true; pending = null; state.retryPublish = null; state.value = null
      const current = await api.detail(actor, id)
      if (valid()) { state.value = current; state.message = '已重新核对当前公开授权状态' }
    } catch (e) {
      if (!valid()) return
      if (e?.kind === 'conflict') { pending = null; state.retryPublish = null; state.value = null }
      else state.retryPublish = !written && pending ? publish : null
      failure(e)
    } finally { if (valid()) state.busy = false }
  }
  return { load, save, reset }
}
export const initialRatingsState = () => ({ busy: false, value: null, message: '', failureKind: '' })
export function createRatingsFlow({ state, token, merchant, api = publicRatingsApi }) {
  let generation = 0
  function reset() { generation++; Object.assign(state, initialRatingsState()) }
  async function load(more = false) {
    if (state.busy) return
    const old = more ? state.value : null, page = old ? old.page + 1 : 1
    const g = ++generation, actor = token(), id = merchant(), valid = () => g === generation && actor === token() && id === merchant()
    Object.assign(state, initialRatingsState(), { busy: true })
    try { const r = await api.list(actor, id, page); if (valid()) state.value = { ...r, items: old ? [...old.items, ...r.items] : r.items } }
    catch (e) { if (valid()) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network' } }
    finally { if (valid()) state.busy = false }
  }
  return { load, reset }
}
