import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {CASE_STATES} from './async-diagnosis.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export const DIAGNOSIS_NOTICE_LABELS={DIAGNOSIS_CREATED:'诊断留言已提交',DIAGNOSIS_MESSAGE:'诊断记录有新增留言',DIAGNOSIS_ASSIGNED:'诊断处理安排已更新',DIAGNOSIS_REPLIED:'诊断记录已有答复',DIAGNOSIS_CLOSED:'诊断记录已关闭'}
const count=n=>Number.isSafeInteger(n)&&n>=0
const role=r=>['owner','admin'].includes(r)
const stamp=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().slice(0,19)===n.slice(0,19)
const uuid=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n)
export function validDiagnosisNotifications(n,p=1,r='admin'){
 const v=n?.notifications
 return Boolean(role(r)&&exact(n,['unread_count','notifications'])&&count(n.unread_count)&&exact(v,['items','total','page','page_size'])&&count(v.total)&&n.unread_count<=v.total&&v.page===p&&v.page_size===20&&Array.isArray(v.items)&&v.items.length===Math.min(20,Math.max(0,v.total-(p-1)*20))&&new Set(v.items.map(i=>i?.notification_id)).size===v.items.length&&v.items.every(i=>exact(i,['notification_id','kind','case_id','case_status','target','occurred_at','read_at'])&&safeId(i.notification_id)&&safeId(i.case_id)&&Object.hasOwn(DIAGNOSIS_NOTICE_LABELS,i.kind)&&Object.hasOwn(CASE_STATES,i.case_status)&&(r==='owner'?i.target==='CASE':['CASE','QUEUE'].includes(i.target))&&stamp(i.occurred_at)&&(i.read_at===null||stamp(i.read_at))))
}
export function diagnosisNotificationUrl(n,r){
 if(!role(r)||!safeId(n?.case_id)||!['CASE','QUEUE'].includes(n.target)||r==='owner'&&n.target!=='CASE')return ''
 return n.target==='QUEUE'?'/pages/operator/diagnosis-cases?scope=queue':`/pages/diagnosis/detail?role=${r}&id=${n.case_id}`
}
export function createDiagnosisNotificationApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(t,r,path,check,key){if(!t)throw new ServiceError('unauthorized','请登录当前身份');if(!role(r))throw new ServiceError('invalid','诊断消息身份无效');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+`/api/${r}/diagnosis/notifications`+path,method:key?'PUT':'GET',data:key?{}:undefined,timeout:15000,header:{Authorization:`Bearer ${t}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server','诊断消息暂不可用，请刷新或重试原操作'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','诊断消息响应异常，请重新读取'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 return {list(t,r,p=1){if(!Number.isSafeInteger(p)||p<1||p>1000000)throw new ServiceError('invalid','分页无效');return call(t,r,`?page=${p}&page_size=20`,n=>validDiagnosisNotifications(n,p,r))},read(t,r,id,key){if(!safeId(id)||!uuid(key))throw new ServiceError('invalid','已读操作无效');return call(t,r,`/${id}/read`,n=>exact(n,['notification_id','read_at'])&&n.notification_id===id&&stamp(n.read_at),key)}}
}
