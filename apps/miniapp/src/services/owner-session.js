import { reactive } from 'vue'

// Keep the preview session in memory. A fresh app launch requires a new login.
export const ownerSession = reactive({ accessToken: '', actorId: 0, phoneBound: false })

export function setOwnerSession(result) {
  ownerSession.actorId = Number.isSafeInteger(result.user?.id) && result.user.id > 0 ? result.user.id : result.access_token === ownerSession.accessToken ? ownerSession.actorId : 0
  ownerSession.accessToken = result.access_token || ''
  ownerSession.phoneBound = Boolean(result.user?.phone_bound)
}

export function clearOwnerSession() {
  ownerSession.accessToken = ''
  ownerSession.actorId = 0
  ownerSession.phoneBound = false
}
