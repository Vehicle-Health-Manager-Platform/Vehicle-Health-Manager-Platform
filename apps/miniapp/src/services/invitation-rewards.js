import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
import {exact} from './experience-publication.js'
import {validCouponTerms} from './coupons.js'

const positive=n=>Number.isSafeInteger(n)&&n>0
const utc=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
export const rewardLabels={READY:'等待后台发券',WAIT_STOCK:'原券库存不足，等待恢复',WAIT_SOURCE:'原券或发行来源不可用，等待恢复',WAIT_ACCOUNT:'奖励相关账号不可用，等待恢复',UNKNOWN_POLICY:'资格确认时奖励政策未知，不补用后来政策',EXPIRED:'原券已过期，未发放',INVALID_EVIDENCE:'资格证据无法确认，未发放',COMPLETE:'奖励已发放'}
export function validInvitationReward(n,mode='owner'){
 if(!['owner','staff'].includes(mode)||!exact(n,['record_id','source_type','state','qualified_at','completed_at','policy_revision','rule_version','worker_enabled','rewards'])||typeof n.record_id!=='string'||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n.record_id)||!['OWNER','STAFF'].includes(n.source_type)||!Object.hasOwn(rewardLabels,n.state)||!utc(n.qualified_at)||n.rule_version!=='R6B_REWARD_V1'||typeof n.worker_enabled!=='boolean'||!Array.isArray(n.rewards))return false
 if(mode==='staff'&&n.source_type!=='STAFF')return false
 if(n.state==='UNKNOWN_POLICY')return n.policy_revision===null&&n.completed_at===null&&n.rewards.length===0
 if(!positive(n.policy_revision)||n.rewards.length!==(n.source_type==='OWNER'?2:1)||new Set(n.rewards.map(r=>r.role)).size!==n.rewards.length)return false
 if(n.state==='COMPLETE'?(!utc(n.completed_at)||Date.parse(n.completed_at)<Date.parse(n.qualified_at)):n.completed_at!==null)return false
 let ownCount=0
 for(const r of n.rewards){
  if(!exact(r,['role','own','terms','user_coupon_id'])||!['INVITER','INVITEE'].includes(r.role)||(n.source_type==='STAFF'&&r.role!=='INVITEE')||typeof r.own!=='boolean'||!validCouponTerms(r.terms))return false
  if(r.own)ownCount++
  if(mode==='staff'&&r.own)return false
  if(r.own&&n.state==='COMPLETE'?!positive(r.user_coupon_id):r.user_coupon_id!==null)return false
 }
 return mode==='staff'?ownCount===0:ownCount===1
}
export function createInvitationRewardsApi({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}){
 return {records(token,mode,page=1){
  if(!token)throw new ServiceError('unauthorized','请先登录当前账号')
  if(!['owner','staff'].includes(mode)||!Number.isSafeInteger(page)||page<1||page>1000000)throw new ServiceError('invalid','奖励分页参数无效')
  const path=(mode==='owner'?'/api/invite':'/api/merchant/invite')+`/rewards?page=${page}&page_size=20`
  return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl+path,method:'GET',timeout:15000,header:{Authorization:`Bearer ${token}`},success:({statusCode,data})=>{
   if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({401:'unauthorized',403:'forbidden',503:'unavailable'})[statusCode]||'server','奖励读取失败，请重试'))
   const n=data?.data
   if(data?.code!==0||!exact(n,['items','total','page','page_size'])||!Number.isSafeInteger(n.total)||n.total<0||n.page!==page||n.page_size!==20||!Array.isArray(n.items)||n.items.length!==Math.min(20,Math.max(0,n.total-(page-1)*20))||!n.items.every(r=>validInvitationReward(r,mode))||new Set(n.items.map(r=>r.record_id)).size!==n.items.length)return reject(new ServiceError('protocol','奖励响应异常，请刷新'))
   resolve(n)
  },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
 }}
}
export function initialRewardState(){return {busy:false,loaded:false,rows:[],total:0,page:1,message:'',kind:''}}
export function createInvitationRewardFlow({state,token,mode='owner',api=createInvitationRewardsApi()}={}){
 let generation=0
 const current=(g,t)=>g===generation&&t===token()
 function reset(){generation++;Object.assign(state,initialRewardState())}
 async function load(page=1){if(state.busy||!token())return;const g=++generation,t=token();state.busy=true;state.message='';state.kind='';try{const n=await api.records(t,mode,page);if(current(g,t))Object.assign(state,{rows:n.items,total:n.total,page:n.page,loaded:true})}catch(e){if(current(g,t))Object.assign(state,{rows:[],total:0,loaded:false,message:e.message||'奖励读取失败',kind:e.kind||'server'})}finally{if(current(g,t))state.busy=false}}
 return {load,reset}
}
