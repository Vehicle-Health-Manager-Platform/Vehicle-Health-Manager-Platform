// 解析当前构建的后端入口。
//
// 微信云托管（callContainer）模式没有可直达的 HTTP 原点：请求由传输层改走
// wx.cloud.callContainer。这里只给出一个固定哨兵，供各 service 拼接路径并通过
// 「未配置」校验；哨兵不会发往网络，真实地址由传输层剥离后交给 callContainer。
//
// 未配置云托管时沿用 VITE_API_BASE_URL（本机、开发者工具与 H5 联调）。
const envId = import.meta.env?.VITE_WECHAT_CLOUD_ENV_ID || ''
const service = import.meta.env?.VITE_WECHAT_CLOUD_SERVICE || ''

export const CLOUD_RUN_ORIGIN = 'https://cloudrun.invalid'

export const cloudRun = Object.freeze({ env: envId, service })
export const cloudRunEnabled = Boolean(envId && service)
export const apiOrigin = cloudRunEnabled ? CLOUD_RUN_ORIGIN : (import.meta.env?.VITE_API_BASE_URL || '')
