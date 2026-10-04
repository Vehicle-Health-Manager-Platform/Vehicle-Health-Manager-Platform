import { ref, computed, watch, onScopeDispose, getCurrentScope } from 'vue'
import { ownerSession } from './owner-session.js'
import { imageApi, ImageError, imageRequestKey } from './private-images.js'

function cancellation() {
  const listeners = new Set()
  return { cancelled: false, subscribe(fn) { listeners.add(fn) }, unsubscribe(fn) { listeners.delete(fn) },
    cancel() { this.cancelled = true; for (const fn of [...listeners]) fn(); listeners.clear() } }
}
export function useImageFlow(api = imageApi, keyFactory = imageRequestKey) {
  const file = ref(null), uploaded = ref(null), operation = ref(''), message = ref(''), failureKind = ref('')
  const key = ref('')
  let generation = 0, pending = null, disposed = false
  const busy = computed(() => Boolean(operation.value))
  const retryable = computed(() => Boolean(file.value) && !busy.value &&
    ['network', 'timeout', 'unavailable', 'rate-limited', 'conflict', 'server', 'protocol', 'preview'].includes(failureKind.value))
  function clear() {
    generation++; pending?.cancel(); pending = null
    file.value = uploaded.value = null; key.value = ''; operation.value = ''; message.value = ''; failureKind.value = ''
  }
  function suspend() {
    if (!busy.value) return
    generation++; pending?.cancel(); pending = null; operation.value = ''
    failureKind.value = file.value ? 'network' : ''; message.value = '操作已暂停，请返回后重试'
  }
  const stop = watch(() => ownerSession.accessToken, clear, { flush: 'sync' })
  function dispose() { disposed = true; clear(); stop() }
  if (getCurrentScope()) onScopeDispose(dispose)
  async function run(name, action) {
    if (busy.value || disposed) return
    const identity = ownerSession.accessToken, version = generation
    operation.value = name; failureKind.value = ''; message.value = ''
    const signal = cancellation(); pending = signal
    const current = () => !disposed && version === generation && identity === ownerSession.accessToken
    try { await action(signal, current); }
    catch (error) {
      if (!current()) return
      const safe = error instanceof ImageError ? error : new ImageError('server', '图片操作未成功，请稍后重试')
      failureKind.value = safe.kind; message.value = safe.message
    } finally { if (current()) { operation.value = ''; pending = null } }
  }
  async function choose() {
    await run('choose', async (_, current) => {
      const selected = await api.choose(ownerSession.accessToken)
      if (!current()) return
      if (!selected) { message.value = '已取消选图，原图片保持不变'; return }
      file.value = selected; uploaded.value = null; key.value = keyFactory(); message.value = '图片已选择，可以上传'
    })
  }
  async function upload() {
    if (!file.value || uploaded.value) return
    await run('upload', async (signal, current) => {
      const result = await api.upload(ownerSession.accessToken, file.value, key.value, signal)
      if (!current()) return
      uploaded.value = result; message.value = '图片已上传，可预览；档案尚未创建'
    })
  }
  async function preview() {
    if (!uploaded.value) return
    await run('preview', async (signal, current) => {
      // Fetch a fresh signature for every preview; never store URLs in persistent state.
      const access = await api.access(ownerSession.accessToken, uploaded.value.file_id, signal)
      if (!current()) return
      await api.preview(access.url)
      if (current()) message.value = '已打开图片预览；档案尚未创建'
    })
  }
  async function retry() { if (retryable.value) await (uploaded.value ? preview() : upload()) }
  return { file, uploaded, busy, operation, message, failureKind, retryable, choose, upload, preview, retry, suspend, dispose }
}
