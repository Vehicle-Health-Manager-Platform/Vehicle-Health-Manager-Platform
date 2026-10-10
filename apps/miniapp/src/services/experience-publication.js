import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { REVIEW_REASONS } from './experience-cards.js'

export const safeId = n => Number.isSafeInteger(n) && n > 0
export const exact = (n, keys) => n && typeof n === 'object' && !Array.isArray(n) && Object.keys(n).length === keys.length && keys.every(k => Object.hasOwn(n, k))
const uuid = n => typeof n === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(n)
export function validFacts(s) {
  return exact(s, ['version', 'work_minutes', 'part_kinds', 'no_parts', 'recorded_month']) && s.version === 1
    && Number.isInteger(s.work_minutes) && s.work_minutes >= 1 && s.work_minutes <= 1440
    && Number.isInteger(s.part_kinds) && s.part_kinds >= 0 && s.part_kinds <= 20 && s.no_parts === (s.part_kinds === 0)
    && typeof s.recorded_month === 'string' && /^[1-9][0-9]{3}-(0[1-9]|1[0-2])$/.test(s.recorded_month)
}
export const validPending = c => exact(c, ['card_id', 'revision', 'title', 'summary', 'model_id']) && safeId(c.card_id)
  && Number.isInteger(c.revision) && c.revision >= 1 && c.revision < 2147483647 && c.title === '施工经验摘要'
  && validFacts(c.summary) && (c.model_id === null || safeId(c.model_id))
export const validPublic = c => exact(c, ['experience_id', 'title', 'summary', 'model_id', 'published_at']) && uuid(c.experience_id)
  && c.title === '施工经验摘要' && validFacts(c.summary) && safeId(c.model_id) && typeof c.published_at === 'string' && Number.isFinite(Date.parse(c.published_at))
export class PublicationError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
const errors = { 400: ['invalid', '请求无效，请刷新'], 401: ['unauthorized', '登录已失效，请重新登录'], 403: ['forbidden', '当前账号没有此项权限'],
  404: ['missing', '记录或车辆已不可用'], 409: ['conflict', '授权、审核版本或来源已变化，请刷新'], 429: ['limited', '请求过于频繁，请稍后重试'],
  503: ['unavailable', '服务尚未开启或暂不可用，请稍后重试'] }
