import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class ImageError extends Error {
  constructor(kind, message) { super(message); this.name = 'ImageError'; this.kind = kind }
}
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const present = value => typeof value === 'string' && value.trim().length > 0
const invalid = () => new ImageError('protocol', '图片服务响应异常，请稍后重试')
function failure(status) {
  const errors = {
    400: ['invalid', '图片格式或上传信息无效，请重新选择'],
    401: ['unauthorized', '登录已失效，请重新登录'],
    403: ['forbidden', '当前身份无权访问图片，请重新登录'],
    404: ['missing', '图片不存在或已不可访问，请重新上传'],
    409: ['conflict', '图片正在处理或信息冲突，请稍后重试'],
    413: ['too-large', '图片超过 10 MiB，请选择较小的图片'],
    422: ['rejected', '图片未通过安全检查，请重新选择'],
    429: ['rate-limited', '图片操作过于频繁，请稍后重试'],
    503: ['unavailable', '图片服务暂不可用，请稍后使用原图片重试'],
  }
  const [kind, message] = errors[status] || ['server', '图片请求未成功，请稍后重试']
  return new ImageError(kind, message)
}
export function imageRequestKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  // A correlation key, not a secret or authorization credential. Server verifies ownership.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const value = Math.floor(Math.random() * 16)
    return (character === 'x' ? value : (value & 3) | 8).toString(16)
  })
}
// 开发者工具没有摄像头。如实告知，而不是让用户以为拍照坏了。
export const DEVTOOLS_NOTICE = '开发者工具没有摄像头，已从相册选择；真实拍照请在真机上验证'

// chooseMedia 返回 tempFilePath，chooseImage 返回 path；旧版本还可能不回报 size。
function selectionOf(result) {
  const file = result?.tempFiles?.[0]
  const path = file?.path || file?.tempFilePath || result?.tempFilePaths?.[0]
  return { path: present(path) ? path : '', size: Number.isFinite(file?.size) && file.size > 0 ? file.size : 0 }
}

// 取图失败要分开说：用户主动取消不该报错，权限被拒要能引导去授权。
function selectionFailure(error) {
  const message = String(error?.errMsg || '').toLowerCase()
  if (message.includes('cancel')) return null
  if (/(auth|authorize|permission)/.test(message))
    return new ImageError('permission', '未获得相机或相册权限，请在设置中允许后重试')
  return new ImageError('selection', '无法选择图片，请检查相册或相机权限')
}

