import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class ServiceError extends Error {
  constructor(kind, message) { super(message); this.kind = kind }
}
export const serviceFailure = error => error instanceof ServiceError ? error : new ServiceError('network', '无法连接服务，请检查网络后重试')
export const serviceCategories = ['全部', '保养', '轮胎', '维修', '美容', '服务', '用品']
const validId = value => Number.isSafeInteger(value) && value > 0
const price = value => typeof value === 'string' && /^\d{1,8}\.\d{2}$/.test(value)
const project = value => validId(value?.id) && typeof value.project_name === 'string' && value.project_name.length > 0
  && Number.isInteger(value.category) && value.category >= 1 && value.category <= 6
  && price(value.base_price_low) && price(value.base_price_high) && Number(value.base_price_low) <= Number(value.base_price_high)
const failures = {
  400: ['invalid', '查询条件无效，请重新选择'], 401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '请使用车主账号查看服务'], 404: ['missing', '项目不存在或已停用'],
  429: ['limited', '操作过于频繁，请稍后重试'], 503: ['unavailable', '服务项目暂不可用，请稍后重试'],
}
export function createServiceCatalogApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, validate) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '服务尚未配置，请联系管理员')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({ url: `${endpoint}${path}`, method: 'GET', timeout: 15000,
          header: { Authorization: `Bearer ${token}` },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) return reject(new ServiceError('protocol', '服务响应异常，请重试'))
            if (statusCode < 200 || statusCode >= 300) {
              const [kind, message] = failures[statusCode] || ['server', '查询未成功，请稍后重试']
              return reject(new ServiceError(kind, message))
            }
            if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '服务响应异常，请重试'))
            resolve(data.data)
          }, fail: () => reject(serviceFailure()),
        })
      } catch { reject(serviceFailure()) }
    })
  }
  return {
    list(token, category = 0, page = 1) {
      if (!Number.isInteger(category) || category < 0 || category > 6 || !validId(page) || page > 1000000)
        throw new ServiceError('invalid', '查询条件无效，请重新选择')
      return request(token, `/api/service/projects?page=${page}&page_size=20${category ? `&category=${category}` : ''}`, result =>
        Array.isArray(result?.items) && result.items.length <= 20 && result.items.every(item => project(item) && (!category || item.category === category))
        && new Set(result.items.map(item => item.id)).size === result.items.length
        && Number.isSafeInteger(result.total) && result.total >= result.items.length && result.page === page && result.page_size === 20)
    },
    detail(token, id) {
      if (!validId(id)) throw new ServiceError('invalid', '项目编号无效')
      return request(token, `/api/service/project/${id}`, item => project(item) && item.id === id
        && typeof item.service_content === 'string' && (item.quality_standard === null || typeof item.quality_standard === 'string'))
    },
  }
}
export const serviceCatalogApi = createServiceCatalogApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
