import {asrApi,asrInput,AsrError} from './asr-jobs.js'
import {materialScope} from './input-material-upload.js'
import {imageRequestKey} from './private-images.js'

export function createAsrReceipts({now=Date.now}={}){
 const records=new Map(),listeners=new Set()
 function notify(){for(const fn of listeners)fn()}
 function prune(){for(const [key,row] of records)if(row.deadline<=now())records.delete(key)}
 return {
  add(row){prune();if(!records.has(row.key)&&records.size>=10)throw new AsrError('limited','尚有待确认转写请求，请先处理或等待到期');records.set(row.key,{...row,status:'CREATING'});notify()},
  unknown(key){const row=records.get(key);if(row){row.status='UNKNOWN';notify()}},
  known(key,jobId){const row=records.get(key);if(row){row.jobId=jobId;row.status='CANCEL_UNKNOWN';notify()}},
  remove(key){records.delete(key);notify()},
  list(actor,scope){prune();return [...records.values()].filter(r=>r.actor===actor&&r.scope===scope&&r.status!=='CREATING').map(r=>({...r,input:{...r.input}}))},
  subscribe(fn){listeners.add(fn);return()=>listeners.delete(fn)},
  clear(){records.clear();notify()},
 }
}
export const asrReceipts=createAsrReceipts()
export const initialAsrState=()=>({consent:false,phase:'idle',busy:false,created:null,job:null,result:null,text:'',reviewed:false,message:'',failureKind:''})
const safe=e=>e instanceof AsrError?e:new AsrError('network','转写请求未完成，可稍后确认原请求或手动填写')
const uncertain=e=>['network','server','unavailable','protocol','cancelled'].includes(e.kind)
const signature=r=>JSON.stringify([r.status,r.language,r.duration_ms,r.segments.map(s=>[s.start_ms,s.end_ms,s.text,s.confidence])])

