import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { ORDER_STATES } from './order-status.js'

// 商家派工与技师本人接单客户端。六个接口的请求/响应、权限与错误码见
// docs/api/TECHNICIAN_DISPATCH.md；前端只做形状校验与展示，不参与状态决策。
export const ASSIGNMENT_STATUSES = ['ASSIGNED', 'ACCEPTED']
export const ASSIGNMENT_LABELS = { ASSIGNED: '待接单', ACCEPTED: '已接单' }
export const assignmentStatusLabel = value => ASSIGNMENT_LABELS[value] || '派工状态未提供'

const id = n => Number.isSafeInteger(n) && n > 0
const pageNumber = n => id(n) && n <= 1000000
const requestKeyValid = n => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(String(n || ''))
const stamp = n => n == null || (typeof n === 'string' && !Number.isNaN(Date.parse(n)))
const timeText = n => typeof n === 'string' && !Number.isNaN(Date.parse(n))

// 快照是白名单投影：键集合必须是允许字段的子集，多余字段一律视为契约被破坏。
const snapshot = (value, fields) => value === null
  || (value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(field => fields.includes(field)))
const PROJECT_FIELDS = ['standard_project_id', 'project_name', 'service_content']
const APPOINTMENT_FIELDS = ['slot_id', 'starts_at', 'ends_at']
// 技师工单是最小投影。出现任何车主、车辆、付款或核销身份字段都说明越界。
const TECHNICIAN_FORBIDDEN = ['technician_id', 'merchant_id', 'assigned_by', 'binding_id', 'openid', 'changed',
  'user_id', 'vehicle_id', 'phone', 'plate_no', 'vin', 'appointment_code', 'pickup_code', 'verify_code',
  'payment_summary', 'pickup_check_id']
// 商家侧可以知道派给了本店哪位技师，但同样不得看到车主与付款身份。
const ASSIGNMENT_FORBIDDEN = ['assigned_by', 'binding_id', 'openid', 'user_id', 'vehicle_id', 'phone',
  'plate_no', 'vin', 'appointment_code', 'pickup_code', 'verify_code', 'payment_summary', 'pickup_check_id']

const candidateRow = value => id(value?.technician_id) && typeof value.label === 'string' && value.label.length > 0
  && Object.keys(value).every(field => ['technician_id', 'label'].includes(field))
const candidatePage = (value, page) => Array.isArray(value?.items) && value.items.length <= 20 && value.items.every(candidateRow)
  && value.page === page && value.page_size === 20 && Number.isSafeInteger(value.total) && value.total >= value.items.length

const assignmentRow = value => id(value?.assignment_id) && id(value.order_id) && id(value.technician_id)
  && ASSIGNMENT_STATUSES.includes(value.status) && typeof value.technician_label === 'string' && value.technician_label.length > 0
  && timeText(value.assigned_at) && stamp(value.accepted_at)
  && !ASSIGNMENT_FORBIDDEN.some(field => field in value)
const assignmentView = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).every(field => field === 'assignment')
  && 'assignment' in value && (value.assignment === null || assignmentRow(value.assignment))

const writeView = value => id(value?.assignment_id) && id(value.order_id) && id(value.technician_id)
  && ASSIGNMENT_STATUSES.includes(value.assignment_status) && ORDER_STATES.includes(value.order_status)
  && timeText(value.assigned_at) && stamp(value.accepted_at) && typeof value.changed === 'boolean'

const workRow = value => id(value?.assignment_id) && id(value.order_id) && typeof value.order_no === 'string' && value.order_no.length > 0
  && ORDER_STATES.includes(value.order_status) && ASSIGNMENT_STATUSES.includes(value.assignment_status)
  && timeText(value.assigned_at) && stamp(value.accepted_at) && typeof value.can_accept === 'boolean'
  && snapshot(value.project_snapshot, PROJECT_FIELDS) && snapshot(value.appointment_snapshot, APPOINTMENT_FIELDS)
  // can_accept 只可能出现在「本人待接 + 订单已接车」的组合上；否则按钮会误导技师。
  && (!value.can_accept || (value.assignment_status === 'ASSIGNED' && value.order_status === 'RECEIVED'))
  && !TECHNICIAN_FORBIDDEN.some(field => field in value)
const workPage = (value, page) => Array.isArray(value?.items) && value.items.length <= 20 && value.items.every(workRow)
  && value.page === page && value.page_size === 20 && Number.isSafeInteger(value.total) && value.total >= value.items.length

