import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class ArchiveReminderError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
const id = value => Number.isSafeInteger(value) && value > 0
const integer = value => Number.isInteger(value) && value >= 0 && value <= 2147483647
const nullableNumber = value => value === null || integer(value)
const types = [1, 3, 7]
const date = value => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || Number(value.slice(0, 4)) < 1000) return false
  const parsed = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value
}
const nullableDate = value => value === null || date(value)
const exact = (row, fields) => row && typeof row === 'object' && !Array.isArray(row) && Object.keys(row).length === fields.length && fields.every(k => Object.hasOwn(row, k))
export const reminderLabels = { 1: '保养目标', 3: '保险到期目标', 7: '年检目标' }
export const reminderSourceLabels = { 1: '保养', 3: '保险', 7: '年检' }
export const reminderStatusLabels = { NOT_SET: '未设置', INACTIVE: '已停用', UNAVAILABLE: '来源不可用', UNKNOWN: '登记里程未知', PLANNED: '尚未到目标', DUE: '已到目标' }
export function canReturnToReminder(pages) { return Array.isArray(pages) && pages.length > 1 && ['pages/archive/reminder', 'pages/archive/reminders'].includes(pages[pages.length - 2]?.route) }
export function validReminderSetting(row, today, current) {
  if (!exact(row, ['archive_type', 'revision', 'active', 'archive_id', 'recorded_date', 'due_date', 'due_mileage', 'status', 'triggered_by'])
    || !types.includes(row.archive_type) || !integer(row.revision) || typeof row.active !== 'boolean' || !nullableDate(row.recorded_date)
    || !nullableDate(row.due_date) || !nullableNumber(row.due_mileage) || !Array.isArray(row.triggered_by)) return false
  if (row.status === 'NOT_SET' || row.status === 'UNAVAILABLE') return (row.status === 'NOT_SET' ? row.revision === 0 && !row.active : row.revision > 0)
    && row.archive_id === null && row.recorded_date === null && row.due_date === null && row.due_mileage === null && row.triggered_by.length === 0
  if (!id(row.archive_id) || row.revision < 1 || (row.due_date === null && row.due_mileage === null) || (row.archive_type !== 1 && row.due_mileage !== null)) return false
  const expected = []
  if (row.active && row.due_date && today >= row.due_date) expected.push('DATE')
  if (row.active && row.due_mileage !== null && current !== null && current >= row.due_mileage) expected.push('MILEAGE')
  const status = !row.active ? 'INACTIVE' : expected.length ? 'DUE' : row.due_mileage !== null && current === null ? 'UNKNOWN' : 'PLANNED'
  return row.status === status && JSON.stringify(row.triggered_by) === JSON.stringify(expected)
}
export function reminderBody(context, fields, active = true, replaceSource = false) {
  if (!context || !types.includes(context.archive_type) || !integer(context.setting?.revision) || context.setting.revision >= 2147483647) throw new ArchiveReminderError('invalid', '请先刷新目标设置')
  if (!active) return { expected_revision: context.setting.revision, active: false }
  const dueDate = fields.date || null
  const raw = fields.mileage == null ? '' : String(fields.mileage).trim()
  const dueMileage = raw === '' ? null : Number(raw)
  if (!nullableDate(dueDate) || (raw !== '' && (!/^\d+$/.test(raw) || !integer(dueMileage))) || (dueDate === null && dueMileage === null)
    || (context.archive_type !== 1 && dueMileage !== null)) throw new ArchiveReminderError('invalid', '请填写有效目标日期或保养里程')
  return { expected_revision: context.setting.revision, active: true, due_date: dueDate, due_mileage: dueMileage, replace_source: Boolean(replaceSource) }
}
export function createArchiveReminderApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new ArchiveReminderError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ArchiveReminderError('unconfigured', '提醒服务尚未配置')
    return new Promise((resolve, reject) => {
      const fail = (kind, message) => reject(new ArchiveReminderError(kind, message))
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            const errors = { 400: ['invalid', '请检查目标日期、里程和设置版本'], 401: ['unauthorized', '登录已失效，请重新登录'],
              403: ['forbidden', '请使用车主账号'], 404: ['missing', '车辆或来源档案不可用，请重新选择'],
              409: ['conflict', '提醒设置已变化，请刷新后复核目标与来源'], 503: ['unavailable', '提醒服务暂不可用，请稍后重试'] }
            if (errors[statusCode]) return fail(...errors[statusCode])
            if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return fail('server', '提醒请求未成功，请重试')
            try { if (data?.code !== 0 || !validate(data.data)) return fail('protocol', '提醒服务响应异常，请重试') }
            catch { return fail('protocol', '提醒服务响应异常，请重试') }
            resolve(data.data)
          }, fail: () => fail('network', '无法连接提醒服务，请检查网络后重试'),
        })
      } catch { fail('network', '无法连接提醒服务，请稍后重试') }
    })
  }
  return {
    list(token, vehicle) {
      if (!id(vehicle)) throw new ArchiveReminderError('invalid', '车辆参数无效')
      return request(token, `/api/vehicle/reminders?vehicle_id=${vehicle}`, 'GET', undefined, undefined,
        data => exact(data, ['vehicle_id', 'today', 'current_mileage', 'list']) && data.vehicle_id === vehicle && date(data.today)
          && nullableNumber(data.current_mileage) && Array.isArray(data.list) && data.list.length === 3
          && data.list.every((row, index) => row.archive_type === types[index] && validReminderSetting(row, data.today, data.current_mileage)))
    },
    context(token, archive, vehicle) {
      if (!id(archive) || !id(vehicle)) throw new ArchiveReminderError('invalid', '来源档案参数无效')
      return request(token, `/api/archive/${archive}/reminder`, 'GET', undefined, undefined,
        data => exact(data, ['vehicle_id', 'archive_id', 'archive_type', 'recorded_date', 'current_mileage', 'today', 'setting'])
          && data.vehicle_id === vehicle && data.archive_id === archive && types.includes(data.archive_type) && nullableDate(data.recorded_date)
          && date(data.today) && nullableNumber(data.current_mileage) && data.setting?.archive_type === data.archive_type
          && validReminderSetting(data.setting, data.today, data.current_mileage))
    },
    save(token, context, body, key) {
      if (!id(context?.archive_id) || !id(context?.vehicle_id) || !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key)) throw new ArchiveReminderError('invalid', '保存信息无效')
      return request(token, `/api/archive/${context.archive_id}/reminder`, 'PUT', body, key,
        data => exact(data, ['vehicle_id', 'archive_id', 'archive_type', 'revision', 'active']) && data.vehicle_id === context.vehicle_id
          && data.archive_id === context.archive_id && data.archive_type === context.archive_type && data.revision === body.expected_revision + 1 && data.active === body.active)
    },
  }
}
export const archiveReminderApi = createArchiveReminderApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
