import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { validPaymentSummary } from './payment-contract.js'
import { ORDER_ACTIONS, ORDER_STATES, allowedActions } from './order-status.js'

const id = n => Number.isSafeInteger(n) && n > 0
const stamp = n => n == null || (typeof n === 'string' && !Number.isNaN(Date.parse(n)))
const money = n => typeof n === 'string' && /^(0|[1-9]\d{0,7})\.\d{2}$/.test(n) && Number(n) > 0
const status = n => ORDER_STATES.includes(n)
const snapshot = (value, fields) => value === null || (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(k => fields.includes(k)))
const row = value => id(value?.order_id) && typeof value.order_no === 'string' && status(value.status) && money(value.amount_due)
  && stamp(value.created_at) && stamp(value.expires_at) && stamp(value.closed_at)
  && typeof value.has_payment_exception === 'boolean' && validPaymentSummary(value.payment_summary) && allowedActions(value.allowed_actions)
  && snapshot(value.project_snapshot, ['standard_project_id', 'project_name', 'service_content'])
  && snapshot(value.merchant_snapshot, ['merchant_id', 'merchant_name', 'address'])
  && snapshot(value.appointment_snapshot, ['slot_id', 'starts_at', 'ends_at'])
  && !['user_id', 'vehicle_id', 'phone', 'plate_no', 'vin'].some(key => key in value)
const page = (value, n) => Array.isArray(value?.items) && value.items.length <= 20 && value.items.every(row)
  && value.page === n && value.page_size === 20 && Number.isSafeInteger(value.total) && value.total >= value.items.length
// 服务端是唯一权威：这些提示只解释拒绝原因，不代表前端可以做状态判定。
const CONFLICTS = {
  40905: '当前订单状态不支持该操作，请刷新后重试',
  43001: '接车检查未完成，请先完成接车检查',
  43003: '车主尚未确认接车，不能开始施工',
  43004: '请由被派工技师本人接单并开始施工',
  43005: '施工报工未完成，不能送核销',
  43006: '该操作的前置校验尚未接入，暂不能执行',
}
const KINDS = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' }
const MESSAGES = { 400: '订单操作参数无效', 401: '登录已失效，请重新登录', 403: '当前身份无权操作本店订单', 404: '订单不存在或不属于本店', 503: '本店订单暂不可用，请稍后重试' }

export function createMerchantOrdersApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, validate, body, key) {
    if (!token) throw new ServiceError('unauthorized', '请先登录商家账号')
    if (!endpoint) throw new ServiceError('unconfigured', '本店订单服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body ? 'POST' : 'GET', data: body, timeout: 15000,
        header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (statusCode < 200 || statusCode >= 300) {
            return reject(new ServiceError(KINDS[statusCode] || 'server', CONFLICTS[data?.code] || MESSAGES[statusCode] || '本店订单请求未成功，请稍后重试'))
          }
          if (!Number.isInteger(statusCode) || data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '本店订单响应异常，请重试'))
          resolve(data.data)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    list(token, n = 1, statusFilter = '', date = '') {
      if (!id(n) || n > 1000000 || (statusFilter && !status(statusFilter)) || (date && !/^\d{4}-\d{2}-\d{2}$/.test(date))) throw new ServiceError('invalid', '筛选条件无效')
      return request(token, `/api/merchant/orders?page=${n}&page_size=20${statusFilter ? '&status=' + statusFilter : ''}${date ? '&date=' + date : ''}`, result => page(result, n))
    },
    detail(token, orderId) {
      if (!id(orderId)) throw new ServiceError('invalid', '订单编号无效')
      return request(token, `/api/merchant/orders/${orderId}`, value => row(value) && snapshot(value.price_snapshot, ['merchant_project_id', 'quote_version_id', 'version', 'price']))
    },
    act(token, orderId, action, note, key) {
      if (!id(orderId)) throw new ServiceError('invalid', '订单编号无效')
      if (!ORDER_ACTIONS.includes(action)) throw new ServiceError('invalid', '订单操作无效')
      if (note != null && (typeof note !== 'string' || note.length < 1 || note.length > 200)) throw new ServiceError('invalid', '备注需为 1–200 字')
      return request(token, `/api/merchant/orders/${orderId}/actions`,
        value => row(value) && value.action === action && typeof value.from_status === 'string' && typeof value.changed === 'boolean',
        note ? { action, note } : { action }, key)
    },
  }
}
export const merchantOrdersApi = createMerchantOrdersApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