// 服务端是唯一权威：这些文案只解释拒绝原因，不代表前端可以做状态判定。
const CONFLICTS = {
  40905: '派工或订单状态已变化，请刷新后重试',
  43001: '接车检查未完成，请先完成接车检查',
  43003: '车主尚未确认接车，不能派工或接单',
  43004: '请由被派工技师本人接单并开始施工',
}
const KINDS = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' }
const DEFAULTS = { 400: '派工或工单参数无效', 401: '登录已失效，请重新登录', 403: '当前身份无权操作派工或工单', 404: '派工或工单资源不存在', 503: '派工服务暂不可用，请使用原幂等键重试' }

/**
 * 异步确认（模态框、系统回调）的守卫。
 *
 * 弹窗的 success 回调会在任意时刻返回：这期间用户可能切换账号、离开页面或换选目标。
 * 只要身份、页面可见性或目标任一变化，旧确认就必须作废——否则会用新账号提交旧工单，
 * 或者把技师派成用户已经改掉的那一位。
 */
export const confirmStillHolds = (opened, now) => Boolean(now?.visible)
  && opened?.actor === now?.token
  && (opened?.target === undefined || opened.target === now?.target)

export class DispatchError extends ServiceError {
  constructor(kind, message, code = 0) { super(kind, message); this.code = code }
}
export const dispatchFailure = error => error instanceof ServiceError ? error : serviceFailure()
const invalid = message => new DispatchError('invalid', message)

export function createDispatchApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')

  function call(token, path, validate, options = {}) {
    const { body, key, hint } = options
    if (!token) throw new DispatchError('unauthorized', hint)
    if (!endpoint) throw new DispatchError('unconfigured', '派工服务尚未配置')
    if (key !== undefined && !requestKeyValid(key)) throw invalid('提交键无效')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({
          url: endpoint + path, method: body === undefined ? 'GET' : 'POST', data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key === undefined ? {} : { 'Idempotency-Key': key }) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new DispatchError('protocol', '派工响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              return reject(new DispatchError(KINDS[statusCode] || 'server',
                CONFLICTS[data?.code] || DEFAULTS[statusCode] || '派工或工单请求未成功，请稍后重试', data?.code || 0))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new DispatchError('protocol', '派工响应异常，请重试'))
            resolve(data.data)
          },
          fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }

  return {
    // 商家：本店候选技师
    candidates(token, page = 1) {
      if (!pageNumber(page)) throw invalid('分页无效')
      return call(token, `/api/merchant/technicians?page=${page}&page_size=20`, result => candidatePage(result, page),
        { hint: '请先登录商家账号' })
    },
    // 商家：查询本店订单的派工结果
    assignment(token, orderId) {
      if (!id(orderId)) throw invalid('订单编号无效')
      return call(token, `/api/merchant/orders/${orderId}/assignment`, assignmentView, { hint: '请先登录商家账号' })
    },
    // 商家：首次派工
    assign(token, orderId, technicianId, key) {
      if (!id(orderId)) throw invalid('订单编号无效')
      if (!id(technicianId)) throw invalid('请选择要派工的技师')
      if (!requestKeyValid(key)) throw invalid('提交键无效')
      return call(token, `/api/merchant/orders/${orderId}/assign`,
        result => writeView(result) && result.assignment_status === 'ASSIGNED' && result.order_status === 'RECEIVED',
        { body: { technician_id: technicianId }, key, hint: '请先登录商家账号' })
    },
    // 技师：本人工单列表
    orders(token, page = 1, assignmentStatus = '') {
      if (!pageNumber(page)) throw invalid('分页无效')
      if (assignmentStatus && !ASSIGNMENT_STATUSES.includes(assignmentStatus)) throw invalid('派工状态筛选无效')
      return call(token, `/api/tech/orders?page=${page}&page_size=20${assignmentStatus ? `&assignment_status=${assignmentStatus}` : ''}`,
        result => workPage(result, page), { hint: '请先登录技师账号' })
    },
    // 技师：本人工单详情
    order(token, orderId) {
      if (!id(orderId)) throw invalid('工单编号无效')
      return call(token, `/api/tech/orders/${orderId}`, workRow, { hint: '请先登录技师账号' })
    },
    // 技师：本人接单并开始施工
    accept(token, orderId, key) {
      if (!id(orderId)) throw invalid('工单编号无效')
      if (!requestKeyValid(key)) throw invalid('提交键无效')
      return call(token, `/api/tech/orders/${orderId}/accept`,
        result => writeView(result) && result.assignment_status === 'ACCEPTED' && result.order_status === 'IN_SERVICE'
          // 接单成功必须带接单时间，否则「已接单」没有任何可核对的时间证据。
          && timeText(result.accepted_at),
        { body: {}, key, hint: '请先登录技师账号' })
    },
  }
}

export const dispatchApi = createDispatchApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
