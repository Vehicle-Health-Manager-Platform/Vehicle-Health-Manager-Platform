import { apiOrigin, cloudRun } from './api-config.js'

// 与 uni 同形的运行时：service 模块只依赖 request/login 两个方法。
//
// 云托管模式下 request 改走 wx.cloud.callContainer，从而无需配置通讯域名；
// 其余情况原样透传 uni.request，行为与改造前一致。
const JSON_CONTENT_TYPE = { 'content-type': 'application/json' }

// 小程序运行时全局对象；Node 离线测试与 H5 构建下不存在。
function defaultCloud() {
  return typeof wx === 'undefined' ? undefined : wx?.cloud
}

export function createApiRuntime({ origin, cloud, runtime, cloudApi }) {
  const enabled = Boolean(cloud?.env && cloud?.service)
  const base = (origin || '').replace(/\/$/, '')
  let initialized = false

  function namespace() {
    return cloudApi === undefined ? defaultCloud() : cloudApi
  }

  function initCloud() {
    if (!enabled || initialized) return
    const api = namespace()
    if (!api || typeof api.init !== 'function') return
    initialized = true
    api.init({ env: cloud.env, traceUser: true })
  }

  function request(options) {
    if (!enabled) return runtime().request(options)
    const api = namespace()
    if (!api || typeof api.callContainer !== 'function') {
      options?.fail?.({ errMsg: 'cloud:fail 当前环境不支持云托管调用' })
      return undefined
    }
    initCloud()
    const target = typeof options?.url === 'string' ? options.url : ''
    const path = target.startsWith(base) ? target.slice(base.length) : target
    const header = { ...(options?.header || {}) }
    if (options?.data !== undefined && header['content-type'] === undefined && header['Content-Type'] === undefined) {
      Object.assign(header, JSON_CONTENT_TYPE)
    }
    return api.callContainer({
      config: { env: cloud.env },
      path: path || '/',
      method: options?.method || 'GET',
      header: { ...header, 'X-WX-SERVICE': cloud.service },
      data: options?.data,
      timeout: options?.timeout ?? 15000,
      success: options?.success,
      fail: options?.fail,
    })
  }

  // 云托管登录不使用 wx.login，但保留透传以支撑非云托管回退通道。
  function login(options) {
    return runtime().login(options)
  }

  return { initCloud, request, login }
}

export const apiRuntime = createApiRuntime({
  origin: apiOrigin,
  cloud: cloudRun,
  runtime: () => uni,
})
