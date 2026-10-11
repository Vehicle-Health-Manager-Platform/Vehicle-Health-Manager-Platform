import { apiOrigin, cloudRunEnabled } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export const ARCHIVE_REPORT_FILE_NAME = 'vehicle-archive-report.pdf'
export const ARCHIVE_REPORT_MAX_BYTES = 3 * 1024 * 1024
export const ARCHIVE_REPORT_BASE64_ACCEPT = 'application/vnd.autocare.pdf-base64+json;version=1'

export class ArchiveReportError extends Error {
  constructor(kind, message) { super(message); this.name = 'ArchiveReportError'; this.kind = kind }
}

const messages = {
  400: ['invalid', '日期范围无效，请检查后重试'],
  401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '当前身份无权导出，请重新登录车主账号'],
  404: ['missing', '车辆档案不可用，请刷新后重试'],
  409: ['too-large', '报告超过导出上限，请缩小日期范围后重试'],
  503: ['unavailable', '报告暂时无法生成，请稍后重试'],
}
const protocolFailure = () => new ArchiveReportError('protocol', '报告文件校验失败，请稍后重试')
const networkFailure = () => new ArchiveReportError('network', '无法连接档案服务，请检查网络后重试')
const cancelled = () => new ArchiveReportError('cancelled', '档案报告导出已取消')

function validDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return false
  const time = Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(time) && new Date(time).toISOString().slice(0, 10) === value
    && Number(value.slice(0, 4)) >= 1000
}

function queryPath(vehicleId, range) {
  if (!Number.isSafeInteger(vehicleId) || vehicleId <= 0)
    throw new ArchiveReportError('invalid', '车辆信息无效，请重新选择')
  let query = `vehicle_id=${vehicleId}`
  if (range == null) return `/api/archive/report?${query}`
  if (!validDate(range.from) || !validDate(range.to) || range.from > range.to)
    throw new ArchiveReportError('invalid', '请选择有效的开始日期和结束日期')
  query += `&from=${range.from}&to=${range.to}`
  return `/api/archive/report?${query}`
}

function failureForStatus(status) {
  const [kind, message] = messages[status] || ['server', '报告请求未成功，请稍后重试']
  return new ArchiveReportError(kind, message)
}

function runtimeHeader(headers, name) {
  if (!headers || typeof headers !== 'object') return ''
  const key = Object.keys(headers).find(item => item.toLowerCase() === name.toLowerCase())
  return key ? String(headers[key] ?? '') : ''
}

function validContentDisposition(value) {
  return /^attachment\s*;\s*filename\s*=\s*(?:"vehicle-archive-report\.pdf"|vehicle-archive-report\.pdf)\s*$/i.test(value)
}

function fileSystem(target) {
  try {
    const manager = target?.getFileSystemManager?.()
    return manager && typeof manager.unlink === 'function' ? manager : null
  } catch { return null }
}

function removePath(target, path) {
  if (!path) return
  try { fileSystem(target)?.unlink({ filePath: path, success() {}, fail() {} }) } catch {}
}

function measureFile(target, path) {
  return new Promise((resolve, reject) => {
    const manager = fileSystem(target)
    if (!manager || typeof manager.getFileInfo !== 'function') {
      reject(new ArchiveReportError('unavailable', '无法验证报告文件大小，请更新微信后重试'))
      return
    }
    try {
      manager.getFileInfo({ filePath: path,
        success: result => resolve(Number.isSafeInteger(result?.size) ? result.size : -1),
        fail: () => reject(new ArchiveReportError('unavailable', '无法读取报告文件，请重试')) })
    } catch { reject(new ArchiveReportError('unavailable', '无法读取报告文件，请重试')) }
  })
}

function hasPdfMagic(target, path) {
  return new Promise((resolve, reject) => {
    const manager = fileSystem(target)
    if (!manager || typeof manager.readFile !== 'function') {
      reject(new ArchiveReportError('unavailable', '无法验证报告文件，请更新微信后重试'))
      return
    }
    try {
      manager.readFile({ filePath: path,
        success: result => {
          const data = result?.data
          const bytes = data instanceof ArrayBuffer ? new Uint8Array(data)
            : ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : null
          resolve(Boolean(bytes && bytes.length >= 5 && bytes[0] === 0x25 && bytes[1] === 0x50
            && bytes[2] === 0x44 && bytes[3] === 0x46 && bytes[4] === 0x2d))
        },
        fail: () => reject(new ArchiveReportError('unavailable', '无法读取报告文件，请重试')) })
    } catch { reject(new ArchiveReportError('unavailable', '无法读取报告文件，请重试')) }
  })
}

