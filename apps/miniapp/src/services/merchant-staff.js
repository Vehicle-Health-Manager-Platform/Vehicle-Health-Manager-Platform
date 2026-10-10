import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'

// 店长维护本店店员与技师的客户端。请求/响应、权限与错误码见 docs/api/MERCHANT_STAFF.md。
// 前端只做形状校验与展示：谁能执行管理动作、员工属于哪家门店，全部由服务端判定。
export const STAFF_ROLES = ['STAFF', 'TECHNICIAN']
export const STAFF_ROLE_LABELS = { STAFF: '店员', TECHNICIAN: '技师' }
export const staffRoleLabel = value => STAFF_ROLE_LABELS[value] || '角色未提供'
export const STAFF_STATUSES = ['ACTIVE', 'DISABLED']
export const staffStatusLabel = value => (value === 'ACTIVE' ? '已启用' : value === 'DISABLED' ? '已停用' : '状态未提供')

// 员工行是白名单投影：密码、员工码明文与手机号原文都不得出现。
// 键集合必须**恰好**相等——少一个字段客户端就会误判，多一个字段则说明投影越界。
export const STAFF_ROW_KEYS = ['staff_id', 'account', 'role', 'display_name', 'phone_masked', 'status',
  'employee_code_issued', 'wechat_bound', 'created_at']

const id = n => Number.isSafeInteger(n) && n > 0
const pageNumber = n => id(n) && n <= 1000000
const uuid = n => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(String(n || ''))
const stamp = n => n == null || (typeof n === 'string' && !Number.isNaN(Date.parse(n)))
const maskedPhone = n => n === null || (typeof n === 'string' && /^1[0-9]{2}\*{4}[0-9]{4}$/.test(n))
const phone = n => typeof n === 'string' && /^1[3-9][0-9]{9}$/.test(n)
const displayName = n => typeof n === 'string' && [...n].length >= 2 && [...n].length <= 32
  && n === n.trim() && !/[\u0000-\u001f\u007f]/.test(n)
const password = n => typeof n === 'string' && n.length >= 8 && n.length <= 64
  && !/[^\u0021-\u007e]/.test(n) && /[A-Za-z]/.test(n) && /[0-9]/.test(n)

const keysAre = (value, expected) => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === expected.length && expected.every(field => field in value)

const staffRow = item => keysAre(item, STAFF_ROW_KEYS) && id(item.staff_id)
  && typeof item.account === 'string' && item.account.length > 0
  && STAFF_ROLES.includes(item.role) && displayName(item.display_name)
  && maskedPhone(item.phone_masked) && STAFF_STATUSES.includes(item.status)
  && typeof item.employee_code_issued === 'boolean' && typeof item.wechat_bound === 'boolean'
  && stamp(item.created_at)

const staffPage = (value, page) => keysAre(value, ['items', 'total', 'page', 'page_size'])
  && Array.isArray(value.items) && value.items.length <= 20 && value.items.every(staffRow)
  && new Set(value.items.map(item => item.staff_id)).size === value.items.length
  && value.page === page && value.page_size === 20
  && Number.isSafeInteger(value.total) && value.total >= value.items.length

// 新增表单 → 请求体。店员的短信登录依赖本人手机号，因此店员必填手机号。
export function staffBody(state) {
  if (!STAFF_ROLES.includes(state?.role)) throw new ServiceError('invalid', '请选择店员或技师')
  if (!displayName(state.displayName)) throw new ServiceError('invalid', '姓名需为 2–32 个字符，首尾不能有空格')
  if (!password(state.password)) throw new ServiceError('invalid', '密码需 8–64 位，且同时包含字母与数字')
  const body = { role: state.role, display_name: state.displayName, password: state.password }
  if (state.role === 'STAFF' && !phone(state.phone)) throw new ServiceError('invalid', '店员必须填写可接收短信的本人手机号')
  if (state.phone) {
    if (!phone(state.phone)) throw new ServiceError('invalid', '手机号格式不正确')
    body.phone = state.phone
  }
  return body
}

// 启停响应带 sessions_revoked（停用）或不带（启用）；出现时必须是合法计数。
const toggleView = (value, staffId, status) => keysAre(value, value?.sessions_revoked === undefined
  ? ['staff_id', 'status'] : ['staff_id', 'status', 'sessions_revoked'])
  && value.staff_id === staffId && value.status === status
  && (value.sessions_revoked === undefined
    || (Number.isSafeInteger(value.sessions_revoked) && value.sessions_revoked >= 0))

