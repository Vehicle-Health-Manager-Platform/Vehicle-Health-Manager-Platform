import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {ORDER_STATES} from './order-status.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export const EVIDENCE_KINDS=['PICKUP','PROTECTION','PROCESS','FAULT','FINISH','SIGNATURE']
export const EVIDENCE_LABELS={PICKUP:'到店接车照片（施工前）',PROTECTION:'施工防护',PROCESS:'施工过程',FAULT:'故障件',FINISH:'完工照片（施工后）',SIGNATURE:'质检签字'}
export const EVENT_LABELS={CREATED:'预约创建',CHECKED_IN:'接车检查完成',OWNER_CONFIRMED:'车主确认接车',ASSIGNED:'已派工',PROTECTED:'防护已记录',REPORTED:'报工已提交',QUALITY_SIGNED:'质检已签字',REDEEMED:'已核销',CLOSED:'订单已关闭'}
const slots=['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR','FUEL']
const utc=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
const instant=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().slice(0,19)===n.slice(0,19)
const text=(n,max)=>typeof n==='string'&&n.trim().length>0&&n.length<=max
const part=n=>exact(n,['name','model','brand','quantity'])&&['name','model','brand'].every(k=>text(n[k],100))&&Number.isInteger(n.quantity)&&n.quantity>=1&&n.quantity<=999
export function validOwnerEvidence(n){
 if(!exact(n,['order_id','order_status','timeline','groups','report_state','report'])||!safeId(n.order_id)||!ORDER_STATES.includes(n.order_status)||!['MISSING','UNKNOWN','SUBMITTED','SIGNED'].includes(n.report_state))return false
 if(!Array.isArray(n.timeline)||n.timeline.length>10||new Set(n.timeline.map(t=>t?.event)).size!==n.timeline.length||!n.timeline.every((t,i)=>exact(t,['event','occurred_at'])&&Object.hasOwn(EVENT_LABELS,t.event)&&utc(t.occurred_at)&&(i===0||Date.parse(t.occurred_at)>=Date.parse(n.timeline[i-1].occurred_at))))return false
 if(!Array.isArray(n.groups)||n.groups.length!==6)return false
 for(let i=0;i<6;i++){const g=n.groups[i];if(!exact(g,['kind','state','photos'])||g.kind!==EVIDENCE_KINDS[i]||!['MISSING','UNKNOWN','RECORDED'].includes(g.state)||!Array.isArray(g.photos)||g.photos.length>(['PROTECTION','SIGNATURE'].includes(g.kind)?1:g.kind==='PICKUP'?8:9)||g.state!=='RECORDED'&&g.photos.length>0||new Set(g.photos.map(p=>p?.file_id)).size!==g.photos.length||!g.photos.every(p=>exact(p,['file_id','slot','available'])&&safeId(p.file_id)&&typeof p.available==='boolean'&&(g.kind==='PICKUP'?slots.includes(p.slot):p.slot==='')))return false}
 const r=n.report
 if(['MISSING','UNKNOWN'].includes(n.report_state))return r===null&&n.groups.slice(2).every(g=>g.state===n.report_state)
 return Boolean(exact(r,['repair_plan','fault_analysis','parts_used','no_parts','no_fault_parts','work_minutes','submitted_at','signed_at'])&&text(r.repair_plan,2000)&&text(r.fault_analysis,2000)&&Array.isArray(r.parts_used)&&r.parts_used.length<=20&&r.parts_used.every(part)&&typeof r.no_fault_parts==='boolean'&&typeof r.no_parts==='boolean'&&r.no_parts===(r.parts_used.length===0)&&Number.isInteger(r.work_minutes)&&r.work_minutes>=1&&r.work_minutes<=1440&&utc(r.submitted_at)&&(n.report_state==='SIGNED'?utc(r.signed_at):r.signed_at===null)&&n.groups.slice(2,5).every(g=>g.state==='RECORDED')&&n.groups[2].photos.length>0&&n.groups[4].photos.length>0&&(r.no_fault_parts?n.groups[3].photos.length===0:n.groups[3].photos.length>0)&&(n.report_state==='SUBMITTED'?n.groups[5].state==='MISSING':['RECORDED','UNKNOWN'].includes(n.groups[5].state)))
}
export function createOwnerEvidenceApi({runtime=()=>apiRuntime,baseUrl=apiOrigin,now=()=>Date.now()}={}){
 function call(t,path,check){if(!t)throw new ServiceError('unauthorized','请先登录车主账号');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+path,method:'GET',timeout:15000,header:{Authorization:`Bearer ${t}`},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',503:'unavailable'})[statusCode]||'server','施工证据读取失败，请刷新或重新登录'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','施工证据响应异常，请刷新核对'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 const checked=n=>{if(!safeId(n))throw new ServiceError('invalid','订单或图片编号无效')}
 return {detail(t,id){checked(id);return call(t,`/api/order/${id}/work`,n=>validOwnerEvidence(n)&&n.order_id===id)},access(t,id,file){checked(id);checked(file);return call(t,`/api/order/${id}/work/files/${file}/access`,n=>exact(n,['url','expires_at'])&&typeof n.url==='string'&&/^https:\/\/[a-z0-9.-]+(?::\d+)?\//i.test(n.url)&&!/[\s#@]/.test(n.url)&&instant(n.expires_at)&&Date.parse(n.expires_at)>now()&&Date.parse(n.expires_at)<=now()+305000)}}
}
export function initialOwnerEvidenceState(){return {busy:false,photoBusy:false,data:null,preview:null,message:'',kind:''}}
export function createOwnerEvidenceFlow({state,token,order,api=createOwnerEvidenceApi(),now=()=>Date.now(),schedule=(fn,ms)=>setTimeout(fn,ms),cancel=timer=>clearTimeout(timer)}={}){
 let epoch=0,photoEpoch=0,timer=null
 const current=(e,t,id)=>e===epoch&&t===token()&&id===order()
 function clearPreview(){photoEpoch++;if(timer!==null)cancel(timer);timer=null;state.preview=null;state.photoBusy=false}
 function reset(){epoch++;clearPreview();Object.assign(state,initialOwnerEvidenceState())}
 async function load(){if(state.busy||!token())return;reset();const e=epoch,t=token(),id=order();state.busy=true;try{const n=await api.detail(t,id);if(current(e,t,id))state.data=n}catch(err){if(current(e,t,id)){state.message=err.message||'证据读取失败';state.kind=err.kind||'server'}}finally{if(current(e,t,id))state.busy=false}}
 async function show(files){if(state.busy||state.photoBusy||!token()||!files.every(file=>state.data?.groups.some(g=>g.photos.some(p=>p.file_id===file&&p.available))))return;clearPreview();const e=epoch,p=photoEpoch,t=token(),id=order(),live=()=>current(e,t,id)&&p===photoEpoch;state.photoBusy=true;state.message='';state.kind='';try{const images=await Promise.all(files.map(async file=>({...await api.access(t,id,file),file_id:file})));if(!live())return;const remaining=Math.min(...images.map(n=>Date.parse(n.expires_at)))-now();if(remaining<=0)throw new ServiceError('expired','图片访问已过期，请重新打开');state.preview={images};timer=schedule(()=>{if(live()){state.preview=null;timer=null}},remaining)}catch(err){if(live()){state.preview=null;state.message=err.message||'图片暂不可用';state.kind=err.kind||'server';if(['unauthorized','forbidden','missing'].includes(state.kind))state.data=null}}finally{if(live())state.photoBusy=false}}
 function preview(file){return show([file])}
 function compare(before,after){if(before===after||!state.data?.groups.find(g=>g.kind==='PICKUP')?.photos.some(p=>p.file_id===before&&p.available)||!state.data?.groups.find(g=>g.kind==='FINISH')?.photos.some(p=>p.file_id===after&&p.available))return;return show([before,after])}
 return {load,preview,compare,closePreview:clearPreview,reset}
}
