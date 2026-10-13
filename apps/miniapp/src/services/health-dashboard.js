import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { exact, safeId } from './experience-publication.js'
import { healthMetrics, healthUnits, validObservation } from './health-observations.js'
import { validVehicleEvidence } from './vehicle-plan-evidence.js'
export const healthStateLabels = { MODEL_UNKNOWN:'有效车型未知',POLICY_NOT_SET:'暂无来源规则',POLICY_INVALID:'来源规则不可用',POLICY_EXPIRED:'来源规则已过期',NOT_APPLICABLE:'来源规则：不适用',SOURCE_UNAVAILABLE:'原始来源已失效',NOT_SET:'未录入（未知）',EVIDENCE_INVALID:'读数格式不可用',NOT_COMPARABLE:'名称、单位、方法或抄录口径不匹配',EVIDENCE_EXPIRED:'读数超过规则有效期',CONFIRMATION_REQUIRED:'需本人核对并确认用途',REFERENCE_READY:'来源规则参考分可用' }
export const reminderKinds = ['FAULT','MAINTENANCE','INSURANCE','INSPECTION','VIOLATION']
export const reminderLabels = { FAULT:'本人故障记录',MAINTENANCE:'保养目标',INSURANCE:'保险目标',INSPECTION:'年检目标',VIOLATION:'本人违章记录' }
export const reminderStateLabels = { NOT_SET:'暂无本人来源',UNAVAILABLE:'来源失效',INACTIVE:'提醒已关闭',UNKNOWN:'目标信息不完整',PLANNED:'已设置，尚未触发',DUE:'已到本人目标',UPCOMING:'进入本人提前范围',ATTENTION:'有本人记录待关注',UNCONFIRMED:'来源或状态尚未确认',CLEAR:'当前本人确认记录无待办' }
const integer = (n,min,max) => Number.isInteger(n) && n >= min && n <= max
const revision = n => integer(n,0,2147483647)
const version = n => Number.isSafeInteger(n) && n >= 0
const text = (s,max) => typeof s === 'string' && s.trim() === s && [...s].length > 0 && [...s].length <= max && !/[\p{Cc}\p{Cf}\p{Cs}]/u.test(s)
const date = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && Number(s.slice(0,4))>=1000 && Number.isFinite(Date.parse(s)) && new Date(s).toISOString().slice(0,10)===s
const decimal = s => typeof s==='string' && /^-?(0|[1-9]\d{0,8})(\.[0-9]{1,3})?$/.test(s)
const matches = (m,r) => ['measurement_name','unit','method','provenance'].every(k=>m[k]===r[k])
export function validHealthRule(r,metric) {
  if(!exact(r,['applicability','source_reference','source_checked_on','valid_until','source_confirmed','measurement_name','unit','method','provenance','max_age_days','weight','bands','action_project_id','action_score_below'])||!text(r.source_reference,256)||!date(r.source_checked_on)||!date(r.valid_until)||r.source_checked_on>r.valid_until||r.source_confirmed!==true||!Array.isArray(r.bands))return false
  if(r.applicability==='NOT_APPLICABLE')return ['measurement_name','unit','method','provenance','action_project_id','action_score_below'].every(k=>r[k]===null)&&r.max_age_days===0&&r.weight===0&&r.bands.length===0
  if(r.applicability!=='APPLICABLE'||!text(r.measurement_name,80)||!text(r.method,120)||!healthUnits[metric]?.includes(r.unit)||!['OWNER_MEASURED','OWNER_TRANSCRIBED'].includes(r.provenance)||!integer(r.max_age_days,1,3650)||!integer(r.weight,1,100)||r.bands.length<1||r.bands.length>20)return false
  let prior=null
  for(let i=0;i<r.bands.length;i++){const b=r.bands[i];if(!exact(b,['upper_bound','score'])||!integer(b.score,0,100))return false;if(i===r.bands.length-1){if(b.upper_bound!==null)return false}else{if(!decimal(b.upper_bound)||prior!==null&&Number(b.upper_bound)<=prior||r.unit!=='CELSIUS'&&Number(b.upper_bound)<0||r.unit==='PERCENT'&&Number(b.upper_bound)>100)return false;prior=Number(b.upper_bound)}}
  return r.action_project_id===null?r.action_score_below===null:safeId(r.action_project_id)&&integer(r.action_score_below,1,100)
}
export const validPolicy = (r,model,metric) => exact(r,['model_id','metric','version','rule'])&&r.model_id===model&&r.metric===metric&&version(r.version)&&(r.rule===null||r.version>0&&validHealthRule(r.rule,metric))
export function validPreference(r){return exact(r,['maintenance_days','maintenance_km','insurance_days','inspection_days','maintenance_project_id','revision'])&&['maintenance_days','insurance_days','inspection_days'].every(k=>integer(r[k],0,365))&&integer(r.maintenance_km,0,100000)&&revision(r.revision)&&(r.maintenance_project_id===null||safeId(r.maintenance_project_id))}
function validTrend(t,row,today){if(!exact(t,['status','points','delta','truncated'])||typeof t.truncated!=='boolean'||!Array.isArray(t.points)||t.points.length>51||!['NOT_AVAILABLE','INSUFFICIENT','COMPARABLE_UNVERIFIED'].includes(t.status))return false;let prior='';for(const p of t.points){if(!exact(p,['revision','archive_id','measured_on','value','unit'])||!revision(p.revision)||p.revision<1||!safeId(p.archive_id)||!date(p.measured_on)||p.measured_on>today||p.measured_on<=prior||!decimal(p.value)||p.unit!==row.rule?.unit)return false;prior=p.measured_on}
 if(t.status==='NOT_AVAILABLE')return t.points.length===0&&t.delta===null
 if(t.status==='INSUFFICIENT')return t.points.length<2&&t.delta===null
 return t.points.length>=2&&typeof t.delta==='string'&&/^-?(0|[1-9]\d{0,9})(\.[0-9]{1,3})?$/.test(t.delta)&&Math.round(Number(t.delta)*1000)===Math.round(Number(t.points.at(-1).value)*1000)-Math.round(Number(t.points[0].value)*1000)
}
function validAction(a,archive){return exact(a,['type','archive_id'])&&['ARCHIVE','TARGET'].includes(a.type)&&a.archive_id===archive||exact(a,['type'])&&a.type==='SERVICES'||exact(a,['type','project_id'])&&a.type==='PROJECT'&&safeId(a.project_id)}
export function validHealthDashboard(r,vehicle){
 if(!exact(r,['vehicle','today','overall','metrics','required_projects','reminders','preference'])||!date(r.today)||!Array.isArray(r.metrics)||r.metrics.length!==6||!validPreference(r.preference))return false
 const raw=r.metrics.map(x=>x?.observation)
 if(!validVehicleEvidence({vehicle:r.vehicle,observations:{vehicle_id:vehicle,today:r.today,list:raw},experiences:{items:[],next_cursor:null},applicability:[]},vehicle,[]))return false
 let weights=0,sum=0,complete=true
 for(let i=0;i<6;i++){const row=r.metrics[i];if(!exact(row,['metric','status','rule_version','rule','observation','confirmation','score','trend','action_status'])||row.metric!==healthMetrics[i]||!Object.hasOwn(healthStateLabels,row.status)||!version(row.rule_version)||!(row.rule===null||row.rule_version>0&&validHealthRule(row.rule,row.metric))||!validObservation(row.observation,r.today)||!exact(row.confirmation,['revision','confirmed'])||!revision(row.confirmation.revision)||typeof row.confirmation.confirmed!=='boolean'||!validTrend(row.trend,row,r.today)||!['NONE','EVIDENCE_UNKNOWN','NOT_TRIGGERED','PROJECT_UNAVAILABLE','APPLICABILITY_UNKNOWN','CONFLICTING_APPLICABILITY','TRIGGERED'].includes(row.action_status))return false
  if(row.status==='REFERENCE_READY'){const rule=row.rule,m=row.observation.measurement;if(!safeId(r.vehicle.model_id)||!rule||rule.applicability!=='APPLICABLE'||row.observation.status!=='RECORDED_UNVERIFIED'||!row.confirmation.confirmed||row.confirmation.revision<1||rule.valid_until<r.today||rule.source_checked_on>r.today||!matches(m,rule)||(Date.parse(r.today)-Date.parse(m.measured_on))/86400000>rule.max_age_days||!integer(row.score,0,100)||row.score!==rule.bands.find(b=>b.upper_bound===null||Number(m.value)<=Number(b.upper_bound))?.score)return false;weights+=rule.weight;sum+=rule.weight*row.score}
  else {if(row.score!==null||row.confirmation.confirmed)return false;if(row.status!=='NOT_APPLICABLE')complete=false}
  if(row.status==='NOT_APPLICABLE'&&(!safeId(r.vehicle.model_id)||row.rule?.applicability!=='NOT_APPLICABLE'||row.rule.valid_until<r.today||row.rule.source_checked_on>r.today))return false
  if(row.action_status==='TRIGGERED'&&(row.status!=='REFERENCE_READY'||!safeId(row.rule.action_project_id)||row.score>=row.rule.action_score_below))return false
 }
 const o=r.overall;if(!exact(o,['status','score','weighted_sum','weight_total']))return false
 if(complete&&weights>0){if(o.status!=='REFERENCE_READY'||o.score!==Math.round(sum/weights)||o.weighted_sum!==sum||o.weight_total!==weights)return false}else if(o.status!=='UNKNOWN'||o.score!==null||o.weighted_sum!==null||o.weight_total!==null)return false
 if(!Array.isArray(r.required_projects)||r.required_projects.length>6||new Set(r.required_projects.map(x=>x?.project_id)).size!==r.required_projects.length)return false
 for(const p of r.required_projects){if(!exact(p,['project_id','project_name','category','tier','evidence'])||!safeId(p.project_id)||!text(p.project_name,128)||!integer(p.category,1,6)||p.tier!=='MUST'||!Array.isArray(p.evidence)||p.evidence.length<1||p.evidence.length>6||new Set(p.evidence.map(x=>x?.metric)).size!==p.evidence.length)return false
  for(const e of p.evidence){const row=r.metrics.find(x=>x.metric===e?.metric);if(!row||row.action_status!=='TRIGGERED'||!exact(e,['metric','policy_version','observation_revision','confirmation_revision','action_rule_version','value','unit','measured_on','source_reference','project_source_reference'])||e.policy_version!==row.rule_version||e.observation_revision!==row.observation.revision||e.confirmation_revision!==row.confirmation.revision||e.value!==row.observation.measurement.value||e.unit!==row.observation.measurement.unit||e.measured_on!==row.observation.measurement.measured_on||e.source_reference!==row.rule.source_reference||!safeId(e.action_rule_version)||!text(e.project_source_reference,256)||row.rule.action_project_id!==p.project_id)return false}
 }
 if(r.metrics.filter(row=>row.action_status==='TRIGGERED').some(row=>!r.required_projects.some(p=>p.project_id===row.rule.action_project_id&&p.evidence.some(e=>e.metric===row.metric))))return false
 if(!Array.isArray(r.reminders)||r.reminders.length!==5)return false
 return r.reminders.every((g,i)=>exact(g,['kind','status','items','limited'])&&g.kind===reminderKinds[i]&&Object.hasOwn(reminderStateLabels,g.status)&&typeof g.limited==='boolean'&&Array.isArray(g.items)&&g.items.length<=200&&g.items.every(a=>exact(a,['kind','severity','title','archive_id','source_revision','source_reference','remaining_days','remaining_km','preference_revision','action'])&&a.kind===g.kind&&['OWNER_URGENT','DUE','PENDING','UPCOMING'].includes(a.severity)&&text(a.title,400)&&safeId(a.archive_id)&&revision(a.source_revision)&&a.source_revision>0&&text(a.source_reference,256)&&a.preference_revision===r.preference.revision&&(a.remaining_days===null||Number.isSafeInteger(a.remaining_days))&&(a.remaining_km===null||Number.isSafeInteger(a.remaining_km))&&validAction(a.action,a.archive_id)))
}
export function createHealthDashboardApi({baseUrl,runtime}){
 const endpoint=(baseUrl||'').replace(/\/$/,'')
 function call(token,path,method,body,key,validate){if(!token)throw new ServiceError('unauthorized','请先登录对应账号');if(!endpoint)throw new ServiceError('unconfigured','健康来源服务尚未配置');return new Promise((resolve,reject)=>{try{runtime().request({url:endpoint+path,method,data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server',statusCode===409?'来源或版本已变化，请重新读取并确认':'健康来源请求未成功，请重试'));try{if(data?.code!==0||!validate(data.data))return reject(new ServiceError('protocol','健康来源响应异常，请重新读取'))}catch{return reject(new ServiceError('protocol','健康来源响应异常，请重新读取'))}resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 const uuid=key=>typeof key==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key)
 return {
  read(token,vehicle){if(!safeId(vehicle))throw new ServiceError('invalid','请重新选择本人车辆');return call(token,`/api/vehicle/${vehicle}/health-dashboard`,'GET',undefined,null,r=>validHealthDashboard(r,vehicle))},
  policy(token,model,metric){if(!safeId(model)||!healthMetrics.includes(metric))throw new ServiceError('invalid','请选择有效车型和指标');return call(token,`/api/admin/health-policies/${model}/${metric}`,'GET',undefined,null,r=>validPolicy(r,model,metric))},
  savePolicy(token,model,metric,body,key){if(!safeId(model)||!healthMetrics.includes(metric)||!uuid(key)||!exact(body,['expected_version','rule'])||!version(body.expected_version)||body.expected_version===Number.MAX_SAFE_INTEGER||!(body.rule===null||validHealthRule(body.rule,metric)))throw new ServiceError('invalid','请核对来源规则和版本');return call(token,`/api/admin/health-policies/${model}/${metric}`,'PUT',body,key,r=>validPolicy(r,model,metric)&&r.version===body.expected_version+1)},
  confirm(token,vehicle,row,confirmed,key){if(!safeId(vehicle)||!healthMetrics.includes(row?.metric)||!uuid(key)||typeof confirmed!=='boolean'||!revision(row.confirmation?.revision)||row.confirmation.revision===2147483647)throw new ServiceError('invalid','请重新读取本车来源');const body={expected_revision:row.confirmation.revision,confirmed,observation_revision:row.observation.revision,policy_version:row.rule_version};return call(token,`/api/vehicle/${vehicle}/health/${row.metric}/confirmation`,'PUT',body,key,r=>exact(r,['vehicle_id','metric','revision'])&&r.vehicle_id===vehicle&&r.metric===row.metric&&r.revision===body.expected_revision+1)},
  preference(token,vehicle,body,key){if(!safeId(vehicle)||!uuid(key)||!exact(body,['expected_revision','maintenance_days','maintenance_km','insurance_days','inspection_days','maintenance_project_id'])||!revision(body.expected_revision)||body.expected_revision===2147483647||!validPreference({...Object.fromEntries(Object.entries(body).filter(([k])=>k!=='expected_revision')),revision:body.expected_revision}))throw new ServiceError('invalid','请检查提前天数、里程和版本');return call(token,`/api/vehicle/${vehicle}/reminder-preference`,'PUT',body,key,r=>validPreference(r)&&r.revision===body.expected_revision+1)},
 }
}
export const healthDashboardApi=createHealthDashboardApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
export const initialHealthDashboardState=()=>({busy:false,value:null,message:'',kind:''})
export function createHealthDashboardFlow({state,token,vehicle,newKey,confirm,api=healthDashboardApi}){
 let epoch=0,pending=null
 const task=()=>{const g=++epoch,actor=token(),car=vehicle();return{actor,car,valid:()=>g===epoch&&actor===token()&&car===vehicle()}}
 function reset(){epoch++;pending=null;Object.assign(state,initialHealthDashboardState())}
 function failure(e){const safe=e instanceof ServiceError?e:serviceFailure();state.message=safe.message;state.kind=safe.kind;if(['unauthorized','forbidden','missing','conflict'].includes(safe.kind)){state.value=null;pending=null}}
 async function load(){if(state.busy)return;const t=task();state.busy=true;state.value=null;state.message='';state.kind='';try{const r=await api.read(t.actor,t.car);if(t.valid())state.value=r}catch(e){if(t.valid())failure(e)}finally{if(t.valid())state.busy=false}}
 async function write(kind,value){if(state.busy||!state.value)return;const t=task();const signature=JSON.stringify([t.actor,t.car,kind,value]);if(!pending||pending.signature!==signature)pending={signature,key:newKey()};state.busy=true;state.message='';state.kind='';try{if(!await confirm(kind,value)||!t.valid())return;if(kind==='CONFIRM')await api.confirm(t.actor,t.car,value.row,value.confirmed,pending.key);else await api.preference(t.actor,t.car,value,pending.key);if(!t.valid())return;pending=null;state.value=null;const r=await api.read(t.actor,t.car);if(t.valid()){state.value=r;state.message='已保存并重新核对当前来源'}}catch(e){if(t.valid())failure(e)}finally{if(t.valid())state.busy=false}}
 return{reset,load,confirmRow:(row,confirmed)=>state.value?.metrics.includes(row)?write('CONFIRM',{row,confirmed}):undefined,preference:body=>write('PREFERENCE',body)}
}
export const initialHealthPolicyState=()=>({busy:false,value:null,message:'',kind:''})
export function createHealthPolicyFlow({state,token,selection,newKey,confirm,api=healthDashboardApi}){
 let epoch=0,pending=null
 const task=()=>{const g=++epoch,actor=token(),s=selection(),stamp=JSON.stringify(s);return{actor,s,valid:()=>g===epoch&&actor===token()&&stamp===JSON.stringify(selection())}}
 function reset(){epoch++;pending=null;Object.assign(state,initialHealthPolicyState())}
 function failure(e){const safe=e instanceof ServiceError?e:serviceFailure();state.message=safe.message;state.kind=safe.kind;if(['unauthorized','forbidden','missing','conflict'].includes(safe.kind)){state.value=null;pending=null}}
 async function load(){if(state.busy)return;const t=task();pending=null;state.busy=true;state.value=null;state.message='';state.kind='';try{const r=await api.policy(t.actor,t.s.model,t.s.metric);if(t.valid())state.value=r}catch(e){if(t.valid())failure(e)}finally{if(t.valid())state.busy=false}}
 async function save(rule){if(state.busy||!state.value)return;const t=task(),body={expected_version:state.value.version,rule};const signature=JSON.stringify([t.actor,t.s,body]);if(!pending||pending.signature!==signature)pending={signature,key:newKey()};state.busy=true;state.message='';state.kind='';try{if(!await confirm(t.s,body)||!t.valid())return;await api.savePolicy(t.actor,t.s.model,t.s.metric,body,pending.key);if(!t.valid())return;pending=null;state.value=null;const r=await api.policy(t.actor,t.s.model,t.s.metric);if(t.valid()){state.value=r;state.message='已保存并读取当前规则'}}catch(e){if(t.valid())failure(e)}finally{if(t.valid())state.busy=false}}
 return{reset,load,save}
}