export function createImageApi({ baseUrl, runtime, environment, prefix = '/api/file' }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  const where = typeof environment === 'function' ? environment : () => 'device'
  function configured(token) {
    if (!present(token)) throw new ImageError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ImageError('unconfigured', '图片服务尚未配置，请联系管理员')
  }
  function transport(method, options, accepts, signal) {
    return new Promise((resolve, reject) => {
      let task, done = false
      const finish = (callback, value) => {
        if (done) return
        done = true
        signal?.unsubscribe?.(cancel)
        callback(value)
      }
      const cancel = () => { finish(reject, new ImageError('cancelled', '操作已取消')); task?.abort?.() }
      if (signal?.cancelled) { cancel(); return }
      signal?.subscribe?.(cancel)
      try {
        task = runtime()[method]({ ...options,
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) { finish(reject, invalid()); return }
            let body = data
            if (statusCode < 200 || statusCode >= 300) { finish(reject, failure(statusCode)); return }
            try { if (typeof data === 'string') body = JSON.parse(data) } catch { finish(reject, invalid()); return }
            if (body?.code !== 0 || !accepts(body.data)) { finish(reject, invalid()); return }
            finish(resolve, body.data)
          },
          fail: error => finish(reject, new ImageError(error?.errMsg?.includes('timeout') ? 'timeout' : 'network',
            error?.errMsg?.includes('timeout') ? '连接超时，请使用原图片重试' : '无法连接图片服务，请检查网络后重试')),
        })
      } catch { finish(reject, new ImageError('network', '无法连接图片服务，请稍后重试')) }
    })
  }
  // 原生选择器偶尔不回报 size；补一次文件信息查询，避免仅因缺少大小就判成读取失败。
  function measure(path) {
    return new Promise(resolve => {
      try {
        const fs = runtime().getFileSystemManager?.()
        if (!fs || typeof fs.getFileInfo !== 'function') { resolve(0); return }
        fs.getFileInfo({ filePath: path, success: result => resolve(Number.isFinite(result?.size) ? result.size : 0),
          fail: () => resolve(0) })
      } catch { resolve(0) }
    })
  }
  return {
    choose(token, source = 'mixed') {
      configured(token)
      if (!['mixed', 'camera'].includes(source)) throw new ImageError('invalid', '图片来源无效，请重试')
      const target = runtime()
      // chooseMedia 是官方推荐接口，可显式指定后置摄像头；旧基础库回退 chooseImage。
      const useMedia = typeof target.supports === 'function' && target.supports('chooseMedia')
      // 开发者工具没有摄像头：退回相册，让上传与归档流程仍能在工具里走通，并如实标记。
      const degraded = source === 'camera' && where() === 'devtools'
      const sourceType = source !== 'camera' ? ['album', 'camera'] : degraded ? ['album'] : ['camera']
      return new Promise((resolve, reject) => {
        const accept = async result => {
          const picked = selectionOf(result)
          if (!picked.path) { reject(new ImageError('invalid', '无法读取图片，请重新选择')); return }
          const size = picked.size || await measure(picked.path)
          if (size <= 0) { reject(new ImageError('invalid', '无法读取图片大小，请重新拍摄')); return }
          if (size > MAX_IMAGE_BYTES) { reject(failure(413)); return }
          resolve({ path: picked.path, size, degraded })
        }
        const handle = {
          success: result => { accept(result).catch(reject) },
          fail: error => { const reason = selectionFailure(error); if (reason) reject(reason); else resolve(null) },
        }
        try {
          if (useMedia) target.chooseMedia({ count: 1, mediaType: ['image'], sizeType: ['original'],
            sourceType, camera: 'back', ...handle })
          else target.chooseImage({ count: 1, sizeType: ['original'], sourceType, ...handle })
        } catch { reject(new ImageError('selection', '无法调起相机或相册，请稍后重试')) }
      })
    },
    upload(token, file, key, signal) {
      configured(token)
      if (!present(file?.path) || !Number.isFinite(file.size) || file.size <= 0 || file.size > MAX_IMAGE_BYTES
          || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new ImageError('invalid', '请重新选择图片')
      return transport('uploadFile', { url: `${endpoint}${prefix}/upload`, filePath: file.path, name: 'file', timeout: 80000,
        header: { Authorization: `Bearer ${token}`, 'Idempotency-Key': key } },
      result => Number.isSafeInteger(result?.file_id) && result.file_id > 0
        && Number.isSafeInteger(result.size_bytes) && result.size_bytes > 0 && result.size_bytes <= MAX_IMAGE_BYTES
        && ['image/jpeg', 'image/png', 'image/webp'].includes(result.content_type), signal)
    },
    access(token, id, signal) {
      configured(token)
      if (!Number.isSafeInteger(id) || id <= 0) throw new ImageError('invalid', '图片信息无效，请重新上传')
      return transport('request', { url: `${endpoint}${prefix}/${id}/access`, method: 'GET', timeout: 15000,
        header: { Authorization: `Bearer ${token}` } },
      result => present(result?.url) && /^https:\/\/[^/\s?#@]+(?:\/|$)/.test(result.url) && !/\s/.test(result.url)
        && Number.isFinite(Date.parse(result.expires_at)) && Date.parse(result.expires_at) > Date.now(), signal)
    },
    preview(url) {
      return new Promise((resolve, reject) => {
        runtime().previewImage({ urls: [url], current: url,
          success: () => resolve(), fail: () => reject(new ImageError('preview', '图片预览未打开，请重试')) })
      })
    },
    // 权限被拒后只能由用户在小程序设置页手动打开，这里负责把入口调起来。
    authorize() {
      return new Promise(resolve => {
        const target = runtime()
        const open = target?.openSetting
        if (typeof open !== 'function') { resolve(false); return }
        try { open.call(target, { success: () => resolve(true), fail: () => resolve(false) }) }
        catch { resolve(false) }
      })
    },
  }
}
export const imageApi = createImageApi({
  baseUrl: apiOrigin,
  runtime: () => apiRuntime,
  environment: () => apiRuntime.environment(),
})
export const merchantImageApi = createImageApi({
  baseUrl: apiOrigin, runtime: () => apiRuntime, environment: () => apiRuntime.environment(), prefix: '/api/merchant/files',
})
