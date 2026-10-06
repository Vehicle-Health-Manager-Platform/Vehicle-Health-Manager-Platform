import { reactive } from 'vue'
export const merchantSession = reactive({ accessToken: '' })
export function clearMerchantSession() { merchantSession.accessToken = '' }
