import { reactive } from 'vue'

// 技师会话必须跨页面有效且与车主/商家完全隔离：
// 技师登录后从「技师入口」跳到「我的工单」「工单详情」，若沿用组件局部 token，
// 页面一换身份就丢，工单页只能报「请先登录」，且旧账号的请求可能被新身份复用。
// 只保留 access token，不落盘、不写日志、不出现在 URL。
export const technicianSession = reactive({ accessToken: '' })

export function setTechnicianSession(result) {
  technicianSession.accessToken = result?.access_token || ''
}

export function clearTechnicianSession() {
  technicianSession.accessToken = ''
}
