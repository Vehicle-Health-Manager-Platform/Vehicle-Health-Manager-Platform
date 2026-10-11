import { ArchiveReportError } from './archive-report.js'

function cancellation() {
  const listeners = new Set()
  return {
    cancelled: false,
    subscribe(fn) { if (this.cancelled) fn(); else listeners.add(fn) },
    unsubscribe(fn) { listeners.delete(fn) },
    cancel() { if (this.cancelled) return; this.cancelled = true; for (const fn of [...listeners]) fn(); listeners.clear() },
  }
}

export function createArchiveReportFlow({ api, token, vehicleId, onChange = () => {} }) {
  const state = { busy: false, message: '', kind: '' }
  let generation = 0, signal = null, activePath = '', openingPath = '', viewerPath = ''
  let hidden = false, returnedDuringOpen = false, disposed = false
  const publish = patch => { Object.assign(state, patch); onChange({ ...state }) }
  const remove = path => { if (path) { try { api.remove(path) } catch {} } }

  function invalidate() {
    generation++
    signal?.cancel(); signal = null
    for (const path of new Set([activePath, openingPath, viewerPath])) remove(path)
    activePath = ''; openingPath = ''; viewerPath = ''
    hidden = false; returnedDuringOpen = false
    publish({ busy: false, message: '', kind: '' })
  }

  function suspend() {
    hidden = true
    if (openingPath || viewerPath) return
    if (state.busy) {
      invalidate()
      hidden = true
      publish({ message: '导出已暂停，返回后可以重新生成', kind: 'paused' })
    }
  }

  function resume() {
    if (!hidden) return
    hidden = false
    if (viewerPath) {
      remove(viewerPath)
      viewerPath = ''
    } else if (openingPath) returnedDuringOpen = true
  }

  async function exportReport(range = null) {
    if (disposed) return
    invalidate()
    const currentGeneration = generation
    const ownerToken = token(), currentVehicle = vehicleId()
    if (typeof ownerToken !== 'string' || !ownerToken || !Number.isSafeInteger(currentVehicle) || currentVehicle <= 0) {
      publish({ message: '请先登录并选择本人车辆', kind: 'unauthorized' })
      return
    }
    const current = () => !disposed && generation === currentGeneration
      && token() === ownerToken && vehicleId() === currentVehicle
    signal = cancellation()
    const operation = signal
    publish({ busy: true, message: '正在生成本人档案 PDF…', kind: '' })
    try {
      const path = await api.download(ownerToken, currentVehicle, range, operation)
      if (!current()) { remove(path); return }
      activePath = path
      openingPath = path
      await api.openDocument(path)
      if (!current()) { remove(path); return }
      openingPath = ''; activePath = ''
      if (returnedDuringOpen) {
        remove(path)
        returnedDuringOpen = false
      } else viewerPath = path
      publish({ busy: false, message: '报告已打开；返回档案页后会清理临时文件', kind: 'success' })
    } catch (error) {
      if (!current()) { remove(activePath || openingPath); return }
      remove(activePath || openingPath)
      activePath = ''; openingPath = ''
      const failure = error instanceof ArchiveReportError
        ? error
        : new ArchiveReportError('network', '报告暂时无法生成，请稍后重试')
      publish({ busy: false, message: failure.message, kind: failure.kind })
    } finally {
      if (current() && signal === operation) signal = null
    }
  }

  function dispose() {
    if (disposed) return
    disposed = true
    generation++
    signal?.cancel(); signal = null
    for (const path of new Set([activePath, openingPath, viewerPath])) remove(path)
    activePath = ''; openingPath = ''; viewerPath = ''
    publish({ busy: false, message: '', kind: '' })
  }

  return { state, export: exportReport, invalidate, suspend, resume, dispose }
}
