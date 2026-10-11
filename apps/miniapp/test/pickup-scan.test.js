import test from 'node:test'
import assert from 'node:assert/strict'
import jsQR from 'jsqr'
import { pickupQrPayload, parsePickupQr, pickupQrMatrix, createPickupScanFlow } from '../src/services/pickup-scan.js'
import { ServiceError } from '../src/services/service-catalog.js'

test('接车二维码由独立解码器还原，内容只有固定协议和订单ID', () => {
  for (const id of [1, 123, Number.MAX_SAFE_INTEGER]) {
    const matrix = pickupQrMatrix(id), scale = 4, size = (matrix.length + 8) * scale
    const rgba = new Uint8ClampedArray(size * size * 4)
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
      const row = Math.floor(y / scale) - 4, col = Math.floor(x / scale) - 4
      const dark = matrix[row]?.[col], offset = (y * size + x) * 4
      rgba[offset] = rgba[offset + 1] = rgba[offset + 2] = dark ? 0 : 255; rgba[offset + 3] = 255
    }
    assert.equal(jsQR(rgba, size, size)?.data, pickupQrPayload(id))
    assert.equal(parsePickupQr(pickupQrPayload(id)), id)
  }
})
test('扫码拒绝URL、其他协议、前导零和不安全整数', () => {
  for (const value of [null, '', 'https://example.test/1', 'VHMP:PICKUP:2:1', 'VHMP:PICKUP:1:01', 'VHMP:PICKUP:1:0', 'VHMP:PICKUP:1:-1', 'VHMP:PICKUP:1:1.1', 'VHMP:PICKUP:1:9007199254740992', 'VHMP:PICKUP:1:1\n', ' VHMP:PICKUP:1:1']) assert.throws(() => parsePickupQr(value))
  for (const id of [0, -1, '1', NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => pickupQrPayload(id))
})
const fixture = () => {
  const state = { busy: false, message: '' }, calls = [], destinations = []
  let actor = 'shop', camera, resolveContext
  const flow = createPickupScanFlow({ state, token: () => actor, runtime: () => ({ scanCode: options => { camera = options } }),
    context: (token, id) => { calls.push({ token, id }); return new Promise(resolve => { resolveContext = resolve }) }, navigate: url => destinations.push(url) })
  return { state, calls, destinations, flow, camera: () => camera, switchActor: () => { actor = 'other' }, finishContext: data => resolveContext(data) }
}
const flush = () => new Promise(resolve => setImmediate(resolve))
test('扫码先查本店可接车上下文，成功只导航固定接车页', async () => {
  const f = fixture(), work = f.flow.scan()
  assert.equal(f.camera().onlyFromCamera, true); assert.deepEqual(f.camera().scanType, ['qrCode'])
  await f.flow.scan(); f.camera().success({ result: pickupQrPayload(23) }); await flush()
  assert.deepEqual(f.calls, [{ token: 'shop', id: 23 }]); assert.deepEqual(f.destinations, [])
  f.finishContext({ order_id: 23, order: { status: 'PAID' } }); await work
  assert.deepEqual(f.destinations, ['/pages/merchant/pickup?id=23']); assert.equal(f.state.busy, false)
})
test('扫码取消安静结束，未知格式不查询订单', async () => {
  const f = fixture(), work = f.flow.scan(); f.camera().fail({ errMsg: 'scanCode:fail cancel' }); await work
  assert.equal(f.state.message, ''); assert.equal(f.state.busy, false)
  const invalid = f.flow.scan(); f.camera().success({ result: 'https://example.test' }); await invalid
  assert.ok(f.state.message); assert.deepEqual(f.calls, []); assert.deepEqual(f.destinations, [])
})
test('隐藏或换身份丢弃相机及上下文迟到结果', async () => {
  for (const reset of [true, false]) {
    const f = fixture(), work = f.flow.scan(); if (reset) f.flow.reset(); else f.switchActor()
    f.camera().success({ result: pickupQrPayload(1) }); await work; assert.deepEqual(f.calls, [])
    const g = fixture(), pending = g.flow.scan(); g.camera().success({ result: pickupQrPayload(1) }); await flush()
    if (reset) g.flow.reset(); else g.switchActor()
    g.finishContext({ order_id: 1, order: { status: 'PAID' } }); await pending; assert.deepEqual(g.destinations, [])
  }
})
test('越权、上下文串单与状态变化均不打开接车页', async () => {
  for (const data of [{ order_id: 2, order: { status: 'PAID' } }, { order_id: 1, order: { status: 'RECEIVED' } }]) {
    const f = fixture(), work = f.flow.scan(); f.camera().success({ result: pickupQrPayload(1) }); await flush(); f.finishContext(data); await work
    assert.ok(f.state.message); assert.deepEqual(f.destinations, [])
  }
  const state = { busy: false, message: '' }; let navigated = false
  const flow = createPickupScanFlow({ state, token: () => 'shop', runtime: () => ({ scanCode: o => o.success({ result: pickupQrPayload(1) }) }), context: async () => { throw new ServiceError('missing', '接车资源不存在或不可访问') }, navigate: () => { navigated = true } })
  await flow.scan(); assert.equal(state.message, '接车资源不存在或不可访问'); assert.equal(navigated, false)
})
