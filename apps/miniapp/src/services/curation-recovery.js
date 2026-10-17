import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validCurationBody} from './experience-curations.js'
const copy=v=>JSON.parse(JSON.stringify(v)),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
function valid(v){return Boolean(exact(v,['action','id','body','key'])&&validCurationBody(v.body,v.action)&&(v.action==='create'?v.id===0:safeId(v.id))&&typeof v.key==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(v.key))}
// Permanent server creation intents make replay independent of the 24h generic receipt.
export function createCurationRecovery({runtime=()=>apiRuntime,actor}={}){
 function key(){if(!safeId(actor()))throw new Error('当前运营账号尚未确认');return `autocare:experience-curation:v1:${actor()}`}
 function read(){const v=runtime().getStorageSync(key());if(!v)return {draft:null,pending:null};if(!exact(v,['version','pending'])||v.version!==1||!valid(v.pending))throw new Error('本机经验精选原请求损坏，请核对服务器后清理');return {draft:null,pending:copy(v.pending)}}
 function check(expected){const saved=read().pending;if(saved&&!same(saved,expected))throw new Error('本机经验精选原请求已变化，请重新读取')}
 return {read,savePending(v){if(!valid(v))throw new Error('经验精选原请求无效');const saved=read().pending;if(saved&&!same(saved,v))throw new Error('已有未决经验精选请求，请先核对');runtime().setStorageSync(key(),{version:1,pending:copy(v)})},complete(expected){check(expected);runtime().removeStorageSync(key())},clearPending(expected){check(expected);runtime().removeStorageSync(key())},clear(){runtime().removeStorageSync(key())},marker(){return JSON.stringify(runtime().getStorageSync(key())||null)},discard(marker){if(JSON.stringify(runtime().getStorageSync(key())||null)!==marker)throw new Error('原请求已被其他页面更新，请重新核对');runtime().removeStorageSync(key())}}
}
