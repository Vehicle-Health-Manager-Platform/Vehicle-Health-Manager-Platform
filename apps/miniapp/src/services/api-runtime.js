import { apiOrigin, cloudRun } from './api-config.js'

// 与 uni 同形的运行时：service 模块只依赖请求、登录与取图/文件能力。
//
// 云托管模式下 request 改走 wx.cloud.callContainer，从而无需配置通讯域名；
// 其余情况原样透传 uni.request，行为与改造前一致。
//
// 取图与文件能力**不经过请求传输层**：callContainer 只承载 JSON body，无法传 multipart，
// 因此这些方法始终透传原生实现。它们必须在这里显式暴露——曾经遗漏过一次，
// 结果是 runtime().chooseImage / uploadFile / previewImage 全是 undefined，
// 而 uploadFile 的失败又被上层 catch 成「无法连接图片服务，请检查网络后重试」，
// 拍照、上传、预览整条链路不可用，且错误信息把排查方向完全带偏。
const JSON_CONTENT_TYPE = { 'content-type': 'application/json' }

// 需要原样透传的本地能力（回调式，参数与 uni/wx 一致）。
const FORWARDED = ['chooseMedia', 'chooseImage', 'uploadFile', 'previewImage', 'openSetting']

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

  // 底层是否真的提供该能力。调用方据此在旧基础库上选择替代实现，
  // 不能依赖「本对象上有同名方法」——透传包装总会存在。
  function supports(name) {
    try {
      return typeof runtime()?.[name] === 'function'
    } catch {
      return false
    }
  }

  function forward(name) {
    return options => {
      let target
      try {
        target = runtime()
      } catch {
        options?.fail?.({ errMsg: `${name}:fail 当前运行环境不可用` })
        return undefined
      }
      const fn = target?.[name]
      if (typeof fn !== 'function') {
        options?.fail?.({ errMsg: `${name}:fail 当前运行环境不支持 ${name}` })
        return undefined
      }
      return fn.call(target, options)
    }
  }

  // 与 uni 同形：调用方拿到的应当是可用的文件系统管理器，而不是回调包装。
  function getFileSystemManager() {
    try {
      const target = runtime()
      const fn = target?.getFileSystemManager
      return typeof fn === 'function' ? fn.call(target) : undefined
    } catch {
      return undefined
    }
  }

  // 开发者工具没有摄像头，取图方式需要据此调整；未知环境按真机处理，保持相机优先。
  function environment() {
    try {
      const info = runtime()?.getSystemInfoSync?.() || {}
      return info.platform === 'devtools' || info.environment === 'devtools' ? 'devtools' : 'device'
    } catch {
      return 'device'
    }
  }

  const forwarded = Object.fromEntries(FORWARDED.map(name => [name, forward(name)]))

  return { initCloud, request, login, supports, environment, getFileSystemManager, ...forwarded }
}

export const apiRuntime = createApiRuntime({
  origin: apiOrigin,
  cloud: cloudRun,
  runtime: () => uni,
})