function parseJson(value) {
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { throw protocolFailure() }
}

const BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
function decodeBase64(value) {
  if (typeof value !== 'string' || value.length === 0 || value.length > Math.ceil(ARCHIVE_REPORT_MAX_BYTES / 3) * 4
      || value.length % 4 !== 0 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value))
    throw protocolFailure()
  const padding = value.endsWith('==') ? 2 : value.endsWith('=') ? 1 : 0
  if ((padding === 2 && (BASE64.indexOf(value[value.length - 3]) & 15) !== 0)
      || (padding === 1 && (BASE64.indexOf(value[value.length - 2]) & 3) !== 0)) throw protocolFailure()
  const output = new Uint8Array(value.length / 4 * 3 - padding)
  let cursor = 0
  for (let i = 0; i < value.length; i += 4) {
    const a = BASE64.indexOf(value[i]), b = BASE64.indexOf(value[i + 1])
    const c = value[i + 2] === '=' ? 0 : BASE64.indexOf(value[i + 2])
    const d = value[i + 3] === '=' ? 0 : BASE64.indexOf(value[i + 3])
    const bits = (a << 18) | (b << 12) | (c << 6) | d
    if (cursor < output.length) output[cursor++] = (bits >>> 16) & 255
    if (cursor < output.length) output[cursor++] = (bits >>> 8) & 255
    if (cursor < output.length) output[cursor++] = bits & 255
  }
  return output
}

function bytesFromEnvelope(responseData) {
  const body = parseJson(responseData)
  if (!body || body.code !== 0 || !body.data || typeof body.data !== 'object') throw protocolFailure()
  const envelope = body.data
  const expectedKeys = ['content_base64', 'content_length', 'content_type', 'file_name', 'version']
  if (Object.keys(envelope).sort().join('|') !== expectedKeys.join('|')
      || envelope.version !== 1 || envelope.file_name !== ARCHIVE_REPORT_FILE_NAME
      || envelope.content_type !== 'application/pdf' || !Number.isSafeInteger(envelope.content_length)
      || envelope.content_length < 1 || envelope.content_length > ARCHIVE_REPORT_MAX_BYTES) throw protocolFailure()
  const bytes = decodeBase64(envelope.content_base64)
  if (bytes.length !== envelope.content_length || bytes.length < 5
      || bytes[0] !== 0x25 || bytes[1] !== 0x50 || bytes[2] !== 0x44 || bytes[3] !== 0x46 || bytes[4] !== 0x2d)
    throw protocolFailure()
  return bytes
}

