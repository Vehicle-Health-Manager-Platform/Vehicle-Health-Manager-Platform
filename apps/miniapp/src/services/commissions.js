import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const id=v=>Number.isSafeInteger(v)&&v>0
const integer=v=>Number.isSafeInteger(v)&&v>=0
const money=v=>typeof v==='string'&&/^(0|[1-9]\d{0,23})\.\d{2}$/.test(v)
const signed=v=>money(v)||(typeof v==='string'&&/^-[1-9]\d{0,23}\.\d{2}$/.test(v))||(typeof v==='string'&&/^-0\.(0[1-9]|[1-9]\d)$/.test(v))
const date=v=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}T/.test(v)&&Number.isFinite(Date.parse(v))
const nullableDate=v=>v===null||date(v)
const kinds=['ACCRUAL','WAIT_REVIEW','REFUND_REVERSAL','REFUND_NO_CHARGE']
const reasons=['NEW_MERCHANT','NONE','WAIT_POLICY','WAIT_LEGACY','WAIT_COUPON','REFUND_BEFORE_COMPLETION']
const itemKeys=['entry_id','order_id','payment_id','refund_id','kind','base_amount','rate_percent','exemption','amount','currency','occurred_at','test_mode','rule_version','reference_entry_id','booked_at','waived_until']
export function validCommissionEntry(v){
 if(!exact(v,itemKeys)||!id(v.entry_id)||!id(v.order_id)||!id(v.payment_id)||(v.refund_id!==null&&!id(v.refund_id))||(v.reference_entry_id!==null&&!id(v.reference_entry_id))||!kinds.includes(v.kind)||!reasons.includes(v.exemption)||!money(v.base_amount)||v.base_amount==='0.00'||!(v.rate_percent===null||v.rate_percent==='3.00')||v.currency!=='CNY'||typeof v.test_mode!=='boolean'||v.rule_version!=='R2C_V1'||!date(v.occurred_at)||!date(v.booked_at)||!nullableDate(v.waived_until))return false
 if(v.kind==='WAIT_REVIEW')return v.amount===null&&['WAIT_POLICY','WAIT_LEGACY','WAIT_COUPON'].includes(v.exemption)
 if(v.kind==='REFUND_NO_CHARGE')return v.refund_id!==null&&v.reference_entry_id===null&&v.amount==='0.00'&&v.exemption==='REFUND_BEFORE_COMPLETION'
 if(!['NEW_MERCHANT','NONE'].includes(v.exemption)||v.rate_percent!=='3.00'||v.waived_until===null)return false
 if(v.kind==='ACCRUAL')return v.refund_id===null&&v.reference_entry_id===null&&money(v.amount)&&(v.exemption!=='NEW_MERCHANT'||v.amount==='0.00')
 return v.refund_id!==null&&v.reference_entry_id!==null&&signed(v.amount)&&!(/^[1-9]|^0\.(0[1-9]|[1-9]\d)/.test(v.amount))&&(v.exemption!=='NEW_MERCHANT'||v.amount==='0.00')
}
function validPolicy(p){if(!exact(p,['state','rate_percent','effective_from','waived_until','rule_version']))return false;if(p.state==='UNCONFIGURED')return p.rate_percent===null&&p.effective_from===null&&p.waived_until===null&&p.rule_version===null;return p.state==='CONFIGURED'&&p.rate_percent==='3.00'&&p.rule_version==='R2C_V1'&&date(p.effective_from)&&date(p.waived_until)&&Date.parse(p.waived_until)>Date.parse(p.effective_from)}
const totals=v=>exact(v,['accrued','reversed','net','pending_count'])&&money(v.accrued)&&signed(v.reversed)&&!(/^[1-9]|^0\.(0[1-9]|[1-9]\d)/.test(v.reversed))&&signed(v.net)&&integer(v.pending_count)
export function validCommissionPage(v,page,size){return exact(v,['merchant_id','policy','summary','page','page_size','total','items'])&&id(v.merchant_id)&&validPolicy(v.policy)&&exact(v.summary,['real','test'])&&totals(v.summary.real)&&totals(v.summary.test)&&v.page===page&&v.page_size===size&&integer(v.total)&&Array.isArray(v.items)&&v.items.length<=size&&v.items.every(validCommissionEntry)&&new Set(v.items.map(i=>i.entry_id)).size===v.items.length&&v.items.length===Math.max(0,Math.min(size,v.total-(page-1)*size))}
export const commissionKindLabel=k=>({ACCRUAL:'完成订单应计',WAIT_REVIEW:'待核对，金额未确定',REFUND_REVERSAL:'按原应计冲销',REFUND_NO_CHARGE:'完成前退款，未计佣'}[k]||'类型未提供')
export const commissionReasonLabel=k=>({NEW_MERCHANT:'新入驻免佣',NONE:'按下单时3%计费',WAIT_POLICY:'缺少可信佣金政策',WAIT_LEGACY:'历史订单无下单快照',WAIT_COUPON:'券金额归属待接入',REFUND_BEFORE_COMPLETION:'尚未完成，未产生应计'}[k]||'原因未提供')
export function createCommissionsApi({baseUrl,runtime}){const base=(baseUrl||'').replace(/\/$/,'');return {list(token,page=1,size=20){if(!token)throw new ServiceError('unauthorized','请先登录本店店长账号');if(!base)throw new ServiceError('unconfigured','佣金账单服务尚未配置');if(!id(page)||page>1000000||!id(size)||size>50)throw new ServiceError('invalid','分页信息无效');return new Promise((resolve,reject)=>{try{runtime().request({url:base+`/api/merchant/commissions?page=${page}&page_size=${size}`,method:'GET',timeout:15000,header:{Authorization:`Bearer ${token}`},success:({statusCode,data})=>{if(statusCode!==200){const kind={400:'invalid',401:'unauthorized',403:'forbidden',503:'unavailable'}[statusCode]||'server',message={400:'账单筛选信息无效',401:'登录已失效，请重新登录',403:'仅本店店长可查看佣金账单',503:'账单暂不可用，请稍后重试'}[statusCode]||'账单请求未成功';return reject(new ServiceError(kind,message))}if(data?.code!==0||!validCommissionPage(data.data,page,size))return reject(new ServiceError('protocol','账单响应异常，请刷新核对'));resolve(data.data)},fail:()=>reject(new ServiceError('network','账单读取失败，请重试'))})}catch{reject(serviceFailure())}})}}}
export const commissionsApi=createCommissionsApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
export const initialCommissionListState=()=>({busy:false,loaded:false,items:[],page:0,total:0,merchantId:0,policy:null,summary:null,message:'',failureKind:''})
export function createCommissionListFlow({state,token,request,size=20}){let generation=0
 function reset(){generation++;Object.assign(state,initialCommissionListState())}
 async function load(replace=true){if(state.busy)return;if(!replace&&state.loaded&&state.page*size>=state.total)return;const actor=token(),page=replace?1:state.page+1,current=++generation;state.busy=true;state.message='';state.failureKind=''
 try{const v=await request(actor,page,size);if(current!==generation||actor!==token())return;if(!replace&&state.merchantId!==v.merchant_id)throw new ServiceError('protocol','门店身份已变化，请刷新账单');const rows=replace?v.items:[...state.items,...v.items];state.items=[...new Map(rows.map(i=>[i.entry_id,i])).values()];state.page=v.page;state.total=v.total;state.merchantId=v.merchant_id;state.policy=v.policy;state.summary=v.summary;state.loaded=true}
 catch(error){if(current!==generation||actor!==token())return;const safe=error?.kind?error:serviceFailure();state.message=safe.message;state.failureKind=safe.kind}
 finally{if(current===generation)state.busy=false}}
 return {reset,load,next:()=>load(false)}
}
