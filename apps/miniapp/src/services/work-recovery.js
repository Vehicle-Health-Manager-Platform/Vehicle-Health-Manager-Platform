import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validReportRequest} from './service-work.js'
const copy=v=>JSON.parse(JSON.stringify(v))
const text=(v,n)=>typeof v==='string'&&v.length<=n&&!/[\u0000-\u0009\u000b-\u001f\u007f\p{Cf}\p{Cs}]/u.test(v)
const canonical=v=>JSON.stringify(normalize(v))
function normalize(v){return Array.isArray(v)?v.map(normalize):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,normalize(v[k])])):v}
export function validWorkDraft(v){return Boolean(exact(v,['plan','analysis','minutes','noFaultParts','noParts','parts'])&&text(v.plan,2000)&&text(v.analysis,2000)&&typeof v.minutes==='string'&&/^\d{0,4}$/.test(v.minutes)&&typeof v.noFaultParts==='boolean'&&typeof v.noParts==='boolean'&&Array.isArray(v.parts)&&v.parts.length<=20&&v.parts.every(p=>exact(p,['name','model','brand','quantity'])&&['name','model','brand'].every(k=>text(p[k],100))&&typeof p.quantity==='string'&&/^\d{0,3}$/.test(p.quantity)))}
export function validWorkIntent(v,order){return Boolean(exact(v,['action','id','body','key'])&&v.id===order&&safeId(order)&&typeof v.key==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(v.key)&&v.body?.order_id===order&&(v.action==='report'?validReportRequest(v.body):v.action==='sign'&&exact(v.body,['order_id','signature_file_id'])&&safeId(v.body.signature_file_id)))}
export function workIntentApplied(intent,view){
 if(!intent||view?.work?.order_id!==intent.id||view?.job?.order_id!==intent.id||view.job.assignment_status!=='ACCEPTED'||view.job.order_status!==view.work.order_status||!view.work.report)return false
 if(intent.action==='sign')return view.work.report.status==='SIGNED'&&view.work.report.signature_file_id===intent.body.signature_file_id
 return intent.action==='report'&&Object.entries(intent.body).every(([k,v])=>k==='order_id'||canonical(v)===canonical(view.work.report[k]))
}
export function createWorkRecovery({runtime=()=>apiRuntime,actor,order,now=()=>Date.now()}={}){
 function key(){if(!safeId(actor())||!safeId(order()))throw new Error('当前技师或工单尚未确认');return `autocare:work:v1:${actor()}:${order()}`}
 function read(){const n=runtime().getStorageSync(key());if(!n)return {draft:null,pending:null,saved_at:now()};if(!exact(n,['version','draft','pending','saved_at'])||n.version!==1||!Number.isSafeInteger(n.saved_at)||n.saved_at>now()||n.draft!==null&&!validWorkDraft(n.draft)||n.pending!==null&&!validWorkIntent(n.pending,order()))throw new Error('本机施工恢复数据损坏，请先核对服务器记录');return {draft:now()-n.saved_at<=7*86400000?n.draft:null,pending:n.pending,saved_at:n.saved_at}}
 function update(field,value){const n=read();if(value&&n.pending&&(field==='draft'||canonical(n.pending)!==canonical(value)))throw new Error('当前工单已有未决请求，请先核对或重试原请求');n[field]=value;if(field==='draft'&&value)n.saved_at=now();runtime().setStorageSync(key(),{version:1,...copy(n)})}
 function check(intent){const n=read().pending;if(n&&canonical(n)!==canonical(intent))throw new Error('本机原请求已变化，请重新读取工单')}
 const store={read:()=>{const {draft,pending}=read();return copy({draft,pending})},saveDraft(v){if(!validWorkDraft(v))throw new Error('施工草稿无效');update('draft',v)},savePending(v){if(!validWorkIntent(v,order()))throw new Error('原施工请求无效');update('pending',v)},clearPending(v){check(v);update('pending',null)},clearDraft(){update('draft',null)},complete(v){check(v);runtime().removeStorageSync(key())},clear(){runtime().removeStorageSync(key())},reconcile(view){const v=read().pending;if(v&&workIntentApplied(v,view)){store.complete(v);return true}return false}}
 return store
}