const codeView = (value, staffId) => keysAre(value, ['staff_id', 'employee_code', 'single_use'])
  && value.staff_id === staffId && typeof value.employee_code === 'string' && value.employee_code.length > 0
  && value.single_use === true
const revokedView = (value, staffId) => keysAre(value, ['staff_id', 'employee_code_revoked'])
  && value.staff_id === staffId && value.employee_code_revoked === true

// 服务端是唯一权威：这里的文案只解释拒绝原因，不代表前端判定权限。
const KINDS = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' }
const CONFLICTS = {
  40300: '只有店长可以维护本店员工与员工码',
  40400: '员工不存在或不属于本店',
  40900: '员工已停用不能签发员工码，或本店账号序号已用尽',
}
const MESSAGES = {
  400: '提交内容不符合要求，请检查后重试', 401: '门店登录已失效，请重新登录',
  403: '只有店长可以维护本店员工与员工码', 404: '员工不存在或不属于本店',
  409: '员工已停用不能签发员工码，或本店账号序号已用尽', 503: '门店员工功能尚未开启或暂不可用，请稍后重试',
}

export const staffFailure = error => error instanceof ServiceError ? error : serviceFailure()

export function createMerchantStaffApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, options = {}) {
    const { method = 'GET', body, key } = options
    if (!token) throw new ServiceError('unauthorized', '请先登录门店账号')
    if (!endpoint) throw new ServiceError('unconfigured', '门店员工服务尚未配置')
    if (key !== undefined && !uuid(key)) throw new ServiceError('invalid', '提交键无效')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({
          url: endpoint + path, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key === undefined ? {} : { 'Idempotency-Key': key }) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new ServiceError('protocol', '门店员工响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              return reject(new ServiceError(KINDS[statusCode] || 'server',
                CONFLICTS[data?.code] || MESSAGES[statusCode] || '门店员工请求未成功，请稍后重试'))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '门店员工响应异常，请重试'))
            resolve(data.data)
          },
          fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    list(token, role = '', page = 1) {
      if (role && !STAFF_ROLES.includes(role)) throw new ServiceError('invalid', '角色筛选无效')
      if (!pageNumber(page)) throw new ServiceError('invalid', '分页无效')
      return call(token, `/api/merchant/staff?page=${page}&page_size=20${role ? `&role=${role}` : ''}`,
        result => staffPage(result, page))
    },
    create(token, body, key) {
      if (!uuid(key)) throw new ServiceError('invalid', '提交键无效')
      // 新增响应与列表行逐字段同形，因此复用同一个行校验器。
      return call(token, '/api/merchant/staff', staffRow, { method: 'POST', body, key })
    },
    disable(token, staffId, key) {
      if (!id(staffId)) throw new ServiceError('invalid', '员工编号无效')
      if (!uuid(key)) throw new ServiceError('invalid', '提交键无效')
      return call(token, `/api/merchant/staff/${staffId}/disable`,
        value => toggleView(value, staffId, 'DISABLED'), { method: 'POST', body: {}, key })
    },
    enable(token, staffId, key) {
      if (!id(staffId)) throw new ServiceError('invalid', '员工编号无效')
      if (!uuid(key)) throw new ServiceError('invalid', '提交键无效')
      return call(token, `/api/merchant/staff/${staffId}/enable`,
        value => toggleView(value, staffId, 'ACTIVE'), { method: 'POST', body: {}, key })
    },
    // 员工码是轮换语义，刻意不带幂等键：幂等重放要返回原响应，与「旧码立即失效」直接冲突，
    // 而且一次性明文码一旦进入幂等记录或审计，就是磁盘上的明文泄露。
    issueCode(token, staffId) {
      if (!id(staffId)) throw new ServiceError('invalid', '员工编号无效')
      return call(token, `/api/merchant/staff/${staffId}/employee-code`,
        value => codeView(value, staffId), { method: 'POST' })
    },
    revokeCode(token, staffId) {
      if (!id(staffId)) throw new ServiceError('invalid', '员工编号无效')
      return call(token, `/api/merchant/staff/${staffId}/employee-code`,
        value => revokedView(value, staffId), { method: 'DELETE' })
    },
  }
}
export const merchantStaffApi = createMerchantStaffApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
