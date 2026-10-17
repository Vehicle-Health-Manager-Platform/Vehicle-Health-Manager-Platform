import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError, serviceFailure } from './service-catalog.js'
import { exact } from './experience-publication.js'
import { validInvitationCode } from './invitation-context.js'

const integer = n => Number.isSafeInteger(n) && n >= 0
const uuid = n => typeof n === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n)
const utc = n => typeof n === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n) && Number.isFinite(Date.parse(n)) && new Date(n).toISOString().replace('.000','') === n
export function invitationCycle(count) {
  if (!integer(count)) return null
  const within = count % 10, tier = within < 1 ? 1 : within < 3 ? 2 : within < 6 ? 3 : 4
  return {qualified_count:count,completed_cycles:Math.floor(count/10),tier,tier_count:within-[0,0,1,3,6][tier],tier_target:tier,next_threshold:count-within+[0,1,3,6,10][tier]}
}
export function validInviteCode(n) {
  if (!exact(n,['code'])) return false
  const c=n.code
  return c===null || exact(c,['value','source_type','created_at','landing_path','rule_version']) && validInvitationCode(c.value) && ['OWNER','STAFF'].includes(c.source_type) && utc(c.created_at) && c.landing_path===`/pages/welfare/invite-entry?code=${c.value}` && c.rule_version==='R6A_INVITE_V1'
}
export function validInviteRecord(n) {
  if (!exact(n,['record_id','source_type','identity_source','attribution_result','qualification_status','qualification_reason','registered_at','qualified_at','reward_month','rule_version']) || !uuid(n.record_id) || ![null,'OWNER','STAFF'].includes(n.source_type) || !['VERIFIED_WECHAT','UNKNOWN','TEST'].includes(n.identity_source) || !['ATTRIBUTED','NO_CODE','INVALID_CODE','SELF','SOURCE_UNAVAILABLE'].includes(n.attribution_result) || !['PENDING','UNKNOWN','TEST','QUALIFIED','REJECTED'].includes(n.qualification_status) || ![null,'PHONE_SEEN','SOURCE_UNAVAILABLE'].includes(n.qualification_reason) || !utc(n.registered_at) || n.rule_version!=='R6A_INVITE_V1') return false
  if (n.qualification_status==='QUALIFIED') return n.identity_source==='VERIFIED_WECHAT' && n.attribution_result==='ATTRIBUTED' && n.source_type!==null && n.qualification_reason===null && utc(n.qualified_at) && Date.parse(n.qualified_at)>=Date.parse(n.registered_at) && n.reward_month===new Date(Date.parse(n.qualified_at)+8*3600000).toISOString().slice(0,7)
  return n.qualified_at===null && n.reward_month===null && (n.attribution_result==='ATTRIBUTED'?n.source_type!==null:n.source_type===null) && (n.qualification_status==='PENDING'?n.identity_source==='VERIFIED_WECHAT' && n.attribution_result==='ATTRIBUTED' && n.qualification_reason===null:n.qualification_status==='UNKNOWN'||n.qualification_status==='TEST'?n.identity_source===n.qualification_status && n.attribution_result==='ATTRIBUTED' && n.qualification_reason===null:true)
}
export function validInviteProgress(n) {
  const fields=['qualified_count','completed_cycles','tier','tier_count','tier_target','next_threshold','month','timezone','verified_identity_enabled','rule_version','pending_total','unknown_total','test_total','rejected_total']
  if (!exact(n,fields) || !integer(n.qualified_count)) return false
  const c=invitationCycle(n.qualified_count)
  return Object.entries(c).every(([k,v])=>n[k]===v) && typeof n.month==='string' && /^[1-9]\d{3}-(?:0[1-9]|1[0-2])$/.test(n.month) && n.timezone==='Asia/Shanghai' && typeof n.verified_identity_enabled==='boolean' && n.rule_version==='R6A_INVITE_V1' && ['pending_total','unknown_total','test_total','rejected_total'].every(k=>integer(n[k]))
}
const validRegistration = n => exact(n,['registration']) && (n.registration===null || validInviteRecord(n.registration))
const validPage = (n,p) => exact(n,['items','total','page','page_size']) && n.page===p && n.page_size===20 && integer(n.total) && Array.isArray(n.items) && n.items.length<=20 && n.items.every(validInviteRecord) && new Set(n.items.map(r=>r.record_id)).size===n.items.length && n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))
export function createInvitationsApi({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}) {
  function call(token,path,method,body,key,check) {
    if (!token) throw new ServiceError('unauthorized','请先登录当前账号')
    return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+path,method,data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{
      if (!Number.isInteger(statusCode)||statusCode<200||statusCode>=300) return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',503:'unavailable'})[statusCode]||'server','邀请请求未成功，请重试'))
      if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','邀请响应异常，请刷新'))
      resolve(data.data)
    },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
  }
  const root = mode => { if(!['owner','staff'].includes(mode))throw new ServiceError('invalid','邀请角色无效');return mode==='owner'?'/api/invite':'/api/merchant/invite' }
  return {
    code:(token,mode)=>call(token,root(mode)+(mode==='owner'?'/qrcode':'/code'),'GET',undefined,null,n=>validInviteCode(n)&&(n.code===null||n.code.source_type===(mode==='owner'?'OWNER':'STAFF'))),
    create:(token,mode,key)=>{if(!uuid(key))throw new ServiceError('invalid','请刷新后生成推广码');return call(token,root(mode)+'/code','POST',{},key,n=>validInviteCode(n)&&n.code!==null&&n.code.source_type===(mode==='owner'?'OWNER':'STAFF'))},
    progress:(token,mode)=>call(token,root(mode)+'/progress','GET',undefined,null,validInviteProgress),
    records:(token,mode,page=1)=>{if(!Number.isInteger(page)||page<1||page>1000000)throw new ServiceError('invalid','分页参数无效');return call(token,root(mode)+`/records?page=${page}&page_size=20`,'GET',undefined,null,n=>validPage(n,page)&&n.items.every(r=>r.source_type===(mode==='owner'?'OWNER':'STAFF')))},
    registration:token=>call(token,'/api/invite/registration','GET',undefined,null,validRegistration),
  }
}
export const invitationLabels={PENDING:'等待手机号证据',UNKNOWN:'来源尚未确认，不计有效邀请',TEST:'测试记录，不计有效邀请',QUALIFIED:'有效新客',REJECTED:'不符合新客资格'}
export function initialInvitationState(){return {busy:false,loaded:false,code:null,progress:null,registration:null,rows:[],total:0,page:1,message:'',kind:''}}
export function createInvitationFlow({state,token,mode='owner',api=createInvitationsApi(),keyFactory}={}) {
  let generation=0,pending=null
  const current=(g,t)=>g===generation&&t===token()
  function reset(){generation++;Object.assign(state,initialInvitationState());if(pending&&pending.token!==token())pending=null}
  async function load(page=1){
    if(state.busy||!token())return
    const g=++generation,t=token();state.busy=true;state.message='';state.kind=''
    try{const [c,p,r,s]=await Promise.all([api.code(t,mode),api.progress(t,mode),api.records(t,mode,page),mode==='owner'?api.registration(t):Promise.resolve(null)])
      if(!current(g,t))return
      Object.assign(state,{code:c.code,progress:p,rows:r.items,total:r.total,page:r.page,registration:s?.registration||null,loaded:true});if(c.code)pending=null
    }catch(e){if(current(g,t)){Object.assign(state,{code:null,progress:null,registration:null,rows:[],total:0,loaded:false,message:e.message||'加载失败，请重试',kind:e.kind||'server'})}}
    finally{if(current(g,t))state.busy=false}
  }
  async function create(){
    if(state.busy||!token())return
    const t=token();if(!pending||pending.token!==t)pending={token:t,key:keyFactory()}
    const g=++generation;state.busy=true;state.message='';state.kind=''
    try{await api.create(t,mode,pending.key);if(!current(g,t))return;pending=null;state.busy=false;await load(1)}
    catch(e){if(current(g,t)){state.message=e.message||'生成失败，请使用同一请求重试';state.kind=e.kind||'server'}}
    finally{if(current(g,t))state.busy=false}
  }
  return {load,create,reset}
}
