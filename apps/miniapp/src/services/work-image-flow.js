import { ImageError, imageRequestKey, DEVTOOLS_NOTICE } from './private-images.js'

export const initialWorkImageState = () => ({ files: [], busy: false, message: '', failureKind: '' })
/** Actor-scoped images: late picker/upload/access callbacks cannot cross a page or account. */
export function createWorkImageFlow({ state, api, token, disabled = () => false, newKey = imageRequestKey }) {
  let generation = 0, active = false
  const current = (version, actor, entry) => active && version === generation && actor === token() && (!entry || state.files.includes(entry))
  function error(e) { state.failureKind = e?.kind || 'server'; state.message = e?.message || '图片操作未成功，请重试' }
  function suspend() { active = false; generation++; state.busy = false }
  function resume() { active = true }
  function reset() { suspend(); Object.assign(state, initialWorkImageState()) }
  function remove(entry) { if (state.busy || disabled()) return; const index = state.files.indexOf(entry); if (index >= 0) state.files.splice(index, 1) }
  function add(kind, candidate) {
    state.files.push({ kind, candidate, localPath: candidate.path, key: newKey(), fileId: 0, message: '已选择，待上传' })
    return state.files[state.files.length - 1]
  }
  async function upload(entry) {
    if (!active || state.busy || disabled() || !state.files.includes(entry) || !entry.candidate || entry.fileId) return
    const version = generation, actor = token(); state.busy = true; entry.message = '正在安全上传…'; state.message = ''; state.failureKind = ''
    try {
      const result = await api.upload(actor, entry.candidate, entry.key)
      if (!current(version, actor, entry)) return
      if (!['image/jpeg', 'image/png'].includes(result.content_type) || entry.kind === 'SIGNATURE' && result.content_type !== 'image/png') throw new ImageError('invalid', entry.kind === 'SIGNATURE' ? '质检签名须为 PNG，请重新生成签名' : '施工证据请使用 JPEG 或 PNG 图片，请重新拍摄')
      entry.fileId = result.file_id; entry.candidate = null; entry.message = '上传完成'
    } catch (e) { if (current(version, actor, entry)) { entry.message = '上传未成功，请使用原图重试'; error(e) } }
    finally { if (current(version, actor)) state.busy = false }
  }
  async function choose(kind) {
    if (!active || state.busy || disabled()) return
    const limit = ['PROTECTION', 'SIGNATURE'].includes(kind) ? 1 : 9
    if (state.files.filter(f => f.kind === kind).length >= limit) { error(new ImageError('invalid', `本类最多 ${limit} 张，请先移除再选择`)); return }
    const version = generation, actor = token(); state.busy = true; state.message = ''; state.failureKind = ''
    let entry
    try {
      const file = await api.choose(actor, 'camera')
      if (!current(version, actor) || !file) return
      entry = add(kind, file); if (file.degraded) state.message = DEVTOOLS_NOTICE
    } catch (e) { if (current(version, actor)) error(e) }
    finally { if (current(version, actor)) state.busy = false }
    if (entry && current(version, actor, entry)) { const notice = state.message; await upload(entry); if (current(version, actor)) state.message = [notice, state.message].filter(Boolean).join('；') }
  }
  async function signature(file) {
    if (!active || state.busy || disabled()) return
    state.files = state.files.filter(f => f.kind !== 'SIGNATURE')
    const entry = add('SIGNATURE', file); await upload(entry)
  }
  async function preview(fileId, access = actor => api.access(actor, fileId)) {
    if (!active || state.busy) return
    const version = generation, actor = token(); state.busy = true; state.message = ''; state.failureKind = ''
    try { const signed = await access(actor); if (current(version, actor)) await api.preview(signed.url) }
    catch (e) { if (current(version, actor)) error(e) }
    finally { if (current(version, actor)) state.busy = false }
  }
  return { resume, suspend, reset, remove, choose, upload, signature, preview }
}
