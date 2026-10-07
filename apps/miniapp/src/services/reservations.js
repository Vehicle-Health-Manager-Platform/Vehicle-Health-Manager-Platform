import { ServiceError,serviceFailure } from './service-catalog.js'
import {validPaymentSummary} from './payment-contract.js'
const id=value=>Number.isSafeInteger(value)&&value>0
const date=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(value)
const stamp=value=>typeof value==='string'&&!Number.isNaN(Date.parse(value))
const money=value=>typeof value==='string'&&/^(0|[1-9]\d{0,7})\.\d{2}$/.test(value)&&Number(value)>0
const page=(value,n,row)=>value?.page===n&&value.page_size===20&&Number.isSafeInteger(value.total)&&value.total>=value.items?.length&&Array.isArray(value.items)&&value.items.length<=20&&value.items.every(row)
const slot=row=>id(row?.slot_id)&&id(row.standard_project_id)&&stamp(row.starts_at)&&stamp(row.ends_at)&&Number.isSafeInteger(row.capacity)&&row.capacity>0&&Number.isSafeInteger(row.capacity_left)&&row.capacity_left>=0&&row.capacity_left<=row.capacity&&typeof row.open==='boolean'
const order=row=>id(row?.order_id)&&typeof row.order_no==='string'&&typeof row.status==='string'&&money(row.amount_due)&&(row.payment_summary===undefined||validPaymentSummary(row.payment_summary))
export class ReservationError extends ServiceError{constructor(kind,message,code){super(kind,message);this.code=code}}
export function createReservationsApi({baseUrl,runtime}){
  const endpoint=(baseUrl||'').replace(/\/$/,'')
  const request=(token,path,validate,body,key)=>{
    if(!token)throw new ReservationError('unauthorized','请先登录对应账号')
    if(!endpoint)throw new ReservationError('unconfigured','预约服务尚未配置')
    if(key && !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key))throw new ReservationError('invalid','提交信息无效')
    return new Promise((resolve,reject)=>{try{runtime().request({url:endpoint+path,method:body?'POST':'GET',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
      if(statusCode<200||statusCode>=300){const conflicts={40901:'报价已更新，请重新确认',40902:'时段已关闭或开始，请重新选择',40903:'预约名额不足，请重新选择',40904:'时段重叠，请调整时间'};const messages={400:'预约信息无效，请检查后重试',401:'登录已失效，请重新登录',403:'当前身份无权操作',404:'预约资源不存在或不可用',503:'预约服务暂不可用，请稍后重试'};return reject(new ReservationError(statusCode===409?'conflict':({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',503:'unavailable'}[statusCode]||'server'),statusCode===409?(conflicts[data?.code]||'预约信息已变化，请重新选择'):messages[statusCode]||'预约请求未成功，请稍后重试',data?.code))}
      if(!Number.isInteger(statusCode)||data?.code!==0||!validate(data.data))return reject(new ReservationError('protocol','预约响应异常，请重试'));resolve(data.data)
    },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
  }
  const validPage=n=>{if(!id(n)||n>1000000)throw new ReservationError('invalid','分页无效')}
  return {
    quote(token,n){if(!id(n))throw new ReservationError('invalid','报价无效');return request(token,`/api/order/quote/${n}`,r=>id(r?.merchant_project_id)&&id(r.quote_version_id)&&id(r.merchant_id)&&id(r.standard_project_id)&&money(r.price)&&typeof r.project_name==='string'&&typeof r.merchant_name==='string')},
    slots(token,merchant,project,day,n=1){validPage(n);if(!id(merchant)||!id(project)||!date(day))throw new ReservationError('invalid','请选择预约日期');return request(token,`/api/order/slots?merchant_id=${merchant}&project_id=${project}&date=${day}&page=${n}&page_size=20`,r=>page(r,n,slot))},
    ownSlots(token,n=1){validPage(n);return request(token,`/api/merchant/slots?page=${n}&page_size=20`,r=>page(r,n,slot))},
    publish(token,body,key){return request(token,'/api/merchant/slots',slot,body,key)},
    close(token,n,key){if(!id(n))throw new ReservationError('invalid','时段无效');return request(token,`/api/merchant/slots/${n}/close`,slot,{},key)},
    create(token,body,key){return request(token,'/api/order/create',r=>order(r)&&r.status==='PENDING_PAYMENT'&&stamp(r.expires_at),body,key)},
    list(token,n=1,status=''){validPage(n);if(!['','PENDING_PAYMENT','PAID','CLOSED'].includes(status))throw new ReservationError('invalid','订单状态无效');return request(token,`/api/order/list?page=${n}&page_size=20${status?'&status='+status:''}`,r=>page(r,n,order))},
    detail(token,n){if(!id(n))throw new ReservationError('invalid','订单无效');return request(token,`/api/order/${n}`,order)},
    cancel(token,n,key){if(!id(n))throw new ReservationError('invalid','订单无效');return request(token,'/api/order/cancel',order,{order_id:n},key)},
  }
}
export const reservationsApi=createReservationsApi({baseUrl:import.meta.env?.VITE_API_BASE_URL,runtime:()=>uni})
export const initialReservationWriteState=()=>({busy:false,saved:null,message:'',failureKind:'',code:0})
export function createReservationWriteFlow({state,token,body,request,newKey,onConflict=()=>{}}){
  let generation=0,pending=null
  function suspend(){generation++;state.busy=false}
  function reset(){suspend();pending=null;Object.assign(state,initialReservationWriteState())}
  async function save(){
    if(state.busy||state.saved)return
    let value;try{value=body()}catch(error){state.message=error.message;state.failureKind='invalid';return}
    const canonical=JSON.stringify(value);if(!pending||pending.canonical!==canonical)pending={canonical,body:value,key:newKey()}
    const current=++generation,actor=token();state.busy=true;state.message='';state.failureKind='';state.code=0
    try{const result=await request(actor,pending.body,pending.key);if(current!==generation||actor!==token())return;state.saved=result;state.message='操作已保存'}
    catch(error){if(current!==generation||actor!==token())return;const safe=error instanceof ServiceError?error:serviceFailure();state.message=safe.message;state.failureKind=safe.kind;state.code=safe.code||0;if(safe.kind==='conflict'){pending=null;await onConflict(safe.code)}}
    finally{if(current===generation)state.busy=false}
  }
  return{save,reset,suspend}
}
export const chinaDate=(value=new Date())=>new Date(value.getTime()+8*3600000).toISOString().slice(0,10)
export const displayTime=value=>value?new Date(Date.parse(value)+8*3600000).toISOString().slice(0,16).replace('T',' ')+'（北京时间）':'未提供'
export const initialReservationReadState=()=>({value:null,busy:false,loaded:false,message:'',failureKind:''})
export function createReservationReadFlow({state,token,request}){
  let generation=0
  function suspend(){generation++;state.busy=false}
  function reset(){suspend();Object.assign(state,initialReservationReadState())}
  async function load(){if(state.busy)return;const current=++generation,actor=token();state.busy=true;state.message='';state.failureKind=''
    try{const value=await request(actor);if(current!==generation||actor!==token())return;state.value=value;state.loaded=true}
    catch(error){if(current!==generation||actor!==token())return;state.value=null;const safe=error?.kind?error:serviceFailure();state.message=safe.message;state.failureKind=safe.kind}
    finally{if(current===generation)state.busy=false}
  }
  return {load,reset,suspend}
}
