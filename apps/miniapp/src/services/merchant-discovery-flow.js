import { initialQuoteListState,createQuoteListFlow } from './quote-flow.js'
import { nativeRegion } from './merchant-discovery.js'
import { serviceFailure } from './service-catalog.js'
import { nearbyApi as defaultNearbyApi, requestNativeLocation } from './nearby-merchants.js'
import { ratedQuotesApi } from './rated-quotes.js'

export const initialRegionalQuoteState=()=>({...initialQuoteListState(),regionCode:null,regionNames:[],position:null,locating:false,locationMessage:''})
export function createRegionalQuoteFlow({state,api,token,project,nearbyApi=defaultNearbyApi,locate=requestNativeLocation,ratedApi=ratedQuotesApi}){
  let visible=false,generation=0,opened=null
  const list=createQuoteListFlow({state,token,fetchPage:async(owner,page,sort)=>{
    const current=generation
    try{return sort==='rating_desc'?await ratedApi.list(owner,project(),page,state.regionCode):state.position ? await nearbyApi.list(owner,project(),state.position,sort,page,state.regionCode) : await api.ownerList(owner,project(),sort,page,state.regionCode)}
    catch(e){
      if(visible&&current===generation&&owner===token()&&['unauthorized','forbidden','missing'].includes(e.kind)){
        state.items=[];state.page=0;state.total=0;state.loaded=false;state.position=null;state.locating=false
      }
      throw e
    }
  }})
  function clear(){generation++;opened=null;list.reset();state.locating=false}
  async function nearby(){
    if(!visible||!token()||state.locating)return
    clear();state.position=null;state.locationMessage='';state.locating=true
    const current=generation,owner=token()
    try{
      const point=await locate()
      if(!visible||current!==generation||owner!==token())return
      state.position=point;state.sort='distance_asc';state.locating=false;return list.load()
    }catch(e){if(visible&&current===generation&&owner===token()){state.locationMessage=e.kind==='location'?e.message:'未取得位置，可选择省市区或按价格查看门店';state.locating=false;return list.load()}}
  }
  return {
    show(){visible=true},
    load(more=false){if(visible)return list.load(more)},
    retry(){if(visible)return list.retry()},
    nearby,
    sort(value){if(visible&&(['price_asc','price_desc','rating_desc'].includes(value)||value==='distance_asc'&&state.position)){clear();if(value==='rating_desc'){state.position=null;state.locationMessage=''}state.sort=value;return list.load()}},
    beginRegion(){if(visible&&token())opened={generation,owner:token()}},
    selectRegion(detail){
      const selection=opened;opened=null
      if(!selection||!visible||selection.owner!==token()||selection.generation!==generation)return
      try{const region=nativeRegion(detail);const sort=state.sort;clear();state.sort=sort;state.regionCode=region.code;state.regionNames=region.names;return list.load()}
      catch(e){state.message=e.message;state.failureKind=e.kind||'invalid'}
    },
    cancelRegion(){opened=null},
    allRegions(){if(!visible)return;const sort=state.sort==='distance_asc'?'price_asc':state.sort;clear();state.sort=sort;state.regionCode=null;state.regionNames=[];state.position=null;state.locationMessage='';return list.load()},
    suspend(){visible=false;clear();state.position=null;state.locationMessage=''},
    reset(){clear();state.regionCode=null;state.regionNames=[];state.position=null;state.locationMessage=''},
  }
}

export const initialStorefrontState=()=>({merchant:null,items:[],page:0,total:0,projectId:0,busy:false,loaded:false,message:'',failureKind:''})
export function createStorefrontFlow({state,api,token}){
  let visible=false,generation=0,merchant=0,project=0,retryMore=false
  function clear(){generation++;Object.assign(state,initialStorefrontState(),{projectId:project})}
  const valid=(current,owner)=>visible&&current===generation&&owner===token()
  async function load(more=false){
    if(!visible||state.busy||more&&(!state.loaded||state.items.length>=state.total))return
    retryMore=more
    const current=++generation,owner=token(),page=more?state.page+1:1
    state.busy=true;state.message='';state.failureKind=''
    if(!more){state.merchant=null;state.items=[];state.page=0;state.total=0;state.loaded=false}
    try{
      const result=await api.detail(owner,merchant,project,page)
      if(!valid(current,owner))return
      const rows=more?[...state.items,...result.offers.items]:result.offers.items
      state.merchant=result.merchant;state.items=[...new Map(rows.map(row=>[row.merchant_project_id,row])).values()]
      state.page=page;state.total=result.offers.total;state.loaded=true
    }catch(e){
      if(!valid(current,owner))return
      const safe=serviceFailure(e);state.message=safe.message;state.failureKind=safe.kind
      if(['unauthorized','forbidden','missing'].includes(safe.kind)){state.merchant=null;state.items=[];state.page=0;state.total=0;state.loaded=false}
    }finally{if(current===generation)state.busy=false}
  }
  return {load,retry:()=>load(retryMore),
    route(id,selected=0){merchant=id;project=selected;clear()},
    allProjects(){if(!visible)return;project=0;clear();return load()},
    resume(){visible=true;clear();return load()},
    suspend(){visible=false;clear()},
    reset(){clear()},
  }
}
