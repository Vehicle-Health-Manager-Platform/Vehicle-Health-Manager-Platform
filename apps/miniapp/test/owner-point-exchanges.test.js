import test from 'node:test'
import assert from 'node:assert/strict'
import {reactive} from 'vue'
import {validOwnerExchangeCatalog,validExchangeReceipt,validExchangeHistory,createOwnerPointExchangeApi,createOwnerExchangeRecovery,createOwnerExchangeFlow,prepareOwnerExchange} from '../src/services/owner-point-exchanges.js'
import {createCheckinRecovery} from '../src/services/points-ledger.js'
import {initialDiagnosisState} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const at='2026-10-16T00:00:00Z',key='11111111-1111-4111-8111-111111111111',key2='22222222-2222-4222-8222-222222222222'
const body=()=>({catalog_id:1,catalog_revision:2})
const catalog=()=>({id:1,coupon_id:1,publication_revision:2,pool_revision:1,points_cost:5,per_owner_limit:3,source_note:'可核对合成兑换来源',valid_from:at,valid_to:'2026-11-16T00:00:00Z',revision:2,status:'PUBLISHED',rule_version:'R8A_EXCHANGE_V1',limit_scope:'OWNER_COUPON_LIFETIME',terms:{name:'合成服务券',type:'SERVICE',project_id:1,amount:'0.00',min_spend:'0.00',total_quantity:3,valid_from:at,valid_to:'2026-11-16T00:00:00Z'},created_at:at,published_at:at})
const offer=()=>({catalog:catalog(),merchant:{id:1,name:'合成门店',address:'合成地址'},project:{id:1,name:'合成项目',service_content:'指定项目内容'},stock_left:3,exchanged_count:0,remaining_limit:3,eligibility:'ELIGIBLE'})
const context=()=>({items:[offer()],total:1,page:1,page_size:20,balance:7,legacy_state:'NONE',exchange_available:true,read_at:at})
const receipt=()=>({exchange_id:1,user_coupon_id:11,catalog_id:1,catalog_revision:2,coupon_id:1,publication_revision:2,points_spent:5,balance_after:2,exchanged_at:at,status:'SUCCESS',rule_version:'R8A_EXCHANGE_V1',catalog:catalog()})
const history=(items=[])=>({items,total:items.length,page:1,page_size:20})
const pending=()=>({action:'exchange',id:0,body:body(),key})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,JSON.parse(JSON.stringify(v))),removeStorageSync:k=>values.delete(k)}}
function harness({api={},storage=memory(),actor=()=>1,token=()=> 'token',confirm=async()=>true,historyPage=()=>1,onWritten=()=>{}}={}){const state=initialDiagnosisState(),store=createOwnerExchangeRecovery({runtime:()=>storage,actor}),service={catalog:async()=>context(),history:async()=>history(),exchange:async()=>receipt(),...api};return {state,store,storage,flow:createOwnerExchangeFlow({state,token,actor,historyPage,api:service,recovery:store,newKey:()=>key,confirm,onWritten})}}

