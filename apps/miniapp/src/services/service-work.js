import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { ORDER_STATES } from './order-status.js'

export const PROTECTION_ITEMS = ['SEAT_COVER', 'STEERING_COVER', 'FLOOR_MAT', 'FENDER_COVER']
export const PROTECTION_LABELS = ['座椅套', '方向盘套', '一次性脚垫', '翼子板布']
export const PHOTO_GROUPS = ['PROCESS', 'FAULT', 'FINISH']
export const PHOTO_LABELS = { PROCESS: '施工过程照', FAULT: '故障件照', FINISH: '完工照' }
const id = n => Number.isSafeInteger(n) && n > 0
const stamp = n => typeof n === 'string' && Number.isFinite(Date.parse(n))
const keyValid = k => /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(String(k || ''))
const exact = (n, keys) => n && typeof n === 'object' && !Array.isArray(n) && Object.keys(n).length === keys.length && keys.every(k => Object.hasOwn(n, k))
const text = (n, max) => typeof n === 'string' && n.trim().length > 0 && n.trim().length <= max
const integer = (n, max) => Number.isSafeInteger(n) && n >= 1 && n <= max
const files = (n, min) => Array.isArray(n) && n.length >= min && n.length <= 9 && n.every(id) && new Set(n).size === n.length
const items = n => Array.isArray(n) && n.length >= 2 && n.length <= 4 && new Set(n).size === n.length && n.every(i => PROTECTION_ITEMS.includes(i)) && ['SEAT_COVER', 'STEERING_COVER'].every(i => n.includes(i))
const part = n => exact(n, ['name', 'model', 'brand', 'quantity']) && ['name', 'model', 'brand'].every(k => text(n[k], 100)) && integer(n.quantity, 999)
const REPORT_FIELDS = ['process_photos', 'fault_part_photos', 'finish_photos', 'no_fault_parts', 'repair_plan', 'fault_analysis', 'parts_used', 'no_parts', 'work_hours']
function reportFields(n) {
  return n && typeof n.no_fault_parts === 'boolean' && typeof n.no_parts === 'boolean'
    && files(n.process_photos, 1) && files(n.finish_photos, 1) && files(n.fault_part_photos, n.no_fault_parts ? 0 : 1)
    && (!n.no_fault_parts || n.fault_part_photos.length === 0)
    && new Set([...n.process_photos, ...n.fault_part_photos, ...n.finish_photos]).size === n.process_photos.length + n.fault_part_photos.length + n.finish_photos.length
    && text(n.repair_plan, 2000) && text(n.fault_analysis, 2000)
    && Array.isArray(n.parts_used) && n.parts_used.length <= 20 && n.parts_used.every(part) && n.no_parts === (n.parts_used.length === 0)
    && integer(n.work_hours, 1440)
}
export function validWorkView(n) {
  if (!exact(n, ['order_id', 'order_status', 'protection', 'report']) || !id(n.order_id) || !ORDER_STATES.includes(n.order_status)) return false
  const p = n.protection, r = n.report
  // Historical evidence cannot enable the form; treat missing file/time as a protocol failure.
  if (p !== null && !(exact(p, ['items', 'uploaded_at', 'photo_file_id']) && items(p.items) && id(p.photo_file_id) && stamp(p.uploaded_at))) return false
  if (r !== null && !(exact(r, [...REPORT_FIELDS, 'report_id', 'submitted_at', 'signed_at', 'signature_file_id', 'status']) && reportFields(r) && id(r.report_id) && stamp(r.submitted_at)
    && (r.status === 'SUBMITTED' ? r.signed_at === null && r.signature_file_id === null : r.status === 'SIGNED' && stamp(r.signed_at) && id(r.signature_file_id)))) return false
  return true
}
export class WorkError extends ServiceError { constructor(kind, message, code = 0) { super(kind, message); this.code = code } }
const invalid = message => new WorkError('invalid', message)
export function protectionBody(order, selectedItems, fileId) {
  if (!id(order) || !items(selectedItems) || !id(fileId)) throw invalid('请勾选座椅套与方向盘套，并完成防护照片上传')
  return { order_id: order, items: [...selectedItems], photo_file_id: fileId }
}
export function reportBody(order, form, photos) {
  const ids = kind => photos.filter(p => p.kind === kind).map(p => p.fileId)
  const parts = form.parts.map(p => ({ name: p.name.trim(), model: p.model.trim(), brand: p.brand.trim(), quantity: /^\d{1,3}$/.test(String(p.quantity)) ? Number(p.quantity) : 0 }))
  const body = { order_id: order, process_photos: ids('PROCESS'), fault_part_photos: ids('FAULT'), finish_photos: ids('FINISH'),
    no_fault_parts: form.noFaultParts, repair_plan: form.plan.trim(), fault_analysis: form.analysis.trim(), parts_used: parts, no_parts: form.noParts,
    work_hours: /^\d{1,4}$/.test(String(form.minutes)) ? Number(form.minutes) : 0 }
  if (!id(order) || !reportFields(body)) throw invalid('请完成施工/完工照片、方案、分析、配件记录与整数工时；无故障件或配件需明确勾选')
  return body
}
export function createWorkApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  const rolePath = role => { if (!['merchant', 'tech'].includes(role)) throw invalid('施工身份无效'); return `/api/${role}` }
  const checked = n => { if (!id(n)) throw invalid('工单或图片编号无效') }
  const conflicts = { 40905: '记录或订单状态已变化，请刷新查看', 43001: '请先完成接车检查', 43002: '请先完成施工防护拍照', 43003: '请等待车主确认接车单', 43004: '请先由本人接单', 43005: '请先提交完整施工报工', 43007: '订单有未解决争议，请商家处理并由车主复核' }
  function call(token, path, validate, body, key) {
    if (!token) throw new WorkError('unauthorized', '请先登录对应账号')
    if (!endpoint) throw new WorkError('unconfigured', '施工服务尚未配置')
    if (body !== undefined && !keyValid(key)) throw invalid('提交键无效')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body === undefined ? 'GET' : 'POST', data: body, timeout: 15000,
        header: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { 'Idempotency-Key': key }) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode)) return reject(new WorkError('protocol', '施工响应异常，请重试'))
          if (statusCode < 200 || statusCode >= 300) {
            const kinds = { 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 422: 'rejected', 429: 'rate-limited', 503: 'unavailable' }
            const messages = { 400: '施工信息无效，请检查必填项', 401: '登录已失效，请重新登录', 403: '当前身份无权操作施工记录', 404: '工单或图片不存在或不可访问', 422: '图片非本人安全文件或已使用，请重新上传', 429: '操作过于频繁，请稍后重试', 503: '施工服务暂不可用，请保留原内容重试' }
            return reject(new WorkError(kinds[statusCode] || 'server', conflicts[data?.code] || messages[statusCode] || '施工请求未成功，请稍后重试', data?.code || 0))
          }
          if (data?.code !== 0 || !validate(data.data)) return reject(new WorkError('protocol', '施工响应异常，请刷新；历史缺证据记录需人工处理'))
          resolve(data.data)
        }, fail: () => reject(serviceFailure()) })
      } catch { reject(serviceFailure()) }
    })
  }
  const matching = order => n => validWorkView(n) && n.order_id === order
  return {
    detail(token, role, order) { checked(order); return call(token, `${rolePath(role)}/orders/${order}/work`, matching(order)) },
    protect(token, body, key) {
      if (!exact(body, ['order_id', 'items', 'photo_file_id'])) throw invalid('防护信息无效')
      protectionBody(body.order_id, body.items, body.photo_file_id)
      return call(token, '/api/check/protection/upload', n => matching(body.order_id)(n) && n.protection?.photo_file_id === body.photo_file_id, body, key)
    },
    submit(token, body, key) {
      if (!exact(body, ['order_id', ...REPORT_FIELDS]) || !id(body.order_id) || !reportFields(body)) throw invalid('请完成完整报工信息')
      return call(token, '/api/tech/report/submit', n => matching(body.order_id)(n) && n.report !== null, body, key)
    },
    sign(token, order, file, key) {
      checked(order); checked(file)
      return call(token, '/api/tech/sign', n => matching(order)(n) && n.order_status === 'PENDING_VERIFY' && n.report?.status === 'SIGNED' && n.report.signature_file_id === file,
        { order_id: order, signature_file_id: file }, key)
    },
    access(token, role, order, file) {
      checked(order); checked(file)
      return call(token, `${rolePath(role)}/orders/${order}/work/files/${file}/access`, n => exact(n, ['url', 'expires_at']) && typeof n.url === 'string'
        && /^https:\/\/[^/\s?#@]+(?:\/|$)/.test(n.url) && !/\s/.test(n.url) && stamp(n.expires_at) && Date.parse(n.expires_at) > Date.now())
    },
  }
}
export const workApi = createWorkApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
