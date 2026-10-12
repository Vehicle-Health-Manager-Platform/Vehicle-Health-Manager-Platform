export function initialHealthHistoryState(){return {vehicleId:0,metric:'',rows:[],today:'',next:null,hasMore:false,busy:false,loaded:false,message:'',kind:''}}
export function createHealthHistoryFlow({state,api,token}){
  let generation=0,visible=false
  const valid=(current,owner,vehicle,metric)=>current===generation&&owner===token()&&visible&&vehicle===state.vehicleId&&metric===state.metric
  function reset(){generation++;Object.assign(state,initialHealthHistoryState())}
  async function load(more=false){
    if(!visible||state.busy||!state.vehicleId||!state.metric||more&&(!state.loaded||!state.hasMore))return
    const current=++generation,owner=token(),vehicle=state.vehicleId,metric=state.metric,before=more?state.next:null
    state.busy=true;state.message='';state.kind=''
    if(!more){state.rows=[];state.loaded=false;state.next=null;state.hasMore=false;state.today=''}
    try{
      const result=await api.history(owner,vehicle,metric,before)
      if(!valid(current,owner,vehicle,metric))return
      state.rows=more?[...state.rows,...result.list]:result.list
      state.today=result.today;state.next=result.next_before_revision;state.hasMore=result.has_more;state.loaded=true
    }catch(e){
      if(!valid(current,owner,vehicle,metric))return
      state.message=e.message||'历史加载失败，请重试';state.kind=e.kind||'server'
      if(['unauthorized','forbidden','missing'].includes(state.kind)){state.rows=[];state.loaded=false;state.next=null;state.hasMore=false;state.today=''}
    }finally{if(current===generation)state.busy=false}
  }
  return {load,more:()=>load(true),reset,
    resume(vehicle,metric){reset();visible=true;state.vehicleId=vehicle;state.metric=metric;return load()},
    suspend(){visible=false;reset()},
  }
}
