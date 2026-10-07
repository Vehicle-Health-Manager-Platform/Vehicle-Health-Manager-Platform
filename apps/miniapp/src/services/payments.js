import {ReservationError} from './reservations.js'
import {serviceFailure} from './service-catalog.js'
import {validPaymentSummary} from './payment-contract.js'
const id=n=>Number.isSafeInteger(n)&&n>0
export function createPaymentsApi({baseUrl,runtime}){
  const endpoint=(baseUrl||'').replace(/\/$/,'')
  function request(token,path,body,key){
    if(!token)throw new ReservationError('unauthorized','请先登录车主账号')
    if(!endpoint)throw new ReservationError('unconfigured','支付服务尚未配置')
    if(key&&!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key))throw new ReservationError('invalid','支付提交信息无效')
    return new Promise((resolve,reject)=>{try{runtime().request({url:endpoint+path,method:body?'POST':'GET',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
      if(statusCode!==200){const kind={400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'}[statusCode]||'server';const messages={400:'支付信息无效',401:'登录已失效，请重新登录',403:'当前身份无权支付',404:'本人支付记录不存在',409:'支付或订单状态已变化，请刷新',503:body?.channel==='WECHAT'?'正式微信支付尚未配置，当前不可支付':'支付服务暂不可用，请重试'};return reject(new ReservationError(kind,messages[statusCode]||'支付请求未成功'))}
      if(data?.code!==0||!validPaymentSummary(data.data)||!id(data.data?.order_id))return reject(new ReservationError('protocol','支付响应异常，请刷新'))
      resolve(data.data)
    },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
  }
  return{create(token,orderId,key){if(!id(orderId))throw new ReservationError('invalid','订单无效');return request(token,'/api/payments/create',{order_id:orderId,channel:'WECHAT'},key)},detail(token,n){if(!id(n))throw new ReservationError('invalid','支付记录无效');return request(token,`/api/payments/${n}`)}}
}
export const paymentsApi=createPaymentsApi({baseUrl:import.meta.env?.VITE_API_BASE_URL,runtime:()=>uni})
