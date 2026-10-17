import {exact,safeId} from './experience-publication.js'
import {apiRuntime} from './api-runtime.js'

const clone=v=>JSON.parse(JSON.stringify(v))
const uuid=v=>typeof v==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(v)
const text=v=>typeof v==='string'&&[...v].length<=2000&&!/[\u0000-\u0009\u000b-\u001f\u007f\p{Cf}\p{Cs}]/u.test(v)
const positive=v=>safeId(v)
export function validDiagnosisIntent(n,role,scope){
 if(!exact(n,['action','id','body','key'])||!uuid(n.key))return false
 const b=n.body,a=n.action,creating=scope==='diagnosis-owner'
 if(scope==='diagnosis-service-policy'){
  if(role!=='admin'||!['create','update','status'].includes(a)||(a==='create'?n.id!==0:!positive(n.id)))return false
  if(a==='status')return Boolean(exact(b,['expected_revision','status'])&&positive(b.expected_revision)&&['PUBLISHED','STOPPED'].includes(b.status))
  return Boolean(exact(b,['mode','source_note','valid_from','valid_to',...(a==='update'?['expected_revision']:[])])&&['AVAILABLE','LEAVE_MESSAGE','PAUSED'].includes(b.mode)&&text(b.source_note)&&[...b.source_note].length>=2&&[...b.source_note].length<=256&&b.source_note===b.source_note.trim()&&typeof b.valid_from==='string'&&typeof b.valid_to==='string'&&Number.isFinite(Date.parse(b.valid_from))&&Number.isFinite(Date.parse(b.valid_to))&&Date.parse(b.valid_from)<Date.parse(b.valid_to)&&(a!=='update'||positive(b.expected_revision)))
 }
 if(creating){if(role!=='owner'||a!=='create'||n.id!==0)return false}
 else if(scope!==`${role}:${n.id}`||!positive(n.id)||!(role==='owner'?['message','close']:['reply','close']).includes(a))return false
 const keys=a==='create'?['vehicle_id','policy_id','policy_revision','message','consent']:a==='message'?['expected_revision','policy_id','policy_revision','message','consent']:a==='reply'?['expected_revision','message']:['expected_revision']
 return Boolean(exact(b,keys)&&(a==='create'||positive(b.expected_revision))&&(!['create','message','reply'].includes(a)||text(b.message)&&b.message===b.message.trim()&&b.message.length>0)&&(!['create','message'].includes(a)||positive(b.policy_id)&&positive(b.policy_revision)&&b.consent===true)&&(a!=='create'||positive(b.vehicle_id)))
}
export function createDiagnosisRecovery({runtime=()=>apiRuntime,actor,role,scope,now=()=>Date.now()}={}){
 function key(){const id=actor(),r=role(),s=scope();if(!positive(id)||!['owner','admin'].includes(r)||!(s==='diagnosis-owner'&&r==='owner'||s==='diagnosis-service-policy'&&r==='admin'||new RegExp(`^${r}:[1-9]\\d*$`).test(s)))throw new Error('当前账号或业务范围未确认');return `autocare:diagnosis:v1:${r}:${id}:${s}`}
 const validDraft=n=>exact(n,['text','vehicle'])&&text(n.text)&&(n.vehicle===0||positive(n.vehicle))
 function read(){const v=runtime().getStorageSync(key());if(!v)return {draft:null,pending:null,saved_at:now()};if(!exact(v,['version','saved_at','draft','pending'])||v.version!==1||!Number.isSafeInteger(v.saved_at)||v.saved_at>now()||(v.draft!==null&&!validDraft(v.draft))||(v.pending!==null&&!validDiagnosisIntent(v.pending,role(),scope())))throw new Error('本机恢复数据损坏，请清除后重新读取服务器记录');if(scope()==='diagnosis-service-policy'&&v.pending&&now()-v.saved_at>23*3600000)throw new Error('原配置请求已超过安全重试窗口，请核对服务器配置后清理本机记录');return {draft:now()-v.saved_at<=7*86400000?v.draft:null,pending:v.pending,saved_at:v.saved_at}}
 const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
 function update(field,value){const v=read();if(value&&v.pending&&(field==='draft'||!same(v.pending,value)))throw new Error('此业务已有待确认原请求，请刷新后重试原操作');if(scope()==='diagnosis-service-policy'&&field==='pending'&&value&&!v.pending)v.saved_at=now();v[field]=value;if(field==='draft'&&value)v.saved_at=now();runtime().setStorageSync(key(),{version:1,...clone(v)})}
 function checkPending(expected){const saved=read().pending;if(saved&&!same(saved,expected))throw new Error('此业务恢复记录已变化，请刷新后核对原操作')}
 return {
  read:()=>{const {draft,pending}=read();return clone({draft,pending})},
  saveDraft(v){if(!validDraft(v))throw new Error('草稿内容无效');update('draft',v)},
  savePending(v){if(!validDiagnosisIntent(v,role(),scope()))throw new Error('原提交恢复内容无效');update('pending',v)},
  clearPending(expected){checkPending(expected);update('pending',null)},
  complete(expected){checkPending(expected);runtime().removeStorageSync(key())},
  clearDraft(){update('draft',null)},
  marker(){return JSON.stringify(runtime().getStorageSync(key())||null)},
  discard(marker){if(JSON.stringify(runtime().getStorageSync(key())||null)!==marker)throw new Error('本机恢复记录已经改变，请重新核对服务器配置');runtime().removeStorageSync(key())},
  clear(){runtime().removeStorageSync(key())}
 }
}
