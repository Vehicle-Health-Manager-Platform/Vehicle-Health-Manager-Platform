import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {QUESTION_CATEGORIES} from './community-questions.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
import {createScopedIntentRecovery,intentUuid} from './scoped-intent-recovery.js'
import {TEXT_REASONS as REVIEW_REASONS} from './public-review-texts.js'

export const PUBLIC_QA_CONSENT='R8C_PUBLIC_QA_V1'
export const PUBLIC_QA_STATES={PENDING:'待审核',APPROVED:'已批准',REJECTED:'已驳回',WITHDRAWN:'已撤回'}
const stamp=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
const text=n=>typeof n==='string'&&[...n].length>0&&[...n].length<=300&&!/[\u0000-\u0009\u000b-\u001f\u007f\p{Cf}\p{Cs}]/u.test(n)
export const validQaPreview=n=>Boolean(exact(n,['model_id','category','question_text','answer_text','redaction_version'])&&safeId(n.model_id)&&Object.hasOwn(QUESTION_CATEGORIES,n.category)&&text(n.question_text)&&text(n.answer_text)&&n.redaction_version==='QA_TEXT_REDACTION_V1')
const hash=n=>typeof n==='string'&&/^[a-f0-9]{64}$/.test(n)
const receipt=n=>exact(n,['application_id','revision','status'])&&safeId(n.application_id)&&safeId(n.revision)&&Object.hasOwn(PUBLIC_QA_STATES,n.status)
const preview=n=>n===null||validQaPreview(n)
const publicRow=n=>exact(n,['public_id','model_id','category','question_text','answer_text','published_at'])&&intentUuid(n.public_id)&&safeId(n.model_id)&&Object.hasOwn(QUESTION_CATEGORIES,n.category)&&text(n.question_text)&&text(n.answer_text)&&stamp(n.published_at)
const validOwn=n=>exact(n,['application_id','revision','status','preview','public_id','reason_code','visible','created_at'])&&receipt({application_id:n.application_id,revision:n.revision,status:n.status})&&preview(n.preview)&&(n.public_id===null||intentUuid(n.public_id))&&(n.reason_code===null||Object.hasOwn(REVIEW_REASONS,n.reason_code))&&typeof n.visible==='boolean'&&(!n.visible||n.status==='APPROVED'&&n.preview!==null&&intentUuid(n.public_id))&&stamp(n.created_at)
const validPending=n=>exact(n,['application_id','revision','status','preview','created_at','source_available'])&&safeId(n.application_id)&&safeId(n.revision)&&n.status==='PENDING'&&preview(n.preview)&&stamp(n.created_at)&&typeof n.source_available==='boolean'&&(!n.source_available||n.preview!==null)
const page=(n,p,check)=>exact(n,['items','total','page','page_size'])&&Number.isSafeInteger(n.total)&&n.total>=0&&n.page===p&&n.page_size===20&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(check)&&new Set(n.items.map(v=>v.application_id)).size===n.items.length
export function validQaBody(b,action){
 if(action==='submit')return exact(b,['question_message_id','answer_message_id','preview_hash','consent_version','consent'])&&safeId(b.question_message_id)&&safeId(b.answer_message_id)&&b.question_message_id<b.answer_message_id&&hash(b.preview_hash)&&b.consent_version===PUBLIC_QA_CONSENT&&b.consent===true
 if(action==='withdraw')return exact(b,['expected_revision'])&&safeId(b.expected_revision)
 return action==='moderate'&&exact(b,['expected_revision','decision','reason_code'])&&safeId(b.expected_revision)&&['APPROVE','REJECT'].includes(b.decision)&&(b.decision==='APPROVE'?b.reason_code===null:Object.hasOwn(REVIEW_REASONS,b.reason_code))
}
export function publicQaRoute(q){
 if(exact(q,['mode','case_id'])&&q.mode==='owner'&&/^[1-9]\d*$/.test(q.case_id)&&safeId(Number(q.case_id)))return {mode:'owner',caseId:Number(q.case_id),publicId:''}
 if(exact(q,['mode'])&&['review','public'].includes(q.mode))return {mode:q.mode,caseId:0,publicId:''}
 if(exact(q,['mode','public_id'])&&q.mode==='public'&&intentUuid(q.public_id))return {mode:'public',caseId:0,publicId:q.public_id}
 return null
}
export const publicQaReference=id=>intentUuid(id)?`/pages/community/public-questions?mode=public&public_id=${id}`:''
export function createPublicQaApi({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}){
 const root=baseUrl.replace(/\/$/,'')
 function call(t,path,check,b,key,method='GET'){
  if(!t)throw new ServiceError('unauthorized','请先登录当前身份')
  if(!root)throw new ServiceError('unconfigured','公开问答尚未配置')
  return new Promise((resolve,reject)=>{try{runtime().request({url:root+path,method,data:b,timeout:15000,header:{Authorization:`Bearer ${t}`,'Content-Type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
   if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server','公开问答暂不可用，请核对权限、来源和当前版本'))
   if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','公开问答响应异常，请重新读取'))
   resolve(data.data)
  },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
 }
 const id=n=>{if(!safeId(n))throw new ServiceError('invalid','编号无效')}
 const pg=p=>{if(!safeId(p)||p>1000000)throw new ServiceError('invalid','页码无效')}
 return {
  preview(t,c,q,a){id(c);id(q);id(a);if(q>=a)throw new ServiceError('invalid','请选择问题和后续答复');return call(t,`/api/owner/community/questions/cases/${c}/publication-preview`,n=>exact(n,['preview','preview_hash'])&&validQaPreview(n.preview)&&hash(n.preview_hash),{question_message_id:q,answer_message_id:a},null,'POST')},
  own(t,c,p=1){id(c);pg(p);return call(t,`/api/owner/community/questions/cases/${c}/publications?page=${p}&page_size=20`,n=>page(n,p,validOwn))},
  pending(t,p=1){pg(p);return call(t,`/api/admin/community/question-publications?page=${p}&page_size=20`,n=>page(n,p,validPending))},
  write(t,action,n,b,key){id(n);if(!intentUuid(key)||!validQaBody(b,action))throw new ServiceError('invalid','公开问答操作无效');const path=action==='submit'?`/api/owner/community/questions/cases/${n}/publications`:action==='withdraw'?`/api/owner/community/question-publications/${n}/withdraw`:`/api/admin/community/question-publications/${n}/moderate`;return call(t,path,v=>receipt(v)&&(action==='submit'?v.revision===1&&v.status==='PENDING':v.application_id===n&&v.revision===b.expected_revision+1&&v.status===(action==='withdraw'?'WITHDRAWN':b.decision==='APPROVE'?'APPROVED':'REJECTED')),b,key,'POST')},
  list(t,v,model,cursor=null){id(v);id(model);if(cursor!==null)id(cursor);return call(t,`/api/community/questions?vehicle_id=${v}${cursor===null?'':`&cursor=${cursor}`}`,n=>exact(n,['vehicle_id','items','next_cursor'])&&n.vehicle_id===v&&Array.isArray(n.items)&&n.items.length<=20&&n.items.every(r=>publicRow(r)&&r.model_id===model)&&new Set(n.items.map(r=>r.public_id)).size===n.items.length&&(n.next_cursor===null||safeId(n.next_cursor)&&(cursor===null||n.next_cursor<cursor)))},
  detail(t,v,model,reference){id(v);id(model);if(!intentUuid(reference))throw new ServiceError('invalid','公开引用无效');return call(t,`/api/community/questions/${reference}?vehicle_id=${v}`,n=>exact(n,['vehicle_id','question'])&&n.vehicle_id===v&&publicRow(n.question)&&n.question.model_id===model&&n.question.public_id===reference)},
 }
}
export function createPublicQaRecovery(options){return createScopedIntentRecovery({...options,namespace:'public-community',permanent:v=>v.action==='submit',validate:(v,r,s)=>validQaBody(v.body,v.action)&&safeId(v.id)&&(r==='admin'?s==='review'&&v.action==='moderate':s.startsWith('case:')&&(v.action==='submit'?v.id===Number(s.slice(5)):v.action==='withdraw'))})}
