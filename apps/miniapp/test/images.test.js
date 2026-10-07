import test from 'node:test'
import assert from 'node:assert/strict'
import { createImageApi, ImageError, MAX_IMAGE_BYTES, DEVTOOLS_NOTICE } from '../src/services/private-images.js'
import { useImageFlow } from '../src/services/image-flow.js'
import { ownerSession, clearOwnerSession } from '../src/services/owner-session.js'
const result = { file_id: 9, content_type: 'image/png', size_bytes: 100 }
const image = { path: 'offline-image.png', size: 100 }
const key = '11111111-1111-4111-8111-111111111111'
const signed = () => ({ url: 'https://offline.invalid/private?signature=test', expires_at: new Date(Date.now() + 120000).toISOString() })
// media：底层是否支持 chooseMedia（官方推荐接口）；environment：'device' | 'devtools'；
// fileSize：原生不回报 size 时由文件系统补出的字节数。
function harness(baseUrl = 'https://offline.invalid', { media = false, environment = 'device', fileSize = 0 } = {}) {
  const calls = [], previews = [], selections = [], mediaCalls = [], settings = []
  let response = { statusCode: 200, data: JSON.stringify({ code: 0, data: result }) }
  let selection = { tempFiles: [image] }
  const runtime = {
    chooseImage(options) { selections.push(options.sourceType); selection.errMsg ? options.fail(selection) : options.success(selection) },
    chooseMedia(options) { mediaCalls.push(options); selection.errMsg ? options.fail(selection) : options.success(selection) },
    uploadFile(options) { calls.push(options); response.errMsg ? options.fail(response) : options.success(response); return { abort() {} } },
    request(options) { calls.push(options); response.errMsg ? options.fail(response) : options.success(response); return { abort() {} } },
    previewImage(options) { previews.push(options); options.success() },
    openSetting(options) { settings.push(options); options.success({ authSetting: {} }) },
  }
  if (fileSize > 0) runtime.getFileSystemManager = () => ({ getFileInfo: ({ success }) => success({ size: fileSize }) })
  if (media) runtime.supports = name => typeof runtime[name] === 'function'
  return { api: createImageApi({ baseUrl, runtime: () => runtime, environment: () => environment }),
    calls, previews, selections, mediaCalls, settings,
    reply(value) { response = value }, select(value) { selection = value } }
}
test('missing service or token prevents native selection and upload', async () => {
  const h = harness('')
  assert.throws(() => h.api.choose('token'), { kind: 'unconfigured' })
  assert.throws(() => h.api.upload('', image, key), { kind: 'unauthorized' })
  assert.equal(h.calls.length, 0)
})
test('selection cancellation is harmless and sizes are bounded', async () => {
  const h = harness(); h.select({ errMsg: 'chooseImage:fail cancel' }); assert.equal(await h.api.choose('token'), null)
  h.select({ tempFiles: [{ ...image, size: MAX_IMAGE_BYTES + 1 }] }); await assert.rejects(h.api.choose('token'), { kind: 'too-large' })
  h.select({ tempFiles: [{ ...image, size: MAX_IMAGE_BYTES }] }); assert.equal((await h.api.choose('token')).size, MAX_IMAGE_BYTES)
  h.select({ tempFilePaths: ['unknown.png'] }); await assert.rejects(h.api.choose('token'), { kind: 'invalid' })
})
test('camera selection only requests camera and preserves cancellation', async () => {
  const h = harness()
  await h.api.choose('token', 'camera')
  assert.deepEqual(h.selections[0], ['camera'])
  await h.api.choose('token')
  assert.deepEqual(h.selections[1], ['album', 'camera'])
  h.select({ errMsg: 'chooseImage:fail cancel' })
  assert.equal(await h.api.choose('token', 'camera'), null)
  h.select({ errMsg: 'chooseImage:fail auth deny' })
  await assert.rejects(h.api.choose('token', 'camera'), { kind: 'permission' })
})
test('upload uses one file field and fixed key and strictly parses string envelope', async () => {
  const h = harness(); assert.deepEqual(await h.api.upload('token', image, key), result)
  assert.equal(h.calls[0].name, 'file'); assert.equal(h.calls[0].header['Idempotency-Key'], key)
  assert.equal(h.calls[0].header.Authorization, 'Bearer token'); assert.equal(h.calls[0].formData, undefined)
  for (const data of ['bad-json', JSON.stringify({ code: 0, data: { ...result, file_id: '9' } }), JSON.stringify({ code: 1, data: result })]) {
    h.reply({ statusCode: 200, data }); await assert.rejects(h.api.upload('token', image, key), { kind: 'protocol' })
  }
  h.reply({ data: { code: 0, data: result } }); await assert.rejects(h.api.upload('token', image, key), { kind: 'protocol' })
})
test('HTTP and transport failures expose fixed safe guidance', async () => {
  const h = harness()
  for (const [status, kind] of [[401, 'unauthorized'], [403, 'forbidden'], [409, 'conflict'], [413, 'too-large'], [422, 'rejected'], [429, 'rate-limited'], [503, 'unavailable']]) {
    h.reply({ statusCode: status, data: { message: 'private response secret' } })
    await assert.rejects(h.api.upload('token', image, key), error => error.kind === kind && !error.message.includes('secret'))
  }
  h.reply({ errMsg: 'uploadFile:fail timeout private' }); await assert.rejects(h.api.upload('token', image, key), { kind: 'timeout' })
  h.reply({ errMsg: 'private network error' }); await assert.rejects(h.api.upload('token', image, key), { kind: 'network' })
})
test('access rejects insecure or expired URLs and never sends upload key', async () => {
  const h = harness(); h.reply({ statusCode: 200, data: { code: 0, data: signed() } }); await h.api.access('token', 9)
  assert.equal(h.calls[0].method, 'GET'); assert.equal(h.calls[0].header['Idempotency-Key'], undefined)
  for (const value of [{ ...signed(), url: 'http://offline.invalid/file' }, { ...signed(), url: 'data:secret' }, { ...signed(), expires_at: new Date(0).toISOString() }]) {
    h.reply({ statusCode: 200, data: { code: 0, data: value } }); await assert.rejects(h.api.access('token', 9), { kind: 'protocol' })
  }
})
function flowHarness() {
  ownerSession.accessToken = 'owner-one'
  const calls = [], previews = []; let fail = null; let selection = image; let sequence = 0
  const api = { async choose() { return selection }, async upload(token, file, key) { calls.push({ token, file, key }); if (fail) throw fail; return result },
    async access(token, id) { calls.push({ token, id }); return signed() }, async preview(url) { previews.push(url) } }
  const flow = useImageFlow(api, () => `key-${++sequence}`)
  return { flow, api, calls, previews, failure(value) { fail = value }, selection(value) { selection = value }, close() { flow.dispose(); clearOwnerSession() } }
}
test('retry preserves selected bytes and key; choosing a replacement creates a new key', async () => {
  const h = flowHarness()
  try {
    await h.flow.choose(); h.failure(new ImageError('unavailable', '暂不可用')); await h.flow.upload()
    assert.equal(h.flow.retryable.value, true); h.failure(null); await h.flow.retry()
    assert.equal(h.calls[0].key, h.calls[1].key); assert.equal(h.calls[0].file.path, h.calls[1].file.path)
    assert.match(h.flow.message.value, /档案尚未创建/)
    await h.flow.choose(); await h.flow.upload(); assert.notEqual(h.calls[1].key, h.calls[2].key)
  } finally { h.close() }
})
test('cancelling selection retains previous image and upload result', async () => {
  const h = flowHarness(); try { await h.flow.choose(); await h.flow.upload(); h.selection(null); await h.flow.choose()
    assert.equal(h.flow.uploaded.value.file_id, 9); assert.equal(h.flow.file.value.path, image.path) } finally { h.close() }
})
test('duplicate submit is suppressed and each preview obtains a new signature', async () => {
  const h = flowHarness(); try {
    await h.flow.choose(); const first = h.flow.upload(); const second = h.flow.upload(); await Promise.all([first, second])
    assert.equal(h.calls.filter(call => call.key).length, 1)
    await h.flow.preview(); await h.flow.preview(); assert.equal(h.calls.filter(call => call.id).length, 2); assert.equal(h.previews.length, 2)
  } finally { h.close() }
})
test('session switch clears sensitive state and discards late upload response', async () => {
  const h = flowHarness(); let finish
  try {
    h.api.upload = () => new Promise(resolve => { finish = resolve }); await h.flow.choose(); const work = h.flow.upload()
    ownerSession.accessToken = 'owner-two'; assert.equal(h.flow.file.value, null); finish(result); await work
    assert.equal(h.flow.uploaded.value, null); assert.equal(h.flow.message.value, '')
  } finally { h.close() }
})
test('hidden page preserves retry key and never previews a late signature', async () => {
  const h = flowHarness(); let finish
  try {
    await h.flow.choose(); await h.flow.upload(); h.api.access = () => new Promise(resolve => { finish = resolve })
    const work = h.flow.preview(); h.flow.suspend(); finish(signed()); await work; assert.equal(h.previews.length, 0)
    assert.equal(h.flow.uploaded.value.file_id, 9)
  } finally { h.close() }
})
test('request cancellation aborts task and ignores late callbacks', async () => {
  let options, aborts = 0, cancel
  const signal = { subscribe(fn) { cancel = fn }, unsubscribe() {} }
  const api = createImageApi({ baseUrl: 'https://offline.invalid', runtime: () => ({ uploadFile(value) { options = value; return { abort() { aborts++ } } } }) })
  const work = api.upload('token', image, key, signal); cancel(); await assert.rejects(work, { kind: 'cancelled' }); assert.equal(aborts, 1)
  options.success({ statusCode: 200, data: { code: 0, data: result } })
})
test('原生支持 chooseMedia 时优先使用，并解析 tempFilePath', async () => {
  const h = harness('https://offline.invalid', { media: true })
  h.select({ tempFiles: [{ tempFilePath: 'shot.jpg', size: 2048 }] })
  const file = await h.api.choose('token', 'camera')
  assert.equal(h.mediaCalls.length, 1)
  assert.deepEqual(h.mediaCalls[0].mediaType, ['image'])
  assert.deepEqual(h.mediaCalls[0].sourceType, ['camera'])
  assert.equal(h.mediaCalls[0].camera, 'back')
  assert.equal(h.selections.length, 0)
  assert.deepEqual(file, { path: 'shot.jpg', size: 2048, degraded: false })
})
test('底层不支持 chooseMedia 时回退 chooseImage', async () => {
  const h = harness()
  await h.api.choose('token', 'camera')
  assert.deepEqual(h.selections[0], ['camera'])
  assert.equal(h.mediaCalls.length, 0)
})
test('开发者工具没有摄像头：退回相册并如实标记，不假装拍到了', async () => {
  const h = harness('https://offline.invalid', { environment: 'devtools' })
  const file = await h.api.choose('token', 'camera')
  assert.deepEqual(h.selections[0], ['album'])
  assert.equal(file.degraded, true)
  assert.match(DEVTOOLS_NOTICE, /开发者工具/)
})
test('真机拍照只请求相机，混选才允许相册', async () => {
  const h = harness()
  await h.api.choose('token', 'camera')
  await h.api.choose('token')
  assert.deepEqual(h.selections, [['camera'], ['album', 'camera']])
})
test('权限被拒与其它失败分开报，并可拉起设置页', async () => {
  const h = harness()
  h.select({ errMsg: 'chooseImage:fail auth deny' })
  await assert.rejects(h.api.choose('token', 'camera'), { kind: 'permission' })
  h.select({ errMsg: 'chooseMedia:fail:fail system permission denied' })
  await assert.rejects(h.api.choose('token', 'camera'), { kind: 'permission' })
  h.select({ errMsg: 'chooseImage:fail internal error' })
  await assert.rejects(h.api.choose('token', 'camera'), { kind: 'selection' })
  assert.equal(await h.api.authorize(), true)
  assert.equal(h.settings.length, 1)
})
test('原生不回报 size 时由文件系统补齐，而不是判成读取失败', async () => {
  const h = harness('https://offline.invalid', { fileSize: 4096 })
  h.select({ tempFiles: [{ path: 'no-size.jpg' }] })
  assert.deepEqual(await h.api.choose('token'), { path: 'no-size.jpg', size: 4096, degraded: false })
})
