import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { ORDER_STATES } from './order-status.js'

// 争议处理客户端：商家追加处理记录，车主复核决定是否恢复订单。
// 契约见 docs/api/DISPUTE_RESOLUTION.md；前端只做形状校验与展示，不参与状态决策。
export const DISPUTE_STATUSES = ['OPEN', 'RESOLVED']
export const DISPUTE_ACTIONS = ['HANDLE', 'ACCEPT', 'REJECT']
export const DISPUTE_DECISIONS = ['ACCEPT', 'REJECT']
export const DISPUTE_STATUS_LABELS = { OPEN: '争议处理中', RESOLVED: '争议已解决' }
export const DISPUTE_ACTION_LABELS = { HANDLE: '商家处理', ACCEPT: '车主接受处理', REJECT: '车主不接受' }
export const disputeStatusLabel = value => DISPUTE_STATUS_LABELS[value] || '争议状态未提供'
export const disputeActionLabel = value => DISPUTE_ACTION_LABELS[value] || '记录'
/** 恢复后的订单状态只可能是争议前的那几种，永远不会是终态。 */
export const RESUMABLE_STATES = ['PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY']

const id = n => Number.isSafeInteger(n) && n > 0
const stamp = n => n == null || (typeof n === 'string' && !Number.isNaN(Date.parse(n)))
const timeText = n => typeof n === 'string' && !Number.isNaN(Date.parse(n))
const requestKeyValid = n => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(String(n || ''))

// 时间线与结果都是白名单投影：出现任何身份字段都说明越界。
const RECORD_FIELDS = ['action', 'note', 'created_at']
const DISPUTE_FIELDS = ['dispute_id', 'status', 'reason', 'from_status', 'opened_at', 'resolved_at', 'records', 'can_review']
const RESULT_FIELDS = ['dispute_id', 'order_id', 'dispute_status', 'order_status', 'owner_confirm', 'record_count', 'last_action', 'updated_at', 'changed']
const FORBIDDEN = ['actor_id', 'actor_type', 'opened_by', 'resolved_by', 'merchant_id', 'staff_id', 'user_id',
  'verify_code', 'appointment_code', 'appointment_snapshot', 'project_snapshot', 'payment_summary']

export const disputeRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).every(field => RECORD_FIELDS.includes(field))
  && DISPUTE_ACTIONS.includes(value.action) && timeText(value.created_at)
  && (value.note === null || typeof value.note === 'string')

/** 接车单投影里的 dispute 字段：null 表示尚未产生争议。 */
export const disputeView = value => value === null
  || (value !== null && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).every(field => DISPUTE_FIELDS.includes(field))
    && id(value.dispute_id) && DISPUTE_STATUSES.includes(value.status)
    && typeof value.reason === 'string' && value.reason.length > 0 && value.reason.length <= 500
    && ORDER_STATES.includes(value.from_status) && timeText(value.opened_at) && stamp(value.resolved_at)
    && Array.isArray(value.records) && value.records.every(disputeRecord)
    && typeof value.can_review === 'boolean'
    // can_review 只可能在「争议未解决 + 商家已提交处理记录」时出现，否则按钮会误导车主。
    && (!value.can_review || (value.status === 'OPEN' && value.records.some(record => record.action === 'HANDLE')))
    && !FORBIDDEN.some(field => field in value))

const result = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).every(field => RESULT_FIELDS.includes(field))
  && id(value.dispute_id) && id(value.order_id) && DISPUTE_STATUSES.includes(value.dispute_status)
  && ORDER_STATES.includes(value.order_status) && [0, 1, 2, 3].includes(value.owner_confirm)
  && Number.isSafeInteger(value.record_count) && value.record_count >= 1
  && (value.last_action === null || DISPUTE_ACTIONS.includes(value.last_action))
  && timeText(value.updated_at) && typeof value.changed === 'boolean'
  && !FORBIDDEN.some(field => field in value)

/** 商家提交处理记录后仍是未解决状态。 */
const handled = value => result(value) && value.dispute_status === 'OPEN' && value.order_status === 'DISPUTED'
  && value.owner_confirm === 2 && value.last_action === 'HANDLE'
/** 车主接受后争议已解决、订单回到争议前状态、接车单转为"争议已解决"。 */
const accepted = value => result(value) && value.dispute_status === 'RESOLVED' && value.owner_confirm === 3
  && RESUMABLE_STATES.includes(value.order_status) && value.last_action === 'ACCEPT'
/** 车主不接受后争议保持未解决，订单仍在争议中。 */
const rejected = value => result(value) && value.dispute_status === 'OPEN' && value.order_status === 'DISPUTED'
  && value.owner_confirm === 2 && value.last_action === 'REJECT'

// 服务端是唯一权威：这些文案只解释拒绝原因，不代表前端可以做状态判定。
const CONFLICTS = {
  40905: '争议已解决或订单状态已变化，请刷新后重试',
  43007: '订单存在未解决的争议，请先在接车单处理争议',
  43008: '商家尚未提交处理记录，暂不能复核',
}
const KINDS = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' }
const DEFAULTS = { 400: '争议处理参数无效', 401: '登录已失效，请重新登录', 403: '当前身份无权处理争议', 404: '争议资源不存在或不属于本人', 503: '争议处理服务暂不可用，请使用原幂等键重试' }

export class DisputeError extends ServiceError {
  constructor(kind, message, code = 0) { super(kind, message); this.code = code }
}
export const disputeFailure = error => error instanceof ServiceError ? error : serviceFailure()
const invalid = message => new DisputeError('invalid', message)

export function createDisputeApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')

  function call(token, path, validate, body, key, hint) {
    if (!token) throw new DisputeError('unauthorized', hint)
    if (!endpoint) throw new DisputeError('unconfigured', '争议处理服务尚未配置')
    if (!requestKeyValid(key)) throw invalid('提交键无效')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({
          url: endpoint + path, method: 'POST', data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, 'Idempotency-Key': key },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new DisputeError('protocol', '争议响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              return reject(new DisputeError(KINDS[statusCode] || 'server',
                CONFLICTS[data?.code] || DEFAULTS[statusCode] || '争议请求未成功，请稍后重试', data?.code || 0))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new DisputeError('protocol', '争议响应异常，请重试'))
            resolve(data.data)
          },
          fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }

  return {
    // 商家：追加一条处理记录（可多次）
    handle(token, orderId, note, key) {
      if (!id(orderId)) throw invalid('订单编号无效')
      const text = String(note ?? '').trim()
      if (!text || text.length > 500) throw invalid('请填写 1–500 字的处理说明')
      return call(token, `/api/merchant/orders/${orderId}/dispute/handle`, handled, { note: text }, key, '请先登录商家账号')
    },
    // 车主：复核；ACCEPT 恢复订单，REJECT 保持争议
    review(token, orderId, decision, note, key) {
      if (!id(orderId)) throw invalid('订单编号无效')
      if (!DISPUTE_DECISIONS.includes(decision)) throw invalid('复核决定无效')
      const text = String(note ?? '').trim()
      if (text.length > 500) throw invalid('说明不能超过 500 字')
      if (decision === 'REJECT' && !text) throw invalid('不接受时必须填写 1–500 字原因')
      const payload = { order_id: orderId, decision, ...(text ? { note: text } : {}) }
      return call(token, '/api/check/pickup/dispute/review', decision === 'ACCEPT' ? accepted : rejected, payload, key, '请先登录车主账号')
    },
  }
}

export const disputeApi = createDisputeApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
