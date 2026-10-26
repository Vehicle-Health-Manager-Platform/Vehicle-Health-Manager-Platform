import {normalizedPlate,normalizedVin,VehicleError,vehicleFailure} from './vehicles.js'
import {vinResponse} from './vin-candidate.js'
export function createVehiclePrefillStore({now=Date.now}={}) {
 let draft=null,timer=null
 function clear(){if(timer!==null)clearTimeout(timer);timer=null;draft=null}
 function put(token,source,fields={}) {
  clear();if(!token||!['plate','vin','license'].includes(source))throw new VehicleError('invalid','录车来源无效，请重新进入')
  const plate=source==='plate'?normalizedPlate(fields.plate):'',vin=source==='vin'?normalizedVin(fields.vin):''
  const vinFacts=fields.vinFacts===undefined?undefined:vinResponse(fields.vinFacts,vin,fields.vinFacts.model_year_hint)
  if(vinFacts&&(source!=='vin'||vinFacts.status==='NO_MATCH'))throw new VehicleError('invalid','候选无效，请手动选择车型')
  draft={token,source,plate,vin,...(vinFacts?{vinFacts}:{}),expires:now()+60000};timer=setTimeout(clear,60000)
 }
 function take(token){const value=draft;clear();return value&&value.token===token&&now()<value.expires?{source:value.source,plate:value.plate,vin:value.vin,...(value.vinFacts?{vinFacts:value.vinFacts}:{})}:null}
 return {put,take,clear}
}
export const initialVehicleEntryState=()=>({mode:'manual',plate:'',vin:'',busy:false,matches:[],loaded:false,matchedPlate:'',matchedToken:'',message:'',failureKind:''})
export function createVehicleEntryFlow({state,api,token,prefill}) {
 let generation=0
 function invalidate(){generation++;state.busy=false;state.matches=[];state.loaded=false;state.matchedPlate='';state.matchedToken='';state.message='';state.failureKind=''}
 function reset(){invalidate();Object.assign(state,initialVehicleEntryState())}
 function mode(value){invalidate();prefill.clear();state.mode=value;state.plate='';state.vin=''}
 async function match(){if(state.busy||!token())return;invalidate();const actor=token(),current=generation;let plate
  try{plate=normalizedPlate(state.plate);state.busy=true;const result=await api.matchPlate(actor,plate)
   if(current!==generation||actor!==token()||plate!==normalizedPlate(state.plate))return
   state.matches=result.matches;state.loaded=true;state.matchedPlate=plate;state.matchedToken=actor
   state.message=result.matches.length?'请确认并选择本人车辆':'本人暂无匹配车辆，可手动登记并确认车型'
  }catch(error){if(current===generation&&actor===token()){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
  finally{if(current===generation)state.busy=false}}
 function select(row){if(!state.loaded||state.busy||state.matchedToken!==token()||state.matchedPlate!==normalizedPlate(state.plate)||!state.matches.some(v=>v===row))throw new VehicleError('invalid','匹配已失效，请重新核对');return row}
 function prepare(){if(!token())throw new VehicleError('unauthorized','请先登录车主账号');prefill.clear();if(state.mode==='plate')prefill.put(token(),'plate',{plate:state.plate});else if(state.mode==='vin')prefill.put(token(),'vin',{vin:state.vin});else if(state.mode==='license')prefill.put(token(),'license');return '/pages/vehicle/manual'}
 return {invalidate,reset,mode,match,select,prepare}
}
