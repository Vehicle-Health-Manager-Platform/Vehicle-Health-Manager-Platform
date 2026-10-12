import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class ArchiveShareError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
const id = value => Number.isSafeInteger(value) && value > 0
const integer = (value, max = 2147483647) => Number.isInteger(value) && value >= 0 && value <= max
const nullableNumber = value => value === null || integer(value)
export const validShareToken = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const instant = value => typeof value === 'string' && Number.isFinite(Date.parse(value)) && /Z$/.test(value)
const date = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) < 1000) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}
const nullableDate = value => value === null || date(value)
const keys = (value, names) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === names.length && names.every(k => Object.hasOwn(value, k))
export function validShareSummary(value) {
  return keys(value, ['version', 'generated_at', 'from', 'to', 'registered_mileage', 'undated_count', 'entries'])
    && value.version === 1 && instant(value.generated_at) && nullableDate(value.from) && nullableDate(value.to)
    && (value.from === null) === (value.to === null) && (value.from === null || value.from <= value.to)
    && nullableNumber(value.registered_mileage) && integer(value.undated_count) && Array.isArray(value.entries) && value.entries.length <= 500
    && value.entries.every(row => keys(row, ['archive_type', 'input_type', 'recorded_date', 'mileage', 'work_minutes', 'test_mode'])
      && integer(row.archive_type, 7) && row.archive_type > 0 && [1, 3, 4].includes(row.input_type)
      && nullableDate(row.recorded_date) && nullableNumber(row.mileage) && integer(row.work_minutes, 1440) && typeof row.test_mode === 'boolean')
}
const metadata = row => keys(row, ['share_id', 'vehicle_id', 'created_at', 'expires_at', 'status']) && id(row.share_id) && id(row.vehicle_id)
  && instant(row.created_at) && instant(row.expires_at) && ['ACTIVE', 'EXPIRED', 'REVOKED'].includes(row.status)
export function archiveShareInput(vehicleId, hours = 24, from = '', to = '') {
  if (!id(vehicleId) || ![1, 24, 168].includes(hours) || Boolean(from) !== Boolean(to) || (from && (!date(from) || !date(to) || from > to)))
    throw new ArchiveShareError('invalid', '请检查车辆、日期范围与有效期')
  return { vehicle_id: vehicleId, expires_in_hours: hours, ...(from ? { from, to } : {}) }
}
export function canReturnToArchiveShare(pages) { return Array.isArray(pages) && pages.length > 1 && pages[pages.length - 2]?.route === 'pages/archive/shared' }
export function createArchiveShareApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new ArchiveShareError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ArchiveShareError('unconfigured', '分享服务尚未配置')
    if (key !== undefined && !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key)) throw new ArchiveShareError('invalid', '请求信息无效，请重试')
    return new Promise((resolve, reject) => {
      const failure = (kind, message) => reject(new ArchiveShareError(kind, message))
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            if (statusCode === 401) return failure('unauthorized', '登录已失效，请重新登录')
            if (statusCode === 403) return failure('forbidden', '请使用车主账号查看')
            if ([404, 410].includes(statusCode)) return failure('gone', '分享链接已失效，请联系车主重新分享')
            if (statusCode === 409) return failure('limit', '记录或有效分享数量超过上限，请缩小范围或撤销旧分享')
            if (statusCode === 400) return failure('invalid', '分享参数无效，请检查后重试')
            if (statusCode === 503) return failure('unavailable', '分享服务暂不可用，请稍后重试')
            if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return failure('server', '分享请求未成功，请重试')
            try { if (data?.code !== 0 || !validate(data.data)) return failure('protocol', '分享服务响应异常，请重试') }
            catch { return failure('protocol', '分享服务响应异常，请重试') }
            resolve(data.data)
          }, fail: () => failure('network', '无法连接分享服务，请检查网络后重试'),
        })
      } catch { failure('network', '无法连接分享服务，请稍后重试') }
    })
  }
  return {
    create(token, body, key) {
      const normalized = archiveShareInput(body.vehicle_id, body.expires_in_hours, body.from, body.to)
      if (JSON.stringify(normalized) !== JSON.stringify(body)) throw new ArchiveShareError('invalid', '分享参数无效')
      return request(token, '/api/archive/shares', 'POST', body, key, row => metadata(row) && row.vehicle_id === body.vehicle_id)
    },
    list(token, vehicle) {
      if (!id(vehicle)) throw new ArchiveShareError('invalid', '车辆参数无效')
      return request(token, `/api/archive/shares?vehicle_id=${vehicle}`, 'GET', undefined, undefined,
        data => keys(data, ['list']) && Array.isArray(data.list) && data.list.length <= 100 && data.list.every(row => metadata(row) && row.vehicle_id === vehicle))
    },
    link(token, shareId) {
      if (!id(shareId)) throw new ArchiveShareError('invalid', '分享参数无效')
      return request(token, `/api/archive/shares/${shareId}/link`, 'GET', undefined, undefined,
        data => keys(data, ['share_id', 'path', 'expires_at']) && data.share_id === shareId && instant(data.expires_at)
          && typeof data.path === 'string' && /^\/pages\/archive\/shared\?token=[a-f0-9]{64}$/.test(data.path))
    },
    revoke(token, shareId, key) {
      if (!id(shareId)) throw new ArchiveShareError('invalid', '分享参数无效')
      return request(token, `/api/archive/shares/${shareId}/revoke`, 'POST', {}, key, row => metadata(row) && row.share_id === shareId && row.status === 'REVOKED')
    },
    view(token, capability) {
      if (!validShareToken(capability)) throw new ArchiveShareError('gone', '分享链接无效，请联系车主重新分享')
      return request(token, '/api/archive/share/view', 'POST', { share_token: capability }, undefined,
        data => keys(data, ['summary', 'expires_at']) && instant(data.expires_at) && Date.parse(data.expires_at) > Date.now() && validShareSummary(data.summary))
    },
  }
}
// Retain only a pending mutation receipt identity in memory; identical retries reuse the UUID.
export function createArchiveShareMutation({ api, newKey }) {
  let pending = null
  return {
    create(token, body) {
      const fingerprint = JSON.stringify(body)
      if (!pending || pending.token !== token || pending.fingerprint !== fingerprint) pending = { token, fingerprint, key: newKey() }
      const operation = pending
      return api.create(token, body, operation.key).then(result => { if (pending === operation) pending = null; return result })
    },
    reset() { pending = null },
  }
}
export const archiveShareApi = createArchiveShareApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
