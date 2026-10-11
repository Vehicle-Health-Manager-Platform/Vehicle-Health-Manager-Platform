import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {ReservationError} from './reservations.js'
import {ORDER_STATES} from './order-status.js'
const id=n=>Number.isSafeInteger(n)&&n>0
const eligible=['PAID','RECEIVED','IN_SERVICE','PENDING_VERIFY','DISPUTED']
const states=['REQUESTED','REJECTED','WITHDRAWN','PENDING','FAILED','SUCCEEDED']
const date=s=>s===null||(typeof s==='string'&&/^\d{4}-\d{2}-\d{2}T/.test(s)&&Number.isFinite(Date.parse(s)))
const note=s=>typeof s==='string'&&s.trim().length>0&&s.length<=256
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const keys=['refund_id','refund_no','payment_id','amount','currency','status','revision','from_status','reason','decision_note','owner_consented_at','merchant_consented_at','refunded_at','test_mode']
export function validRefundDetail(v){
  if(!exact(v,['order_id','order_status','can_request','refund'])||!id(v.order_id)||!ORDER_STATES.includes(v.order_status)||typeof v.can_request!=='boolean')return false
  const r=v.refund
  if(v.can_request&&(!eligible.includes(v.order_status)||(r&&!['REJECTED','WITHDRAWN'].includes(r.status))))return false
  if(r===null)return true
  if(!exact(r,keys)||!id(r.refund_id)||!id(r.payment_id)||!id(r.revision)||(typeof r.refund_no!=='string'||!/^R[a-f0-9]{32}$/.test(r.refund_no))||!states.includes(r.status)||!eligible.includes(r.from_status)||typeof r.test_mode!=='boolean'||typeof r.amount!=='string'||!/^(0|[1-9]\d{0,7})\.\d{2}$/.test(r.amount)||Number(r.amount)<=0||r.currency!=='CNY'||!note(r.reason)||(r.decision_note!==null&&!note(r.decision_note))||!date(r.owner_consented_at)||r.owner_consented_at===null||!date(r.merchant_consented_at)||!date(r.refunded_at))return false
  if(['PENDING','FAILED','SUCCEEDED'].includes(r.status)&&(v.order_status!=='CLOSED'||r.merchant_consented_at===null))return false
  if(r.status==='SUCCEEDED'&&r.refunded_at===null)return false
  if(r.status!=='SUCCEEDED'&&r.refunded_at!==null)return false
  return true
}
export const refundStatusLabel=s=>({REQUESTED:'等待店长确认',REJECTED:'店长已拒绝',WITHDRAWN:'车主已撤回',PENDING:'退款处理中，尚未确认到账',FAILED:'供应商报告退款失败，需继续核对',SUCCEEDED:'退款记录为成功'}[s]||'状态未提供')
export function createOrderRefundsApi({baseUrl,runtime}){
  const base=(baseUrl||'').replace(/\/$/,'')
  function path(role,n){if(!['owner','merchant'].includes(role))throw new ReservationError('forbidden','当前身份不能处理退款');if(!id(n))throw new ReservationError('invalid','订单无效');return role==='owner'?`/api/order/${n}/refund`:`/api/merchant/orders/${n}/refund`}
  function call(token,url,n,body,key){
    if(!token)throw new ReservationError('unauthorized','请先登录对应账号')
    if(!base)throw new ReservationError('unconfigured','退款服务尚未配置')
    if(body!==undefined&&!url.endsWith('/reconcile')&&!/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(key))throw new ReservationError('invalid','提交信息无效')
    return new Promise((resolve,reject)=>{try{runtime().request({url:base+url,method:body===undefined?'GET':'POST',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
      if(statusCode!==200){const kind={400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'}[statusCode]||'server';const message={400:'退款提交信息无效',401:'登录已失效，请重新登录',403:'仅本人车主或本店店长可处理退款',404:'本人或本店订单不存在',409:'订单、支付或退款申请已变化，请刷新；必要时撤回后重新申请',503:'退款渠道或服务暂不可用，请稍后重试'}[statusCode]||'退款请求未成功';return reject(new ReservationError(kind,message))}
      if(data?.code!==0||!validRefundDetail(data.data)||data.data.order_id!==n)return reject(new ReservationError('protocol','退款响应异常，请刷新核对'))
      resolve(data.data)
    },fail:()=>reject(new ReservationError('network','连接失败，结果未确定；请用原请求重试'))})}catch{reject(new ReservationError('network','连接失败，结果未确定；请重试'))}})
  }
  return{
    detail(t,role,n){return call(t,path(role,n),n)},
    request(t,n,reason,key){if(!note(reason))throw new ReservationError('invalid','请填写1至256字退款原因');return call(t,path('owner',n),n,{reason:reason.trim()},key)},
    withdraw(t,n,revision,key){if(!id(revision))throw new ReservationError('invalid','申请版本无效');return call(t,path('owner',n)+'/withdraw',n,{revision},key)},
    decide(t,n,revision,decision,reason,key){if(!id(revision)||!['ACCEPT','REJECT'].includes(decision)||(decision==='REJECT'&&!note(reason))||(reason!==undefined&&reason!==''&&!note(reason)))throw new ReservationError('invalid','请确认申请版本，拒绝须填写原因');return call(t,path('merchant',n)+'/decision',n,{revision,decision,...(reason?{note:reason.trim()}:{})},key)},
    reconcile(t,n){return call(t,path('merchant',n)+'/reconcile',n,{})},
  }
}
export const orderRefundsApi=createOrderRefundsApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
