import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
export const PICKUP_SLOTS = ['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR']
export const PICKUP_LABELS = { FRONT:'车前',REAR:'车后',LEFT:'车左',RIGHT:'车右',ROOF:'车顶',DASHBOARD:'仪表盘',INTERIOR:'内饰' }
export const FUEL_VALUES = ['EMPTY','QUARTER','HALF','THREE_QUARTERS','FULL']
export const FUEL_LABELS = ['空','¼','½','¾','满']
const id = n => Number.isSafeInteger(n) && n > 0
const invalid = message => new ServiceError('invalid',message)
export function pickupBody(order,state) {
  if (!id(order) || !/^\d{6}$/.test(state.code)) throw invalid('请输入车主提供的六位预约码')
  if (!/^\d{1,7}$/.test(String(state.mileage))) throw invalid('请输入 0–9999999 的整数里程')
  const photos = Object.fromEntries(PICKUP_SLOTS.map(slot => [slot,state.photos[slot]?.fileId]))
  if (!Object.values(photos).every(id) || new Set(Object.values(photos)).size !== 7) throw invalid('请完成七个位置的图片上传')
  if (!FUEL_VALUES.includes(state.fuel)) throw invalid('请选择油量')
  if (!['NONE','PRESENT'].includes(state.damageStatus)) throw invalid('请明确选择有无损伤')
  const damages = state.damageStatus === 'NONE' ? [] : state.damages.map(d=>({...d,note:d.note.trim()}))
  if (state.damageStatus === 'PRESENT' && (!damages.length || damages.length>20 || damages.some(d=>!PICKUP_SLOTS.includes(d.photo_slot)||!Number.isFinite(d.x)||d.x<0||d.x>1||!Number.isFinite(d.y)||d.y<0||d.y>1||!d.note||d.note.length>200))) throw invalid('请在照片上标注损伤位置并填写说明，最多 20 处')
  const body={order_id:order,appointment_code:state.code,photos,mileage:Number(state.mileage),fuel_level:state.fuel,damage_status:state.damageStatus,damages}
  for(const [field,value] of [['mileage_reason',state.mileageReason],['arrival_reason',state.arrivalReason]]) {
    const text=value.trim();if(text.length>200)throw invalid('原因不能超过 200 字');if(text)body[field]=text
  }
  return body
}
const validSheet = s => id(s?.pickup_check_id) && id(s.order_id) && PICKUP_SLOTS.every(slot=>id(s.photos?.[slot])) && Array.isArray(s.damages) && Number.isSafeInteger(s.mileage) && [0,1,2].includes(s.owner_confirm)
export function createPickupApi({baseUrl,runtime}) {
  const endpoint=(baseUrl||'').replace(/\/$/,'')
  function request(token,path,accepts,body,key) {
    if(!token)throw new ServiceError('unauthorized','请先登录对应账号')
    if(!endpoint)throw new ServiceError('unconfigured','接车服务尚未配置')
    if(key&&!/^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i.test(key))throw invalid('提交键无效')
    return new Promise((resolve,reject)=>runtime().request({url:endpoint+path,method:body?'POST':'GET',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
      if(statusCode<200||statusCode>=300){const kinds={400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',422:'rejected',429:'rate-limited',503:'unavailable'};const messages={400:'检查信息无效，请检查必填项和原因',401:'登录已失效，请重新登录',403:'当前身份无权查看或提交接车单',404:'接车资源不存在或不可访问',409:'接车单状态已变化，请刷新查看',422:'预约码无效或图片不可用于接车单',429:'操作过于频繁，请稍后重试',503:'接车服务暂不可用，请使用原内容重试'};reject(new ServiceError(kinds[statusCode]||'server',messages[statusCode]||'接车请求未成功，请重试'));return}
      if(data?.code!==0||!accepts(data.data)){reject(new ServiceError('protocol','接车响应异常，请重试'));return}resolve(data.data)
    },fail:()=>reject(serviceFailure())}))
  }
  const checked=n=>{if(!id(n))throw invalid('订单信息无效')}
  return {
    context(token,n){checked(n);return request(token,`/api/check/pickup/context?order_id=${n}`,v=>v?.order_id===n&&Number.isSafeInteger(v.mileage_baseline?.mileage))},
    submit(token,body,key){checked(body.order_id);return request(token,'/api/check/pickup/submit',validSheet,body,key)},
    detail(token,n){checked(n);return request(token,`/api/check/pickup/${n}`,v=>validSheet(v)&&v.order_id===n)},
    decide(token,n,decision,reason,key){checked(n);if(!['CONFIRM','DISPUTE'].includes(decision))throw invalid('接车单操作无效');const note=reason?.trim();if(decision==='DISPUTE'&&(!note||note.length>500))throw invalid('请填写 1–500 字异议原因');const body={order_id:n,decision,...(decision==='DISPUTE'?{reason:note}:{})};return request(token,'/api/check/pickup/confirm',v=>validSheet(v)&&v.order_id===n,body,key)},
    access(token,n,file){checked(n);checked(file);return request(token,`/api/check/pickup/${n}/files/${file}/access`,v=>typeof v?.url==='string'&&/^https:\/\//.test(v.url)&&Date.parse(v.expires_at)>Date.now())},
  }
}
export const pickupApi=createPickupApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
