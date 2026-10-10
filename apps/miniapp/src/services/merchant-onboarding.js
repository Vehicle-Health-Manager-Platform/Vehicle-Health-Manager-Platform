import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

// R1a 商家入驻：车主提交/查询/重提 + 运营待审/审核/配额。
//
// 与服务端 MerchantOnboardingInput 一一对应：固定品类、固定驳回码、6 位行政区划码、
// 1..9 个互不相同的资质文件 id。客户端先按同一套规则拦住明显非法的输入，
// 但任何判定都以服务端为准——本地校验只用于尽早给出可读提示。

export const CATEGORIES = { MAINTENANCE: '维修保养', TIRE: '轮胎服务', REPAIR: '钣喷修复', BEAUTY: '美容洗护', SERVICE: '综合服务', SUPPLIES: '用品供应' }
export const REJECT_REASONS = { QUALIFICATION_INCOMPLETE: '资质材料不完整', CATEGORY_MISMATCH: '经营品类不符', DUPLICATE_STORE: '与既有门店重复', REGION_QUOTA_FULL: '区域品类名额已满' }
export const APPLICATION_STATUS = { PENDING_REVIEW: '待审核', APPROVED: '已通过', REJECTED: '已驳回' }

export const safeId = n => Number.isSafeInteger(n) && n > 0
// 始终返回布尔：验证函数可能与 && 串联，返回 null/undefined 会让断言与日志难以判读。
export const exact = (n, keys) => Boolean(n && typeof n === 'object' && !Array.isArray(n)
  && Object.keys(n).length === keys.length && keys.every(k => Object.hasOwn(n, k)))
const uuid = n => typeof n === 'string' && /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(n)
const stamp = n => typeof n === 'string' && Number.isFinite(Date.parse(n))
const region = value => typeof value === 'string' && /^[0-9]{6}$/.test(value)
const bounded = (value, least, most) => typeof value === 'string' && [...value].length >= least && [...value].length <= most
  && value === value.trim() && ![...value].some(c => c.codePointAt(0) < 0x20)

export class OnboardingError extends Error {
  constructor(kind, message) { super(message); this.name = 'OnboardingError'; this.kind = kind }
}

export const validReview = r => exact(r, ['revision', 'decision', 'reason_code', 'merchant_id', 'decided_at'])
  && Number.isInteger(r.revision) && r.revision >= 1 && ['APPROVED', 'REJECTED'].includes(r.decision)
  && (r.decision === 'APPROVED' ? r.reason_code === null : Object.hasOwn(REJECT_REASONS, r.reason_code))
  && (r.decision === 'APPROVED' ? safeId(r.merchant_id) : r.merchant_id === null) && stamp(r.decided_at)

export const validApplication = a => exact(a, ['application_id', 'merchant_name', 'category', 'region_code', 'address',
  'contact_phone', 'status', 'revision', 'merchant_id', 'review_reason', 'qualification_file_ids', 'created_at', 'updated_at'])
  && safeId(a.application_id) && bounded(a.merchant_name, 2, 64) && Object.hasOwn(CATEGORIES, a.category) && region(a.region_code)
  && bounded(a.address, 1, 256) && /^1[3-9][0-9]{9}$/.test(a.contact_phone)
  && Object.hasOwn(APPLICATION_STATUS, a.status) && Number.isInteger(a.revision) && a.revision >= 1
  && (a.status === 'APPROVED' ? safeId(a.merchant_id) : a.merchant_id === null)
  && (a.status === 'REJECTED' ? Object.hasOwn(REJECT_REASONS, a.review_reason) : a.review_reason === null)
  && Array.isArray(a.qualification_file_ids) && a.qualification_file_ids.length >= 1 && a.qualification_file_ids.length <= 9
  && a.qualification_file_ids.every(safeId) && new Set(a.qualification_file_ids).size === a.qualification_file_ids.length
  && stamp(a.created_at) && stamp(a.updated_at)

