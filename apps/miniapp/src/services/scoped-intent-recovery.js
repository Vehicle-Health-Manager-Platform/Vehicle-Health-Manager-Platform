import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'

const copy=n=>JSON.parse(JSON.stringify(n))
const same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
export const intentUuid=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(n)

/** Separate business namespaces; never stores session tokens or credentials. */
export function createScopedIntentRecovery({namespace,actor,role,scope,validate,permanent=()=>false,runtime=()=>apiRuntime,now=()=>Date.now()}){
 let observed=null
 function key(){
  const a=actor(),r=role(),s=scope()
  if(!safeId(a)||!['owner','admin'].includes(r)||typeof s!=='string'||!/^[a-z0-9:-]{1,100}$/.test(s))throw new Error('当前账号或业务范围未确认')
  return `autocare:${namespace}:v1:${r}:${a}:${s}`
 }
 function read(observe=true){
  const k=key(),v=runtime().getStorageSync(k)
  if(observe)observed={key:k,marker:JSON.stringify(v||null)}
  if(!v)return {draft:null,pending:null}
  if(!exact(v,['version','saved_at','pending'])||v.version!==1||!Number.isSafeInteger(v.saved_at)||v.saved_at<0||v.saved_at>now()||!valid(v.pending))throw new Error('本机原请求损坏，请核对服务器后清理')
  if(!permanent(v.pending)&&now()-v.saved_at>=23*3600000)throw new Error('原操作已超过23小时安全重试窗口，请先核对服务器状态')
  return {draft:null,pending:copy(v.pending)}
 }
 function valid(v){return exact(v,['action','id','body','key'])&&intentUuid(v.key)&&validate(v,role(),scope())}
 function check(v){if(!same(read(false).pending,v))throw new Error('其他页面已改变本机原请求，请重新核对')}
 return {
  read,peek:()=>read(false),
  savePending(v){
   if(!valid(v))throw new Error('原请求无效')
   const old=read(false).pending
   if(old&&!same(old,v))throw new Error('已有未决请求，请先核对')
   if(!old){const saved={version:1,saved_at:now(),pending:copy(v)};runtime().setStorageSync(key(),saved);if(!same(runtime().getStorageSync(key()),saved))throw new Error('原请求未保存，暂不发送')}
   observed={key:key(),marker:JSON.stringify(runtime().getStorageSync(key()))}
  },
  complete(v){check(v);runtime().removeStorageSync(key())},
  clearPending(v){check(v);runtime().removeStorageSync(key())},
  clear(){if(observed&&observed.key===key()){if(JSON.stringify(runtime().getStorageSync(key())||null)!==observed.marker)throw new Error('本机原请求已变化');runtime().removeStorageSync(key())}},
  marker:()=>JSON.stringify(runtime().getStorageSync(key())||null),
  discard(marker){if(JSON.stringify(runtime().getStorageSync(key())||null)!==marker)throw new Error('其他页面已改变本机原请求');runtime().removeStorageSync(key())},
 }
}
