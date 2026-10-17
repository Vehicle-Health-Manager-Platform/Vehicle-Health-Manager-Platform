import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
import {createInvitePolicyFlow,initialInvitePolicyState} from './invite-policies.js'
const utc=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
const uuid=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n)
export const diagnosisLabels={UNKNOWN:'解锁规则尚未配置',NOT_STARTED:'规则尚未生效',EXPIRED:'规则已到期',LOCKED:'尚未达到本月邀请条件',ELIGIBLE:'已满足当前邀请条件'}
export function validDiagnosisPolicy(n){return exact(n,['id','revision','status','required_count','source_note','valid_from','valid_to','created_at','published_at','rule_version'])&&safeId(n.id)&&safeId(n.revision)&&['DRAFT','PUBLISHED','STOPPED'].includes(n.status)&&Number.isInteger(n.required_count)&&n.required_count>=1&&n.required_count<=1000000&&typeof n.source_note==='string'&&n.source_note===n.source_note.trim()&&[...n.source_note].length>=2&&[...n.source_note].length<=256&&!/[\u0000-\u001f\u007f]/.test(n.source_note)&&utc(n.valid_from)&&utc(n.valid_to)&&Date.parse(n.valid_from)<Date.parse(n.valid_to)&&utc(n.created_at)&&(n.status==='DRAFT'?n.published_at===null:utc(n.published_at))&&n.rule_version==='R6B_DIAGNOSIS_V1'}
export function validDiagnosisEligibility(n){
 if(!exact(n,['month','timezone','qualified_count','state','policy','usable_until','service_available','rule_version'])||!/^\d{4}-(0[1-9]|1[0-2])$/.test(n.month)||n.timezone!=='Asia/Shanghai'||!Number.isSafeInteger(n.qualified_count)||n.qualified_count<0||!Object.hasOwn(diagnosisLabels,n.state)||typeof n.service_available!=='boolean'||n.service_available&&n.state!=='ELIGIBLE'||n.rule_version!=='R6B_DIAGNOSIS_V1')return false
 if(n.state==='UNKNOWN')return n.policy===null&&n.usable_until===null
 if(!validDiagnosisPolicy(n.policy)||n.policy.status!=='PUBLISHED')return false
 if(n.state==='ELIGIBLE'){
  // Calendar arithmetic uses a UTC month anchor, then subtracts the fixed Beijing offset.
  const [y,m]=n.month.split('-').map(Number),boundary=Date.UTC(y,m,1)-8*3600000
  return n.qualified_count>=n.policy.required_count&&utc(n.usable_until)&&Date.parse(n.usable_until)===Math.min(Date.parse(n.policy.valid_to),boundary)
 }
 return n.usable_until===null&&(n.state!=='LOCKED'||n.qualified_count<n.policy.required_count)
}
export function createDiagnosisApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(t,path,method,body,key,check){if(!t)throw new ServiceError('unauthorized','请先登录');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+path,method,data:body,timeout:15000,header:{Authorization:`Bearer ${t}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',409:'conflict',503:'unavailable'})[statusCode]||'server','解锁规则请求失败，请刷新或重试原操作'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','解锁规则响应异常'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 return {current:t=>call(t,'/api/invite/diagnosis-eligibility','GET',undefined,null,validDiagnosisEligibility),list:(t,p=1)=>{if(!Number.isSafeInteger(p)||p<1||p>1000000)throw new ServiceError('invalid','分页无效');return call(t,`/api/admin/invite/diagnosis-policies?page=${p}&page_size=20`,'GET',undefined,null,n=>exact(n,['items','total','page','page_size'])&&n.page===p&&n.page_size===20&&Number.isSafeInteger(n.total)&&n.total>=0&&Array.isArray(n.items)&&n.items.every(validDiagnosisPolicy)&&new Set(n.items.map(r=>r.id)).size===n.items.length&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20)))},write:(t,kind,id,b,key)=>{if(!uuid(key)||!['create','update','status'].includes(kind)||kind!=='create'&&!safeId(id))throw new ServiceError('invalid','规则操作无效');return call(t,'/api/admin/invite/diagnosis-policies'+(kind==='create'?'':`/${id}`)+(kind==='status'?'/status':''),kind==='create'?'POST':'PUT',b,key,n=>validDiagnosisPolicy(n)&&(kind==='create'?n.status==='DRAFT'&&n.revision===1:n.id===id&&n.revision===b.expected_revision+1&&n.status===(kind==='status'?b.status:'DRAFT'))&&Object.keys(b).filter(k=>!['expected_revision','status'].includes(k)).every(k=>n[k]===b[k]))}}
}
export const initialDiagnosisPolicyState=initialInvitePolicyState
export function createDiagnosisPolicyFlow(options){const api=options.api||createDiagnosisApi();return createInvitePolicyFlow({...options,api:{list:(t,p,offers)=>offers?Promise.resolve({items:[],total:0,page:p,page_size:20}):api.list(t,p),write:api.write}})}
export function initialEligibilityState(){return {busy:false,data:null,message:'',kind:''}}
export function createEligibilityFlow({state,token,api=createDiagnosisApi()}={}){let epoch=0;const current=(e,t)=>e===epoch&&t===token();function reset(){epoch++;Object.assign(state,initialEligibilityState())}async function load(){if(state.busy||!token())return;const e=++epoch,t=token();state.busy=true;state.message='';state.kind='';try{const n=await api.current(t);if(current(e,t))state.data=n}catch(err){if(current(e,t)){state.data=null;state.message=err.message;state.kind=err.kind||'server'}}finally{if(current(e,t))state.busy=false}}return {load,reset}}
