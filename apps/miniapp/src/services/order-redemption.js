import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'

const id = n => Number.isSafeInteger(n) && n > 0
const exact = (n, keys) => n && typeof n === 'object' && !Array.isArray(n) && Object.keys(n).length === keys.length && keys.every(k => Object.prototype.hasOwnProperty.call(n, k))
const stamp = n => typeof n === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(n) && !Number.isNaN(Date.parse(n))
const uuid = n => typeof n === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(n)
export class RedemptionError extends ServiceError {
  constructor(kind, message, code = 0, retryAfter = 0) { super(kind, message); this.code = code; this.retryAfter = retryAfter }
}
const messages = {
  43001: '接车检查证据不完整，请核对接车单', 43002: '施工防护图片不可用，请核对防护记录',
  43003: '车主尚未确认接车单', 43004: '派工与技师接单证据不完整',
  43005: '完整报工或质检签字证据不可用，请核对施工记录', 43007: '存在未解决争议，请先处理争议',
  43009: '付款凭据缺失或存在异常，请核对付款记录', 40905: '订单状态或核销记录已变化，请刷新',
}
export function redemptionBody(order, code) {
  if (!id(order) || typeof code !== 'string' || !/^[0-9]{6}$/.test(code)) throw new RedemptionError('invalid', '请输入车主出示的六位数字核销码')
  return { order_id: order, code }
}
export const validRedemption = (n, order) => exact(n, ['order_id', 'order_status', 'redeemed_at', 'test_mode', 'changed']) && n.order_id === order && n.order_status === 'COMPLETED' && stamp(n.redeemed_at) && typeof n.test_mode === 'boolean' && typeof n.changed === 'boolean'
const validDetail = (n, order) => exact(n, ['order_id', 'redemption']) && n.order_id === order && (n.redemption === null || (exact(n.redemption, ['redeemed_at', 'test_mode']) && stamp(n.redemption.redeemed_at) && typeof n.redemption.test_mode === 'boolean'))
export function createRedemptionApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, body, key) {
    if (!token) throw new RedemptionError('unauthorized', '请先登录对应账号')
    if (!endpoint) throw new RedemptionError('unconfigured', '核销服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body ? 'POST' : 'GET', data: body, timeout: 15000,
        header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data, header }) => {
          if (statusCode < 200 || statusCode >= 300) {
            const kind = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 422: 'invalidCode', 429: 'rateLimited', 503: 'unavailable' }[statusCode] || 'server'
            const safe = { 400: '核销信息无效，请刷新后重试', 401: '登录已失效，请重新登录', 403: '当前身份无权核销本店订单', 404: '订单不存在、非本店订单或测试核销未开放', 422: '核销码无效，请向车主核对', 429: '核销码尝试过于频繁，请等待后再试', 503: '核销或付款通道暂不可用，请使用原请求重试' }[statusCode] || '核销请求未成功，请稍后重试'
            const raw = header?.['Retry-After'] ?? header?.['retry-after'], seconds = Number(raw)
            return reject(new RedemptionError(kind, messages[data?.code] || safe, data?.code, statusCode === 429 ? (Number.isInteger(seconds) && seconds >= 1 && seconds <= 600 ? seconds : 600) : 0))
          }
          if (!Number.isInteger(statusCode) || data?.code !== 0 || !validate(data.data)) return reject(new RedemptionError('protocol', '核销响应异常，请使用原请求重试'))
          resolve(data.data)
        }, fail: () => reject(serviceFailure()) })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    redeem(token, order, code, key) { redemptionBody(order, code); if (!uuid(key)) throw new RedemptionError('invalid', '核销请求编号无效'); return call(token, `/api/merchant/orders/${order}/redeem`, n => validRedemption(n, order), { code }, key) },
    detail(token, role, order) { if (!id(order) || !['merchant', 'owner'].includes(role)) throw new RedemptionError('invalid', '核销记录查询无效'); return call(token, role === 'merchant' ? `/api/merchant/orders/${order}/redemption` : `/api/order/${order}/redemption`, n => validDetail(n, order)) },
  }
}
export const redemptionApi = createRedemptionApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })

export const initialRedemptionState = () => ({ code: '', busy: false, saved: null, message: '', failureKind: '', waitUntil: 0 })
/** Pending credentials live only in page memory, with identity and modal callback guards. */
export function createRedemptionFlow({ state, token, order, api, newKey, confirm, onChanged = () => {}, now = Date.now }) {
  let generation = 0, active = false, pending = null
  function reset() { generation++; active = false; pending = null; Object.assign(state, initialRedemptionState()) }
  function resume() { active = true }
  async function submit() {
    if (!active || state.busy || state.saved || state.waitUntil > now()) return
    let body; try { body = redemptionBody(order(), state.code) } catch (e) { state.message = e.message; state.failureKind = 'invalid'; return }
    const actor = token(), current = ++generation; state.busy = true; state.message = ''; state.failureKind = ''
    const holds = () => active && generation === current && actor === token() && body.order_id === order() && body.code === state.code
    try {
      if (!await confirm() || !holds()) return
      if (!pending || pending.actor !== actor || pending.body.order_id !== body.order_id || pending.body.code !== body.code) pending = { actor, body, key: newKey() }
      const result = await api.redeem(actor, body.order_id, body.code, pending.key)
      if (!holds()) return
      state.saved = result; state.code = ''; pending = null; state.message = result.test_mode ? '测试核销已完成，未真实扣款' : '核销已完成'
      await onChanged()
    } catch (e) {
      if (!holds()) return
      const safe = e instanceof ServiceError ? e : serviceFailure(); state.message = safe.message; state.failureKind = safe.kind
      if (safe.kind === 'rateLimited') state.waitUntil = now() + safe.retryAfter * 1000
      if (['conflict', 'unauthorized', 'forbidden', 'missing'].includes(safe.kind)) { pending = null; state.code = ''; await onChanged() }
    } finally { if (generation === current) state.busy = false }
  }
  return { submit, reset, resume, suspend: reset }
}