test('owner catalog accepts exact source, quota and independent availability with real state boundaries',()=>{
 assert.equal(validOwnerExchangeCatalog(context(),1),true)
 for(const fields of [{balance:'7'},{exchange_available:'true'},{legacy_state:'IMPORTED'},{user_id:2},{read_at:'bad'}])assert.equal(validOwnerExchangeCatalog({...context(),...fields},1),false)
 for(const fields of [{remaining_limit:2},{exchanged_count:-1},{stock_left:-1},{stock_left:4},{merchant:null},{project:{...offer().project,id:2}},{eligibility:'GUESS'}])assert.equal(validOwnerExchangeCatalog({...context(),items:[{...offer(),...fields}]},1),false)
 assert.equal(validOwnerExchangeCatalog({...context(),exchange_available:false},1),false)
 assert.equal(validOwnerExchangeCatalog({...context(),exchange_available:false,items:[{...offer(),eligibility:'EXCHANGE_UNAVAILABLE'}]},1),true)
 assert.equal(validOwnerExchangeCatalog({...context(),items:[{...offer(),catalog:{...catalog(),valid_from:'2026-10-17T00:00:00Z'},eligibility:'NOT_STARTED'}]},1),true)
 assert.equal(validOwnerExchangeCatalog({...context(),read_at:'2026-11-16T00:00:00Z',items:[{...offer(),eligibility:'EXPIRED'}]},1),true)
 assert.equal(validOwnerExchangeCatalog({...context(),items:[{...offer(),merchant:null,project:null,stock_left:null,eligibility:'SOURCE_UNKNOWN'}]},1),true)
})
test('frozen success must agree on template revision price time and safe balance without current expiry checks',()=>{
 assert.equal(validExchangeReceipt(receipt()),true)
 for(const fields of [{catalog_id:2},{catalog_revision:3},{coupon_id:2},{points_spent:1},{balance_after:-1},{balance_after:Number.MAX_SAFE_INTEGER},{status:'PENDING'},{exchanged_at:'2026-11-16T00:00:00Z'},{identity_session:'private'}])assert.equal(validExchangeReceipt({...receipt(),...fields}),false)
 assert.equal(validExchangeHistory(history([receipt()]),1),true);assert.equal(validExchangeHistory(history([receipt(),receipt()]),1),false)
 assert.equal(validExchangeHistory(history([{...receipt(),catalog:{...catalog(),status:'STOPPED'}}]),1),false)
})
test('owner API uses exact original ids key and safe pagination and rejects unrelated or malformed receipts',async()=>{
 const calls=[];let data=context();const api=createOwnerPointExchangeApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data}})}})})
 await api.catalog('token');assert.match(calls[0].url,/\/api\/point\/exchange-catalog\?page=1&page_size=20$/)
 data=history([receipt()]);await api.history('token');data=receipt();await api.exchange('token',body(),key);assert.equal(calls.at(-1).header['Idempotency-Key'],key);assert.deepEqual(calls.at(-1).data,body())
 data={...receipt(),catalog_id:2};await assert.rejects(api.exchange('token',body(),key),e=>e.kind==='protocol')
 for(const b of [{...body(),points_cost:1},{...body(),user_id:2},{...body(),catalog_revision:0}])assert.throws(()=>api.exchange('token',b,key),e=>e.kind==='invalid')
 assert.throws(()=>api.exchange('token',body(),'bad'));assert.throws(()=>api.catalog('token',0));assert.throws(()=>api.history('token',1000001));assert.throws(()=>api.catalog(''))
})
test('confirmation quote is copied from exact server context including Vue reactive rows',()=>{
 const c=reactive(context()),r=c.items[0],prepared=prepareOwnerExchange(r,c);c.balance=100;r.catalog.points_cost=99;r.project.name='已改变';assert.equal(prepared.quote.catalog.points_cost,5);assert.equal(prepared.quote.balance,7);assert.equal(prepared.quote.project.name,'合成项目');assert.deepEqual(prepared.body,body())
 assert.throws(()=>prepareOwnerExchange(offer(),context()));const unavailable=context();unavailable.exchange_available=false;unavailable.items[0].eligibility='EXCHANGE_UNAVAILABLE';assert.throws(()=>prepareOwnerExchange(unavailable.items[0],unavailable))
})
test('owner persistence isolates checkin and other accounts without tokens or mutable request references',()=>{
 let actor=1;const storage=memory(),store=createOwnerExchangeRecovery({runtime:()=>storage,actor:()=>actor}),checkin=createCheckinRecovery({runtime:()=>storage,actor:()=>actor}),p=pending();store.savePending(p);p.body.catalog_id=99;assert.deepEqual(store.read().pending,pending());assert.equal(checkin.read().pending,null);actor=2;assert.equal(store.read().pending,null);actor=1;assert.throws(()=>store.savePending({...pending(),key:key2}));assert.equal(JSON.stringify([...storage.values.values()]).includes('token'),false);assert.throws(()=>store.complete({...pending(),key:key2}));store.complete(pending());assert.equal(store.read().pending,null)
})
test('cancelled confirmation never persists or sends and independent catalog/history pages are wired',async()=>{
 const calls=[];let writes=0;const h=harness({historyPage:()=>3,confirm:async()=>false,api:{catalog:async(t,p)=>{calls.push(['catalog',p]);return context()},history:async(t,p)=>{calls.push(['history',p]);return history()},exchange:async()=>{writes++}}});await h.flow.load(2);await h.flow.save('exchange',0,body());assert.deepEqual(calls,[['catalog',2],['history',3]]);assert.equal(writes,0);assert.equal(h.store.read().pending,null)
})
test('lost success response rebuild recovers same permanent request after directory disappears without a new key',async()=>{
 const storage=memory(),calls=[];let committed=false,lose=true;const api=createOwnerPointExchangeApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{if(o.method==='POST'){calls.push([o.header['Idempotency-Key'],JSON.parse(JSON.stringify(o.data))]);committed=true;if(lose){lose=false;o.fail();return}o.success({statusCode:200,data:{code:0,data:receipt()}});return}const data=o.url.includes('exchange-catalog')?(committed?{...context(),items:[],total:0,balance:2,exchange_available:false}:context()):history(committed?[receipt()]:[]);o.success({statusCode:200,data:{code:0,data}})}})})
 let h=harness({storage,api});await h.flow.load();await h.flow.save('exchange',0,body());assert.equal(h.state.retry,true);h.flow.reset();h=harness({storage,api});await h.flow.retry();assert.equal(calls.length,1);await h.flow.load();assert.equal(h.state.data.offers.items.length,0);await h.flow.retry();assert.deepEqual(calls,[[key,body()],[key,body()]]);assert.equal(h.store.read().pending,null);assert.equal(h.state.data.history.items.length,1)
})
test('storage failure prevents exchange and compare-and-clear cannot delete another page intent',async()=>{
 const storage=memory();let writes=0;const h=harness({storage,api:{exchange:async()=>{writes++}}});await h.flow.load();storage.setStorageSync=()=>{throw Error('quota')};await h.flow.save('exchange',0,body());assert.equal(writes,0);assert.equal(h.state.kind,'storage')
 const other=memory(),store=createOwnerExchangeRecovery({runtime:()=>other,actor:()=>1});store.savePending(pending());const mark=store.marker();store.complete(pending());store.savePending({...pending(),key:key2});assert.throws(()=>store.discard(mark));other.values.set('autocare:point-exchange:v1:1',{version:1,pending:{...pending(),body:{...body(),points_cost:1}}});assert.throws(()=>store.read())
})
test('late read and write responses cannot populate a hidden page or another account or clear original intent',async()=>{
 let actor=1,token='one',resolveWrite,resolveRead;const storage=memory(),h=harness({storage,actor:()=>actor,token:()=>token,api:{catalog:async()=>new Promise(r=>{resolveRead=r})}});const reading=h.flow.load();await new Promise(r=>setTimeout(r,0));h.flow.reset();resolveRead(context());await reading;assert.equal(h.state.data,null)
 const w=harness({storage,actor:()=>actor,token:()=>token,api:{exchange:()=>new Promise(r=>{resolveWrite=r})}});await w.flow.load();const writing=w.flow.save('exchange',0,body());await new Promise(r=>setTimeout(r,0));actor=2;token='two';w.flow.reset();resolveWrite(receipt());await writing;assert.equal(w.state.data,null);actor=1;assert.deepEqual(w.store.read().pending,pending())
})
test('401 preserves original account request and definite conflict releases it without replacement writes',async()=>{
 const storage=memory();let error=new ServiceError('unauthorized','expired'),writes=0;const api={exchange:async()=>{writes++;throw error}};let h=harness({storage,api});await h.flow.load();await h.flow.save('exchange',0,body());assert.deepEqual(h.store.read().pending,pending());assert.equal(h.state.kind,'unauthorized');h=harness({storage,api});await h.flow.load();error=new ServiceError('conflict','changed');await h.flow.retry();assert.equal(writes,2);assert.equal(h.store.read().pending,null);assert.equal(h.state.data,null)
})
test('confirmed write with local cleanup failure retains original until exact recovery succeeds',async()=>{
 const storage=memory();let writes=0;const h=harness({storage,api:{exchange:async()=>{writes++;return receipt()}}});await h.flow.load();storage.removeStorageSync=()=>{throw Error('storage unavailable')};await h.flow.save('exchange',0,body());assert.equal(h.state.kind,'storage');assert.equal(h.state.retry,true);assert.deepEqual(h.store.read().pending,pending());storage.removeStorageSync=k=>storage.values.delete(k);await h.flow.retry();assert.equal(writes,2);assert.equal(h.store.read().pending,null)
})
test('owner login return accepts only the static owner exchange route and ignores forged selectors',()=>{
 assert.equal(serviceLoginReturn([{route:'pages/points/exchange'},{}],'owner'),true);assert.equal(serviceLoginReturn([{route:'pages/points/exchange'},{}],'admin'),false);assert.equal(serviceLoginReturn([{route:'pages/points/exchange',options:{user_id:'2'}},{}],'owner'),false)
})
