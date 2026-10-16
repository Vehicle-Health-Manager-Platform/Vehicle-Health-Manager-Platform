import { reactive } from 'vue'

// 独立内存会话，不复用车主/员工身份，不将密码、验证码或令牌写入存储。
// can_review / can_onboard 是两组互不交叉的权限，页面按各自权限决定可用入口。
export const operatorSession = reactive({ accessToken: '', actorId: 0, expiresAt: 0, canReview: false, canOnboard: false, canCatalog: false, canCoupon: false, canInvite: false, canDiagnose: false, canDiagnoseManage: false })
export function clearOperatorSession() { Object.assign(operatorSession, { accessToken: '', actorId: 0, expiresAt: 0, canReview: false, canOnboard: false, canCatalog: false, canCoupon: false, canInvite: false, canDiagnose: false, canDiagnoseManage: false }) }
export function setOperatorSession(result) {
  Object.assign(operatorSession, { accessToken: result.access_token, actorId: Number.isSafeInteger(result.user.id) && result.user.id > 0 ? result.user.id : 0, expiresAt: Date.now() + result.expires_in * 1000,
    canReview: result.user.can_review, canOnboard: result.user.can_onboard, canCatalog: result.user.can_catalog === true, canCoupon: result.user.can_coupon === true, canInvite: result.user.can_invite === true, canDiagnose: result.user.can_diagnose === true, canDiagnoseManage: result.user.can_diagnose_manage === true })
}
export function operatorToken() {
  if (operatorSession.expiresAt <= Date.now()) clearOperatorSession()
  return operatorSession.accessToken
}
export function operatorLandingPage() {
  if (!operatorSession.canReview && operatorSession.canDiagnoseManage) return '/pages/operator/diagnosis-service'
  if (!operatorSession.canReview && operatorSession.canDiagnose) return '/pages/operator/diagnosis-cases'
  return '/pages/operator/review'
}
