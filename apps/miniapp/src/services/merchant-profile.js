import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'

// 本店对外资料：店长与店员都可读，只有店长可写。契约见 docs/api/MERCHANT_STAFF.md。
// `can_edit` 是服务端给出的「当前登录身份是否为店长」，页面据此决定是否展示编辑表单——
// 前端不自行判断角色。
export const PROFILE_REQUIRED_KEYS = ['merchant_id', 'name', 'address', 'contact_phone', 'lng', 'lat',
  'merchant_type', 'region_code', 'status', 'can_edit']
export const EDITABLE_KEYS = ['name', 'address', 'contact_phone', 'lng', 'lat']
// 只读字段：品类、区域、经营状态属于入驻与运营范畴，读得到但不得经本接口修改。
export const READ_ONLY_KEYS = ['merchant_type', 'region_code', 'status']
// 读响应里绝不该出现的字段：资质、评级、佣金与区域保护是运营侧数据，不该经门店自助接口外泄。
// 注意与 READ_ONLY_KEYS 分开——`region_code` 是合法只读字段，混在一起会让整个读响应被判协议错误。
export const NEVER_READ_KEYS = ['qualification', 'grade', 'commission_rate', 'region_protected', 'is_deleted']

export const MERCHANT_TYPE_LABELS = { 1: '洗车美容', 2: '维修保养', 3: '轮胎', 4: '加油站', 5: '保险', 6: '用品' }
export const merchantTypeLabel = value => MERCHANT_TYPE_LABELS[value] || '类型未提供'
export const MERCHANT_STATUS_LABELS = { 0: '待审核', 1: '在营', 2: '已拒绝' }
export const merchantStatusLabel = value => MERCHANT_STATUS_LABELS[value] || '状态未提供'

const id = n => Number.isSafeInteger(n) && n > 0
const uuid = n => /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(String(n || ''))
const phone = n => typeof n === 'string' && /^1[3-9][0-9]{9}$/.test(n)
const storeName = n => typeof n === 'string' && [...n].length >= 2 && [...n].length <= 64
  && n === n.trim() && !/[\u0000-\u001f\u007f]/.test(n)
const address = n => typeof n === 'string' && n.length >= 1 && n.length <= 256 && n === n.trim()
  && !/[\u0000-\u001f\u007f]/.test(n)
// 坐标以字符串回传（DECIMAL 列），写入时必须是 JSON 数字；两者都要校验。
const coordinateText = n => n === null || (typeof n === 'string' && /^-?\d{1,3}(\.\d+)?$/.test(n))

const keysAre = (value, expected) => value !== null && typeof value === 'object' && !Array.isArray(value)
  && Object.keys(value).length === expected.length && expected.every(field => field in value)

const profileView = value => keysAre(value, PROFILE_REQUIRED_KEYS) && id(value.merchant_id)
  && storeName(value.name) && address(value.address)
  && (value.contact_phone === null || phone(value.contact_phone))
  && coordinateText(value.lng) && coordinateText(value.lat)
  && Number.isInteger(value.merchant_type) && value.merchant_type >= 1 && value.merchant_type <= 6
  && typeof value.region_code === 'string'
  && Number.isInteger(value.status) && value.status >= 0 && value.status <= 2
  && typeof value.can_edit === 'boolean'
// 读响应绝不允许出现 NEVER_READ_KEYS 里的字段。这不是运行时再查一遍——
// keysAre 已要求「恰好这 10 个键」，多一个就判错；该清单的作用是让白名单本身可被断言，
// 防止日后有人把资质/佣金之类运营字段写进 PROFILE_REQUIRED_KEYS（见离线测试）。

// 写响应就是白名单五字段本身，且必须与读响应里的同名字段同形。
const editableView = value => keysAre(value, EDITABLE_KEYS) && storeName(value.name) && address(value.address)
  && phone(value.contact_phone) && coordinateText(value.lng) && coordinateText(value.lat)

// 表单（字符串）→ 请求体（坐标转数字或 null）。空串表示未填，与 null 等价。
export function profileBody(state) {
  if (!storeName(state?.name)) throw new ServiceError('invalid', '门店名称需为 2–64 个字符，首尾不能有空格')
  if (!address(state?.address)) throw new ServiceError('invalid', '门店地址需为 1–256 个字符，首尾不能有空格')
  if (!phone(state?.contactPhone)) throw new ServiceError('invalid', '联系电话需为可接收短信的大陆手机号')
  return {
    name: state.name,
    address: state.address,
    contact_phone: state.contactPhone,
    lng: coordinate(state.lng, 180, '经度'),
    lat: coordinate(state.lat, 90, '纬度'),
  }
}

function coordinate(raw, bound, label) {
  // 表单里坐标是字符串，空串表示「未填」；不要把空串当成 0（Number('') === 0）。
  if (raw === '' || raw === null || raw === undefined) return null
  const trimmed = typeof raw === 'string' ? raw.trim() : raw
  const value = typeof trimmed === 'number' ? trimmed
    : (typeof trimmed === 'string' && /^-?\d{1,3}(\.\d+)?$/.test(trimmed) ? Number(trimmed) : NaN)
  if (!Number.isFinite(value) || Math.abs(value) > bound) {
    throw new ServiceError('invalid', `${label}需为 -${bound} 到 ${bound} 之间的数字，或留空`)
  }
  return value
}

const KINDS = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 503: 'unavailable' }
const CONFLICTS = {
  40300: '只有店长可以修改本店资料',
  40400: '本店不存在或已停用',
}
const MESSAGES = {
  400: '提交内容不符合要求，请检查后重试', 401: '门店登录已失效，请重新登录',
  403: '只有店长可以修改本店资料', 404: '本店不存在或已停用',
  503: '门店资料服务尚未开启或暂不可用，请稍后重试',
}

export const profileFailure = error => error instanceof ServiceError ? error : serviceFailure()

export function createMerchantProfileApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, validate, options = {}) {
    const { method = 'GET', body, key } = options
    if (!token) throw new ServiceError('unauthorized', '请先登录门店账号')
    if (!endpoint) throw new ServiceError('unconfigured', '门店资料服务尚未配置')
    if (key !== undefined && !uuid(key)) throw new ServiceError('invalid', '提交键无效')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({
          url: endpoint + path, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key === undefined ? {} : { 'Idempotency-Key': key }) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new ServiceError('protocol', '门店资料响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              return reject(new ServiceError(KINDS[statusCode] || 'server',
                CONFLICTS[data?.code] || MESSAGES[statusCode] || '门店资料请求未成功，请稍后重试'))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '门店资料响应异常，请重试'))
            resolve(data.data)
          },
          fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    read(token) {
      return request(token, '/api/merchant/profile', profileView)
    },
    update(token, body, key) {
      if (!uuid(key)) throw new ServiceError('invalid', '提交键无效')
      return request(token, '/api/merchant/profile', editableView, { method: 'PUT', body, key })
    },
  }
}
export const merchantProfileApi = createMerchantProfileApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
