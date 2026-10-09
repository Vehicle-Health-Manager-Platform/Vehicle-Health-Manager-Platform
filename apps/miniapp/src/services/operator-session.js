import { reactive } from 'vue'

// 独立内存会话，不复用车主/员工身份，不将密码、验证码或令牌写入存储。
export const operatorSession = reactive({ accessToken: '', expiresAt: 0, canReview: false })
export function clearOperatorSession() { Object.assign(operatorSession, { accessToken: '', expiresAt: 0, canReview: false }) }
export function setOperatorSession(result) {
  Object.assign(operatorSession, { accessToken: result.access_token, expiresAt: Date.now() + result.expires_in * 1000, canReview: result.user.can_review })
}
export function operatorToken() {
  if (operatorSession.expiresAt <= Date.now()) clearOperatorSession()
  return operatorSession.accessToken
}
