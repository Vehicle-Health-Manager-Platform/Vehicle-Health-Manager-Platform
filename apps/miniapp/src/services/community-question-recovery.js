import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validQuestionBody} from './community-questions.js'
const copy=n=>JSON.parse(JSON.stringify(n)),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
export function validQuestionIntent(n,role,scope){
 if(!exact(n,['action','id','body','key'])||typeof n.key!=='string'||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n.key))return false
 const policy=scope==='policy',create=scope==='create'
 if(policy)return role==='admin'&&validQuestionBody(n.body,n.action,true)&&(n.action==='create'?n.id===0:safeId(n.id))
 if(create)return role==='owner'&&n.action==='create'&&n.id===0&&validQuestionBody(n.body,n.action)
 if(scope==='queue')return role==='admin'&&n.action==='assign'&&safeId(n.id)&&validQuestionBody(n.body,n.action)
 return scope===`case:${n.id}`&&safeId(n.id)&&(role==='owner'?['message','close']:role==='admin'?['reply','close']:[]).includes(n.action)&&validQuestionBody(n.body,n.action)
}
export function createCommunityQuestionRecovery({runtime=()=>apiRuntime,actor,role,scope,now=()=>Date.now()}={}){
 function key(){const a=actor(),r=role(),s=scope();if(!safeId(a)||!['owner','admin'].includes(r)||!(s==='create'&&r==='owner'||['policy','queue'].includes(s)&&r==='admin'||/^case:[1-9]\d*$/.test(s)&&safeId(Number(s.slice(5)))))throw new Error('当前问答账号或范围未确认');return `autocare:private-community:v1:${r}:${a}:${s}`}
 let observed=null
 function read(observe=true){const currentKey=key(),v=runtime().getStorageSync(currentKey);if(!v){if(observe)observed={key:currentKey,marker:JSON.stringify(v||null)};return {draft:null,pending:null};}if(!exact(v,['version','saved_at','pending'])||v.version!==1||!Number.isSafeInteger(v.saved_at)||v.saved_at<0||v.saved_at>now()||!validQuestionIntent(v.pending,role(),scope()))throw new Error('本机私有问答原请求损坏，请核对服务器后清理');if(v.pending.action!=='create'&&now()-v.saved_at>=23*3600000)throw new Error('原操作已超过23小时安全重试窗口，请先核对服务器状态再清理本机记录');if(observe)observed={key:currentKey,marker:JSON.stringify(v)};return {draft:null,pending:copy(v.pending)}}
 function check(expected){if(!same(read(false).pending,expected))throw new Error('其他页面已改变本机原请求，请重新核对')}
 return {read,peek:()=>read(false),savePending(v){if(!validQuestionIntent(v,role(),scope()))throw new Error('私有问答原请求无效');const existing=read(false).pending;if(existing&&!same(existing,v))throw new Error('已有未决私有问答请求，请先核对');if(existing){observed={key:key(),marker:JSON.stringify(runtime().getStorageSync(key()))};return;}const saved={version:1,saved_at:now(),pending:copy(v)};runtime().setStorageSync(key(),saved);if(!same(runtime().getStorageSync(key()),saved))throw new Error('本机原请求未保存，暂不发送');observed={key:key(),marker:JSON.stringify(saved)}},complete(expected){check(expected);runtime().removeStorageSync(key())},clearPending(expected){check(expected);runtime().removeStorageSync(key())},clear(){if(!observed||observed.key!==key())return;if(JSON.stringify(runtime().getStorageSync(key())||null)!==observed.marker)throw new Error('其他页面已改变本机原请求，请重新核对');runtime().removeStorageSync(key())},marker(){return JSON.stringify(runtime().getStorageSync(key())||null)},discard(marker){if(JSON.stringify(runtime().getStorageSync(key())||null)!==marker)throw new Error('其他页面已改变本机原请求，请重新核对');runtime().removeStorageSync(key())}}
}
export function pendingQuestion(recovery){try{return (recovery.peek?recovery.peek():recovery.read()).pending}catch{return null}}
export function createQuestionConfirmation({state,preview}){
 let resolve=null
 function settle(ok){const answer=resolve;resolve=null;state.prompt=null;answer?.(ok===true)}
 function ask(action){settle(false);const p=action==='discard-local'?{title:'核对后仅清理本机请求',lines:['请先核对刚读取的服务器记录。仅清除本机未决原请求，不撤销服务器操作，也不自动重发。']}:preview();if(!p||!Array.isArray(p.lines))return Promise.resolve(false);state.prompt=copy({...p,action});return new Promise(r=>resolve=r)}
 return {ask,settle,cancel:()=>settle(false)}
}
