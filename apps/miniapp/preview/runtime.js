import { previewResponse, PREVIEW_ORIGIN } from './samples.js'

export const PREVIEW_TOKENS = Object.freeze({owner:'LOCAL_PREVIEW_OWNER_NOT_A_JWT',merchant:'LOCAL_PREVIEW_MERCHANT_NOT_A_JWT',tech:'LOCAL_PREVIEW_TECH_NOT_A_JWT'})
const calls = []
const storage = new Map()
let active = false
const blocked = runtime => options => {
  runtime.showToast?.({title:'只读演示，不发送或保存真实数据',icon:'none',duration:2200})
  options?.fail?.({errMsg:'local-preview:fail read-only demonstration'})
  options?.complete?.({errMsg:'local-preview:fail read-only demonstration'})
  return {abort(){}}
}
export function installPreviewGuard(runtime) {
  active = runtime.getSystemInfoSync?.().platform === 'devtools'
  if (!active) throw new Error('Local preview only supports WeChat Devtools')
  // Diagnostic hook returns only synthetic request paths, never tokens or bodies.
  if (typeof wx !== 'undefined') wx.__autocareLocalPreviewStatus = options => {
    const result = {mode:'READ_ONLY_SYNTHETIC',version:'2026-10-18-v2',calls:calls.slice(-150)}
    options?.success?.(result)
    return result
  }
}
export function createLocalPreviewRuntime(runtime) {
  const stub = Object.create(runtime)
  const clone = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value))
  // Do not read or overwrite the real app's drafts/recovery records.
  stub.getStorageSync = key => clone(storage.get(key))
  stub.setStorageSync = (key,value) => { storage.set(key,clone(value)) }
  stub.removeStorageSync = key => { storage.delete(key) }
  stub.login = blocked(runtime)
  for (const key of ['uploadFile','downloadFile','previewImage','requestPayment','requestSubscribeMessage']) stub[key] = blocked(runtime)
  stub.request = options => {
    let aborted = false
    const ready = active || runtime.getSystemInfoSync?.().platform === 'devtools'
    const result = ready ? previewResponse(options.url,options.method || 'GET',options.data) : {statusCode:403,data:{code:40300,message:'Preview requires Devtools'}}
    const safePath = String(options.url || '').startsWith(PREVIEW_ORIGIN) ? String(options.url).slice(PREVIEW_ORIGIN.length) : '[blocked external URL]'
    calls.push({path:safePath,method:options.method || 'GET',statusCode:result.statusCode})
    if (calls.length > 150) calls.shift()
    if (result.statusCode !== 200) runtime.showToast?.({title:result.data.message,icon:'none',duration:2400})
    const timer = setTimeout(() => {
      if (!aborted) { options.success?.(result); options.complete?.(result) }
    },30)
    return {abort(){aborted=true;clearTimeout(timer)}}
  }
  return stub
}
