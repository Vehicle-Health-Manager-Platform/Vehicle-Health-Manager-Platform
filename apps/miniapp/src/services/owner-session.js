import { reactive } from 'vue'

// Keep the preview session in memory. A fresh app launch requires a new login.
export const ownerSession = reactive({ accessToken: '', phoneBound: false })

export function setOwnerSession(result) {
  ownerSession.accessToken = result.access_token || ''
  ownerSession.phoneBound = Boolean(result.user?.phone_bound)
}

export function clearOwnerSession() {
  ownerSession.accessToken = ''
  ownerSession.phoneBound = false
}
