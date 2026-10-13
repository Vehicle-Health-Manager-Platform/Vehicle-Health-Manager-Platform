import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { publicReviewTextsApi } from './public-review-texts.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
const id = x => Number.isSafeInteger(x) && x > 0
const version = x => Number.isSafeInteger(x) && x >= 0
const uuid = s => typeof s === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(s)
export const validSummaryConsent = (r, order) => exact(r, ['order_id', 'version', 'text_version', 'consented', 'effective', 'can_consent']) && r.order_id === order && version(r.version) && version(r.text_version) && [r.consented, r.effective, r.can_consent].every(x => typeof x === 'boolean') && (!r.effective || r.consented && r.can_consent)
export function summarySentences(s) { const rows = (s.match(/[^。！？\n]+[。！？]?/gu) || []).map(x => x.trim()).filter(Boolean).slice(0, 2); return rows.length ? rows : s.trim() ? [s.trim()] : [] }
export function validReviewSummary(r, merchant) {
  if (!exact(r, ['merchant_id', 'status', 'method', 'sample_count', 'eligible_count', 'highlights', 'sources']) || r.merchant_id !== merchant || !version(r.sample_count) || r.sample_count > 20 || !version(r.eligible_count) || r.eligible_count < r.sample_count || !Array.isArray(r.highlights) || !Array.isArray(r.sources)) return false
  if (['EMPTY', 'DISABLED', 'SOURCE_CHANGED'].includes(r.status)) return r.method === null && r.sample_count === 0 && r.eligible_count === 0 && r.highlights.length === 0 && r.sources.length === 0
  if (!['AI_READY', 'FALLBACK'].includes(r.status) || r.method !== (r.status === 'AI_READY' ? 'AI_EXTRACTIVE_V1' : 'EXACT_EXTRACT_V1') || r.sample_count < 1 || r.highlights.length < 1 || r.highlights.length > 3 || r.sources.length < 1 || r.sources.length > 3 || r.sources.length > r.sample_count) return false
  if (new Set(r.sources.map(s => s?.public_id)).size !== r.sources.length || !r.sources.every(s => exact(s, ['public_id', 'public_text', 'published_date']) && uuid(s.public_id) && typeof s.public_text === 'string' && [...s.public_text].length >= 1 && [...s.public_text].length <= 300 && typeof s.published_date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s.published_date))) return false
  const seen = new Set()
  if (!r.highlights.every(h => { if (!exact(h, ['public_id', 'quote']) || !uuid(h.public_id) || typeof h.quote !== 'string') return false; const s = r.sources.find(s => s.public_id === h.public_id), k = JSON.stringify([h.public_id, h.quote]); if (!s || !summarySentences(s.public_text).includes(h.quote) || seen.has(k)) return false; seen.add(k); return true })) return false
  return r.sources.every(s => r.highlights.some(h => h.public_id === s.public_id))
}
export function createReviewSummaryApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, method = 'GET', body, key) {
    if (!token) throw new ServiceError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ServiceError('unconfigured', '评价总结服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method, data: body, timeout: 60000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' })[statusCode] || 'server', '评价总结请求未成功，请刷新核对'))
          try { if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '评价总结响应异常，请重试')) } catch { return reject(new ServiceError('protocol', '评价总结响应异常，请重试')) }
          resolve(data.data)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    detail(token, order) { if (!id(order)) throw new ServiceError('invalid', '订单无效'); return call(token, `/api/order/${order}/review-summary-consent`, r => validSummaryConsent(r, order)) },
    update(token, order, body, key) {
      if (!id(order) || !uuid(key) || !exact(body, ['consented', 'expected_version', 'expected_text_version', 'consent_version']) || typeof body.consented !== 'boolean' || !version(body.expected_version) || !version(body.expected_text_version) || body.consent_version !== 'R4B2_SUMMARY_V1') throw new ServiceError('invalid', 'AI用途授权无效')
      return call(token, `/api/order/${order}/review-summary-consent`, r => validSummaryConsent(r, order), 'PUT', body, key)
    },
    summarize(token, merchant) { if (!id(merchant)) throw new ServiceError('invalid', '门店无效'); return call(token, `/api/service/merchant/${merchant}/review-summary`, r => validReviewSummary(r, merchant), 'POST', {}) },
  }
}
export const reviewSummaryApi = createReviewSummaryApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialSummaryConsentState = () => ({ busy: false, value: null, publicText: null, retryConsent: null, message: '', failureKind: '' })
export function createSummaryConsentFlow({ state, token, order, api = reviewSummaryApi, textApi = publicReviewTextsApi, newKey, confirm }) {
  let generation = 0, pending = null
  function reset() { generation++; pending = null; Object.assign(state, initialSummaryConsentState()) }
  function context() { const g = ++generation, actor = token(), id = order(); return { actor, id, valid: () => g === generation && actor === token() && id === order() } }
  function failure(e) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network'; if (['unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.failureKind)) { state.value = null; state.publicText = null; pending = null; state.retryConsent = null } }
  async function read(c) { const [r, text] = await Promise.all([api.detail(c.actor, c.id), textApi.detail(c.actor, c.id)]); if (r.text_version !== text.version || r.can_consent && (!text.visible || text.status !== 'APPROVED')) throw new ServiceError('conflict', '批准文字已变化，请刷新核对'); return { r, text } }
  async function load() {
    if (state.busy) return; const c = context(); pending = null; Object.assign(state, initialSummaryConsentState(), { busy: true })
    try { const { r, text } = await read(c); if (c.valid()) { state.value = r; state.publicText = text.public_text } } catch (e) { if (c.valid()) failure(e) } finally { if (c.valid()) state.busy = false }
  }
  async function save(consented) {
    if (state.busy || !state.value || consented && !state.value.can_consent) return
    const c = context(), body = { consented, expected_version: state.value.version, expected_text_version: state.value.text_version, consent_version: 'R4B2_SUMMARY_V1' }, canonical = JSON.stringify(body)
    state.busy = true; state.message = ''; state.failureKind = ''; let written = false
    try {
      if (!pending || pending.canonical !== canonical) { if (!await confirm(consented, state.publicText) || !c.valid()) return; pending = { body, key: newKey(), canonical } }
      await api.update(c.actor, c.id, pending.body, pending.key); if (!c.valid()) return; written = true; pending = null; state.value = null; state.publicText = null; state.retryConsent = null
      const { r, text } = await read(c); if (c.valid()) { state.value = r; state.publicText = text.public_text; state.message = '已刷新当前AI用途授权状态' }
    } catch (e) { if (c.valid()) { state.retryConsent = !written && pending ? consented : null; failure(e) } } finally { if (c.valid()) state.busy = false }
  }
  return { load, save, reset }
}
export const initialReviewSummaryState = () => ({ busy: false, value: null, message: '', failureKind: '' })
export function createReviewSummaryFlow({ state, token, merchant, api = reviewSummaryApi }) {
  let generation = 0
  function reset() { generation++; Object.assign(state, initialReviewSummaryState()) }
  async function load() {
    if (state.busy) return; const g = ++generation, actor = token(), shop = merchant(), valid = () => g === generation && actor === token() && shop === merchant(); Object.assign(state, initialReviewSummaryState(), { busy: true })
    try { const r = await api.summarize(actor, shop); if (valid()) state.value = r } catch (e) { if (valid()) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network' } } finally { if (valid()) state.busy = false }
  }
  return { load, reset }
}
