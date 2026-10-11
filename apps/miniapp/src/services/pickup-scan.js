import qrcode from 'qrcode-generator'
import { ServiceError } from './service-catalog.js'

const validId = id => Number.isSafeInteger(id) && id > 0
export function pickupQrPayload(orderId) {
  if (!validId(orderId)) throw new ServiceError('invalid', '订单信息无效')
  return `VHMP:PICKUP:1:${orderId}`
}
export function parsePickupQr(value) {
  if (typeof value !== 'string' || !/^VHMP:PICKUP:1:[1-9][0-9]{0,15}$/.test(value)) throw new ServiceError('invalid', '请扫描车主订单详情中的接车二维码')
  const id = Number(value.slice('VHMP:PICKUP:1:'.length))
  if (!validId(id) || value !== pickupQrPayload(id)) throw new ServiceError('invalid', '接车二维码中的订单信息无效')
  return id
}
export function pickupQrMatrix(orderId) {
  const qr = qrcode(0, 'M')
  qr.addData(pickupQrPayload(orderId), 'Byte'); qr.make()
  return Array.from({ length: qr.getModuleCount() }, (_, row) => Array.from({ length: qr.getModuleCount() }, (_, col) => qr.isDark(row, col)))
}

export function createPickupScanFlow({ state, token, runtime, context, navigate }) {
  let generation = 0
  function reset() { generation++; state.busy = false; state.message = '' }
  async function scan() {
    if (state.busy) return
    const actor = token(), version = ++generation
    if (!actor) { state.message = '请先登录门店账号'; return }
    const current = () => version === generation && actor === token()
    state.busy = true; state.message = ''
    try {
      const result = await new Promise((resolve, reject) => runtime().scanCode({ onlyFromCamera: true, scanType: ['qrCode'], success: resolve, fail: reject }))
      if (!current()) return
      const id = parsePickupQr(result?.result)
      const order = await context(actor, id)
      if (!current()) return
      if (order?.order_id !== id || order.order?.status !== 'PAID') throw new ServiceError('protocol', '订单状态已变化，请刷新本店订单')
      navigate(`/pages/merchant/pickup?id=${id}`)
    } catch (error) {
      if (current() && !/\bcancel\b/i.test(error?.errMsg || '')) state.message = error instanceof ServiceError ? error.message : '扫码未成功，请检查相机权限后重试'
    } finally { if (version === generation) state.busy = false }
  }
  return { scan, reset }
}
