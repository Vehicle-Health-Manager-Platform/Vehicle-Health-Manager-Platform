import { ref, watch } from 'vue'
import { ownerSession } from './owner-session.js'

// The selected vehicle is private session state. Never persist it across logins.
export const selectedOwnerVehicle = ref(null)

export function clearOwnerVehicle() { selectedOwnerVehicle.value = null }

export function selectOwnerVehicle(row) {
  if (!ownerSession.accessToken || !Number.isSafeInteger(row?.vehicle_id) || row.vehicle_id <= 0)
    throw new TypeError('Invalid owner vehicle selection')
  selectedOwnerVehicle.value = row
  return row.vehicle_id
}

export function selectionFromPage(rows, selectedId) {
  if (!Array.isArray(rows) || !rows.length) return null
  if (selectedId > 0) return rows.find(row => row.vehicle_id === selectedId) || null
  return rows[0]
}

watch(() => ownerSession.accessToken, clearOwnerVehicle, { flush: 'sync' })