export function createPublicationApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(path, method, token, body, validate, key) {
    if (!endpoint) throw new PublicationError('unconfigured', '服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method, data: body, timeout: 15000,
        header: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode)) { reject(new PublicationError('protocol', '服务响应异常，请重试')); return }
          if (statusCode < 200 || statusCode >= 300) { const [kind, message] = errors[statusCode] || ['server', '请求未成功，请重试']; reject(new PublicationError(kind, message)); return }
          if (data?.code !== 0 || !validate(data.data)) { reject(new PublicationError('protocol', '服务响应异常，请重试')); return }
          resolve(data.data)
        }, fail: () => reject(new PublicationError('network', '网络连接失败，请重试')),
      }) } catch { reject(new PublicationError('network', '网络连接失败，请重试')) }
    })
  }
  const credentials = (account, password) => {
    if (typeof account !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.-]{2,63}$/.test(account)
      || typeof password !== 'string' || !password.trim() || password.includes('\0')
      || unescape(encodeURIComponent(password)).length > 72) throw new PublicationError('invalid', '请输入有效运营账号和密码')
  }
  const authorized = token => { if (!token) throw new PublicationError('unauthorized', '请先登录') }
  return {
    code(account, password) { credentials(account, password); return call('/api/auth/operator/code', 'POST', '', { account, password },
      n => exact(n, ['sent', 'expires_in']) && n.sent === true && n.expires_in === 300) },
    login(account, password, sms_code) { credentials(account, password); if (!/^[0-9]{6}$/.test(sms_code)) throw new PublicationError('invalid', '请输入六位短信验证码')
      return call('/api/auth/operator/login', 'POST', '', { account, password, sms_code }, n => exact(n, ['access_token', 'token_type', 'expires_in', 'user'])
        && typeof n.access_token === 'string' && n.access_token.length > 0 && n.token_type === 'Bearer' && n.expires_in === 900
        && exact(n.user, ['id', 'role', 'can_review']) && safeId(n.user.id) && n.user.role === 'operator' && typeof n.user.can_review === 'boolean') },
    logout(token) { authorized(token); return call('/api/auth/operator/logout', 'POST', token, {}, n => exact(n, ['revoked']) && n.revoked === true) },
    pending(token, page = 1) { authorized(token); if (!Number.isInteger(page) || page < 1 || page > 1000000) throw new PublicationError('invalid', '页码无效')
      return call(`/api/admin/experience-cards?page=${page}&page_size=20`, 'GET', token, undefined,
        n => exact(n, ['items', 'total', 'page', 'page_size']) && Array.isArray(n.items) && n.items.length <= 20 && n.items.every(validPending)
          && new Set(n.items.map(c => c.card_id)).size === n.items.length && Number.isSafeInteger(n.total) && n.total >= n.items.length && n.page === page && n.page_size === 20) },
    moderate(token, card, decision, reason, key) { authorized(token)
      if (!validPending(card) || !uuid(key) || !['APPROVE', 'REJECT'].includes(decision)
        || (decision === 'APPROVE' ? reason !== null || card.model_id === null : !Object.hasOwn(REVIEW_REASONS, reason))) throw new PublicationError('invalid', '请选择有效审核操作和理由')
      return call(`/api/admin/experience-cards/${card.card_id}/moderate`, 'POST', token, { revision: card.revision, decision, reason_code: reason },
        n => exact(n, ['card_id', 'status', 'revision', 'decision', 'reason_code']) && n.card_id === card.card_id && n.status === (decision === 'APPROVE' ? 'PUBLISHED' : 'REJECTED')
          && n.revision === card.revision + 1 && n.decision === decision && n.reason_code === reason, key) },
    experiences(token, vehicle, cursor = null) { authorized(token)
      if (!safeId(vehicle) || cursor !== null && !safeId(cursor)) throw new PublicationError('invalid', '车辆或游标无效')
      return call(`/api/community/experiences?vehicle_id=${vehicle}${cursor === null ? '' : `&cursor=${cursor}`}`, 'GET', token, undefined,
        n => exact(n, ['items', 'next_cursor']) && Array.isArray(n.items) && n.items.length <= 20 && n.items.every(validPublic)
          && new Set(n.items.map(c => c.experience_id)).size === n.items.length && (n.next_cursor === null || safeId(n.next_cursor) && (cursor === null || n.next_cursor < cursor))) },
  }
}
export const publicationApi = createPublicationApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialPublicationState = () => ({ rows: [], total: 0, page: 0, cursor: null, loaded: false, busy: false, writing: false, message: '', kind: '' })
export function createPublicationFlow({ state, api, token, vehicle = () => null, operator = false, newKey }) {
  let active = false, epoch = 0, pending = null
  function reset() { active = false; epoch++; pending = null; Object.assign(state, initialPublicationState()) }
  const current = (version, actor, car) => active && epoch === version && token() === actor && vehicle() === car
  async function load(more = false) {
    if (!active || state.busy || state.writing || !token() || !operator && !safeId(vehicle()) || more && !operator && state.cursor === null) return
    const version = ++epoch, actor = token(), car = vehicle(), page = more ? state.page + 1 : 1, cursor = more ? state.cursor : null
    pending = null; state.busy = true; state.message = ''; state.kind = ''
    if (!more) { state.rows = []; state.loaded = false; state.cursor = null; state.total = 0; state.page = 0 }
    try {
      const result = operator ? await api.pending(actor, page) : await api.experiences(actor, car, cursor)
      if (!current(version, actor, car)) return
      const field = operator ? 'card_id' : 'experience_id'
      state.rows = more ? [...state.rows, ...result.items.filter(c => !state.rows.some(old => old[field] === c[field]))] : result.items
      state.total = result.total || 0; state.page = page; state.cursor = result.next_cursor ?? null; state.loaded = true
    } catch (e) { if (current(version, actor, car)) { state.message = e.message || '加载失败'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.rows = []; state.total = 0; state.cursor = null; state.loaded = false } } }
    finally { if (current(version, actor, car)) state.busy = false }
  }
  async function moderate(card, decision, reason) {
    if (!operator || !active || state.busy || state.writing || !token() || !state.rows.some(c => c.card_id === card.card_id && c.revision === card.revision)) return
    const version = epoch, actor = token(), car = vehicle(), scope = `${card.card_id}:${card.revision}:${decision}:${reason}`
    if (pending?.scope !== scope) pending = { scope, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try { await api.moderate(actor, card, decision, reason, pending.key); if (!current(version, actor, car)) return
      state.rows = state.rows.filter(c => c.card_id !== card.card_id); state.total = Math.max(0, state.total - 1); pending = null
      // 审核会改变offset列表；下一页前必须刷新，避免删除后跳过一条待审记录。
      state.loaded = false; state.page = 0
      state.message = decision === 'APPROVE' ? '已批准；车主可随时撤回授权' : '已驳回；车主可重新明确授权送审'
    } catch (e) { if (current(version, actor, car)) { state.message = e.message || '审核未确认，请重试'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.rows = []; state.total = 0; state.loaded = false }
      if (['invalid', 'unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) pending = null } }
    finally { if (current(version, actor, car)) state.writing = false }
  }
  return { reset, resume: () => { active = true }, load, moderate }
}
