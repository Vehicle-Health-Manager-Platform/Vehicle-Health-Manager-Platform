import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
const exact = (r, keys) => r && typeof r === 'object' && !Array.isArray(r) && Object.keys(r).length === keys.length && keys.every(k => Object.hasOwn(r, k))
const id = x => Number.isSafeInteger(x) && x > 0
const version = x => Number.isSafeInteger(x) && x >= 0
const text = s => typeof s === 'string' && [...s].length >= 1 && [...s].length <= 300
const uuid = s => typeof s === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(s)
export const TEXT_REASONS = { PRIVACY: '含隐私或联系信息', ABUSE: '辱骂或不当内容', OFF_TOPIC: '与服务无关', UNVERIFIABLE: '信息无法核对' }
export const TEXT_STATUS = { NONE: '尚未投稿', PENDING: '待审核', APPROVED: '已批准', REJECTED: '已驳回', WITHDRAWN: '已撤销' }
export function validTextState(r, order) {
  return exact(r, ['order_id', 'version', 'status', 'public_text', 'reason_code', 'visible', 'can_submit', 'unavailable_reason']) && r.order_id === order && version(r.version) && Object.hasOwn(TEXT_STATUS, r.status)
    && (['NONE', 'WITHDRAWN'].includes(r.status) ? r.public_text === null : text(r.public_text)) && (r.status === 'NONE' ? r.version === 0 : r.version > 0)
    && (r.status === 'REJECTED' ? Object.hasOwn(TEXT_REASONS, r.reason_code) : r.reason_code === null) && typeof r.visible === 'boolean' && typeof r.can_submit === 'boolean'
    && (!r.visible || r.status === 'APPROVED' && r.can_submit) && (r.can_submit ? r.unavailable_reason === null : ['DISABLED', 'PUBLIC_SOURCE_UNVERIFIED'].includes(r.unavailable_reason))
}
const validPage = (r, page) => exact(r, ['items', 'total', 'page', 'page_size']) && r.page === page && r.page_size === 20 && Number.isSafeInteger(r.total) && r.total >= 0 && Array.isArray(r.items) && r.items.length <= 20 && r.items.length <= r.total
export function createPublicReviewTextsApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, validate, method = 'GET', body, key) {
    if (!token) throw new ServiceError('unauthorized', '请先登录对应账号')
    if (!endpoint) throw new ServiceError('unconfigured', '公开文本服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method, data: body, timeout: 15000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return reject(new ServiceError(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 422: 'preview', 503: 'unavailable' })[statusCode] || 'server', statusCode === 422 ? '请重新预览脱敏文本并确认授权' : '公开文本请求未成功，请刷新核对'))
          try { if (data?.code !== 0 || !validate(data.data)) return reject(new ServiceError('protocol', '公开文本响应异常，请重试')) } catch { return reject(new ServiceError('protocol', '公开文本响应异常，请重试')) }
          resolve(data.data)
        }, fail: () => reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  function scope(n) { if (!id(n)) throw new ServiceError('invalid', '资源编号无效') }
  return {
    detail(token, order) { scope(order); return call(token, `/api/order/${order}/public-review-text`, r => validTextState(r, order)) },
    preview(token, order, content) { scope(order); if (!text(content)) throw new ServiceError('invalid', '请填写1–300字独立公开文本'); return call(token, `/api/order/${order}/public-review-text/preview`, r => exact(r, ['public_text', 'redaction_version']) && text(r.public_text) && r.redaction_version === 'TEXT_REDACTION_V1', 'POST', { public_text: content }) },
    update(token, order, body, key) {
      scope(order)
      if (!uuid(key) || !version(body?.expected_version) || !(body.action === 'WITHDRAW' && exact(body, ['action', 'expected_version']) || body.action === 'SUBMIT' && exact(body, ['action', 'expected_version', 'public_text', 'consent_version']) && text(body.public_text) && body.consent_version === 'R4B2_TEXT_V1')) throw new ServiceError('invalid', '授权请求无效')
      return call(token, `/api/order/${order}/public-review-text`, r => validTextState(r, order), 'PUT', body, key)
    },
    pending(token, page = 1) { scope(page); if (page > 1000000) throw new ServiceError('invalid', '分页无效'); return call(token, `/api/admin/review-texts?page=${page}&page_size=20`, r => validPage(r, page) && new Set(r.items.map(x => x.review_id)).size === r.items.length && r.items.every(x => exact(x, ['review_id', 'version', 'public_text', 'can_approve']) && id(x.review_id) && id(x.version) && text(x.public_text) && typeof x.can_approve === 'boolean')) },
    moderate(token, review, body, key) {
      scope(review)
      if (!uuid(key) || !exact(body, ['expected_version', 'decision', 'reason_code']) || !version(body.expected_version) || !(body.decision === 'APPROVE' && body.reason_code === null || body.decision === 'REJECT' && Object.hasOwn(TEXT_REASONS, body.reason_code))) throw new ServiceError('invalid', '审核请求无效')
      return call(token, `/api/admin/review-texts/${review}/moderate`, r => exact(r, ['review_id', 'version', 'status']) && r.review_id === review && r.version === body.expected_version + 1 && r.status === (body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'), 'POST', body, key)
    },
    list(token, merchant, page = 1) {
      scope(merchant); scope(page); if (page > 1000000) throw new ServiceError('invalid', '分页无效')
      return call(token, `/api/service/merchant/${merchant}/review-texts?page=${page}&page_size=20`, r => exact(r, ['merchant_id', 'items', 'total', 'page', 'page_size']) && r.merchant_id === merchant && validPage({ items: r.items, total: r.total, page: r.page, page_size: r.page_size }, page)
        && new Set(r.items.map(x => x.public_id)).size === r.items.length && r.items.every(x => exact(x, ['public_id', 'public_text', 'published_date']) && uuid(x.public_id) && text(x.public_text) && typeof x.published_date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(x.published_date)))
    },
  }
}
export const publicReviewTextsApi = createPublicReviewTextsApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialTextState = () => ({ busy: false, value: null, draft: '', preview: null, retryAction: null, message: '', failureKind: '' })
export function createTextFlow({ state, token, order, api = publicReviewTextsApi, newKey, confirm }) {
  let generation = 0, pending = null
  const reset = () => { generation++; pending = null; Object.assign(state, initialTextState()) }
  function failure(e) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network'; if (['unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.failureKind)) { state.value = null; state.preview = null; state.draft = ''; state.retryAction = null; pending = null } }
  function context() { const g = ++generation, actor = token(), id = order(); return { actor, id, valid: () => g === generation && actor === token() && id === order() } }
  async function load() {
    if (state.busy) return; const c = context(); pending = null; Object.assign(state, initialTextState(), { busy: true })
    try { const r = await api.detail(c.actor, c.id); if (c.valid()) { state.value = r; state.draft = r.public_text || '' } } catch (e) { if (c.valid()) failure(e) } finally { if (c.valid()) state.busy = false }
  }
  function edit(draft) { generation++; pending = null; state.draft = draft; state.preview = null; state.retryAction = null; state.busy = false; state.message = '' }
  async function preview() {
    if (state.busy || !state.value?.can_submit) return; const c = context(), draft = state.draft; state.busy = true; state.preview = null; pending = null; state.retryAction = null; state.message = ''
    try { const r = await api.preview(c.actor, c.id, draft); if (c.valid() && draft === state.draft) state.preview = r } catch (e) { if (c.valid()) failure(e) } finally { if (c.valid()) state.busy = false }
  }
  async function save(action) {
    if (state.busy || !state.value || !['SUBMIT', 'WITHDRAW'].includes(action) || action === 'SUBMIT' && (!state.value.can_submit || !state.preview)) return
    const c = context(), body = { action, expected_version: state.value.version, ...(action === 'SUBMIT' ? { public_text: state.preview.public_text, consent_version: 'R4B2_TEXT_V1' } : {}) }, canonical = JSON.stringify(body)
    state.busy = true; state.message = ''; state.failureKind = ''; let written = false
    try {
      if (!pending || pending.canonical !== canonical) { if (!await confirm(action, body.public_text) || !c.valid()) return; pending = { body, key: newKey(), canonical } }
      await api.update(c.actor, c.id, pending.body, pending.key)
      if (!c.valid()) return; written = true; pending = null; state.value = null; state.preview = null; state.draft = ''; state.retryAction = null
      const r = await api.detail(c.actor, c.id); if (c.valid()) { state.value = r; state.draft = r.public_text || ''; state.message = '已刷新当前文本授权状态' }
    } catch (e) { if (c.valid()) { state.retryAction = !written && pending ? action : null; if (e?.kind === 'preview') { pending = null; state.preview = null; state.retryAction = null } failure(e) } }
    finally { if (c.valid()) state.busy = false }
  }
  return { load, edit, preview, save, reset }
}
export const initialTextListState = () => ({ busy: false, value: null, message: '', failureKind: '' })
export function createTextListFlow({ state, token, merchant = () => 0, operator = false, api = publicReviewTextsApi, newKey, confirm = async () => false }) {
  let generation = 0, pending = null
  function reset() { generation++; pending = null; Object.assign(state, initialTextListState()) }
  async function load(more = false) {
    if (state.busy) return; const g = ++generation, actor = token(), shop = merchant(), old = more ? state.value : null, page = old ? old.page + 1 : 1, valid = () => g === generation && actor === token() && shop === merchant()
    Object.assign(state, initialTextListState(), { busy: true })
    try { const r = await (operator ? api.pending(actor, page) : api.list(actor, shop, page)); if (valid()) state.value = { ...r, items: old ? [...old.items, ...r.items].filter((x, i, all) => all.findIndex(y => operator ? y.review_id === x.review_id : y.public_id === x.public_id) === i) : r.items } }
    catch (e) { if (valid()) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network' } } finally { if (valid()) state.busy = false }
  }
  async function moderate(row, decision, reason) {
    if (!operator || state.busy || !state.value?.items.includes(row) || decision === 'APPROVE' && !row.can_approve) return
    const g = ++generation, actor = token(), valid = () => g === generation && actor === token(), body = { expected_version: row.version, decision, reason_code: reason }, canonical = JSON.stringify([row.review_id, body]); state.busy = true; state.message = ''
    let success = false
    try { if (!pending || pending.canonical !== canonical) { if (!await confirm(row, decision) || !valid()) return; pending = { review: row.review_id, body, key: newKey(), canonical } }
      await api.moderate(actor, pending.review, pending.body, pending.key); if (valid()) { pending = null; success = true } }
    catch (e) { if (valid()) { state.message = serviceFailure(e).message; state.failureKind = e?.kind || 'network'; if (['conflict', 'unauthorized', 'forbidden', 'missing'].includes(state.failureKind)) { pending = null; state.value = null } } }
    finally { if (valid()) state.busy = false }
    if (success && valid()) await load()
  }
  return { load, moderate, reset }
}
