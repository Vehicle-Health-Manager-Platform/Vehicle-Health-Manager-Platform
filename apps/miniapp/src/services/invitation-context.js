// Only the explicit invitation landing action sets context. Never infer new-user status.
export const validInvitationCode = n => typeof n === 'string' && /^[0-9a-f]{32}$/.test(n)
let pending = null
let attempted = false
let frozen = null
export function setInvitationContext(code) {
  if (!validInvitationCode(code) || attempted) return false
  pending = code
  return true
}
export function invitationContext() { return pending }
export function beginInvitationLogin() {
  if (!attempted) { frozen = pending; attempted = true }
  return frozen
}
export function clearInvitationContext() { pending = null; frozen = null; attempted = false }