/** mine 在无申请时只返回 application:null，不带 reviews。 */
export const validMine = n => exact(n, ['application'])
  ? n.application === null
  : exact(n, ['application', 'reviews']) && validApplication(n.application)
    && Array.isArray(n.reviews) && n.reviews.every(validReview) && n.reviews.every((r, i, all) => i === 0 || all[i - 1].revision > r.revision)

export const validSubmit = n => exact(n, ['application', 'revision']) && validApplication(n.application)
  && Number.isInteger(n.revision) && n.revision === n.application.revision

export const validSummary = s => exact(s, ['application_id', 'merchant_name', 'category', 'region_code', 'revision', 'submitted_at'])
  && safeId(s.application_id) && bounded(s.merchant_name, 2, 64) && Object.hasOwn(CATEGORIES, s.category)
  && region(s.region_code) && Number.isInteger(s.revision) && s.revision >= 1 && stamp(s.submitted_at)

export const validPendingPage = (n, page) => exact(n, ['items', 'total', 'page', 'page_size']) && Array.isArray(n.items)
  && n.items.length <= 20 && n.items.every(validSummary) && new Set(n.items.map(s => s.application_id)).size === n.items.length
  && Number.isSafeInteger(n.total) && n.total >= n.items.length && n.page === page && n.page_size === 20

export const validDetail = n => exact(n, ['application', 'reviews']) && validApplication(n.application)
  && Array.isArray(n.reviews) && n.reviews.every(validReview)

export const validModerate = (n, decision, reason) => exact(n, ['application', 'decision', 'reason_code', 'merchant_id'])
  && validApplication(n.application) && n.decision === decision && n.reason_code === reason
  && (decision === 'APPROVE' ? safeId(n.merchant_id) && n.application.status === 'APPROVED' && n.application.merchant_id === n.merchant_id
    : n.merchant_id === null && n.application.status === 'REJECTED' && n.application.review_reason === reason)

