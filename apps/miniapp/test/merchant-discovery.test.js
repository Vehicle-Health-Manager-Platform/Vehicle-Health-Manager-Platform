import test from 'node:test'
import assert from 'node:assert/strict'
import { nativeRegion,validStorefront,createMerchantDiscoveryApi,canReturnToDiscovery } from '../src/services/merchant-discovery.js'
import { initialRegionalQuoteState,createRegionalQuoteFlow,initialStorefrontState,createStorefrontFlow } from '../src/services/merchant-discovery-flow.js'
import { createQuotesApi } from '../src/services/merchant-quotes.js'
import { ServiceError } from '../src/services/service-catalog.js'
const offer=(id=101,project=11)=>({merchant_project_id:id,standard_project_id:project,project_name:'合成服务',category:1,price:'110.00',version_id:1002,version:2})
const merchant={merchant_id:1,name:'合成门店',address:'合成地址',merchant_type:2,region_code:'440305'}
const data=(items=[offer()],page=1,total=items.length)=>({merchant,offers:{items,total,page,page_size:20}})
const region={value:['广东省','深圳市','南山区'],code:['440000','440300','440305']}
const quotePage=(items=[{merchant_project_id:101}],page=1,total=items.length)=>({items,total,page,page_size:20})
const deferred=()=>{let resolve,reject;const promise=new Promise((r,j)=>{resolve=r;reject=j});return {resolve,reject,promise}}
test('native region requires complete labels and district code, never guesses from address',()=>{
  assert.deepEqual(nativeRegion(region),{code:'440305',names:region.value})
  for(const change of [{code:undefined},{code:[440000,440300,440305]},{code:['440000','440300','']},{code:['440000','440300','440305123456']},{value:['广东','深圳']},{value:['广东','深圳',' ']}])assert.throws(()=>nativeRegion({...region,...change}),{kind:'invalid'})
})
test('minimal storefront projection rejects private extras, foreign project and malformed money',()=>{
  assert.equal(validStorefront(data(),1,11,1),true)
  assert.equal(validStorefront({...data(),merchant:{...merchant,region_code:null}},1,0,1),true)
  for(const change of [{merchant:{...merchant,contact_phone:'private'}},{merchant:{...merchant,merchant_id:2}},{merchant:{...merchant,region_code:''}},{offers:{...data().offers,items:[{...offer(),price:'NaN'}]}},{offers:{...data().offers,items:[offer(101,12)]}},{offers:{...data().offers,items:[{...offer(),phone:'private'}]}},{offers:{...data().offers,items:[offer(),offer()]}}])assert.equal(validStorefront({...data(),...change},1,11,1),false)
})
test('detail request binds merchant, project and page and controls failures',async()=>{
  const api=createMerchantDiscoveryApi({baseUrl:'https://test.invalid/',runtime:()=>({request:r=>{assert.match(r.url,/merchant\/1\?page=2.*project_id=11/);assert.equal(r.header.Authorization,'Bearer owner');r.success({statusCode:200,data:{code:0,data:data([offer()],2)}})}})})
  await api.detail('owner',1,11,2)
  for(const statusCode of [401,403,404,503]){
    const failed=createMerchantDiscoveryApi({baseUrl:'https://test.invalid',runtime:()=>({request:r=>r.success({statusCode,data:{message:'private SQL'}})})})
    await assert.rejects(failed.detail('owner',1),e=>!e.message.includes('SQL'))
  }
  assert.throws(()=>api.detail('owner',1,-1),{kind:'invalid'});assert.throws(()=>api.detail('',1),{kind:'unauthorized'})
  const invalid=createMerchantDiscoveryApi({baseUrl:'https://test.invalid',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:{...data(),merchant:{...merchant,staff:[]}}}})})})
  await assert.rejects(invalid.detail('owner',1),{kind:'protocol'})
})
test('regional quote API keeps old URL and rejects invalid filters before request',async()=>{
  const calls=[];const row={merchant_project_id:101,version_id:1002,version:2,price:'110.00',merchant_id:1,merchant_name:'店',address:'地址'}
  const api=createQuotesApi({baseUrl:'https://test.invalid',runtime:()=>({request:r=>{calls.push(r.url);r.success({statusCode:200,data:{code:0,data:quotePage([row])}})}})})
  await api.ownerList('owner',11);assert.equal(calls[0].includes('region_code'),false)
  await api.ownerList('owner',11,'price_desc',1,'440305');assert.match(calls[1],/region_code=440305/)
  assert.throws(()=>api.ownerList('owner',11,'price_asc',1,''),{kind:'invalid'});assert.equal(calls.length,2)
})
test('region selection clears old pages, preserves sort and discards superseded requests',async()=>{
  const wait=deferred(),state=initialRegionalQuoteState(),calls=[]
  const flow=createRegionalQuoteFlow({state,token:()=> 'owner',project:()=>11,api:{ownerList:(t,id,sort,page,area)=>{calls.push({sort,area});return area?Promise.resolve(quotePage([{merchant_project_id:201}])):wait.promise}}})
  flow.show();const old=flow.load();flow.beginRegion();await flow.selectRegion(region)
  wait.resolve(quotePage([{merchant_project_id:101}]));await old;assert.equal(state.items[0].merchant_project_id,201);assert.equal(state.regionCode,'440305')
  await flow.sort('price_desc');assert.equal(calls.at(-1).area,'440305');assert.equal(calls.at(-1).sort,'price_desc')
  await flow.allRegions();assert.equal(state.regionCode,null);assert.equal(calls.at(-1).sort,'price_desc')
})
test('cancel, incomplete codes, hidden picker and account change cannot alter region',async()=>{
  const state=initialRegionalQuoteState();let owner='one',count=0
  const flow=createRegionalQuoteFlow({state,token:()=>owner,project:()=>11,api:{ownerList:async()=>{count++;return quotePage()}}})
  flow.show();await flow.load();flow.beginRegion();flow.cancelRegion();await flow.selectRegion(region);assert.equal(count,1)
  flow.beginRegion();await flow.selectRegion({value:region.value});assert.equal(count,1);assert.equal(state.regionCode,null)
  flow.beginRegion();owner='two';flow.reset();await flow.selectRegion(region);assert.equal(count,1);assert.deepEqual(state.items,[])
  flow.beginRegion();flow.suspend();await flow.selectRegion(region);assert.equal(count,1)
})
test('region quote permission loss clears already loaded rows',async()=>{
  const state=initialRegionalQuoteState(),flow=createRegionalQuoteFlow({state,token:()=> 'owner',project:()=>11,api:{ownerList:async(t,id,s,p)=>{if(p===2)throw new ServiceError('unauthorized','expired');return quotePage(undefined,1,30)}}})
  flow.show();await flow.load();await flow.load(true);assert.deepEqual(state.items,[]);assert.equal(state.loaded,false)
})
test('late error from superseded sort cannot clear the new quote page',async()=>{
  const wait=deferred(),state=initialRegionalQuoteState()
  const flow=createRegionalQuoteFlow({state,token:()=> 'owner',project:()=>11,api:{ownerList:async(t,id,sort)=>sort==='price_asc'?wait.promise:quotePage([{merchant_project_id:201}])}})
  flow.show();const old=flow.load();await flow.sort('price_desc');wait.reject(new ServiceError('unauthorized','old error'));await old
  assert.equal(state.items[0].merchant_project_id,201);assert.equal(state.failureKind,'')
})
test('storefront pagination failure retries same page, then all projects resets scope',async()=>{
  const state=initialStorefrontState(),calls=[];let fail=true
  const flow=createStorefrontFlow({state,token:()=> 'owner',api:{detail:async(t,m,p,n)=>{calls.push({p,n});if(n===2&&fail){fail=false;throw new ServiceError('network','retry')}return data([offer(n===1?101:102,p||12)],n,n===1?30:2)}}})
  flow.route(1,11);await flow.resume();await flow.load(true);assert.equal(state.page,1);assert.equal(state.items.length,1)
  await flow.retry();assert.deepEqual(calls.map(c=>c.n),[1,2,2]);assert.equal(state.page,2);assert.equal(state.items.length,2)
  await flow.allProjects();assert.equal(state.projectId,0);assert.equal(state.page,1);assert.equal(calls.at(-1).p,0)
})
test('late storefront response after hide or account switch never restores data',async()=>{
  for(const action of ['hide','account']){
    const wait=deferred(),state=initialStorefrontState();let owner='one'
    const flow=createStorefrontFlow({state,token:()=>owner,api:{detail:()=>wait.promise}});flow.route(1,11);const loading=flow.resume()
    if(action==='hide')flow.suspend();else{owner='two';flow.reset()}
    wait.resolve(data());await loading;assert.equal(state.merchant,null);assert.deepEqual(state.items,[])
  }
})
test('storefront revocation clears old offers and login has a bounded return target',async()=>{
  for(const kind of ['unauthorized','forbidden','missing']){
    const state=initialStorefrontState(),flow=createStorefrontFlow({state,token:()=> 'owner',api:{detail:async(t,m,p,n)=>{if(n===2)throw new ServiceError(kind,'gone');return data([offer()],1,30)}}})
    flow.route(1,11);await flow.resume();await flow.load(true);assert.equal(state.merchant,null);assert.deepEqual(state.items,[])
  }
  assert.equal(canReturnToDiscovery([{route:'pages/service/merchant'},{route:'pages/owner/index'}]),true)
  assert.equal(canReturnToDiscovery([{route:'pages/merchant/orders'},{route:'pages/owner/index'}]),false)
})
