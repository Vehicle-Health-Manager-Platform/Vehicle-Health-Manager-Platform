import { watch } from 'vue'
import { ownerSession } from './owner-session.js'
const id = x => Number.isSafeInteger(x) && x > 0
export function createPlanBookingHandoff({ token, now = Date.now }) {
  let pending = null
  function clear() { pending = null }
  function route(project, vehicle) { if (!token() || !id(project) || !id(vehicle)) return null; pending = { actor: token(), project, vehicle, at: now() }; return `/pages/ai/plan-booking?project_id=${project}` }
  function take(project) { const value = pending; clear(); return value && value.actor === token() && value.project === project && id(project) && now() >= value.at && now() - value.at <= 900000 ? { actor: value.actor, project: value.project, vehicle: value.vehicle } : null }
  return { route, take, clear }
}
export const planBookingHandoff = createPlanBookingHandoff({ token: () => ownerSession.accessToken })
watch(() => ownerSession.accessToken, planBookingHandoff.clear, { flush: 'sync' })
