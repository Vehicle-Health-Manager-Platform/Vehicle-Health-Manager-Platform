import { MAX_IMAGE_BYTES, ImageError } from './private-images.js'

export function createSignatureStroke(context, width, height) {
  let last = null, ink = false
  const point = p => p && Number.isFinite(p.x) && Number.isFinite(p.y) ? { x: Math.max(0, Math.min(width, p.x)), y: Math.max(0, Math.min(height, p.y)) } : null
  function clear() {
    last = null; ink = false
    context.clearRect(0, 0, width, height); context.setFillStyle('#ffffff'); context.fillRect(0, 0, width, height); context.draw()
  }
  function start(p) { last = point(p) }
  function move(p) {
    const next = point(p); if (!last || !next) return
    if (Math.hypot(next.x - last.x, next.y - last.y) < 2) return
    context.setStrokeStyle('#111827'); context.setLineWidth(2); context.setLineCap('round'); context.setLineJoin('round')
    context.beginPath(); context.moveTo(last.x, last.y); context.lineTo(next.x, next.y); context.stroke(); context.draw(true)
    last = next; ink = true
  }
  return { clear, start, move, end: () => { last = null }, hasInk: () => ink }
}
export async function signatureFile(runtime, path) {
  if (typeof path !== 'string' || !path) throw new ImageError('invalid', '签名图片生成失败，请重新签名')
  const size = await new Promise((resolve, reject) => {
    const done = result => resolve(result?.size || 0)
    const fail = () => reject(new ImageError('invalid', '无法读取签名图片，请重新签名'))
    try {
      const fs = runtime.getFileSystemManager?.()
      if (typeof fs?.getFileInfo === 'function') fs.getFileInfo({ filePath: path, success: done, fail })
      else if (typeof runtime.getFileInfo === 'function') runtime.getFileInfo({ filePath: path, success: done, fail })
      else fail()
    } catch { fail() }
  })
  if (!Number.isFinite(size) || size <= 0 || size > MAX_IMAGE_BYTES) throw new ImageError('invalid', '签名图片大小无效，请重新签名')
  return { path, size }
}
export async function exportSignature({ context, runtime, scope, current, timeout = 15000 }) {
  const check = () => { if (!current()) throw new ImageError('cancelled', '签名已取消，请重新签名') }
  let timer
  const operation = async () => {
    check()
    await new Promise((resolve, reject) => { try { context.draw(true, resolve) } catch { reject(new ImageError('invalid', '签名绘制失败，请重试')) } })
    check()
    const result = await new Promise((resolve, reject) => runtime.canvasToTempFilePath({ canvasId: 'quality-signature', fileType: 'png', quality: 1,
      success: resolve, fail: () => reject(new ImageError('invalid', '签名图片生成失败，请重试')) }, scope))
    check()
    const file = await signatureFile(runtime, result.tempFilePath)
    check(); return file
  }
  try { return await Promise.race([operation(), new Promise((_, reject) => { timer = setTimeout(() => reject(new ImageError('timeout', '签名图片生成超时，请重试')), timeout) })]) }
  finally { clearTimeout(timer) }
}