/** 资质文件只换取短时签名地址，不产生公开链接。 */
export const validAccess = n => exact(n, ['url', 'expires_at'])
  && typeof n.url === 'string' && /^https:\/\/[^/\s?#@]+(?:\/|$)/.test(n.url) && !/\s/.test(n.url)
  && stamp(n.expires_at) && Date.parse(n.expires_at) > Date.now()

export const validQuota = q => exact(q, ['region_code', 'category', 'max_active', 'active_stores'])
  && region(q.region_code) && Object.hasOwn(CATEGORIES, q.category)
  && Number.isSafeInteger(q.max_active) && q.max_active >= 0 && q.max_active <= 100000
  && Number.isSafeInteger(q.active_stores) && q.active_stores >= 0

export const validQuotas = n => exact(n, ['items']) && Array.isArray(n.items) && n.items.every(validQuota)
  && new Set(n.items.map(q => `${q.region_code}/${q.category}`)).size === n.items.length

const errors = { 400: ['invalid', '提交内容无效，请检查后重试'], 401: ['unauthorized', '登录已失效，请重新登录'],
  403: ['forbidden', '当前账号没有此项权限'], 404: ['missing', '申请或资质文件已不可用'],
  409: ['conflict', '状态或版本已变化，请刷新后重试'], 429: ['limited', '请求过于频繁，请稍后重试'],
  503: ['unavailable', '入驻服务尚未开启或暂不可用，请稍后重试'] }

/**
 * 弹窗的 success 回调会在任意时刻返回：这期间用户可能切换账号、离开页面或换了审核目标。
 * 身份、页面可见性或目标任一变化，旧确认就必须作废——否则会用新账号批准旧申请，
 * 或把决定落到此刻已变化的 revision 上。抽在这里是为了能直接单测，而不是靠人读 .vue。
 */
export const confirmStillHolds = (opened, now) => Boolean(now?.visible)
  && opened?.actor === now?.token
  && (opened?.target === undefined || opened.target === now?.target)

export function createOnboardingApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(path, method, token, body, validate, key) {
    if (!endpoint) throw new OnboardingError('unconfigured', '服务尚未配置')
    return new Promise((resolve, reject) => {
      try {
        runtime().request({ url: endpoint + path, method, data: body, timeout: 15000,
          header: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            if (!Number.isInteger(statusCode)) { reject(new OnboardingError('protocol', '服务响应异常，请重试')); return }
            if (statusCode < 200 || statusCode >= 300) { const [kind, message] = errors[statusCode] || ['server', '请求未成功，请重试']; reject(new OnboardingError(kind, message)); return }
            if (data?.code !== 0 || !validate(data.data)) { reject(new OnboardingError('protocol', '服务响应异常，请重试')); return }
            resolve(data.data)
          }, fail: () => reject(new OnboardingError('network', '网络连接失败，请重试')),
        })
      } catch { reject(new OnboardingError('network', '网络连接失败，请重试')) }
    })
  }
  const authorized = token => { if (typeof token !== 'string' || !token) throw new OnboardingError('unauthorized', '请先登录') }
  const application = draft => {
    if (!exact(draft, ['merchant_name', 'category', 'region_code', 'address', 'contact_phone', 'qualification_file_ids'])) throw new OnboardingError('invalid', '请填写完整的入驻信息')
    if (!bounded(draft.merchant_name, 2, 64)) throw new OnboardingError('invalid', '商家名称需 2 至 64 个字符，且首尾不留空格')
    if (!Object.hasOwn(CATEGORIES, draft.category)) throw new OnboardingError('invalid', '请选择经营品类')
    if (!region(draft.region_code)) throw new OnboardingError('invalid', '行政区划码需为 6 位数字')
    if (!bounded(draft.address, 1, 256)) throw new OnboardingError('invalid', '门店地址需 1 至 256 个字符')
    if (typeof draft.contact_phone !== 'string' || !/^1[3-9][0-9]{9}$/.test(draft.contact_phone)) throw new OnboardingError('invalid', '请填写有效的手机号')
    if (!Array.isArray(draft.qualification_file_ids) || draft.qualification_file_ids.length < 1 || draft.qualification_file_ids.length > 9
      || !draft.qualification_file_ids.every(safeId) || new Set(draft.qualification_file_ids).size !== draft.qualification_file_ids.length)
      throw new OnboardingError('invalid', '请上传 1 至 9 张互不相同的资质图片')
  }
  return {
    submit(token, draft, key) { authorized(token); application(draft)
      if (!uuid(key)) throw new OnboardingError('invalid', '提交标识无效，请重试')
      return call('/api/merchant-applications', 'POST', token, { ...draft }, n => validSubmit(n), key) },
    mine(token) { authorized(token); return call('/api/merchant-applications/mine', 'GET', token, undefined, n => validMine(n)) },
    pending(token, page = 1) { authorized(token)
      if (!Number.isInteger(page) || page < 1 || page > 1000000) throw new OnboardingError('invalid', '页码无效')
      return call(`/api/admin/merchant-applications?page=${page}&page_size=20`, 'GET', token, undefined, n => validPendingPage(n, page)) },
    detail(token, id) { authorized(token); if (!safeId(id)) throw new OnboardingError('invalid', '申请编号无效')
      return call(`/api/admin/merchant-applications/${id}`, 'GET', token, undefined, n => validDetail(n)) },
    fileAccess(token, id, file) { authorized(token); if (!safeId(id) || !safeId(file)) throw new OnboardingError('invalid', '资质文件编号无效')
      return call(`/api/admin/merchant-applications/${id}/files/${file}/access`, 'GET', token, undefined, n => validAccess(n)) },
    moderate(token, id, revision, decision, reason, key) { authorized(token)
      if (!safeId(id) || !Number.isInteger(revision) || revision < 1 || !uuid(key)
        || !['APPROVE', 'REJECT'].includes(decision)
        || (decision === 'APPROVE' ? reason !== null : !Object.hasOwn(REJECT_REASONS, reason)))
        throw new OnboardingError('invalid', '请选择有效的审核结论与驳回理由')
      return call(`/api/admin/merchant-applications/${id}/moderate`, 'POST', token, { revision, decision, reason_code: reason },
        n => validModerate(n, decision, reason), key) },
    quotas(token) { authorized(token); return call('/api/admin/merchant-quotas', 'GET', token, undefined, n => validQuotas(n)) },
    setQuota(token, draft, key) { authorized(token)
      if (!exact(draft, ['region_code', 'category', 'max_active']) || !region(draft.region_code)
        || !Object.hasOwn(CATEGORIES, draft.category) || !Number.isInteger(draft.max_active) || draft.max_active < 0 || draft.max_active > 100000
        || !uuid(key)) throw new OnboardingError('invalid', '请填写有效的区域、品类与名额上限')
      return call('/api/admin/merchant-quotas', 'PUT', token, { ...draft }, n => validQuota(n), key) },
  }
}
export const onboardingApi = createOnboardingApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })

export const initialOwnerOnboardingState = () => ({ application: null, reviews: [], loaded: false, busy: false, writing: false, message: '', kind: '' })
export const initialOperatorOnboardingState = () => ({ rows: [], total: 0, page: 0, loaded: false, busy: false, writing: false, message: '', kind: '',
  detail: null, detailReviews: [], quotas: [], quotasLoaded: false })

/** 车主：读取本人当前申请与审核历史，并提交首次申请或驳回后的重提。 */
export function createOwnerOnboardingFlow({ state, api, token, newKey }) {
  let active = false, epoch = 0, pending = null
  function reset() { active = false; epoch++; pending = null; Object.assign(state, initialOwnerOnboardingState()) }
  const current = (version, actor) => active && epoch === version && token() === actor
  async function load() {
    if (!active || state.busy || state.writing || !token()) return
    const version = ++epoch, actor = token()
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const result = await api.mine(actor)
      if (!current(version, actor)) return
      state.application = result.application
      state.reviews = result.application ? result.reviews : []
      state.loaded = true
    } catch (e) { if (current(version, actor)) { state.message = e.message || '加载失败'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.application = null; state.reviews = []; state.loaded = false } } }
    finally { if (current(version, actor)) state.busy = false }
  }
  async function submit(draft) {
    if (!active || state.busy || state.writing || !token()) return
    const version = epoch, actor = token(), scope = JSON.stringify(draft)
    if (pending?.scope !== scope) pending = { scope, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try {
      const result = await api.submit(actor, draft, pending.key)
      if (!current(version, actor)) return
      pending = null; state.application = result.application; state.loaded = true
      state.message = result.revision > 1 ? '已重新提交，等待运营审核' : '已提交，等待运营审核'
    } catch (e) { if (current(version, actor)) { state.message = e.message || '提交未确认，请重试'; state.kind = e.kind || 'network'
      if (['invalid', 'unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) pending = null } }
    finally { if (current(version, actor)) state.writing = false }
  }
  return { reset, resume: () => { active = true }, load, submit }
}

/** 运营：待审列表、详情、批准/固定码驳回、配额维护与资质文件受控访问。 */
export function createOperatorOnboardingFlow({ state, api, token, newKey }) {
  let active = false, epoch = 0, pending = null
  function reset() { active = false; epoch++; pending = null; Object.assign(state, initialOperatorOnboardingState()) }
  const current = (version, actor) => active && epoch === version && token() === actor
  async function load(more = false) {
    if (!active || state.busy || state.writing || !token() || (more && !state.loaded)) return
    const version = ++epoch, actor = token(), page = more ? state.page + 1 : 1
    state.busy = true; state.message = ''; state.kind = ''
    if (!more) { state.rows = []; state.total = 0; state.page = 0; state.loaded = false }
    try {
      const result = await api.pending(actor, page)
      if (!current(version, actor)) return
      state.rows = more ? [...state.rows, ...result.items.filter(s => !state.rows.some(old => old.application_id === s.application_id))] : result.items
      state.total = result.total; state.page = page; state.loaded = true
    } catch (e) { if (current(version, actor)) { state.message = e.message || '加载失败'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.rows = []; state.total = 0; state.loaded = false } } }
    finally { if (current(version, actor)) state.busy = false }
  }
  async function open(summary) {
    if (!active || state.busy || state.writing || !token() || !state.rows.some(s => s.application_id === summary.application_id && s.revision === summary.revision)) return
    const version = epoch, actor = token()
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const result = await api.detail(actor, summary.application_id)
      if (!current(version, actor)) return
      state.detail = result.application; state.detailReviews = result.reviews
    } catch (e) { if (current(version, actor)) { state.message = e.message || '详情加载失败'; state.kind = e.kind || 'network'
      if (['missing', 'unauthorized', 'forbidden'].includes(state.kind)) { state.detail = null; state.detailReviews = [] } } }
    finally { if (current(version, actor)) state.busy = false }
  }
  function close() { state.detail = null; state.detailReviews = [] }
  async function decide(summary, decision, reason) {
    if (!active || state.busy || state.writing || !token() || !state.rows.some(s => s.application_id === summary.application_id && s.revision === summary.revision)) return
    const version = epoch, actor = token(), scope = `${summary.application_id}:${summary.revision}:${decision}:${reason}`
    if (pending?.scope !== scope) pending = { scope, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try {
      await api.moderate(actor, summary.application_id, summary.revision, decision, reason, pending.key)
      if (!current(version, actor)) return
      pending = null; state.detail = null; state.detailReviews = []
      state.rows = state.rows.filter(s => s.application_id !== summary.application_id)
      state.total = Math.max(0, state.total - 1)
      // 审核会改变 offset 列表，下一页前必须刷新，避免删除后跳过一条待审记录。
      state.loaded = false; state.page = 0
      state.message = decision === 'APPROVE' ? '已批准，门店与店长待激活账号已创建' : '已驳回，车主可修改后重新提交'
    } catch (e) { if (current(version, actor)) { state.message = e.message || '审核未确认，请重试'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.rows = []; state.total = 0; state.loaded = false; state.detail = null; state.detailReviews = [] }
      if (['invalid', 'unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) pending = null } }
    finally { if (current(version, actor)) state.writing = false }
  }
  async function loadQuotas() {
    if (!active || state.busy || state.writing || !token()) return
    const version = ++epoch, actor = token()
    state.busy = true; state.message = ''; state.kind = ''
    try {
      const result = await api.quotas(actor)
      if (!current(version, actor)) return
      state.quotas = result.items; state.quotasLoaded = true
    } catch (e) { if (current(version, actor)) { state.message = e.message || '配额加载失败'; state.kind = e.kind || 'network'
      if (['unauthorized', 'forbidden'].includes(state.kind)) { state.quotas = []; state.quotasLoaded = false } } }
    finally { if (current(version, actor)) state.busy = false }
  }
  async function saveQuota(draft) {
    if (!active || state.busy || state.writing || !token()) return
    const version = epoch, actor = token(), scope = `${draft.region_code}/${draft.category}/${draft.max_active}`
    if (pending?.scope !== scope) pending = { scope, key: newKey() }
    state.writing = true; state.message = ''; state.kind = ''
    try {
      const result = await api.setQuota(actor, draft, pending.key)
      if (!current(version, actor)) return
      pending = null
      const item = { region_code: result.region_code, category: result.category, max_active: result.max_active, active_stores: result.active_stores }
      const index = state.quotas.findIndex(q => q.region_code === item.region_code && q.category === item.category)
      state.quotas = index < 0 ? [...state.quotas, item] : state.quotas.map((q, i) => (i === index ? item : q))
      state.quotasLoaded = true
      state.message = '配额已保存'
    } catch (e) { if (current(version, actor)) { state.message = e.message || '配额未确认，请重试'; state.kind = e.kind || 'network'
      if (['invalid', 'unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) pending = null } }
    finally { if (current(version, actor)) state.writing = false }
  }
  return { reset, resume: () => { active = true }, load, open, close, decide, loadQuotas, saveQuota }
}