function fileName(target) {
  let directory = ''
  try { directory = target?.userDataPath?.() || '' } catch {}
  if (!directory && typeof wx !== 'undefined') directory = wx?.env?.USER_DATA_PATH || ''
  if (typeof directory !== 'string' || !directory) throw new ArchiveReportError('unavailable', '当前微信环境无法保存临时报告')
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 0x100000000).toString(36)}`
  return `${directory.replace(/\/$/, '')}/archive-report-${suffix}.pdf`
}

function writeBytes(target, bytes) {
  return new Promise((resolve, reject) => {
    const manager = fileSystem(target)
    if (!manager || typeof manager.writeFile !== 'function') {
      reject(new ArchiveReportError('unavailable', '当前微信环境无法保存临时报告'))
      return
    }
    const path = fileName(target)
    try {
      manager.writeFile({ filePath: path, data: bytes.buffer,
        success: () => resolve(path),
        fail: () => { removePath(target, path); reject(new ArchiveReportError('unavailable', '无法保存报告文件，请重试')) } })
    } catch { removePath(target, path); reject(new ArchiveReportError('unavailable', '无法保存报告文件，请重试')) }
  })
}

function callbackRequest(invoke, signal) {
  return new Promise((resolve, reject) => {
    let settled = false, task
    const finish = (fn, value) => {
      if (settled) return
      settled = true
      signal?.unsubscribe?.(abort)
      fn(value)
    }
    const abort = () => {
      try { task?.abort?.() } catch {}
      finish(reject, cancelled())
    }
    if (signal?.cancelled) { finish(reject, cancelled()); return }
    signal?.subscribe?.(abort)
    try {
      task = invoke({ success: result => finish(resolve, result),
        fail: error => finish(reject, /cancel/i.test(error?.errMsg || '') ? cancelled() : networkFailure()) })
    } catch { finish(reject, networkFailure()) }
  })
}

function directDownload(target, url, token, signal) {
  return new Promise((resolve, reject) => {
    let settled = false, task, downloadedPath = ''
    const finish = (fn, value) => {
      if (settled) return false
      settled = true
      signal?.unsubscribe?.(abort)
      fn(value)
      return true
    }
    const abort = () => {
      try { task?.abort?.() } catch {}
      discardDownloaded()
      finish(reject, cancelled())
    }
    const discardDownloaded = () => {
      if (!downloadedPath) return
      const path = downloadedPath
      downloadedPath = ''
      removePath(target, path)
    }
    const success = async result => {
      const path = result?.tempFilePath
      downloadedPath = typeof path === 'string' ? path : ''
      if (settled) { discardDownloaded(); return }
      if (result?.statusCode !== 200) { discardDownloaded(); finish(reject, failureForStatus(result?.statusCode)); return }
      const mime = runtimeHeader(result.header, 'content-type').split(';')[0].trim().toLowerCase()
      const disposition = runtimeHeader(result.header, 'content-disposition')
      if (mime !== 'application/pdf' || !validContentDisposition(disposition) || typeof path !== 'string' || !path) {
        discardDownloaded(); finish(reject, protocolFailure()); return
      }
      try {
        const size = await measureFile(target, path)
        if (settled) { discardDownloaded(); return }
        if (!Number.isSafeInteger(size) || size < 1 || size > ARCHIVE_REPORT_MAX_BYTES) {
          discardDownloaded()
          finish(reject, size > ARCHIVE_REPORT_MAX_BYTES ? failureForStatus(409) : protocolFailure())
          return
        }
        if (!await hasPdfMagic(target, path)) {
          discardDownloaded()
          finish(reject, protocolFailure())
          return
        }
        if (settled) { discardDownloaded(); return }
        if (finish(resolve, path)) downloadedPath = ''
        else discardDownloaded()
      } catch (error) {
        discardDownloaded()
        finish(reject, error instanceof ArchiveReportError ? error : protocolFailure())
      }
    }
    if (signal?.cancelled) { finish(reject, cancelled()); return }
    signal?.subscribe?.(abort)
    try {
      task = target.downloadFile({ url, timeout: 30000,
        header: { Authorization: `Bearer ${token}`, Accept: 'application/pdf' }, success, fail: () => finish(reject, networkFailure()) })
    } catch { finish(reject, networkFailure()) }
  })
}

export function createArchiveReportApi({ baseUrl, runtime, cloudMode = false }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function configured(token) {
    if (typeof token !== 'string' || !token.trim()) throw new ArchiveReportError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ArchiveReportError('unconfigured', '档案服务尚未配置，请联系管理员')
    if (!endpoint.startsWith('https://') || (!cloudMode && endpoint === 'https://cloudrun.invalid'))
      throw new ArchiveReportError('unconfigured', '档案服务必须配置为 HTTPS 地址')
  }
  return {
    async download(token, vehicleId, range, signal) {
      configured(token)
      const path = queryPath(vehicleId, range)
      const target = runtime()
      if (cloudMode) {
        const response = await callbackRequest(callbacks => target.request({ url: `${endpoint}${path}`, method: 'GET', timeout: 30000,
          header: { Authorization: `Bearer ${token}`, Accept: ARCHIVE_REPORT_BASE64_ACCEPT }, ...callbacks }), signal)
        if (response?.statusCode !== 200) throw failureForStatus(response?.statusCode)
        const bytes = bytesFromEnvelope(response.data)
        if (signal?.cancelled) throw cancelled()
        return await writeBytes(target, bytes)
      }
      if (typeof target?.supports === 'function' && !target.supports('downloadFile'))
        throw new ArchiveReportError('unavailable', '当前微信版本不支持下载文件，请更新微信后重试')
      return directDownload(target, `${endpoint}${path}`, token, signal)
    },
    openDocument(path) {
      return new Promise((resolve, reject) => {
        const target = runtime()
        if (typeof target?.supports === 'function' && !target.supports('openDocument')) {
          reject(new ArchiveReportError('unavailable', '当前微信版本不支持打开 PDF，请更新微信后重试'))
          return
        }
        try {
          target.openDocument({ filePath: path, fileType: 'pdf', showMenu: true,
            success: () => resolve(), fail: () => reject(new ArchiveReportError('open', 'PDF 未能打开，请重试')) })
        } catch { reject(new ArchiveReportError('open', 'PDF 未能打开，请重试')) }
      })
    },
    remove(path) { removePath(runtime(), path) },
  }
}

export const archiveReportApi = createArchiveReportApi({ baseUrl: apiOrigin, runtime: () => apiRuntime, cloudMode: cloudRunEnabled })
