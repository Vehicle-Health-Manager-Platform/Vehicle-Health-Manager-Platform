import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class ArchiveError extends Error {
  constructor(kind, message) { super(message); this.kind = kind }
}
const id = value => Number.isSafeInteger(value) && value > 0
const messages = {
  400: ['invalid', '档案信息无效，请检查后重试'],
  401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '当前身份无权操作，请重新登录车主账号'],
  404: ['missing', '车辆或图片已不可用，请刷新后重试'],
  503: ['unavailable', '档案服务暂不可用，请稍后重试'],
}
export const archiveFailure = error => error instanceof ArchiveError ? error : new ArchiveError('network', '无法连接档案服务，请检查网络后重试')
const validDate = value => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value && Number(value.slice(0, 4)) >= 1000
}
export function archiveBody(fields) {
  const inputType = fields.inputType ?? 3
  const title = typeof fields.title === 'string' ? fields.title.trim() : ''
  const notes = typeof fields.notes === 'string' ? fields.notes.trim() : ''
  const fileIds = fields.fileIds || []
  const mileageText = fields.mileage === '' || fields.mileage == null ? '' : String(fields.mileage)
  const mileage = mileageText === '' ? null : Number(mileageText)
  if (!id(fields.vehicleId) || ![1, 3].includes(inputType) || !Number.isInteger(fields.archiveType) || fields.archiveType < 1 || fields.archiveType > 7
    || !validDate(fields.recordedDate)
    || typeof title !== 'string' || !title.length || [...title].length > 80
    || [...notes].length > 1000 || (mileage !== null && (!/^\d+$/.test(mileageText) || !Number.isInteger(mileage) || mileage > 2147483647))
    || !Array.isArray(fileIds) || fileIds.length > 5 || (inputType === 1 && fileIds.length === 0) || fileIds.some(value => !id(value))
    || new Set(fileIds).size !== fileIds.length) throw new ArchiveError('invalid', '请检查类型、日期、标题、里程和图片')
  return { vehicle_id: fields.vehicleId, archive_type: fields.archiveType, recorded_date: fields.recordedDate,
    mileage, title, notes, file_ids: [...fileIds], ...(inputType === 1 ? { input_type: 1 } : {}) }
}
export function createArchiveApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new ArchiveError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ArchiveError('unconfigured', '档案服务尚未配置，请联系管理员')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) { reject(new ArchiveError('protocol', '档案服务响应异常，请重试')); return }
            if (statusCode < 200 || statusCode >= 300) {
              const [kind, message] = messages[statusCode] || ['server', '档案请求未成功，请稍后重试']
              reject(new ArchiveError(kind, message)); return
            }
            if (data?.code !== 0 || !validate(data.data)) { reject(new ArchiveError('protocol', '档案服务响应异常，请重试')); return }
            resolve(data.data)
          },
          fail: () => reject(new ArchiveError('network', '无法连接档案服务，请检查网络后重试')),
        })
      } catch { reject(new ArchiveError('network', '无法连接档案服务，请稍后重试')) }
    })
  }
  return {
    list(token, vehicleId, page = 1) {
      if (!id(vehicleId) || !Number.isInteger(page) || page < 1) throw new ArchiveError('invalid', '车辆或页码无效')
      return request(token, `/api/archive/list?vehicle_id=${vehicleId}&page=${page}&page_size=20`, 'GET', undefined, undefined,
        data => Array.isArray(data?.list) && data.page === page && data.page_size === 20
          && Number.isSafeInteger(data.total) && data.total >= data.list.length
          && data.list.every(row => id(row.archive_id) && row.vehicle_id === vehicleId
            && Number.isInteger(row.archive_type) && row.archive_type >= 1 && row.archive_type <= 7
            && [1, 3, 4].includes(row.input_type)
            && (row.input_type !== 4 || (['order_id', 'report_id', 'review_id', 'redemption_id'].every(k => id(row.source?.[k]))
              && typeof row.test_mode === 'boolean' && row.mileage === null
              && Number.isInteger(row.work_minutes) && row.work_minutes > 0 && row.work_minutes <= 1440
              && typeof row.no_parts === 'boolean' && Array.isArray(row.parts_used) && row.no_parts === (row.parts_used.length === 0)
              && row.parts_used.every(part => part && typeof part === 'object' && ['name', 'model', 'brand'].every(k => typeof part[k] === 'string') && Number.isInteger(part.quantity) && part.quantity > 0)
              && ['submitted_at', 'signed_at', 'redeemed_at'].every(k => Number.isFinite(Date.parse(row[k])))))
            && typeof row.recorded_date === 'string' && typeof row.title === 'string'
            && typeof row.notes === 'string' && Array.isArray(row.file_ids) && row.file_ids.every(id)))
    },
    access(token, archiveId, fileId) {
      if (!id(archiveId) || !id(fileId)) throw new ArchiveError('invalid', '档案或图片参数无效')
      return request(token, `/api/archive/${archiveId}/files/${fileId}/access`, 'GET', undefined, undefined,
        data => typeof data?.url === 'string' && /^https:\/\/[^/\s?#@]+(?:\/|$)/.test(data.url) && !/\s/.test(data.url)
          && Number.isFinite(Date.parse(data.expires_at)) && Date.parse(data.expires_at) > Date.now())
    },
    add(token, body, key) {
      if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key))
        throw new ArchiveError('invalid', '保存信息无效，请重新尝试')
      return request(token, '/api/archive/add', 'POST', body, key,
        data => id(data?.archive_id) && data.vehicle_id === body.vehicle_id)
    },
  }
}
export const archiveApi = createArchiveApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
