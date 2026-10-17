import { reactive } from 'vue'

// 独立内存会话，不复用车主/员工身份，不将密码、验证码或令牌写入存储。
// can_review / can_onboard 是两组互不交叉的权限，页面按各自权限决定可用入口。
export const operatorSession = reactive({ accessToken: '', actorId: 0, expiresAt: 0, canReview: false, canOnboard: false, canCatalog: false, canCoupon: false, canInvite: false, canDiagnose: false, canDiagnoseManage: false, canPoints: false, canAssessment: false, canAnalytics: false, canPriceMonitor: false, canCommunityCurate: false, canCommunityAnswer: false, canCommunityManage: false })
export function clearOperatorSession() { Object.assign(operatorSession, { accessToken: '', actorId: 0, expiresAt: 0, canReview: false, canOnboard: false, canCatalog: false, canCoupon: false, canInvite: false, canDiagnose: false, canDiagnoseManage: false, canPoints: false, canAssessment: false, canAnalytics: false, canPriceMonitor: false, canCommunityCurate: false, canCommunityAnswer: false, canCommunityManage: false }) }
export function setOperatorSession(result) {
  Object.assign(operatorSession, { actorId: Number.isSafeInteger(result.user.id) && result.user.id > 0 ? result.user.id : 0, expiresAt: Date.now() + result.expires_in * 1000,
    canReview: result.user.can_review, canOnboard: result.user.can_onboard, canCatalog: result.user.can_catalog === true, canCoupon: result.user.can_coupon === true, canInvite: result.user.can_invite === true, canDiagnose: result.user.can_diagnose === true, canDiagnoseManage: result.user.can_diagnose_manage === true, canPoints: result.user.can_points === true, canAssessment: result.user.can_assessment === true, canAnalytics: result.user.can_analytics === true, canPriceMonitor: result.user.can_price_monitor === true, canCommunityCurate: result.user.can_community_curate === true, canCommunityAnswer: result.user.can_community_answer === true, canCommunityManage: result.user.can_community_manage === true })
  operatorSession.accessToken = result.access_token
}
export function operatorToken() {
  if (operatorSession.expiresAt <= Date.now()) clearOperatorSession()
  return operatorSession.accessToken
}
export function operatorLandingPage() {
  if (!operatorSession.canReview && operatorSession.canCommunityManage) return '/pages/operator/question-service'
  if (!operatorSession.canReview && operatorSession.canCommunityAnswer) return '/pages/operator/questions?scope=mine'
  if (!operatorSession.canReview && operatorSession.canDiagnoseManage) return '/pages/operator/diagnosis-service'
  if (!operatorSession.canReview && operatorSession.canDiagnose) return '/pages/operator/diagnosis-cases'
  if (!operatorSession.canReview && operatorSession.canPoints) return '/pages/operator/points-policies'
  if (!operatorSession.canReview && operatorSession.canAssessment) return '/pages/operator/assessment-policies'
  if (!operatorSession.canReview && operatorSession.canCommunityCurate) return '/pages/operator/experience-curations'
  if (!operatorSession.canReview && operatorSession.canPriceMonitor) return '/pages/operator/price-policies'
  if (!operatorSession.canReview && operatorSession.canAnalytics) return '/pages/operator/business-dashboard'
  return '/pages/operator/review'
}
