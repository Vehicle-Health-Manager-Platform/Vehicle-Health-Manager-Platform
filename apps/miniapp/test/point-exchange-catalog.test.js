import test from 'node:test'
import assert from 'node:assert/strict'
import {validPointExchangeBody,validPointExchangeCatalog,createPointExchangeCatalogApi} from '../src/services/point-exchange-catalog.js'
import {createPointExchangeRecovery} from '../src/services/point-exchange-recovery.js'
import {createPointPolicyRecovery} from '../src/services/point-policy-recovery.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {ServiceError} from '../src/services/service-catalog.js'
const key='11111111-1111-4111-8111-111111111111'
const body=()=>({coupon_id:1,publication_revision:2,pool_revision:1,points_cost:17,per_owner_limit:2,source_note:'有来源的合成兑换测试',valid_from:'2026-10-16T00:00:00Z',valid_to:'2026-11-16T00:00:00Z'})
const terms=()=>({name:'合成券',type:'SERVICE',project_id:1,amount:'0.00',min_spend:'0.00',total_quantity:3,valid_from:'2026-10-15T00:00:00Z',valid_to:'2026-11-16T00:00:00Z'})
const row=(over={})=>({id:1,...body(),terms:terms(),revision:1,status:'DRAFT',rule_version:'R8A_EXCHANGE_V1',limit_scope:'OWNER_COUPON_LIFETIME',created_at:'2026-10-16T00:00:00Z',published_at:null,...over})
const source=()=>({coupon_id:1,merchant_id:2,terms:terms(),revision:2,status:'PUBLISHED',stock_left:0,publication_revision:2,pool_selected:true,pool_revision:1})
const page=(items)=>({items,total:items.length,page:1,page_size:20})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,JSON.parse(JSON.stringify(v))),removeStorageSync:k=>values.delete(k)}}
const pending=()=>({action:'create',id:0,body:body(),key})
test('exchange catalog requires sourced exact price quota and bounded coupon window for both kinds',()=>{
 assert.equal(validPointExchangeCatalog(row()),true)
 assert.equal(validPointExchangeCatalog(row({terms:{...terms(),type:'DISCOUNT',amount:'5.00',min_spend:'10.00'}})),true)
 for(const over of [{points_cost:0},{per_owner_limit:0},{points_cost:1.5},{per_owner_limit:1000001},{pool_revision:0},{source_note:' x '},{limit_scope:'PER_REVISION'},{valid_to:'2026-11-17T00:00:00Z'},{balance:99},{status:'PUBLISHED'}])assert.equal(validPointExchangeCatalog(row(over)),false,JSON.stringify(over))
 assert.equal(validPointExchangeBody({...body(),user_id:2},'create'),false)
})
test('exchange API only accepts selected published source pages and immutable original receipts',async()=>{
 const calls=[];let result=page([source()]);const api=createPointExchangeCatalogApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data:result}})}})})
 await api.sources('token');assert.match(calls[0].url,/exchange-sources\?page=1&page_size=20$/)
 result=page([{...source(),pool_selected:false}]);await assert.rejects(api.sources('token'),e=>e.kind==='protocol')
 result=page([row()]);await api.list('token');result={...result,total:2};await assert.rejects(api.list('token'),e=>e.kind==='protocol')
 result=row();await api.write('token','create',0,body(),key);assert.equal(calls.at(-1).header['Idempotency-Key'],key);assert.equal(calls.at(-1).method,'POST')
 result=row({points_cost:18});await assert.rejects(api.write('token','create',0,body(),key),e=>e.kind==='protocol')
 assert.throws(()=>api.write('token','create',0,{...body(),points_cost:0},key),e=>e.kind==='invalid')
})
test('exchange pending requests isolate operator and task policy storage and reject multi-page overwrites',()=>{
 const storage=memory();let actor=1;const recovery=createPointExchangeRecovery({runtime:()=>storage,actor:()=>actor}),tasks=createPointPolicyRecovery({runtime:()=>storage,actor:()=>actor});const p=pending();recovery.savePending(p);p.body.points_cost=999;assert.equal(recovery.read().pending.body.points_cost,17);assert.equal(tasks.read().pending,null)
 actor=2;assert.equal(recovery.read().pending,null);actor=1;assert.throws(()=>recovery.savePending({...pending(),key:'22222222-2222-4222-8222-222222222222'}));assert.throws(()=>recovery.complete({...pending(),body:{...body(),points_cost:18}}));recovery.complete(pending());assert.equal(recovery.read().pending,null)
})
test('exchange corrupted and changed local intents require fresh server review before discard',()=>{
 const storage=memory(),recovery=createPointExchangeRecovery({runtime:()=>storage,actor:()=>1});recovery.savePending(pending());const marker=recovery.marker();storage.values.get('autocare:points-exchange-catalog:v1:1').pending.body.points_cost=19;assert.throws(()=>recovery.discard(marker));storage.values.set('autocare:points-exchange-catalog:v1:1',{version:1,pending:{...pending(),body:{...body(),per_owner_limit:0}}});assert.throws(()=>recovery.read());const corrupt=recovery.marker();recovery.discard(corrupt);assert.equal(recovery.read().pending,null)
})
test('exchange reload verifies server then retries the exact durable original without a second confirmation',async()=>{
 const storage=memory(),recovery=createPointExchangeRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState(),calls=[];recovery.savePending(pending());const flow=createAsyncDiagnosisFlow({state,token:()=> 'token',scope:()=> 'catalog',recovery,newKey:()=>{throw Error('must not replace key')},load:async()=>page([row()]),write:async(...args)=>{calls.push(args);return row()}})
 await flow.retry();assert.equal(calls.length,0);await flow.load();assert.equal(state.retry,true);await flow.retry();assert.deepEqual(calls[0],['token','create',0,body(),key]);assert.equal(recovery.read().pending,null)
})
test('exchange storage failure blocks writes and delayed completion cannot affect another operator',async()=>{
 let actor=1,token='one',resolve;const storage=memory(),recovery=createPointExchangeRecovery({runtime:()=>storage,actor:()=>actor}),state=initialDiagnosisState();let writes=0;const flow=createAsyncDiagnosisFlow({state,token:()=>token,scope:()=>String(actor),recovery,newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:()=>{writes++;return new Promise(r=>{resolve=r})}})
 await flow.load();storage.setStorageSync=()=>{throw Error('full')};await flow.save('create',0,body());assert.equal(writes,0);assert.equal(state.kind,'storage')
 storage.setStorageSync=(k,v)=>storage.values.set(k,JSON.parse(JSON.stringify(v)));await flow.load();const write=flow.save('create',0,body());await new Promise(r=>setTimeout(r,0));assert.equal(writes,1);actor=2;token='two';flow.reset();resolve(row());await write;assert.equal(state.data,null);actor=1;assert.deepEqual(recovery.read().pending,pending())
})
test('exchange transient transport error retains original request and exact permission controls login return',async()=>{
 const storage=memory(),recovery=createPointExchangeRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();const flow=createAsyncDiagnosisFlow({state,token:()=> 'token',scope:()=> 'catalog',recovery,newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:async()=>{throw new ServiceError('network','lost')}});await flow.load();await flow.save('create',0,body());assert.equal(state.retry,true);assert.deepEqual(recovery.read().pending,pending());assert.equal(serviceLoginReturn([{route:'pages/operator/points-exchange'},{}],'admin',{canPoints:true}),true);assert.equal(serviceLoginReturn([{route:'pages/operator/points-exchange'},{}],'admin',{canCoupon:true}),false)
})
