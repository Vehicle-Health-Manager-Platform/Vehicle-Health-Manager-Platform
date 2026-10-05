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
export function createImageApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
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
  return {
    choose(token, source = 'mixed') {
      configured(token)
      if (!['mixed', 'camera'].includes(source)) throw new ImageError('invalid', '图片来源无效，请重试')
      return new Promise((resolve, reject) => {
        runtime().chooseImage({ count: 1, sizeType: ['original'], sourceType: source === 'camera' ? ['camera'] : ['album', 'camera'],
          success: result => {
            const file = result.tempFiles?.[0]
            const path = file?.path || result.tempFilePaths?.[0]
            if (!present(path) || !Number.isFinite(file?.size) || file.size <= 0) { reject(new ImageError('invalid', '无法读取图片，请重新选择')); return }
            if (file.size > MAX_IMAGE_BYTES) { reject(failure(413)); return }
            resolve({ path, size: file.size })
          },
          fail: error => error?.errMsg?.toLowerCase().includes('cancel') ? resolve(null)
            : reject(new ImageError('selection', '无法选择图片，请检查相册或相机权限')),
        })
      })
    },
    upload(token, file, key, signal) {
      configured(token)
      if (!present(file?.path) || !Number.isFinite(file.size) || file.size <= 0 || file.size > MAX_IMAGE_BYTES
          || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new ImageError('invalid', '请重新选择图片')
      return transport('uploadFile', { url: `${endpoint}/api/file/upload`, filePath: file.path, name: 'file', timeout: 80000,
        header: { Authorization: `Bearer ${token}`, 'Idempotency-Key': key } },
      result => Number.isSafeInteger(result?.file_id) && result.file_id > 0
        && Number.isSafeInteger(result.size_bytes) && result.size_bytes > 0 && result.size_bytes <= MAX_IMAGE_BYTES
        && ['image/jpeg', 'image/png', 'image/webp'].includes(result.content_type), signal)
    },
    access(token, id, signal) {
      configured(token)
      if (!Number.isSafeInteger(id) || id <= 0) throw new ImageError('invalid', '图片信息无效，请重新上传')
      return transport('request', { url: `${endpoint}/api/file/${id}/access`, method: 'GET', timeout: 15000,
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
  }
}
export const imageApi = createImageApi({ baseUrl: import.meta.env?.VITE_API_BASE_URL, runtime: () => uni })
