export function archiveEntryUrl(vehicleId, mode = 'manual') {
  if (!Number.isSafeInteger(vehicleId) || vehicleId <= 0 || !['manual', 'photo'].includes(mode))
    throw new TypeError('Invalid archive entry route')
  return `/pages/archive/record-add?vehicle_id=${vehicleId}${mode === 'photo' ? '&mode=photo' : ''}`
}

export function archiveInputType(query) { return query?.mode === 'photo' ? 1 : 3 }

export function archiveInputTypeName(value) { return value === 1 ? '拍照录入' : value === 3 ? '手动录入' : value === 4 ? '施工自动归档' : '其他录入' }
