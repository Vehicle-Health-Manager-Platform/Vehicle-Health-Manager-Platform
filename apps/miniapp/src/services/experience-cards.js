import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export const EXPERIENCE_CONSENT_VERSION = 'experience-v1'
export const EXPERIENCE_CONSENT_TEXT = '我授权这份不含照片、施工原文和个人身份信息的摘要接受审核，并在审核通过后用于车友经验展示。我可以撤回，原档案和评价保留。'
export const CARD_STATES = { DRAFT: '私有草稿', PENDING_REVIEW: '已授权 · 待审核，尚未公开', WITHDRAWN: '已撤回 · 未授权', PUBLISHED: '审核通过 · 已展示', REJECTED: '审核未通过 · 尚未公开' }
export const REVIEW_REASONS = { INSUFFICIENT_DETAIL: '摘要信息不足', NOT_SUITABLE: '不适合经验展示' }
const id = n => Number.isSafeInteger(n) && n > 0
const exact = (n, keys) => n && typeof n === 'object' && !Array.isArray(n) && Object.keys(n).length === keys.length && keys.every(k => Object.hasOwn(n, k))
const time = n => typeof n === 'string' && Number.isFinite(Date.parse(n))
export class ExperienceCardError extends Error { constructor(kind, message) { super(message); this.kind = kind } }
export function validExperienceCard(c) {
  if (!exact(c, ['card_id', 'vehicle_id', 'order_id', 'archive_id', 'title', 'status', 'revision', 'test_mode', 'summary', 'consent_version', 'consented_at', 'withdrawn_at', ...(c?.status === 'REJECTED' ? ['review_reason'] : [])])
    || !['card_id', 'vehicle_id', 'order_id', 'archive_id'].every(k => id(c[k])) || c.title !== '施工经验摘要'
    || !Object.hasOwn(CARD_STATES, c.status) || !Number.isSafeInteger(c.revision) || c.revision < 0 || typeof c.test_mode !== 'boolean') return false
  const s = c.summary
  if (!exact(s, ['version', 'work_minutes', 'part_kinds', 'no_parts', 'recorded_month']) || s.version !== 1
    || !Number.isInteger(s.work_minutes) || s.work_minutes < 1 || s.work_minutes > 1440
    || !Number.isInteger(s.part_kinds) || s.part_kinds < 0 || s.part_kinds > 20 || s.no_parts !== (s.part_kinds === 0)
    || typeof s.recorded_month !== 'string' || !/^[1-9][0-9]{3}-(0[1-9]|1[0-2])$/.test(s.recorded_month)) return false
  if (c.status === 'DRAFT') return c.revision === 0 && c.consent_version === null && c.consented_at === null && c.withdrawn_at === null
  if (['PENDING_REVIEW', 'PUBLISHED', 'REJECTED'].includes(c.status)) return !c.test_mode && c.revision > 0 && c.consent_version === EXPERIENCE_CONSENT_VERSION && time(c.consented_at) && c.withdrawn_at === null
    && (c.status !== 'REJECTED' || c.review_reason === null || Object.hasOwn(REVIEW_REASONS, c.review_reason))
  return c.revision > 0 && c.consent_version === null && c.consented_at === null && time(c.withdrawn_at)
}
const errors = {
  400: ['invalid', '授权信息无效，请刷新后重试'], 401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '请使用正式车主账号'], 404: ['missing', '车辆或卡片已不可用，请刷新'],
  409: ['conflict', '卡片来源或授权状态已变更，请刷新后重新确认；测试记录不能送审'],
  503: ['unavailable', '卡片服务暂不可用，可使用原请求重试'],
}
export function createExperienceCardsApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, method, body, key, validate) {
    if (!token) throw new ExperienceCardError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ExperienceCardError('unconfigured', '卡片服务尚未配置')
    return new Promise((resolve, reject) => {
      try { runtime().request({ url: endpoint + path, method, data: body, timeout: 15000,
        header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key, 'Content-Type': 'application/json' } : {}) },
        success: ({ statusCode, data }) => {
          if (!Number.isInteger(statusCode)) { reject(new ExperienceCardError('protocol', '卡片响应异常，请使用原请求重试')); return }
          if (statusCode < 200 || statusCode >= 300) {
            const [kind, message] = errors[statusCode] || ['server', '卡片请求未成功，请重试']; reject(new ExperienceCardError(kind, message)); return
          }
          if (data?.code !== 0 || !validate(data.data)) { reject(new ExperienceCardError('protocol', '卡片响应异常，请使用原请求重试')); return }
          resolve(data.data)
        }, fail: () => reject(new ExperienceCardError('network', '无法连接卡片服务，可使用原请求重试')),
      }) } catch { reject(new ExperienceCardError('network', '无法连接卡片服务，可使用原请求重试')) }
    })
  }
  return {
    list(token, vehicle, page = 1) {
      if (!id(vehicle) || !Number.isInteger(page) || page < 1 || page > 1000000) throw new ExperienceCardError('invalid', '车辆或页码无效')
      return call(token, `/api/experience-cards?vehicle_id=${vehicle}&page=${page}&page_size=20`, 'GET', undefined, undefined,
        n => exact(n, ['items', 'total', 'page', 'page_size']) && Array.isArray(n.items) && n.items.length <= 20
          && Number.isSafeInteger(n.total) && n.total >= n.items.length && n.page === page && n.page_size === 20
          && n.items.every(c => validExperienceCard(c) && c.vehicle_id === vehicle) && new Set(n.items.map(c => c.card_id)).size === n.items.length)
    },
    change(token, card, action, key) {
      if (!validExperienceCard(card) || !['consent', 'withdraw'].includes(action)
        || !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key)) throw new ExperienceCardError('invalid', '卡片操作无效，请刷新')
      if (action === 'consent' && (card.test_mode || ['PENDING_REVIEW', 'PUBLISHED'].includes(card.status))) throw new ExperienceCardError('invalid', '请刷新状态；已展示卡片需先撤回授权')
      return call(token, `/api/experience-cards/${card.card_id}/${action}`, 'POST',
        action === 'consent' ? { agree: true, consent_version: EXPERIENCE_CONSENT_VERSION } : {}, key,
        n => exact(n, ['card']) && validExperienceCard(n.card) && ['card_id', 'vehicle_id', 'order_id', 'archive_id'].every(k => n.card[k] === card[k])
          && n.card.test_mode === card.test_mode && n.card.revision >= card.revision && n.card.status === (action === 'consent' ? 'PENDING_REVIEW' : 'WITHDRAWN'))
    },
  }
}
export const experienceCardsApi = createExperienceCardsApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialExperienceState = () => ({ rows: [], total: 0, page: 0, loaded: false, busy: false, writing: false, message: '', kind: '' })
export function createExperienceFlow({ state, api, token, vehicle, newKey }) {
  let epoch = 0, active = false, pending = null
  const current = (version, actor, car) => active && epoch === version && token() === actor && vehicle() === car
  function reset() { epoch++; active = false; pending = null; Object.assign(state, initialExperienceState()) }
  async function load(more = false) {
    if (!active || state.busy || state.writing || !token() || !id(vehicle())) return
    const version = ++epoch, actor = token(), car = vehicle(), page = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.kind = ''
    if (!more) { state.rows = []; state.loaded = false; state.total = 0; state.page = 0 }
    try {
      const result = await api.list(actor, car, page); if (!current(version, actor, car)) return
      state.rows = more ? [...state.rows, ...result.items.filter(c => !state.rows.some(old => old.card_id === c.card_id))] : result.items
      state.total = result.total; state.page = page; state.loaded = true
    } catch (e) { if (current(version, actor, car)) { state.message = e.message || '卡片加载失败'; state.kind = e.kind || 'network' } }
    finally { if (current(version, actor, car)) state.busy = false }
  }
  async function change(card, action, agreed = false) {
    if (!active || state.busy || state.writing || !token() || card.vehicle_id !== vehicle()
      || !state.rows.some(c => c.card_id === card.card_id && c.revision === card.revision)) return
    if (action === 'consent' && (!agreed || card.test_mode || ['PENDING_REVIEW', 'PUBLISHED'].includes(card.status))) return
    if (!['consent', 'withdraw'].includes(action) || action === 'withdraw' && card.status === 'WITHDRAWN') return
    const version = epoch, actor = token(), car = vehicle(), scope = `${card.card_id}:${card.revision}:${action}`
    if (pending?.scope !== scope) pending = { scope, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try {
      const result = await api.change(actor, card, action, pending.key); if (!current(version, actor, car)) return
      state.rows = state.rows.map(c => c.card_id === card.card_id ? result.card : c); pending = null
      state.message = action === 'consent' ? '已授权送审，尚未公开；可以撤回' : '已撤回授权，原档案和评价保留'
    } catch (e) {
      if (!current(version, actor, car)) return
      state.message = e.message || '操作未确认，可使用原请求重试'; state.kind = e.kind || 'network'
      if (['invalid', 'unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) pending = null
    } finally { if (current(version, actor, car)) state.writing = false }
  }
  return { reset, resume: () => { active = true }, load, change }
}
