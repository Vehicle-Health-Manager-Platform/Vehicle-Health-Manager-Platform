import test from 'node:test'
import assert from 'node:assert/strict'
import {validPointsOverview,validPointsFlow,createPointsApi,createCheckinRecovery} from '../src/services/points-ledger.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const key='11111111-1111-4111-8111-111111111111',day='2026-10-16',at=day+'T00:00:00Z'
const body=()=>({day,policy_id:1,policy_revision:2}),intent=()=>({action:'checkin',id:0,body:body(),key})
const policy=()=>({id:1,task_type:'CHECK_IN',points:7,source_note:'合成可核对来源',revision:2,status:'PUBLISHED',rule_version:'R8A_TASK_V1',frequency:'DAILY',timezone:'Asia/Shanghai',valid_from:at,valid_to:'2026-11-16T00:00:00Z',created_at:at,published_at:at})
const overview=()=>({balance:0,balance_scope:'VERIFIED_TASKS_V1',legacy_state:'NONE',earning_available:false,day,timezone:'Asia/Shanghai',tasks:['CHECK_IN','ARCHIVE_FIRST_COMPLETE','AI_FIRST_SUCCESS'].map((task_type,i)=>({task_type,frequency:i===0?'DAILY':'LIFETIME',policy:null,state:'UNKNOWN',points_awarded:0,occurred_at:null,credited_at:null}))})
const entry=()=>({id:1,delta:7,balance_after:7,created_at:at,task_type:'CHECK_IN',period_key:day,policy_id:1,policy_revision:2})
const page=()=>({items:[entry()],total:1,page:1,page_size:20})
const memory=()=>{const map=new Map();return {map,getStorageSync:k=>map.get(k),setStorageSync:(k,v)=>map.set(k,JSON.parse(JSON.stringify(v))),removeStorageSync:k=>map.delete(k)}}
test('verified balance overview distinguishes unknown legacy data, eligibility and pending credit',()=>{
 const n=overview();assert.equal(validPointsOverview(n),true);assert.equal(validPointsOverview({...n,legacy_state:'REVIEW_REQUIRED'}),true)
 for(const over of [{balance:'0'},{balance:-1},{balance_scope:'ALL_POINTS'},{legacy_state:'IMPORTED'},{earning_available:'true'},{day:'2026-02-30'},{total_balance:1280},{tasks:n.tasks.slice(0,2)}])assert.equal(validPointsOverview({...n,...over}),false)
 n.tasks[0]={...n.tasks[0],state:'READY',policy:policy()};assert.equal(validPointsOverview(n),false);n.earning_available=true;assert.equal(validPointsOverview(n),true)
 n.tasks[0]={...n.tasks[0],state:'PENDING',occurred_at:at};assert.equal(validPointsOverview(n),true);n.tasks[0].points_awarded=7;assert.equal(validPointsOverview(n),false);n.tasks[0].state='CREDITED';n.tasks[0].credited_at=at;n.balance=7;assert.equal(validPointsOverview(n),true)
 n.tasks[0].policy=null;assert.equal(validPointsOverview(n),true);n.tasks[0].credited_at=null;assert.equal(validPointsOverview(n),false)
})
test('point flow accepts only exact owner ledger entries and safe unique pagination',()=>{
 assert.equal(validPointsFlow(page(),1),true)
 for(const fields of [{delta:0},{delta:-7},{balance_after:6},{period_key:'LIFETIME'},{task_type:'FORGED'},{source_hash:'private'},{user_id:2}])assert.equal(validPointsFlow({...page(),items:[{...entry(),...fields}]},1),false)
 assert.equal(validPointsFlow({...page(),total:2},1),false);assert.equal(validPointsFlow({...page(),items:[entry(),entry()],total:2},1),false)
})
test('checkin API binds day, server policy and immutable original key while rejecting forged fields',async()=>{
 const calls=[];let response={event_id:1,day,status:'PENDING',points:7};const api=createPointsApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data:response}})}})})
 await api.checkin('token',body(),key);assert.equal(calls[0].method,'POST');assert.equal(calls[0].header['Idempotency-Key'],key);assert.deepEqual(calls[0].data,body());response={...response,day:'2026-10-17'};await assert.rejects(api.checkin('token',body(),key),e=>e.kind==='protocol')
 for(const b of [{...body(),eligible:true},{...body(),points:1000},{...body(),day:'2026-02-30'}])assert.throws(()=>api.checkin('token',b,key),e=>e.kind==='invalid')
 assert.throws(()=>api.flow('token',0));assert.throws(()=>api.checkin('token',body(),'bad'))
})
test('stored original checkin remains bound to its old day across reconstruction and actor switches',()=>{
 const storage=memory();let actor=1;const store=createCheckinRecovery({runtime:()=>storage,actor:()=>actor}),p=intent();store.savePending(p);p.body.day='2026-10-17';assert.equal(store.read().pending.body.day,day)
 assert.throws(()=>store.savePending({...intent(),body:{...body(),day:'2026-10-17'}}));actor=2;assert.equal(store.read().pending,null);actor=1;const rebuilt=createCheckinRecovery({runtime:()=>storage,actor:()=>actor});assert.equal(rebuilt.read().pending.key,key);assert.throws(()=>rebuilt.complete({...intent(),key:'22222222-2222-4222-8222-222222222222'}));assert.equal(JSON.stringify([...storage.map.values()]).includes('token'),false)
})
test('unknown checkin rebuild requires fresh authorization then retries old day even if new day is shown',async()=>{
 const storage=memory(),store=createCheckinRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState(),writes=[];let today=day
 const build=()=>createAsyncDiagnosisFlow({state,recovery:store,token:()=> 'token',scope:()=> 'point-checkin',newKey:()=>key,confirm:async()=>true,load:async()=>({day:today}),write:async(t,a,id,b,k)=>{writes.push([{...b},k]);throw new ServiceError('network','未知')}})
 let flow=build();await flow.load();await flow.save('checkin',0,body());flow.reset();today='2026-10-17';flow=build();await flow.retry();assert.equal(writes.length,1);await flow.load();await flow.retry();assert.deepEqual(writes[0],writes[1]);assert.equal(writes[1][0].day,day)
})
test('definite conflict releases original pending without sending a replacement day',async()=>{
 const storage=memory(),store=createCheckinRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();store.savePending(intent());let writes=0
 const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 'token',scope:()=> 'point-checkin',load:async()=>({day:'2026-10-17'}),write:async()=>{writes++;throw new ServiceError('conflict','日期变化')}});await flow.load();await flow.retry();assert.equal(writes,1);assert.equal(state.retry,false);assert.equal(store.read().pending,null);assert.equal(state.data,null)
})
test('checkin storage failure blocks network and manual cleanup cannot delete another page request',async()=>{
 const storage=memory(),store=createCheckinRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();let writes=0;const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 'token',scope:()=> 'point-checkin',load:async()=>({}),newKey:()=>key,confirm:async()=>true,write:async()=>{writes++}});await flow.load();storage.setStorageSync=()=>{throw Error('quota')};await flow.save('checkin',0,body());assert.equal(writes,0);assert.equal(state.kind,'storage')
 const other=memory(),store2=createCheckinRecovery({runtime:()=>other,actor:()=>1});store2.savePending(intent());const marker=store2.marker();store2.complete(intent());store2.savePending({...intent(),body:{...body(),day:'2026-10-17'}});assert.throws(()=>store2.discard(marker))
})
test('points page return is owner-only and no query can grant another role access',()=>{
 const pages=[{route:'pages/points/index',options:{role:'owner'}},{route:'pages/owner/index'}];assert.equal(serviceLoginReturn(pages,'owner'),true);assert.equal(serviceLoginReturn(pages,'merchant'),false);assert.equal(serviceLoginReturn(pages,'admin',{canPoints:true}),false)
})
