import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {VehicleError,vehicleFailure} from './vehicles.js'
import {vinInput,vinResponse,VIN_PROVIDER} from './vin-candidate.js'

export function createVinDecode({baseUrl,runtime,provider=VIN_PROVIDER}) {
 return ({token,vin,year='',consent,signal})=>new Promise((resolve,reject)=>{
  let input
  try { input=vinInput(vin,year);if(!token)throw new VehicleError('unauthorized','请先登录车主账号');if(consent!==true)throw new VehicleError('invalid','请先阅读并同意来源查询用途');if(!baseUrl)throw new VehicleError('unconfigured','VIN解码未配置，可直接手动录车');if(signal?.aborted)throw new VehicleError('cancelled','查询已取消') }
  catch(e){reject(e);return}
  let settled=false,task
  function finish(error,value){if(settled)return;settled=true;signal?.removeEventListener?.('abort',abort);error?reject(error):resolve(value)}
  function abort(){finish(new VehicleError('cancelled','查询已取消'));task?.abort?.()}
  signal?.addEventListener?.('abort',abort,{once:true})
  try {
   task=runtime().request({url:`${baseUrl.replace(/\/$/,'')}/api/vehicle/vin-decode`,method:'POST',timeout:13000,
    header:{Authorization:`Bearer ${token}`,'content-type':'application/json'},
    data:{vin:input.vin,...(input.year===null?{}:{model_year:input.year}),consent:true,consent_version:provider==='LOCAL_LICENSED'?'VIN_LOCAL_V1':'VIN_VPIC_V1'},
    success:({statusCode,data})=>{
     if(settled||signal?.aborted)return
     const errors={400:['invalid','请检查VIN、年款和用途同意'],401:['unauthorized','登录已失效，请重新登录'],403:['forbidden','请使用车主账号查询'],429:['limited','VIN查询频繁或繁忙，请稍后重试或手动录车'],503:['unavailable','VIN解码未配置或暂不可用，请手动确认车型']}
     if(statusCode!==200){const [kind,message]=errors[statusCode]||['server','VIN查询未成功，请手动确认车型'];finish(new VehicleError(kind,message));return}
     try{if(data?.code!==0||(provider==='LOCAL_LICENSED')!==['LOCAL_LICENSED','LOCAL_SYNTHETIC'].includes(data?.data?.source))throw Error();finish(null,vinResponse(data.data,input.vin,input.year))}
     catch{finish(new VehicleError('protocol','VIN服务响应异常，请手动确认车型'))}
    },fail:()=>finish(new VehicleError('network','VIN查询连接失败或超时，可重试或手动录车'))})
   if(signal?.aborted)abort()
  }catch{finish(new VehicleError('network','VIN查询连接失败，可手动录车'))}
 })
}
export const decodeVin=createVinDecode({baseUrl:apiOrigin,runtime:()=>apiRuntime})
export const initialVinState=()=>({year:'',consent:false,reviewed:false,busy:false,candidate:null,message:'',failureKind:''})
// Mini-program runtimes need cancellation even when AbortController is absent.
function cancellation(){const listeners=new Set();const signal={aborted:false,addEventListener:(_,fn)=>listeners.add(fn),removeEventListener:(_,fn)=>listeners.delete(fn)};return {signal,abort(){signal.aborted=true;for(const fn of [...listeners])fn();listeners.clear()}}}
export function createVinFlow({state,vin,token,decode=decodeVin,prefill}) {
 let generation=0,controller=null,acceptedContext=''
 const context=()=>JSON.stringify([token(),vin(),state.year,state.consent])
 function invalidate(){generation++;controller?.abort();controller=null;acceptedContext='';Object.assign(state,{busy:false,candidate:null,reviewed:false,message:'',failureKind:''})}
 function reset(){invalidate();Object.assign(state,initialVinState())}
 async function recognize(){if(state.busy)return;invalidate();const epoch=generation,scope=context(),actor=token();controller=cancellation();state.busy=true
  try{const result=await decode({token:actor,vin:vin(),year:state.year,consent:state.consent,signal:controller.signal});if(epoch!==generation||scope!==context())return
   const input=vinInput(vin(),state.year);state.candidate=vinResponse(result,input.vin,input.year);acceptedContext=scope
   state.message=result.status==='NO_MATCH'?'未找到可用候选，请手动选择车型。':result.status==='PARTIAL'?'仅有部分候选信息，请逐项核对后手动选车型。':'请核对候选信息，再进入手动表单选择车型与里程。'
  }catch(error){if(epoch===generation&&scope===context()){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
  finally{if(epoch===generation){state.busy=false;controller=null}}
 }
 function prepare(){if(!token())throw new VehicleError('unauthorized','请先登录车主账号');if(state.busy||!state.reviewed||!state.candidate||state.candidate.status==='NO_MATCH'||acceptedContext!==context())throw new VehicleError('invalid','候选未核对或已失效，请重新查询');prefill.put(token(),'vin',{vin:state.candidate.vin,vinFacts:state.candidate});return '/pages/vehicle/manual'}
 return {invalidate,reset,recognize,prepare}
}
