import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'

const id = n => Number.isSafeInteger(n) && n > 0
const exact = (n, keys) => n && typeof n === 'object' && !Array.isArray(n) && Object.keys(n).length === keys.length && keys.every(k => Object.hasOwn(n, k))
const stamp = n => typeof n === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(n) && Number.isFinite(Date.parse(n))
const uuid = n => typeof n === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(n)
const validText = n => typeof n === 'string' && n.trim().length > 0 && [...n].length <= 500 && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(n) && [...n].every(c => c.length === 2 || !/[\ud800-\udfff]/.test(c))
const validPhotos = n => Array.isArray(n) && n.length <= 3 && n.every(id) && new Set(n).size === n.length
export class OrderReviewError extends ServiceError { constructor(kind, message, code = 0) { super(kind, message); this.code = code } }
export function reviewBody(order, rating, content, photos = []) {
  const text = typeof content === 'string' ? content.trim() : content
  if (!id(order) || !Number.isInteger(rating) || rating < 1 || rating > 5 || !validText(text) || !validPhotos(photos)) throw new OrderReviewError('invalid', '请选择 1–5 分，填写 1–500 字说明，图片最多 3 张且须上传完成')
  return { order_id: order, rating, content: text, photo_file_ids: [...photos] }
}
export const validReview = n => exact(n, ['review_id', 'rating', 'content', 'photo_file_ids', 'submitted_at', 'test_mode']) && id(n.review_id) && Number.isInteger(n.rating) && n.rating >= 1 && n.rating <= 5 && validText(n.content) && validPhotos(n.photo_file_ids) && stamp(n.submitted_at) && typeof n.test_mode === 'boolean'
export const REVIEW_REASONS = { ORDER_NOT_COMPLETED: '订单尚未完成核销，暂不可评价', REDEMPTION_UNVERIFIED: '历史订单缺少可信核销记录，请联系商家核对', OPEN_DISPUTE: '订单仍有未解决争议，暂不可评价', PAYMENT_UNVERIFIED: '付款或核销凭据尚未核实，暂不可评价', ALREADY_REVIEWED: '此订单已评价，提交后不可修改' }
export function validReviewDetail(n, order) {
  if (!exact(n, ['order_id', 'can_submit', 'unavailable_reason', 'test_mode', 'review']) || n.order_id !== order || typeof n.can_submit !== 'boolean') return false
  if (n.review !== null) return validReview(n.review) && !n.can_submit && n.unavailable_reason === 'ALREADY_REVIEWED' && n.test_mode === n.review.test_mode
  if (n.can_submit) return n.unavailable_reason === null && typeof n.test_mode === 'boolean'
  return Object.hasOwn(REVIEW_REASONS, n.unavailable_reason) && n.unavailable_reason !== 'ALREADY_REVIEWED' && n.test_mode === null
}
export function createOrderReviewsApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, body, key) {
    if (!token) throw new OrderReviewError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new OrderReviewError('unconfigured', '评价服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body ? 'POST' : 'GET', data: body, timeout: 15000,
        header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode)) return reject(new OrderReviewError('protocol', '评价响应异常，请使用原请求重试'))
          if (statusCode < 200 || statusCode >= 300) {
            const kind = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 422: 'invalidPhoto', 503: 'unavailable' }[statusCode] || 'server'
            const messages = { 400: '评价信息无效，请核对后重试', 401: '登录已失效，请重新登录', 403: '只有车主本人可以评价', 404: '订单不存在或非本人订单', 422: '图片不安全、非本人或已不可用，请重新上传', 503: '评价服务暂不可用，请使用原请求重试' }
            const conflict = data?.code === 44002 ? '订单已评价，请刷新查看；提交后不可修改' : '订单评价资格已变化，请刷新核对核销、争议和付款记录'
            return reject(new OrderReviewError(kind, statusCode === 409 ? conflict : messages[statusCode] || '评价请求未成功，请稍后重试', data?.code))
          }
          if (data?.code !== 0 || !validate(data.data)) return reject(new OrderReviewError('protocol', '评价响应异常，请使用原请求重试'))
          resolve(data.data)
        }, fail: () => reject(serviceFailure()) })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    detail(token, order) { if (!id(order)) throw new OrderReviewError('invalid', '订单编号无效'); return call(token, `/api/order/${order}/review`, n => validReviewDetail(n, order)) },
    submit(token, raw, key) {
      if (!exact(raw, ['order_id', 'rating', 'content', 'photo_file_ids']) || !uuid(key)) throw new OrderReviewError('invalid', '评价提交信息无效')
      const body = reviewBody(raw.order_id, raw.rating, raw.content, raw.photo_file_ids)
      return call(token, '/api/order/review', n => exact(n, ['order_id', 'review']) && n.order_id === body.order_id && validReview(n.review) && n.review.rating === body.rating && n.review.content === body.content && JSON.stringify(n.review.photo_file_ids) === JSON.stringify(body.photo_file_ids), body, key)
    },
  }
}
export const orderReviewsApi = createOrderReviewsApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialOrderReviewState = () => ({ busy: false, saved: null, message: '', failureKind: '' })
export function createOrderReviewFlow({ state, token, body, api, newKey, confirm, onConflict = () => {} }) {
  let active = false, generation = 0, pending = null
  function reset() { active = false; generation++; pending = null; Object.assign(state, initialOrderReviewState()) }
  async function submit() {
    if (!active || state.busy || state.saved) return
    let value; try { value = body() } catch (e) { state.message = e.message; state.failureKind = 'invalid'; return }
    const canonical = JSON.stringify(value), actor = token(), version = ++generation; state.busy = true; state.message = ''; state.failureKind = ''
    const holds = () => { try { return active && version === generation && actor === token() && canonical === JSON.stringify(body()) } catch { return false } }
    try {
      if (!await confirm() || !holds()) return
      if (!pending || pending.actor !== actor || pending.canonical !== canonical) pending = { actor, canonical, body: value, key: newKey() }
      const result = await api.submit(actor, pending.body, pending.key)
      if (!holds()) return
      state.saved = result; pending = null; state.message = result.review.test_mode ? '本人测试订单评价已保存，未真实扣款' : '本人订单评价已保存'
    } catch (e) {
      if (!holds()) return
      const safe = e instanceof ServiceError ? e : serviceFailure(); state.message = safe.message; state.failureKind = safe.kind
      if (['conflict', 'unauthorized', 'forbidden', 'missing', 'invalidPhoto'].includes(safe.kind)) pending = null
      if (safe.kind === 'conflict') await onConflict()
    } finally { if (version === generation) state.busy = false }
  }
  return { submit, reset, suspend: reset, resume: () => { active = true } }
}
