import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {ORDER_STATES} from './order-status.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export const NOTICE_LABELS={ORDER_PAID:'订单已付款',ORDER_CHECK_IN:'接车检查已提交，请查看本单',ORDER_ASSIGN:'订单已派工，请查看当前安排',ORDER_TECH_ACCEPT:'技师已接单，施工开始',ORDER_SERVICE_FINISH:'施工已签字完成，待核销',ORDER_COMPLETE:'订单已核销完成',ORDER_CLOSED:'订单已关闭，请查看当前原因',REFUND_REQUESTED:'退款申请已提交',REFUND_WITHDRAWN:'退款申请已撤回',REFUND_REJECTED:'退款申请未获同意，请查看本单',REFUND_ACCEPTED:'退款已受理，等待渠道确认',REFUND_SUCCEEDED:'渠道已确认退款成功，请查看本单记录',REFUND_FAILED:'渠道报告退款失败，请查看本单',DISPUTE_OPENED:'接车异议已提出',DISPUTE_HANDLED:'门店已追加异议处理记录',DISPUTE_REJECTED:'车主未接受异议处理结果',DISPUTE_RESOLVED:'异议已解决，订单已恢复'}
const count=n=>Number.isSafeInteger(n)&&n>=0
const stamp=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().slice(0,19)===n.slice(0,19)
const uuid=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n)
const role=n=>['owner','merchant','tech'].includes(n)
export function validNotifications(n,p=1){const list=n?.notifications;return Boolean(exact(n,['unread_count','notifications'])&&count(n.unread_count)&&exact(list,['items','total','page','page_size'])&&count(list.total)&&n.unread_count<=list.total&&list.page===p&&list.page_size===20&&Array.isArray(list.items)&&list.items.length===Math.min(20,Math.max(0,list.total-(p-1)*20))&&new Set(list.items.map(v=>v?.notification_id)).size===list.items.length&&list.items.every(v=>exact(v,['notification_id','kind','order_id','order_no','order_status','occurred_at','read_at'])&&safeId(v.notification_id)&&safeId(v.order_id)&&Object.hasOwn(NOTICE_LABELS,v.kind)&&typeof v.order_no==='string'&&v.order_no.length>0&&v.order_no.length<=64&&ORDER_STATES.includes(v.order_status)&&stamp(v.occurred_at)&&(v.read_at===null||stamp(v.read_at))))}
export function createNotificationApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(token,r,path,check,key){if(!token)throw new ServiceError('unauthorized','请登录当前身份');if(!role(r))throw new ServiceError('invalid','消息身份无效');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+`/api/${r}/notifications`+path,method:key?'PUT':'GET',data:key?{}:undefined,timeout:15000,header:{Authorization:`Bearer ${token}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server','站内消息暂不可用，请刷新或重试原操作'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','消息响应异常，请重新读取'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 return {list(t,r,p=1){if(!Number.isSafeInteger(p)||p<1||p>1000000)throw new ServiceError('invalid','分页无效');return call(t,r,`?page=${p}&page_size=20`,n=>validNotifications(n,p))},read(t,r,id,key){if(!safeId(id)||!uuid(key))throw new ServiceError('invalid','已读操作无效');return call(t,r,`/${id}/read`,n=>exact(n,['notification_id','read_at'])&&n.notification_id===id&&stamp(n.read_at),key)}}
}
export function initialNotificationState(){return {busy:false,data:null,page:1,message:'',kind:'',retry:false}}
export function createNotificationFlow({state,token,role,api=createNotificationApi(),newKey}={}){
 let epoch=0,pending=null
 const current=(e,t,r)=>e===epoch&&t===token()&&r===role()
 function reset(){epoch++;Object.assign(state,initialNotificationState());if(pending&&(pending.token!==token()||pending.role!==role()))pending=null}
 async function load(p=1){if(state.busy||!token())return;const e=++epoch,t=token(),r=role();Object.assign(state,{busy:true,data:null,page:p,message:'',kind:'',retry:Boolean(pending)});try{const result=await api.list(t,r,p);if(current(e,t,r))state.data=result}catch(err){if(current(e,t,r)){state.message=err.message;state.kind=err.kind||'server'}}finally{if(current(e,t,r))state.busy=false}}
 async function retry(){if(state.busy||!pending||pending.token!==token()||pending.role!==role())return;const e=++epoch,t=token(),r=role();state.busy=true;try{await api.read(t,r,pending.id,pending.key);if(!current(e,t,r))return;pending=null;state.retry=false;state.busy=false;await load(state.page)}catch(err){if(current(e,t,r)){state.data=null;state.kind=err.kind||'server';state.message=err.message;state.retry=true;if(['invalid','unauthorized','forbidden','missing','conflict'].includes(state.kind)){pending=null;state.retry=false}}}finally{if(current(e,t,r))state.busy=false}}
 async function read(id){if(state.busy||pending||!token()||!state.data?.notifications.items.some(n=>n.notification_id===id&&n.read_at===null))return;pending={token:token(),role:role(),id,key:newKey()};await retry()}
 return {load,read,retry,reset}
}