export function createAsrFlow({state,actor,scope,material,value,allowed=()=>true,apply,api=asrApi,receipts=asrReceipts,keyFactory=imageRequestKey,now=Date.now,schedule=setTimeout,cancelTimer=clearTimeout,adapter=null}){
 let epoch=0,visible=true,timer=null,operation=null,receipt=null,baseline='',polls=0
 const context=()=>JSON.stringify([actor(),scope(),material()?.materialId,adapter?adapter.context(material()):material()?.durationMs])
 const resultSignature=adapter?.signature||signature
 const current=(e,c)=>visible&&epoch===e&&context()===c&&Boolean(actor())&&allowed()&&state.consent
 function stop(){epoch++;if(timer!==null)cancelTimer(timer);timer=null;operation?.abort?.();operation=null}
 async function cancelKnown(row){
  // Creation receipts are removed after success; cancellation uncertainty needs its own retained handle.
  let tracked=false
  try{receipts.add(row);receipts.known(row.key,row.jobId);tracked=true}catch{}
  try{await api.cancel(row.actor,row.jobId,row.cancelKey).promise;receipts.remove(row.key);return true}
  catch{if(tracked)receipts.known(row.key,row.jobId);return false}
 }
 function reset(){const old=receipt;stop();receipt=null;Object.assign(state,initialAsrState());if(old?.jobId)void cancelKnown(old)}
 function report(error){const e=safe(error);state.message=e.message;state.failureKind=e.kind}
 function cancel(){const old=receipt;stop();Object.assign(state,{busy:false,phase:'cancelled',created:null,job:null,result:null,text:'',reviewed:false,message:'已停止接收转写结果；取消请求不删除原音频。',failureKind:''});receipt=null
  if(old?.jobId){state.message='正在请求取消服务端任务…';const e=epoch;void cancelKnown(old).then(done=>{if(epoch===e)state.message=done?'已请求取消任务，原音频仍保留。':'取消结果未知，可在待确认请求中重试；最迟按任务期限失效。'})}
  else if(old){receipts.unknown(old.key);state.message='创建结果未知，无法确认服务端已取消；已保留原请求句柄，旧候选最多10分钟到期。'}
 }
 function schedulePoll(e,c){if(timer!==null)cancelTimer(timer);timer=schedule(()=>{timer=null;void poll(e,c)},2000)}
 function expireCandidate(e,c,expiresAt){
  if(timer!==null)cancelTimer(timer)
  timer=schedule(()=>{timer=null;if(!current(e,c))return
   state.result=null;state.text='';state.reviewed=false;if(state.job)state.job={...state.job,result:null}
   if(state.phase!=='applied'){state.phase='terminal';state.message='转写候选已到期，已清除本页候选；请重新准备或手动填写。'}
  },Math.max(1,Date.parse(expiresAt)-now()))
 }
 async function poll(e=epoch,c=context()){
  if(!current(e,c)||!receipt?.jobId)return
  if(now()>=receipt.deadline||polls++>=300){cancel();state.message='已停止等待，任务候选已到本地等待期限，请手动填写。';return}
  state.busy=true;state.phase='polling';const row=receipt
  try{operation=api.get(row.actor,state.created,row.validationContext??row.durationMs);const job=await operation.promise;if(!current(e,c))return
   state.job=job;state.result=null;state.text='';state.reviewed=false
   if(Date.parse(job.expires_at)<=now()||job.state==='EXPIRED'){state.phase='terminal';state.message='转写候选已过期，请重新录制或手动填写';return}
   if(['QUEUED','RUNNING'].includes(job.state)){state.message=job.state==='QUEUED'?'任务已排队，可取消；尚未得到转写。':'正在转写，可取消；不会自动发送或保存。';schedulePoll(e,c);return}
   if(job.state==='SUCCEEDED'){
    state.result=job.result;state.text=adapter?adapter.text(job.result):job.result.segments.map(s=>s.text).join('\n');state.phase=job.result.status==='EMPTY'?'empty':'ready';state.message=job.result.status==='EMPTY'?'未提取到语音文字，请手动填写。':'请核对转写片段，可修正后勾选带入；仍需原发送或保存按钮。';expireCandidate(e,c,job.expires_at);return
   }
   state.phase='terminal';state.message=adapter?.failure?.(job)||{CANCELLED:'任务已取消或原授权失效，请手动填写。',FAILED:'转写未成功，请检查音频或服务配置后重新创建。'}[job.state]||'任务不可用，请手动填写。'
  }catch(error){if(current(e,c)){state.phase='poll-error';report(error)}}finally{if(epoch===e){state.busy=false;operation=null}}
 }
 async function start(){
  if(state.busy||!visible||!allowed())return
  if(!state.consent){report(new AsrError('invalid','请先同意本次语音转写用途'));return}
  if(receipt?.jobId){await poll();return}
  const m=material();let input,validationContext
  try{if(adapter){({input,validationContext}=adapter.prepare(materialScope(scope()),m));if(!actor())throw new AsrError('invalid','请登录车主账号')}else{input=asrInput(materialScope(scope()),m?.materialId);if(!actor()||!Number.isInteger(m?.durationMs)||m.durationMs<1000||m.durationMs>60000)throw new AsrError('invalid','请登录并上传当前车辆的合规音频')}}
  catch(error){report(error);return}
  const c=context();stop();const e=epoch
  if(!receipt||receipt.context!==c){receipt={actor:actor(),scope:scope(),context:c,input,key:keyFactory(),cancelKey:keyFactory(),durationMs:m.durationMs,validationContext,deadline:now()+600000,jobId:null};baseline=String(value()||'')}
  const row=receipt
  try{receipts.add(row);state.busy=true;state.phase='creating';state.message='正在创建转写任务…';state.failureKind=''
   operation=api.create(row.actor,row.input,row.key);const created=await operation.promise;row.jobId=created.job_id
   if(!current(e,c)){receipts.known(row.key,row.jobId);void cancelKnown(row);return}
   receipts.remove(row.key);state.created=created;state.busy=false;operation=null;await poll(e,c)
  }catch(error){const failure=safe(error);if(uncertain(failure))receipts.unknown(row.key);else receipts.remove(row.key)
   if(current(e,c)){state.phase=uncertain(failure)?'unknown':'idle';report(failure)}
  }finally{if(epoch===e){state.busy=false;operation=null}}
 }
 function edit(text){if(state.busy||state.phase!=='ready'||typeof text!=='string')return;state.text=text;state.reviewed=false}
 async function confirm(){
  if(state.busy||state.phase!=='ready'||!state.reviewed||!receipt?.jobId||!current(epoch,receipt.context))return false
  const text=state.text.trim();if(!text||text.length>1000||/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(text)){report(new AsrError('invalid','请核对1至1000字的转写内容'));return false}
  if(String(value()||'')!==baseline){state.reviewed=false;state.message='原表单已变化，不能覆盖新内容；请取消后重新核对。';return false}
  const e=epoch,c=receipt.context,source=resultSignature(state.result);state.busy=true
  try{operation=api.get(receipt.actor,state.created,receipt.validationContext??receipt.durationMs);const fresh=await operation.promise
   if(!current(e,c)||!state.reviewed||String(value()||'')!==baseline||text!==state.text.trim())return false
   if(fresh.state!=='SUCCEEDED'||Date.parse(fresh.expires_at)<=now()||resultSignature(fresh.result)!==source){state.reviewed=false;state.result=null;state.text='';state.phase='terminal';state.message='任务已失效、取消或来源变化，未带入表单。';return false}
   state.phase='applied';state.reviewed=false;state.message='文字已带入，尚未发送或保存。';apply(text);return true
  }catch(error){if(current(e,c)){state.reviewed=false;report(error)}return false}finally{if(epoch===e){state.busy=false;operation=null}}
 }
 return {start,refresh:poll,confirm,edit,cancel,reset,suspend(){visible=false;reset()},resume(){visible=true}}
}

export async function recoverAndCancelAsr(row,{actor,scope,consent=false,api=asrApi,receipts=asrReceipts}){
 if(row.actor!==actor||row.scope!==scope)throw new AsrError('forbidden','原请求属于其他会话或用途')
 let jobId=row.jobId
 if(!jobId){if(!consent)throw new AsrError('invalid','确认原创建需要重新同意音频转写用途');jobId=(await api.create(actor,row.input,row.key).promise).job_id;receipts.known(row.key,jobId)}
 await api.cancel(actor,jobId,row.cancelKey).promise;receipts.remove(row.key)
}
