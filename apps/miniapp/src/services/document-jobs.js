import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'

import {AsrError} from './asr-jobs.js'
import {documentResult} from './document-result.js'
const id=v=>Number.isSafeInteger(v)&&v>0&&v<9007199254740991
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const uuid=v=>typeof v==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)
const bad=()=>new AsrError('protocol','图文提取服务响应异常，请手动填写')
export function documentInput(scope,materialId){
 if(!scope||!id(scope.vehicleId)||!['AI','ARCHIVE'].includes(scope.purpose)||!id(materialId))throw new AsrError('invalid','请先选择当前车辆及已上传的本人图片/PDF')
 return {vehicle_id:scope.vehicleId,material_id:materialId,purpose:scope.purpose,consent:true,consent_version:'DOCUMENT_EXTRACT_V1'}
}
export function documentCreated(value,input){
 if(!exact(value,['job_id','vehicle_id','material_id','purpose','task'])||!id(value.job_id)||value.task!=='DOCUMENT'||value.vehicle_id!==input.vehicle_id||value.material_id!==input.material_id||value.purpose!==input.purpose)throw bad()
 return Object.freeze({...value})
}
export function documentJob(value,created,metadata){
 if(!exact(value,['job_id','vehicle_id','material_id','purpose','task','state','expires_at','error_code','result']))throw bad()
 documentCreated(Object.fromEntries(['job_id','vehicle_id','material_id','purpose','task'].map(k=>[k,value[k]])),created)
 if(value.job_id!==created.job_id||typeof value.expires_at!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value.expires_at)||!Number.isFinite(Date.parse(value.expires_at))||new Date(value.expires_at).toISOString().replace('.000Z','Z')!==value.expires_at)throw bad()
 const errors={QUEUED:[null],RUNNING:[null],SUCCEEDED:[null],FAILED:['UNAVAILABLE','SOURCE_CHANGED','WORKER_LOST','INVALID_RESULT','INVALID_DOCUMENT','LIMIT_EXCEEDED'],CANCELLED:['AUTH_REVOKED','USER_CANCELLED','FEATURE_DISABLED'],EXPIRED:['EXPIRED']}
 if(!Object.hasOwn(errors,value.state)||!errors[value.state].includes(value.error_code))throw bad()
 const result=value.state==='SUCCEEDED'?documentResult(value.result,metadata):null
 if(value.state!=='SUCCEEDED'&&value.result!==null)throw bad()
 return Object.freeze({...value,result})
}
export function createDocumentApi({baseUrl,runtime}){
 function request(token,path,method,data,key,validate){
  let task,done=false,rejectPending
  const promise=new Promise((resolve,reject)=>{
   rejectPending=reject
   const finish=(error,value)=>{if(done)return;done=true;error?reject(error):resolve(value)}
   if(!token)return finish(new AsrError('unauthorized','请先登录车主账号'))
   if(!baseUrl)return finish(new AsrError('unconfigured','图文提取服务尚未配置，请手动填写'))
   if(method==='POST'&&!uuid(key))return finish(new AsrError('invalid','图文提取请求信息无效，请重新进入'))
   try{task=runtime().request({url:baseUrl.replace(/\/$/,'')+path,method,data,timeout:15000,header:{Authorization:`Bearer ${token}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},
    success:({statusCode,data:body})=>{if(done)return
     const errors={400:['invalid','请检查图片/PDF、用途及图文提取同意'],401:['unauthorized','登录已失效，请重新登录'],403:['forbidden','请使用车主账号操作'],404:['missing','车辆、图片/PDF或任务不可用'],409:['conflict','当前任务状态冲突，请查询后重试'],429:['limited','图文提取任务频繁或已有任务进行中，请稍后重试'],503:['unavailable','图文提取服务未配置或暂不可用，请手动填写']}
     if(statusCode!==200){const [kind,message]=errors[statusCode]||['server','图文提取请求未成功，请稍后确认原请求'];finish(new AsrError(kind,message));return}
     try{if(body?.code!==0)throw bad();finish(null,validate(body.data))}catch{finish(bad())}
    },fail:()=>finish(new AsrError('network','连接失败或超时，原请求可能已接收，请使用原请求确认'))})
   }catch{finish(new AsrError('network','无法连接图文提取服务，请稍后确认原请求'))}
  })
  return {promise,abort(){if(done)return;done=true;try{task?.abort?.()}catch{}rejectPending(new AsrError('cancelled','已停止接收，服务端任务可能仍在处理'))}}
 }
 return {
  create(token,input,key){if(!exact(input,['vehicle_id','material_id','purpose','consent','consent_version'])||input.consent!==true||input.consent_version!=='DOCUMENT_EXTRACT_V1')throw new AsrError('invalid','请核对图文提取用途同意');documentInput({vehicleId:input.vehicle_id,purpose:input.purpose},input.material_id);return request(token,'/api/recognition/documents/jobs','POST',input,key,value=>documentCreated(value,input))},
  get(token,created,metadata){if(!id(created?.job_id))throw new AsrError('invalid','任务信息无效');return request(token,`/api/recognition/documents/jobs/${created.job_id}`,'GET',undefined,undefined,value=>documentJob(value,created,metadata))},
  cancel(token,jobId,key){if(!id(jobId))throw new AsrError('invalid','任务信息无效');return request(token,`/api/recognition/documents/jobs/${jobId}/cancel`,'POST',{},key,value=>{if(!exact(value,['job_id'])||value.job_id!==jobId)throw bad();return Object.freeze({...value})})},
 }
}
export const documentApi=createDocumentApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
