import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {ORDER_STATES} from './order-status.js'
import {FUEL_VALUES} from './pickup.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

/** 取车对比的缺口编码。与后端 DeliveryCompare.missing 逐字一致，前端只做展示，不自行判定。 */
export const MISSING_CODES=['OWNER_CONFIRM','PROTECTION_PHOTO','REPORT','PROCESS','FINISH','FAULT','SIGNATURE']
export const MISSING_LABELS={
  OWNER_CONFIRM:'车主尚未确认接车单',
  PROTECTION_PHOTO:'缺少施工防护照片',
  REPORT:'施工报工尚未提交',
  PROCESS:'缺少施工过程照片',
  FINISH:'缺少完工照片',
  FAULT:'缺少故障件照片（或未声明无故障件）',
  SIGNATURE:'缺少质检签字',
}
export const RESULT_LABELS={READY:'证据齐备',INCOMPLETE:'仍有缺口'}
export const REPORT_STATE_LABELS={MISSING:'报工未提交',SUBMITTED:'已报工待签字',SIGNED:'已质检签字'}
export const OWNER_CONFIRM_LABELS={0:'待车主确认',1:'车主已确认接车',2:'车主已提出异议',3:'异议已处理并恢复'}
const SLOTS=['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR']
const integer=(n,min,max)=>Number.isInteger(n)&&n>=min&&n<=max
const instant=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(n)&&Number.isFinite(Date.parse(n))
const slotList=n=>Array.isArray(n)&&n.length<=SLOTS.length&&new Set(n).size===n.length&&n.every(s=>SLOTS.includes(s))
const count=(n,max)=>integer(n,0,max)
const codeList=n=>Array.isArray(n)&&n.length<=MISSING_CODES.length&&new Set(n).size===n.length&&n.every(c=>MISSING_CODES.includes(c))

export function validDeliveryCompare(n){
  if(!exact(n,['order_id','order_status','result','deliverable','baseline','work','missing']))return false
  if(!safeId(n.order_id)||!ORDER_STATES.includes(n.order_status)||!Object.hasOwn(RESULT_LABELS,n.result)||typeof n.deliverable!=='boolean')return false
  if(!codeList(n.missing))return false
  // 结论必须与缺口一致：说齐备却又列了缺口，或说仍有缺口却什么都不缺，都是协议错误。
  if((n.result==='READY')!==(n.missing.length===0))return false
  if(n.deliverable&&n.result!=='READY')return false
  const b=n.baseline
  if(!exact(b,['pickup_check_id','mileage','mileage_delta','fuel_level','damage_status','damage_count','photo_count','photo_slots','owner_confirm','confirm_at']))return false
  if(!safeId(b.pickup_check_id)||!integer(b.mileage,0,9999999)||!integer(b.mileage_delta,-9999999,9999999))return false
  if(!(b.fuel_level===null||FUEL_VALUES.includes(b.fuel_level))||!(b.damage_status===null||['NONE','PRESENT'].includes(b.damage_status)))return false
  if(!count(b.damage_count,100)||!count(b.photo_count,SLOTS.length)||!slotList(b.photo_slots))return false
  if(b.photo_count!==b.photo_slots.length||!Object.hasOwn(OWNER_CONFIRM_LABELS,b.owner_confirm))return false
  if(!(b.confirm_at===null||instant(b.confirm_at)))return false
  const w=n.work
  if(!exact(w,['protection','report'])||!exact(w.protection,['submitted','item_count','photo']))return false
  const p=w.protection
  if(typeof p.submitted!=='boolean'||typeof p.photo!=='boolean'||!count(p.item_count,50))return false
  if(p.photo&&!p.submitted)return false
  const r=w.report
  if(!exact(r,['submitted','status','no_fault_parts','no_parts','signed_at','process_photos','fault_photos','finish_photos','signature_photos']))return false
  if(typeof r.submitted!=='boolean'||!Object.hasOwn(REPORT_STATE_LABELS,r.status)||typeof r.no_fault_parts!=='boolean'||typeof r.no_parts!=='boolean')return false
  if(!count(r.process_photos,9)||!count(r.fault_photos,9)||!count(r.finish_photos,9)||!count(r.signature_photos,1))return false
  if(!(r.signed_at===null||instant(r.signed_at)))return false
  return r.submitted===(r.status!=='MISSING')&&(r.status==='SIGNED')===(r.signed_at!==null)
}

/** 取车对比是只读投影：没有幂等键、没有正文以外的参数，交付动作仍由核销完成。 */
export function createDeliveryCompareApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
  function call(token,order){
    if(!token)throw new ServiceError('unauthorized','请先登录车主账号')
    if(!safeId(order))throw new ServiceError('invalid','订单信息无效')
    return new Promise((resolve,reject)=>{
      try{
        runtime().request({url:baseUrl+'/api/check/delivery/compare',method:'POST',data:{order_id:order},timeout:15000,
          header:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},
          success:({statusCode,data})=>{
            if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300){
              const kinds={400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'}
              const messages={400:'订单信息无效，请返回订单详情重试',401:'登录已失效，请重新登录',403:'当前身份无权查看取车对比',404:'订单不存在或不属于本人',409:'接车检查还没完成，暂时无法对比',503:'取车对比暂不可用，请稍后重试'}
              reject(new ServiceError(kinds[statusCode]||'server',messages[statusCode]||'取车对比未成功，请重试'))
              return
            }
            // 投影必须回指请求的订单：拿到别的订单的对比结果等于展示错误的车况。
            if(data?.code!==0||!validDeliveryCompare(data.data)||data.data.order_id!==order){reject(new ServiceError('protocol','取车对比响应异常，请刷新核对'));return}
            resolve(data.data)
          },
          fail:()=>reject(serviceFailure())})
      }catch{reject(serviceFailure())}
    })
  }
  return {compare(token,order){return call(token,order)}}
}
export const deliveryCompareApi=createDeliveryCompareApi()
