import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export const catalogKinds = {
  projects: { label: '标准项目', fields: { project_name: '项目名称', category: '分类（1保养2轮胎3维修4美容5服务6用品）', service_content: '服务内容', quality_standard: '质检标准', base_price_low: '参考最低价', base_price_high: '参考最高价' } },
  brands: { label: '品牌', fields: { name: '品牌名称' } },
  series: { label: '车系', parent: 'brands', parentKey: 'brand_id', fields: { name: '车系名称' } },
  models: { label: '车型', parent: 'series', parentKey: 'series_id', fields: { year: '年款', config_name: '配置名称', power_type: '动力（FUEL/HYBRID/ELECTRIC）' } },
}
const exact = (o, keys) => o && typeof o === 'object' && !Array.isArray(o) && Object.keys(o).length === keys.length && keys.every(k => Object.hasOwn(o, k))
const id = v => Number.isSafeInteger(v) && v > 0
export class CatalogError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
export function validCatalogRow(kind, row) {
  const config = catalogKinds[kind]; if (!config) return false
  const keys = ['id', ...Object.keys(config.fields), ...(config.parentKey ? [config.parentKey] : []), 'status', 'sort_order', 'revision', 'source_note']
  return exact(row, keys) && id(row.id) && id(row.revision) && [0, 1].includes(row.status)
    && Number.isInteger(row.sort_order) && row.sort_order >= 0 && row.sort_order <= 9999 && typeof row.source_note === 'string'
    && (!config.parentKey || id(row[config.parentKey]))
    && Object.keys(config.fields).every(k => k === 'category' ? Number.isInteger(row[k]) && row[k] >= 1 && row[k] <= 6 : typeof row[k] === 'string')
}
export function catalogBody(kind, form, current = null) {
  const config = catalogKinds[kind]; if (!config) throw new CatalogError('invalid', '目录类型无效')
  const body = { sort_order: Number(form.sort_order), source_note: String(form.source_note || '').trim() }
  for (const field of Object.keys(config.fields)) body[field] = field === 'category' ? Number(form[field]) : String(form[field] ?? '').trim()
  if (config.parentKey) body[config.parentKey] = Number(form[config.parentKey])
  if (!Number.isInteger(body.sort_order) || body.sort_order < 0 || body.sort_order > 9999 || body.source_note.length < 2 || body.source_note.length > 256
    || (config.parentKey && !id(body[config.parentKey]))) throw new CatalogError('invalid', '请填写有效来源、排序和上级目录')
  if (kind === 'projects' && (!(body.category >= 1 && body.category <= 6 && Number.isInteger(body.category)) || !/^(0|[1-9][0-9]{0,5})\.[0-9]{2}$/.test(body.base_price_low) || !/^(0|[1-9][0-9]{0,5})\.[0-9]{2}$/.test(body.base_price_high) || Number(body.base_price_low) > Number(body.base_price_high))) throw new CatalogError('invalid', '请填写分类与两位小数参考价范围')
  if (kind === 'models' && (!/^\d{4}$/.test(body.year) || Number(body.year) < 1900 || Number(body.year) > 2100 || !['FUEL', 'HYBRID', 'ELECTRIC'].includes(body.power_type))) throw new CatalogError('invalid', '请填写合法年款与动力类型')
  if (current) { body.revision = current.revision; body.status = current.status }
  return body
}
export function createCatalogApi({ baseUrl = apiOrigin, runtime = apiRuntime } = {}) {
  function call(path, method, token, data, key, validate) {
    if (!token) return Promise.reject(new CatalogError('unauthorized', '请先登录运营账号'))
    const bridge = runtime(), endpoint = typeof baseUrl === 'function' ? baseUrl() : baseUrl
    return new Promise((resolve, reject) => {
      if (!endpoint || !bridge?.request) { reject(new CatalogError('unavailable', '目录服务尚未配置')); return }
      const errors = { 400: ['invalid', '目录字段无效，请检查后重试'], 401: ['unauthorized', '运营登录已失效'], 403: ['forbidden', '当前账号没有目录维护权限'], 404: ['missing', '目录项不存在'], 409: ['conflict', '目录已变化、名称重复或上级未发布，请刷新核对'], 503: ['unavailable', '目录维护尚未开启或暂不可用'] }
      try { bridge.request({ url: `${endpoint}${path}`, method, data, timeout: 15000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success({ statusCode, data: result }) { if (statusCode !== 200) { const error = errors[statusCode] || ['server', '目录请求失败']; reject(new CatalogError(...error)); return }
          if (result?.code !== 0 || !validate(result.data)) { reject(new CatalogError('protocol', '目录响应异常，请刷新')); return } resolve(result.data) },
        fail: () => reject(new CatalogError('network', '网络连接失败，请重试')),
      }) } catch { reject(new CatalogError('network', '网络连接失败，请重试')) }
    })
  }
  return {
    list(token, kind, page = 1) { if (!catalogKinds[kind] || !Number.isInteger(page) || page < 1 || page > 1000000) return Promise.reject(new CatalogError('invalid', '目录页码无效'))
      return call(`/api/admin/catalog/${kind}?page=${page}&page_size=20`, 'GET', token, undefined, undefined, r => exact(r, ['items', 'total', 'page', 'page_size']) && Array.isArray(r.items) && r.items.length <= 20 && r.items.every(x => validCatalogRow(kind, x)) && new Set(r.items.map(x => x.id)).size === r.items.length && Number.isSafeInteger(r.total) && r.total >= r.items.length && r.page === page && r.page_size === 20) },
    save(token, kind, body, key, target = null) {
      if (!catalogKinds[kind] || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key) || (target !== null && !id(target))) return Promise.reject(new CatalogError('invalid', '保存请求无效'))
      return call(`/api/admin/catalog/${kind}${target ? '/' + target : ''}`, target ? 'PUT' : 'POST', token, body, key, r => validCatalogRow(kind, r) && (!target || r.id === target))
    },
  }
}
export const catalogApi = createCatalogApi()
