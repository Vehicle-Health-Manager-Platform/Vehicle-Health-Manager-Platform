import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {imageApi,imageRequestKey,ImageError} from './private-images.js'
import {VehicleError,normalizedPlate} from './vehicles.js'
import {vinInput} from './vin-candidate.js'

export const LICENSE_DISCLOSURE='同意将选定照片上传到平台私有存储，用于本次新车登记的车牌和VIN识别。图片按现有私有附件策略保留，不会自动作为车辆档案附件。识别结果及本人修改均需核对，不能用于证明证件真伪或车辆归属。'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const id=value=>Number.isSafeInteger(value)&&value>0&&value<9007199254740991
const invalid=()=>new VehicleError('protocol','行驶证识别响应异常，请手动填写')
export function licenseResponse(data,fileId){
 if(!exact(data,['file_id','purpose','source','review_status','requires_confirmation','fields'])||data.file_id!==fileId||data.purpose!=='NEW_VEHICLE'||data.source!=='LOCAL_OCR'||data.requires_confirmation!==true||!Array.isArray(data.fields)||data.fields.length!==2)throw invalid()
 const fields=data.fields.map((field,index)=>{
  const key=index===0?'plate':'vin'
  if(!exact(field,['key','status','candidates'])||field.key!==key||!Array.isArray(field.candidates)||field.candidates.length>10)throw invalid()
  const seen=new Set(),candidates=field.candidates.map(row=>{
   if(!exact(row,['value','confidence'])||typeof row.value!=='string'||typeof row.confidence!=='number'||!Number.isFinite(row.confidence)||row.confidence<0||row.confidence>1)throw invalid()
   let normalized;try{normalized=key==='plate'?normalizedPlate(row.value):vinInput(row.value).vin}catch{throw invalid()}
   if(row.value!==normalized||seen.has(row.value))throw invalid();seen.add(row.value);return Object.freeze({...row})
  })
  const status=!candidates.length?'NO_CANDIDATE':candidates.length>1?'AMBIGUOUS':candidates[0].confidence>=.9?'CANDIDATE':'LOW_CONFIDENCE'
  if(field.status!==status)throw invalid();return Object.freeze({key,status,candidates:Object.freeze(candidates)})
 })
 const states=fields.map(f=>f.status),status=states.includes('AMBIGUOUS')?'AMBIGUOUS':states.includes('LOW_CONFIDENCE')?'LOW_CONFIDENCE':states.filter(s=>s==='CANDIDATE').length===2?'CANDIDATE':states.includes('CANDIDATE')?'PARTIAL':'NO_CANDIDATE'
 if(status!==data.review_status)throw invalid()
 return Object.freeze({...data,fields:Object.freeze(fields)})
}
export function createLicenseRecognition({baseUrl,runtime}){
 return ({token,fileId,consent,signal})=>new Promise((resolve,reject)=>{
  if(!token||!baseUrl||!id(fileId)||consent!==true||signal?.aborted){reject(new VehicleError('invalid','请登录、选择图片并同意本次新车识别用途'));return}
  let task,done=false
  function finish(error,value){if(done)return;done=true;signal?.removeEventListener?.('abort',abort);error?reject(error):resolve(value)}
  function abort(){finish(new VehicleError('cancelled','本次识别已取消'));task?.abort?.()}
  signal?.addEventListener?.('abort',abort,{once:true})
  try{task=runtime().request({url:`${baseUrl.replace(/\/$/,'')}/api/vehicle/license-recognition`,method:'POST',timeout:15000,
   header:{Authorization:`Bearer ${token}`,'content-type':'application/json'},data:{file_id:fileId,purpose:'NEW_VEHICLE',consent:true,consent_version:'NEW_VEHICLE_LICENSE_OCR_V1'},
   success:({statusCode,data})=>{if(done||signal?.aborted)return
    const errors={400:['invalid','请检查图片和本次新车识别同意'],401:['unauthorized','登录已失效，请重新登录'],403:['forbidden','请使用车主账号操作'],404:['missing','本人识别图片不可用，请重新选择并上传'],409:['conflict','图片状态已变化，请重新上传'],429:['limited','识别频繁或繁忙，请稍后重试或手动录车'],503:['unavailable','行驶证识别未配置或暂不可用，请手动填写']}
    if(statusCode!==200){const [kind,message]=errors[statusCode]||['server','识别未成功，请手动填写'];finish(new VehicleError(kind,message));return}
    try{if(data?.code!==0)throw invalid();finish(null,licenseResponse(data.data,fileId))}catch{finish(invalid())}
   },fail:()=>finish(new VehicleError('network','识别连接失败或超时，可重试或手动填写'))});if(signal?.aborted)abort()
  }catch{finish(new VehicleError('network','识别连接失败，可手动填写'))}
 })
}
export const recognizeLicense=createLicenseRecognition({baseUrl:apiOrigin,runtime:()=>apiRuntime})
export const initialLicenseState=()=>({selected:null,uploaded:null,key:'',consent:false,busy:false,phase:'idle',candidate:null,form:{plate:'',vin:''},reviewed:false,message:'',failureKind:''})
function cancellation(){const listeners=new Set(),signal={aborted:false,cancelled:false,addEventListener:(_,fn)=>listeners.add(fn),removeEventListener:(_,fn)=>listeners.delete(fn),subscribe:fn=>listeners.add(fn),unsubscribe:fn=>listeners.delete(fn)};return {signal,abort(){signal.aborted=true;signal.cancelled=true;for(const fn of [...listeners])fn();listeners.clear()}}}
export function createLicenseFlow({state,token,prefill,images=imageApi,recognize=recognizeLicense,newKey=imageRequestKey}){
 let generation=0,controller=null,accepted='',selectedActor=''
 const context=()=>JSON.stringify([token(),state.selected?.path,state.selected?.size,state.consent])
 function invalidate(){generation++;controller?.abort();controller=null;accepted='';Object.assign(state,{busy:false,phase:'idle',candidate:null,form:{plate:'',vin:''},reviewed:false,message:'',failureKind:''})}
 function reset(){invalidate();selectedActor='';Object.assign(state,initialLicenseState())}
 function fail(error){const safe=error instanceof VehicleError||error instanceof ImageError?error:new VehicleError('network','识别未成功，请重试或手动填写');state.message=safe.message;state.failureKind=safe.kind}
 async function choose(){if(state.busy)return;reset();const epoch=generation,actor=token();state.busy=true;state.phase='choosing'
  try{if(!actor)throw new VehicleError('unauthorized','请先登录车主账号');const file=await images.choose(actor);if(epoch!==generation||actor!==token())return;if(!file)return
   if(!file.path||!Number.isFinite(file.size)||file.size<=0||file.size>4194304||/\.(webp|gif|bmp)(?:\?|$)/i.test(file.path))throw new VehicleError('invalid','请选择4MiB以内的JPEG或PNG行驶证图片')
   state.selected={...file};selectedActor=actor;state.key=newKey();state.message=file.degraded?'已从相册选图，请同意用途后上传识别。':'照片仅在本机，阅读用途后可上传识别。'
  }catch(error){if(epoch===generation&&actor===token())fail(error)}finally{if(epoch===generation){state.busy=false;state.phase='idle'}}
 }
 async function run(){if(state.busy)return;invalidate();const epoch=generation,scope=context(),actor=token();controller=cancellation();const signal=controller.signal;state.busy=true
  try{if(!actor)throw new VehicleError('unauthorized','请先登录车主账号');if(!state.selected||!state.consent||selectedActor!==actor)throw new VehicleError('invalid','请用当前账号选图并同意本次新车上传识别用途')
   const current=()=>epoch===generation&&scope===context()
   if(!state.uploaded){state.phase='uploading';const uploaded=await images.upload(actor,state.selected,state.key,signal);if(!current())return
    if(!id(uploaded?.file_id)||!['image/jpeg','image/png'].includes(uploaded.content_type)||!Number.isSafeInteger(uploaded.size_bytes)||uploaded.size_bytes<=0||uploaded.size_bytes>4194304||uploaded.size_bytes!==state.selected.size)throw new VehicleError('invalid','识别仅支持4MiB以内的JPEG或PNG原图，请重新选择')
    state.uploaded={...uploaded}
   }
   state.phase='recognizing';const data=await recognize({token:actor,fileId:state.uploaded.file_id,consent:true,signal});if(!current())return
   const result=licenseResponse(data,state.uploaded.file_id);state.candidate=result;accepted=scope;state.phase='review'
   state.form=Object.fromEntries(result.fields.map(field=>[field.key,field.candidates.length===1?field.candidates[0].value:'']))
   state.message={CANDIDATE:'请对照照片核对车牌和VIN。',PARTIAL:'仅提取到部分字段，其他内容可手动填写。',LOW_CONFIDENCE:'存在低可信候选，请仔细核对或修正。',AMBIGUOUS:'存在多个候选，不会自动选择，请按照片手动确定。',NO_CANDIDATE:'未得到可用字段，可以手动填写或重新拍摄。'}[result.review_status]
  }catch(error){if(epoch===generation&&scope===context())fail(error)}finally{if(epoch===generation){state.busy=false;controller=null;if(!state.candidate)state.phase='idle'}}
 }
 function edit(key,value){if(state.busy||!['plate','vin'].includes(key)||typeof value!=='string')return;state.form={...state.form,[key]:value};state.reviewed=false}
 function prepare(){if(!token())throw new VehicleError('unauthorized','请先登录车主账号');if(state.busy||!state.reviewed||!state.candidate||accepted!==context())throw new VehicleError('invalid','请先识别并逐项核对，或使用手动录车入口')
  const plate=state.form.plate.trim(),vin=state.form.vin.trim();if(!plate&&!vin)throw new VehicleError('invalid','请至少填写并核对车牌或VIN')
  const fields={plate:plate?normalizedPlate(plate):'',vin:vin?vinInput(vin).vin:''};prefill.put(token(),'license',fields);return '/pages/vehicle/manual'
 }
 return {invalidate,reset,choose,run,edit,prepare}
}
