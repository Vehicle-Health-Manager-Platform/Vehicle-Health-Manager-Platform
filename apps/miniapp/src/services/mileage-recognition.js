import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { recognitionCandidates, candidateFields } from './input-candidates.js'

const exact = (v, keys) => v && typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === keys.length && keys.every(k => Object.hasOwn(v, k))
const validId = v => Number.isSafeInteger(v) && v > 0 && v < 9007199254740991
export function mileageResponse(data, vehicleId, fileId) {
  if (!exact(data, ['vehicle_id', 'file_id', 'review_status', 'requires_confirmation', 'recognition']) || data.vehicle_id !== vehicleId || data.file_id !== fileId || data.requires_confirmation !== true) throw Error('invalid response')
  const rows = recognitionCandidates(data.recognition, 'archive', candidateFields.archive)
  if (['CANDIDATE', 'LOW_CONFIDENCE'].includes(data.review_status)) {
    const row = rows[0]
    if (rows.length !== 1 || row.key !== 'mileage' || !/^(0|[1-9]\d*)$/.test(row.value) || Number(row.value) > 2000000 || typeof row.confidence !== 'number' || (data.review_status === 'CANDIDATE') !== (row.confidence >= 0.9)) throw Error('invalid mileage')
  } else if (!['AMBIGUOUS', 'NO_CANDIDATE'].includes(data.review_status) || data.recognition.status !== 'EMPTY' || rows.length) throw Error('invalid status')
  return data.recognition
}

export function pickupMileageResponse(data, orderId, fileId) {
  if (!exact(data, ['order_id', 'file_id', 'review_status', 'requires_confirmation', 'recognition']) || data.order_id !== orderId) throw Error('invalid pickup response')
  const { order_id, ...rest } = data
  return mileageResponse({ ...rest, vehicle_id: order_id }, orderId, fileId)
}
export function createMileageRecognition({ baseUrl, runtime, pickup = false }) {
  return ({ token, vehicleId, orderId, fileId, consent, signal }) => new Promise((resolve, reject) => {
    const scopeId = pickup ? orderId : vehicleId
    if (!token || !baseUrl || !validId(scopeId) || !validId(fileId) || consent !== true || signal?.aborted) { reject(Error('识别条件不完整')); return }
    let settled = false, task
    const finish = (error, value) => {
      if (settled) return
      settled = true
      signal?.removeEventListener?.('abort', abort)
      error ? reject(Error('识别暂不可用，请手动整理')) : resolve(value)
    }
    const abort = () => { finish(true); task?.abort?.() }
    signal?.addEventListener?.('abort', abort, { once: true })
    try {
      task = runtime().request({
        url: `${baseUrl.replace(/\/$/, '')}${pickup ? '/api/check/pickup/mileage-recognition' : '/api/vehicle/mileage-recognition'}`, method: 'POST', timeout: 25000,
        header: { Authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        data: { [pickup ? 'order_id' : 'vehicle_id']: scopeId, file_id: fileId, consent: true, consent_version: pickup ? 'PICKUP_MILEAGE_OCR_V1' : 'MILEAGE_OCR_V1' },
        success: ({ statusCode, data }) => {
          if (settled || signal?.aborted) return
          try {
            if (statusCode !== 200 || data?.code !== 0) throw Error()
            finish(false, pickup ? pickupMileageResponse(data.data, scopeId, fileId) : mileageResponse(data.data, scopeId, fileId))
          } catch { finish(true) }
        }, fail: () => finish(true)
      })
      if (signal?.aborted) abort()
    } catch { finish(true) }
  })
}
export const recognizeMileage = createMileageRecognition({ baseUrl: apiOrigin, runtime: () => apiRuntime })
export const recognizePickupMileage = createMileageRecognition({ baseUrl: apiOrigin, runtime: () => apiRuntime, pickup: true })
