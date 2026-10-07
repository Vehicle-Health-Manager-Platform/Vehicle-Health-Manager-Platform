import { apiOrigin, cloudRunEnabled } from './api-config.js'
import { apiRuntime } from './api-runtime.js'

export class AuthError extends Error {
  constructor(kind, message, status = 0, detail = '') {
    super(message)
    this.name = 'AuthError'
    this.kind = kind
    this.status = status
    // 原始底层错误文本，仅供排查（vConsole / 日志），不面向用户展示。
    this.detail = detail
  }
}

// 传输层失败有三种完全不同的原因，处置方式也完全不同，必须分开：
//   域名白名单拦截 → 要在小程序后台配置通讯域名，或开发阶段在开发者工具/真机调试中临时关闭校验；
//   网络不通       → 要核对后端地址，以及手机与电脑是否在同一局域网（校园网常见 AP 隔离）；
//   超时           → 可直接重试。
// 此前除超时外统一报「无法连接服务」，会把「域名未配置」误导成「网络问题」。
// 实测踩过：真机调试时产物指向 127.0.0.1，页面只报「无法连接服务」，无法区分是地址错还是被拦。
function transportFailure(error) {
  const detail = String(error?.errMsg || '')
  const lower = detail.toLowerCase()
  if (lower.includes('timeout')) {
    return new AuthError('timeout', '连接超时，请检查网络后重试', 0, detail)
  }
  if (lower.includes('not in domain list')) {
    return new AuthError('domain', '后端地址未通过微信域名校验，请配置通讯域名后重试', 0, detail)
  }
  return new AuthError('network', '无法连接服务，请检查网络后重试', 0, detail)
}

function responseError(status, response) {
  // Only expose known safe messages, never arbitrary upstream response text.
  if (status === 401) return new AuthError('unauthorized', '身份验证失败，请核对登录信息或重新登录', status)
  if (status === 403) return new AuthError('forbidden', '当前身份无权执行此操作，请重新登录或联系管理员', status)
  if (status === 429) return new AuthError('rate-limited', '操作过于频繁，请稍后重试', status)
  if (status === 503) return new AuthError('unavailable', response?.message === '短信服务尚未配置'
    ? '短信服务尚未开通，请联系管理员后再试' : '身份服务暂不可用，请稍后重试', status)
  if (status === 400 || status === 409) return new AuthError('invalid', '提交信息无效或已失效，请核对后重试', status)
  return new AuthError('server', '请求未成功，请稍后重试', status)
}

const tokenPresent = (value) => typeof value === 'string' && value.trim().length > 0
const validSession = (result, role) => tokenPresent(result?.access_token) && result?.user?.role === role
const malformed = () => new AuthError('protocol', '身份服务响应异常，请稍后重试')

// Injection is for offline tests. The application always uses the real uni runtime.
// `cloud` 为真时走微信云托管通道：身份由网关注入，不再调用 wx.login。
export function createAuthApi({ baseUrl, runtime, cloud = false }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')

  function configured() {
    if (!endpoint) throw new AuthError('unconfigured', '登录服务尚未配置，请联系管理员')
  }

  function request(path, data, token, accepts) {
    return new Promise((resolve, reject) => {
      configured()
      if (token !== undefined && !tokenPresent(token)) {
        throw new AuthError('unauthorized', '请先登录后再执行此操作')
      }
      runtime().request({
        url: `${endpoint}${path}`,
        method: 'POST',
        timeout: 15000,
        header: token === undefined ? {} : { Authorization: `Bearer ${token}` },
        ...(data === undefined ? {} : { data }),
        success: ({ statusCode, data: response }) => {
          if (statusCode < 200 || statusCode >= 300) {
            reject(responseError(statusCode, response))
          } else if (response?.code !== 0 || !accepts(response.data)) {
            reject(malformed())
          } else {
            resolve(response.data)
          }
        },
        fail: (error) => reject(transportFailure(error)),
      })
    })
  }

  return {
    requestWechatLogin(role) {
      return new Promise((resolve, reject) => {
        if (!['owner', 'technician'].includes(role)) throw new AuthError('invalid', '当前角色不支持微信登录')
        configured()
        const accepted = (result) => validSession(result, role) || (role === 'technician' &&
          result?.status === 'BIND_REQUIRED' && tokenPresent(result.binding_token))
        // 云托管下身份由微信网关注入请求头，无需 wx.login 取 code，也不存在 code 被重放的问题。
        if (cloud) {
          request('/api/auth/cloud-login', { role }, undefined, accepted).then(resolve, reject)
          return
        }
        runtime().login({
          provider: 'weixin',
          timeout: 15000,
          success: ({ code }) => {
            if (!tokenPresent(code)) { reject(new AuthError('wechat', '微信登录未完成，请重新授权')); return }
            request('/api/auth/wx-login', { code, role }, undefined, accepted).then(resolve, reject)
          },
          fail: () => reject(new AuthError('wechat', '微信登录未完成，请重新授权')),
        })
      })
    },
    bindTechnician(bindingToken, employeeCode) {
      if (!employeeCode?.trim()) return Promise.reject(new AuthError('invalid', '请输入商家发放的员工码'))
      return request('/api/auth/technician/bind', { employee_code: employeeCode.trim() }, bindingToken || '',
        (result) => validSession(result, 'technician'))
    },
    bindOwnerPhone(accessToken, phoneCode) {
      if (!tokenPresent(phoneCode)) return Promise.reject(new AuthError('invalid', '请同意微信手机号授权'))
      return request('/api/auth/phone/bind', { code: phoneCode }, accessToken || '',
        (result) => result?.phone_bound === true)
    },
    logoutWechat(accessToken) {
      return request('/api/auth/logout', undefined, accessToken || '', (result) => result?.revoked === true)
    },
    refreshSession(refreshToken) {
      if (!tokenPresent(refreshToken)) {
        return Promise.reject(new AuthError('unauthorized', '登录状态已失效，请重新登录'))
      }
      return request('/api/auth/refresh', { refresh_token: refreshToken }, undefined,
        (result) => tokenPresent(result?.access_token) && tokenPresent(result?.refresh_token))
    },
    requestMerchantCode(account, password) {
      return request('/api/auth/merchant/code', { account, password }, undefined, (result) => result?.sent === true)
    },
    requestMerchantLogin(account, password, smsCode) {
      return request('/api/auth/merchant/login', { account, password, sms_code: smsCode }, undefined,
        (result) => validSession(result, 'merchant'))
    },
  }
}

export const authApi = createAuthApi({
  baseUrl: apiOrigin,
  runtime: () => apiRuntime,
  cloud: cloudRunEnabled,
})
