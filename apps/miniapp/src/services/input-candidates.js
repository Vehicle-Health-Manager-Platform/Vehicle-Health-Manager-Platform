import {normalizedPlate,normalizedVin} from './vehicles.js'

export const candidateFields={
 vehicle:[{key:'plate',label:'车牌',kind:'plate',max:8},{key:'vin',label:'VIN',kind:'vin',max:17},{key:'mileage',label:'仪表盘里程（公里）',kind:'number',max:10}],
 archive:[{key:'title',label:'档案标题',max:80},{key:'notes',label:'材料内容/语音人工整理',kind:'multiline',max:1000},{key:'mileage',label:'材料所载里程（公里）',kind:'number',max:10},{key:'recordedDate',label:'材料所载日期',kind:'date',max:10}],
 voice:[{key:'question',label:'语音材料人工整理的问题',kind:'multiline',max:1000}],
}
const error=message=>new Error(message)
const scenes=new Set(['vehicle','archive','license','voice'])
export function normalizeCandidate(def,raw){
 if(typeof raw!=='string')throw error('候选内容格式无效，请重新整理')
 const value=raw.trim()
 if(!value)return ''
 const controls=def.kind==='multiline'?/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/:/[\u0000-\u001f\u007f-\u009f]/
 if([...value].length>(def.max||100)||controls.test(value))throw error('候选内容过长或包含无效字符')
 if(def.kind==='vin')return normalizedVin(value)
 if(def.kind==='plate')return normalizedPlate(value)
 if(def.kind==='number'&&(!/^\d{1,10}$/.test(value)||Number(value)>(def.maxValue??2147483647)))throw error('请核对非负整数及允许范围')
 if(def.kind==='date'){
  const date=new Date(value+'T00:00:00Z')
  if(!/^\d{4}-\d{2}-\d{2}$/.test(value)||value.slice(0,4)<'1000'||!Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==value)throw error('请填写有效日期，格式为年-月-日')
 }
 return value
}
const exact=(value,keys)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(k=>Object.hasOwn(value,k))
export function recognitionCandidates(result,scene,definitions){
 if(!scenes.has(scene)||!exact(result,['version','scene','status','fields'])||result.version!==1||result.scene!==scene||!['CANDIDATE','EMPTY','UNAVAILABLE'].includes(result.status)
  ||!Array.isArray(result.fields)||result.fields.length>Math.min(20,definitions.length))throw error('识别结果校验失败，请手动整理')
 if(result.status!=='CANDIDATE'){if(result.fields.length)throw error('识别结果校验失败，请手动整理');return []}
 if(!result.fields.length)throw error('识别结果校验失败，请手动整理')
 const seen=new Set()
 return result.fields.map(field=>{
  const def=definitions.find(d=>d.key===field?.key)
  if(!exact(field,['key','value','confidence'])||!def||seen.has(field.key)||!(field.confidence===null||typeof field.confidence==='number'&&Number.isFinite(field.confidence)&&field.confidence>=0&&field.confidence<=1))throw error('识别结果校验失败，请手动整理')
  seen.add(field.key);const value=normalizeCandidate(def,field.value);if(!value)throw error('识别结果为空，请手动整理')
  return {key:field.key,value,confidence:field.confidence}
 })
}
export const unavailableRecognition=async({scene})=>({version:1,scene,status:'UNAVAILABLE',fields:[]})
export const initialCandidateState=()=>({phase:'closed',form:{},rows:[],source:'',ack:false,message:''})
export function createCandidateFlow({state,actor,scope,scene,definitions,values,allowed=()=>true,apply,timeoutMs=15000,schedule=setTimeout,cancelTimer=clearTimeout}){
 let epoch=0,visible=true,snapshot='',session=null,controller=null,timer=null,finish=null
 const context=()=>String(actor()||'')+'\0'+String(scope()||'')+'\0'+scene()
 const signature=()=>JSON.stringify(definitions().map(d=>[d.key,String(values()?.[d.key]??'')]))
 const valid=(e,c)=>visible&&e===epoch&&c===context()&&Boolean(actor())
 function cancel(){epoch++;controller?.abort();controller=null;if(timer!==null)cancelTimer(timer);timer=null;finish?.();finish=null;Object.assign(state,initialCandidateState())}
 function begin(){if(!visible||!actor()||!allowed())return;cancel();session={epoch,context:context()};snapshot=signature();state.form=Object.fromEntries(definitions().map(d=>[d.key,'']));state.phase='editing';state.source='MANUAL';state.message='本人手动整理，未机器识别；空项不会覆盖现值。'}
 function edit(key,value){if(!['editing','review'].includes(state.phase)||!definitions().some(d=>d.key===key))return;state.form={...state.form,[key]:value};state.ack=false;state.phase='editing';state.rows=[]}
 function review(){if(state.phase!=='editing'||!allowed()||!session||!valid(session.epoch,session.context))return;try{const rows=[];for(const def of definitions()){const value=normalizeCandidate(def,state.form[def.key]??'');if(value)rows.push({key:def.key,value,confidence:state.rows.find(r=>r.key===def.key)?.confidence??null})}if(!rows.length)throw error('请至少整理一项候选内容');state.rows=rows;state.phase='review';state.ack=false;state.message='请逐项对照原材料。确认带入后仍需点击原页面的保存或发送。'}catch(e){state.message=e.message;state.ack=false}}
 function confirm(){
  if(state.phase!=='review'||!state.ack||!allowed()||!session||!valid(session.epoch,session.context))return false
  if(signature()!==snapshot){state.ack=false;state.message='原表单已变化，请取消后重新整理，避免覆盖新内容';return false}
  const patch={};try{for(const row of state.rows){const def=definitions().find(d=>d.key===row.key);if(!def)throw error('候选字段已变化，请重新整理');patch[row.key]=normalizeCandidate(def,row.value)}}catch(e){state.message=e.message;state.ack=false;return false}
  state.phase='applied';state.ack=false;state.message='已带入表单，尚未保存或发送。';apply(Object.freeze(patch));return true
 }
 async function recognize(adapter=unavailableRecognition){
  if(!visible||!actor()||!allowed()||state.phase==='recognizing')return
  begin();state.phase='recognizing';const e=epoch,c=context();controller=typeof AbortController==='function'?new AbortController():{signal:{aborted:false},abort(){this.signal.aborted=true}}
  const control=controller,requestedScene=scene()
  try{
   const result=await Promise.race([Promise.resolve().then(()=>valid(e,c)?adapter({scene:requestedScene,signal:control.signal}):null),new Promise((resolve,reject)=>{finish=()=>resolve(null);timer=schedule(()=>{control.abort();reject(error('识别超时，请手动整理'))},timeoutMs)})])
   if(!valid(e,c)||!result)return
   const rows=recognitionCandidates(result,scene(),definitions())
   if(!rows.length){state.phase='editing';state.source='MANUAL';state.message=result.status==='EMPTY'?'未得到可用内容，请手动整理':'识别适配尚未配置，请手动整理';return}
   state.form=Object.fromEntries(rows.map(r=>[r.key,r.value]));state.rows=rows;state.source='PROVIDER';state.phase='editing';state.message='机器候选尚未核对；低可信或未知可信内容均需逐项人工确认。'
  }catch(ignored){if(valid(e,c)){state.phase='editing';state.source='MANUAL';state.message='识别未成功或超时，请手动整理'}}
  finally{if(epoch===e){if(timer!==null)cancelTimer(timer);timer=null;controller=null;finish=null}}
 }
 return {begin,edit,review,confirm,recognize,cancel,suspend(){visible=false;cancel()},resume(){visible=true}}
}
