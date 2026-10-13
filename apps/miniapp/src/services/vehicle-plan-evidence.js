import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError } from './service-catalog.js'
import { exact, safeId, validPublic } from './experience-publication.js'
import { healthMetrics, validObservation } from './health-observations.js'
export const applicabilityLabels = { UNKNOWN: '适用性未知，需人工核对', APPLICABLE: '运营来源规则：适用', NOT_APPLICABLE: '运营来源规则：不适用' }
const version = n => Number.isSafeInteger(n) && n >= 0
const source = s => typeof s === 'string' && s.trim() === s && [...s].length > 0 && [...s].length <= 256 && !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(s)
const projectsValid = ps => Array.isArray(ps) && ps.length <= 8 && ps.every(safeId) && new Set(ps).size === ps.length
export function validRule(r, project, model) {
  return exact(r, ['project_id', 'model_id', 'version', 'rule']) && r.project_id === project && r.model_id === model && version(r.version)
    && (r.rule === null || r.version > 0 && exact(r.rule, ['verdict', 'source_reference']) && ['APPLICABLE', 'NOT_APPLICABLE'].includes(r.rule.verdict) && source(r.rule.source_reference))
}
export function validVehicleEvidence(r, vehicle, projects) {
  if (!exact(r, ['vehicle', 'observations', 'experiences', 'applicability']) || !exact(r.vehicle, ['vehicle_id', 'model_id', 'model_name', 'model_source', 'power_type']) || r.vehicle.vehicle_id !== vehicle || r.vehicle.model_source !== 'OWNER_REGISTERED'
    || !(r.vehicle.model_id === null ? r.vehicle.model_name === null : safeId(r.vehicle.model_id) && typeof r.vehicle.model_name === 'string' && r.vehicle.model_name.length > 0)
    || !(r.vehicle.power_type === null || typeof r.vehicle.power_type === 'string' && r.vehicle.power_type.length <= 16)) return false
  const o = r.observations, e = r.experiences
  if (!exact(o, ['vehicle_id', 'today', 'list']) || o.vehicle_id !== vehicle || typeof o.today !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(o.today) || !Number.isFinite(Date.parse(o.today)) || new Date(o.today).toISOString().slice(0,10) !== o.today || !Array.isArray(o.list) || o.list.length !== 6 || !o.list.every((x, i) => x.metric === healthMetrics[i] && validObservation(x, o.today))) return false
  if (!exact(e, ['items', 'next_cursor']) || !Array.isArray(e.items) || e.items.length > 20 || !e.items.every(x => validPublic(x) && x.model_id === r.vehicle.model_id) || new Set(e.items.map(x => x.experience_id)).size !== e.items.length || !(e.next_cursor === null || safeId(e.next_cursor) && e.items.length === 20) || r.vehicle.model_id === null && (e.items.length > 0 || e.next_cursor !== null)) return false
  return Array.isArray(r.applicability) && r.applicability.length === projects.length && r.applicability.every((x, i) => exact(x, ['project_id', 'verdict', 'rule_version', 'source_reference']) && x.project_id === projects[i] && Object.hasOwn(applicabilityLabels, x.verdict)
    && (x.verdict === 'UNKNOWN' ? x.rule_version === 0 && x.source_reference === null : r.vehicle.model_id !== null && version(x.rule_version) && x.rule_version > 0 && source(x.source_reference)))
}
export function createVehiclePlanEvidenceApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function call(token, path, method, body, key, validate) {
    if (!token) throw new ServiceError('unauthorized', '请先登录对应账号')
    if (!endpoint) throw new ServiceError('unconfigured', '车辆方案证据服务尚未配置')
    return new Promise((resolve, reject) => {
      const fail = (kind, message) => reject(new ServiceError(kind, message))
      try { runtime().request({ url: endpoint + path, method, data: body, timeout: 15000, header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) }, success: ({ statusCode, data }) => {
        if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return fail(({ 400: 'invalid', 401: 'unauthorized', 403: 'forbidden', 404: 'missing', 409: 'conflict', 503: 'unavailable' })[statusCode] || 'server', statusCode === 409 ? '规则或目录已变化，请重新读取并核对' : '证据或规则请求未成功，请重新读取')
        try { if (data?.code !== 0 || !validate(data.data)) return fail('protocol', '证据或规则响应异常，请重新读取') } catch { return fail('protocol', '证据或规则响应异常，请重新读取') }
        resolve(data.data)
      }, fail: () => fail('network', '无法连接服务，请检查网络后重试') }) } catch { fail('network', '无法连接服务，请重试') }
    })
  }
  return {
    evidence(token, vehicle, projects) { if (!safeId(vehicle) || !projectsValid(projects)) throw new ServiceError('invalid', '请选择本车及有效项目'); return call(token, '/api/ai/service-plan/vehicle-evidence', 'POST', { vehicle_id: vehicle, project_ids: [...projects] }, null, r => validVehicleEvidence(r, vehicle, projects)) },
    rule(token, project, model) { if (!safeId(project) || !safeId(model)) throw new ServiceError('invalid', '项目和车型编号无效'); return call(token, `/api/admin/project-applicability/${project}/${model}`, 'GET', undefined, null, r => validRule(r, project, model)) },
    saveRule(token, project, model, body, key) { if (!safeId(project) || !safeId(model) || !exact(body, ['verdict', 'source_reference', 'expected_version']) || !version(body.expected_version) || body.expected_version === Number.MAX_SAFE_INTEGER || !['APPLICABLE', 'NOT_APPLICABLE', 'WITHDRAW'].includes(body.verdict) || !(body.verdict === 'WITHDRAW' ? body.source_reference === null : source(body.source_reference)) || typeof key !== 'string' || !/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key)) throw new ServiceError('invalid', '请核对规则、来源及版本'); return call(token, `/api/admin/project-applicability/${project}/${model}`, 'PUT', body, key, r => validRule(r, project, model) && r.version === body.expected_version + 1 && (body.verdict === 'WITHDRAW' ? r.rule === null : r.rule?.verdict === body.verdict && r.rule?.source_reference === body.source_reference)) },
  }
}
export const vehiclePlanEvidenceApi = createVehiclePlanEvidenceApi({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const initialVehicleEvidenceState = () => ({ busy: false, value: null, message: '', kind: '' })
export function createVehicleEvidenceFlow({ state, token, selection, api = vehiclePlanEvidenceApi }) {
  let generation = 0
  function reset() { generation++; Object.assign(state, initialVehicleEvidenceState()) }
  async function load() {
    if (state.busy) return; const g = ++generation, actor = token(), selected = selection(), stamp = JSON.stringify(selected), valid = () => g === generation && actor === token() && stamp === JSON.stringify(selection())
    Object.assign(state, initialVehicleEvidenceState(), { busy: true })
    try { const r = await api.evidence(actor, selected.vehicle, selected.projects); if (valid()) state.value = r } catch (e) { if (valid()) { state.message = e.message; state.kind = e.kind || 'network' } } finally { if (valid()) state.busy = false }
  }
  function canChoose(project) { return !selection().vehicle || !state.busy && !!state.value && state.value.applicability.some(x => x.project_id === project && x.verdict !== 'NOT_APPLICABLE') }
  return { reset, load, canChoose }
}
export const initialApplicabilityState = () => ({ busy: false, value: null, message: '', kind: '' })
export function createApplicabilityFlow({ state, token, selection, newKey, confirm, api = vehiclePlanEvidenceApi }) {
  let generation = 0, pending = null
  function reset() { generation++; pending = null; Object.assign(state, initialApplicabilityState()) }
  function fail(e) { state.message = e.message; state.kind = e.kind || 'network'; if (['unauthorized', 'forbidden', 'missing', 'conflict'].includes(state.kind)) { state.value = null; pending = null } }
  async function load() {
    if (state.busy) return; const g = ++generation, actor = token(), s = selection(), stamp = JSON.stringify(s), valid = () => g === generation && actor === token() && stamp === JSON.stringify(selection())
    pending = null; Object.assign(state, initialApplicabilityState(), { busy: true })
    try { const r = await api.rule(actor, s.project, s.model); if (valid()) state.value = r } catch (e) { if (valid()) fail(e) } finally { if (valid()) state.busy = false }
  }
  async function save(verdict, reference) {
    if (state.busy || !state.value) return; const g = ++generation, actor = token(), s = selection(), stamp = JSON.stringify(s), valid = () => g === generation && actor === token() && stamp === JSON.stringify(selection())
    const body = { verdict, source_reference: verdict === 'WITHDRAW' ? null : reference, expected_version: state.value.version }, signature = JSON.stringify([actor, s, body])
    if (!pending || pending.signature !== signature) pending = { signature, key: newKey() }
    state.busy = true; state.message = ''; state.kind = ''
    try { if (!await confirm(s, body) || !valid()) return; await api.saveRule(actor, s.project, s.model, body, pending.key); if (!valid()) return; pending = null; state.value = null; const current = await api.rule(actor, s.project, s.model); if (valid()) { state.value = current; state.message = '已保存，并重新读取当前规则' } } catch (e) { if (valid()) fail(e) } finally { if (valid()) state.busy = false }
  }
  return { reset, load, save }
}
