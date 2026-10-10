import { profileBody, profileFailure } from './merchant-profile.js'

// 门店资料的读写状态机。菜单里的 can_edit 是服务端给出的「当前身份是否店长」，
// 页面据此决定是否可编辑；前端不自行判断角色。

export const initialProfileState = () => ({ merchantId: 0, name: '', address: '', contactPhone: '', lng: '', lat: '',
  merchantType: 0, regionCode: '', status: 0, canEdit: false, loaded: false,
  busy: false, message: '', failureKind: '',
  saving: false, saved: null, saveMessage: '', saveFailureKind: '' })

export function createProfileFlow({ state, api, token, newKey }) {
  let generation = 0, pending = null
  function suspend() { generation++; state.busy = false; state.saving = false }
  function reset() { suspend(); pending = null; Object.assign(state, initialProfileState()) }

  async function load() {
    if (state.busy) return
    const current = ++generation, actor = token()
    state.busy = true; state.message = ''; state.failureKind = ''
    try {
      const profile = await api.read(actor)
      if (current !== generation || actor !== token()) return
      Object.assign(state, {
        merchantId: profile.merchant_id, name: profile.name, address: profile.address,
        // 坐标是字符串列（DECIMAL），读回可能是 null；表单里用空串表示「未填」。
        contactPhone: profile.contact_phone ?? '', lng: profile.lng ?? '', lat: profile.lat ?? '',
        merchantType: profile.merchant_type, regionCode: profile.region_code, status: profile.status,
        canEdit: profile.can_edit === true, loaded: true, saved: null, saveMessage: '', saveFailureKind: '',
      })
    } catch (error) {
      if (current !== generation || actor !== token()) return
      const safe = profileFailure(error); state.message = safe.message; state.failureKind = safe.kind
    } finally { if (current === generation) state.busy = false }
  }

  async function save() {
    if (state.saving || state.saved) return
    let body
    try { body = profileBody(state) } catch (error) {
      const safe = profileFailure(error); state.saveMessage = safe.message; state.saveFailureKind = safe.kind; return
    }
    // 重试同一份内容复用原幂等键；改了内容才换键。
    const canonical = JSON.stringify(body)
    if (!pending || pending.canonical !== canonical) pending = { canonical, body, key: newKey() }
    const current = ++generation, actor = token()
    state.saving = true; state.saveMessage = ''; state.saveFailureKind = ''
    try {
      const result = await api.update(actor, pending.body, pending.key)
      if (current !== generation || actor !== token()) return
      state.saved = result
      Object.assign(state, { name: result.name, address: result.address,
        contactPhone: result.contact_phone, lng: result.lng ?? '', lat: result.lat ?? '' })
      state.saveMessage = '门店资料已保存'
    } catch (error) {
      if (current !== generation || actor !== token()) return
      const safe = profileFailure(error); state.saveMessage = safe.message; state.saveFailureKind = safe.kind
    } finally { if (current === generation) state.saving = false }
  }

  // 保存成功后表单只读，避免把「已保存」的响应当成新编辑；要继续改必须显式回到编辑态。
  function edit() { state.saved = null; state.saveMessage = ''; state.saveFailureKind = '' }

  return { load, save, edit, suspend, reset }
}
