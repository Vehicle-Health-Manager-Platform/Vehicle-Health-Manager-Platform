import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
const id = x => Number.isSafeInteger(x) && x > 0
const question = s => typeof s === 'string' && [...s.trim()].length >= 1 && [...s].length <= 300
const baseProject = r => id(r?.project_id) && typeof r.project_name === 'string' && r.project_name.length > 0 && Number.isInteger(r.category) && r.category >= 1 && r.category <= 6
export const PLAN_TIERS = { REQUESTED: '你明确指定的项目', RECOMMENDED: 'AI推荐候选', OPTIONAL: 'AI可选候选' }
export const PLAN_REASONS = { OWNER_SELECTED: '依据你对项目的明确选择；不是技术必要性证明。', MODEL_MATCH: 'AI依据问题匹配当前公开目录；是否适用仍需到店确认。' }
export function validPlan(r, requested) {
  if (!exact(r, ['status', 'question', 'candidate_count', 'items']) || !['AI_READY', 'MANUAL', 'EMPTY', 'SOURCE_CHANGED'].includes(r.status) || !question(r.question) || !Number.isInteger(r.candidate_count) || r.candidate_count < 0 || r.candidate_count > 40 || !Array.isArray(r.items) || r.items.length > 8) return false
  if (['EMPTY', 'SOURCE_CHANGED'].includes(r.status)) return r.candidate_count === 0 && r.items.length === 0
  if (r.candidate_count < 1 || r.items.length > r.candidate_count || new Set(r.items.map(x => x?.project_id)).size !== r.items.length) return false
  if (!r.items.every(x => exact(x, ['project_id', 'project_name', 'category', 'tier', 'reason_code', 'evidence_quote']) && baseProject(x) && Object.hasOwn(PLAN_TIERS, x.tier) && x.evidence_quote === r.question && (x.tier === 'REQUESTED' ? x.reason_code === 'OWNER_SELECTED' && requested.includes(x.project_id) : x.reason_code === 'MODEL_MATCH' && !requested.includes(x.project_id)))) return false
  const chosen = r.items.filter(x => x.tier === 'REQUESTED').map(x => x.project_id)
  if (JSON.stringify(chosen) !== JSON.stringify(requested) || r.items.slice(0, requested.length).some(x => x.tier !== 'REQUESTED') || r.items.filter(x => x.tier !== 'REQUESTED').length > 5) return false
  return r.status !== 'MANUAL' || r.items.length === requested.length
}
export function createAiServicePlanApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, body) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '结构化项目服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method: body ? 'POST' : 'GET', data: body, timeout: 60000, header: { Authorization: `Bearer ${token}` }, success: ({ statusCode, data }) => {
        if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 503: 'unavailable' })[statusCode] || 'server', '项目建议请求未成功，请刷新目录核对'))
        try { if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '项目建议响应异常，请重试')) } catch { return reject(new ServiceError('protocol', '项目建议响应异常，请重试')) }
        resolve(data.data)
      }, fail: () => reject(serviceFailure()) }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    catalog(token) { return call(token, '/api/ai/service-plan/catalog', r => exact(r, ['items', 'total', 'limit']) && r.limit === 40 && Number.isSafeInteger(r.total) && r.total >= 0 && Array.isArray(r.items) && r.items.length <= 40 && r.items.length <= r.total && new Set(r.items.map(x => x?.project_id)).size === r.items.length && r.items.every(x => exact(x, ['project_id', 'project_name', 'category', 'service_content']) && baseProject(x) && typeof x.service_content === 'string')) },
    generate(token, text, requested) { if (!question(text) || !Array.isArray(requested) || requested.length > 3 || !requested.every(id) || new Set(requested).size !== requested.length) throw new ServiceError('invalid', '请填写1–300字问题并最多指定3个项目'); return call(token, '/api/ai/service-plan', r => validPlan(r, requested), { question: text, requested_project_ids: requested, consent_to_provider: true }) },
  }
}
export const aiServicePlanApi = createAiServicePlanApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialAiServicePlanState = () => ({ busy: false, catalog: null, draft: '', requested: [], value: null, message: '', failureKind: '' })
export function createAiServicePlanFlow({ state, token, api = aiServicePlanApi, confirm }) {
  let generation = 0
  function reset() { generation++; Object.assign(state, initialAiServicePlanState()) }
  function failure(e) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network'; if (['unauthorized', 'forbidden', 'missing'].includes(state.failureKind)) { state.catalog = null; state.draft = ''; state.requested = []; state.value = null } }
  async function load() {
    if (state.busy) return; const g = ++generation, actor = token(), valid = () => g === generation && actor === token(); Object.assign(state, initialAiServicePlanState(), { busy: true })
    try { const r = await api.catalog(actor); if (valid()) state.catalog = r } catch (e) { if (valid()) failure(e) } finally { if (valid()) state.busy = false }
  }
  function edit(draft) { generation++; state.draft = draft; state.value = null; state.busy = false; state.message = '' }
  function toggle(project) { if (state.busy || !state.catalog?.items.some(x => x.project_id === project)) return; if (!state.requested.includes(project) && state.requested.length >= 3) { state.message = '最多明确指定3个项目'; return } generation++; state.requested = state.requested.includes(project) ? state.requested.filter(x => x !== project) : [...state.requested, project]; state.value = null; state.message = '' }
  async function generate() {
    if (state.busy || !state.catalog || !question(state.draft)) return
    const g = ++generation, actor = token(), text = state.draft, requested = [...state.requested], valid = () => g === generation && actor === token(); state.busy = true; state.value = null; state.message = ''; state.failureKind = ''
    try { if (!await confirm(text, requested) || !valid()) return; const r = await api.generate(actor, text, requested); if (valid()) state.value = r } catch (e) { if (valid()) failure(e) } finally { if (valid()) state.busy = false }
  }
  function projectRoute(row) { if (state.busy || !token() || !state.value?.items.includes(row) || !id(row.project_id)) return null; return `/pages/service/detail?id=${row.project_id}` }
  return { load, edit, toggle, generate, reset, projectRoute }
}
