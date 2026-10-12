import { validShareToken } from './archive-shares.js'

export function initialArchiveShareReaderState() { return { summary: null, expiresAt: '', busy: false, message: '', kind: '' } }
export function createArchiveShareReader({ state, api, token, now = Date.now, schedule = setTimeout, cancel = clearTimeout }) {
  let capability = '', generation = 0, visible = false, disposed = false, timer
  function clear() { generation++; cancel(timer); Object.assign(state, initialArchiveShareReaderState()) }
  async function load() {
    clear()
    if (!visible || disposed) return
    if (!validShareToken(capability)) { state.kind = 'gone'; state.message = '分享链接无效，请联系车主重新分享'; return }
    const owner = token(), current = generation
    if (!owner) { state.kind = 'unauthorized'; state.message = '请登录车主账号后查看摘要，登录后返回此页。'; return }
    state.busy = true
    try {
      const data = await api.view(owner, capability)
      if (current !== generation || owner !== token() || !visible || disposed) return
      const remaining = Date.parse(data.expires_at) - now()
      if (remaining <= 0) { state.kind = 'gone'; state.message = '分享已过期，请联系车主重新分享'; return }
      state.summary = data.summary; state.expiresAt = data.expires_at
      timer = schedule(() => { clear(); state.kind = 'gone'; state.message = '分享已过期，请联系车主重新分享' }, remaining)
    } catch (error) {
      if (current !== generation || owner !== token() || !visible || disposed) return
      state.kind = error?.kind || 'server'; state.message = error?.message || '摘要加载失败，请重试'
    } finally { if (current === generation) state.busy = false }
  }
  return {
    setCapability(value) { clear(); capability = validShareToken(value) ? value : '' },
    load,
    resume() { visible = true; return load() },
    suspend() { visible = false; clear() },
    invalidate() { clear(); if (visible) return load() },
    dispose() { disposed = true; visible = false; capability = ''; clear() },
  }
}
