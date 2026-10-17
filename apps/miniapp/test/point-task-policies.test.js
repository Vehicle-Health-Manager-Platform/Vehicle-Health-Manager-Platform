import test from 'node:test'
import assert from 'node:assert/strict'
import {watch} from 'vue'
import {POINT_TASKS,validPointPolicy,validPointTaskContext,createPointTaskPolicyApi} from '../src/services/point-task-policies.js'
import {createPointPolicyRecovery} from '../src/services/point-policy-recovery.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession,operatorLandingPage} from '../src/services/operator-session.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {ServiceError} from '../src/services/service-catalog.js'
const key='11111111-1111-4111-8111-111111111111'
const body=()=>({task_type:'CHECK_IN',points:7,source_note:'有来源的合成测试规则',valid_from:'2026-10-16T00:00:00Z',valid_to:'2026-11-16T00:00:00Z'})
const row=(over={})=>({id:1,...body(),revision:1,status:'DRAFT',rule_version:'R8A_TASK_V1',frequency:'DAILY',timezone:'Asia/Shanghai',created_at:'2026-10-16T00:00:00Z',published_at:null,...over})
const context=()=>({tasks:Object.keys(POINT_TASKS).map(task_type=>({task_type,frequency:task_type==='CHECK_IN'?'DAILY':'LIFETIME',state:'UNKNOWN',policy:null})),earning_available:false,timezone:'Asia/Shanghai'})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,JSON.parse(JSON.stringify(v))),removeStorageSync:k=>values.delete(k)}}
const pending=()=>({action:'create',id:0,body:body(),key})

test('point policies have exact task, source, points, time and fixed frequency contracts',()=>{
 assert.equal(validPointPolicy(row()),true)
 for(const over of [{points:0},{points:1.2},{points:1000001},{frequency:'LIFETIME'},{timezone:'UTC'},{source_note:' x '},{valid_to:'2026-10-16T00:00:00Z'},{status:'PUBLISHED'},{balance:100},{submission_key:key}])assert.equal(validPointPolicy(row(over)),false,JSON.stringify(over))
 assert.equal(validPointPolicy(row({task_type:'AI_FIRST_SUCCESS',frequency:'LIFETIME'})),true)
 assert.equal(validPointTaskContext(context()),true)
 for(const over of [{earning_available:'true'},{balance:0},{tasks:context().tasks.slice(0,2)},{timezone:'UTC'}])assert.equal(validPointTaskContext({...context(),...over}),false)
 const configured=context();configured.tasks[0]={...configured.tasks[0],state:'CONFIGURED',policy:row({status:'PUBLISHED',revision:2,published_at:'2026-10-16T00:00:01Z'})};assert.equal(validPointTaskContext(configured),true);configured.tasks[0].policy.task_type='AI_FIRST_SUCCESS';assert.equal(validPointTaskContext(configured),false)
})
test('policy API validates input, exact pages and original receipt before accepting writes',async()=>{
 const calls=[];let result=row();const api=createPointTaskPolicyApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data:result}})}})})
 assert.deepEqual(await api.write('token','create',0,body(),key),result);assert.equal(calls[0].method,'POST');assert.equal(calls[0].header['Idempotency-Key'],key)
 result={items:[row()],total:1,page:1,page_size:20};await api.list('token');result={...result,total:2};await assert.rejects(api.list('token'),e=>e.kind==='protocol')
 result=row({id:2,revision:2});await assert.rejects(api.write('token','update',1,{...body(),expected_revision:1},key),e=>e.kind==='protocol')
 for(const b of [{...body(),points:'7'},{...body(),frequency:'DAILY'},{...body(),points:0}])assert.throws(()=>api.write('token','create',0,b,key),e=>e.kind==='invalid')
 assert.throws(()=>api.list('token',0));assert.throws(()=>api.write('token','create',1,body(),key));assert.throws(()=>api.write('token','create',0,body(),'bad-key'))
})
test('account-isolated immutable policy recovery survives restarts and generic receipt expiry',()=>{
 const storage=memory();let actor=1;const store=createPointPolicyRecovery({runtime:()=>storage,actor:()=>actor}),original=pending();store.savePending(original);original.body.points=99;assert.equal(store.read().pending.body.points,7)
 const reconstructed=createPointPolicyRecovery({runtime:()=>storage,actor:()=>actor});assert.deepEqual(reconstructed.read().pending,pending());assert.throws(()=>reconstructed.savePending({...pending(),key:'22222222-2222-4222-8222-222222222222'}));assert.throws(()=>reconstructed.complete({...pending(),body:{...body(),points:8}}))
 actor=2;assert.equal(store.read().pending,null);actor=1;assert.equal(store.read().pending.key,key);assert.equal(Object.keys(storage.values.values().next().value).includes('saved_at'),false);assert.equal(JSON.stringify([...storage.values.values()]).includes('token'),false)
 store.complete(pending());assert.equal(store.read().pending,null)
})
test('reconstructed policy flow authorizes before storage and retries original body and key',async()=>{
 const storage=memory(),store=createPointPolicyRecovery({runtime:()=>storage,actor:()=>1});let authorized=false,writes=[];const state=initialDiagnosisState(),build=()=>createAsyncDiagnosisFlow({state,token:()=> 'op-token',scope:()=> 'point-task-policy',recovery:store,newKey:()=>key,confirm:async()=>true,load:async()=>{authorized=true;return {items:[]}},write:async(t,a,id,b,k)=>{assert.equal(authorized,true);writes.push([a,id,{...b},k]);throw new ServiceError('network','未知')}})
 let flow=build();await flow.save('create',0,body());assert.equal(writes.length,0);await flow.load();const b=body();await flow.save('create',0,b);b.points=999;assert.equal(state.retry,true);flow.reset();flow=build();authorized=false;await flow.retry();assert.equal(writes.length,1);await flow.load();await flow.retry();assert.deepEqual(writes[0],writes[1]);assert.equal(writes[1][2].points,7)
})
test('storage failure blocks dispatch and another page cannot clear a changed pending policy',async()=>{
 const storage=memory(),store=createPointPolicyRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();let writes=0;const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 't',scope:()=> 'point-task-policy',newKey:()=>key,confirm:async()=>true,load:async()=>({items:[]}),write:async()=>{writes++}});await flow.load();storage.setStorageSync=()=>{throw Error('quota')};await flow.save('create',0,body());assert.equal(state.kind,'storage');assert.equal(writes,0)
 const other=memory(),one=createPointPolicyRecovery({runtime:()=>other,actor:()=>1});one.savePending(pending());const marker=one.marker();one.complete(pending());one.savePending({...pending(),body:{...body(),points:8}});assert.throws(()=>one.discard(marker));assert.equal(one.read().pending.body.points,8)
})
test('401 hides a policy intent until reauthentication while explicit loss of permission removes it',async()=>{
 const storage=memory(),store=createPointPolicyRecovery({runtime:()=>storage,actor:()=>1});store.savePending(pending());let failure='unauthorized';const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 't',scope:()=> 'point-task-policy',load:async()=>{throw new ServiceError(failure,'失权')}});await flow.load();assert.equal(state.data,null);assert.equal(state.retry,false);assert.equal(store.read().pending.key,key);failure='forbidden';await flow.load();assert.equal(store.read().pending,null)
})
test('points capability is strict, independent and published before new token observers run',()=>{
 const user={id:31,role:'operator',can_review:false,can_onboard:false,can_catalog:false,can_coupon:false,can_invite:false,can_diagnose:false,can_diagnose_manage:false,can_points:true};assert.equal(validOperatorUser(user),true);assert.equal(validOperatorUser({...user,can_points:'true'}),false);assert.equal(validOperatorUser({...user,can_spend:true}),false)
 clearOperatorSession();let snapshot;const stop=watch(()=>operatorSession.accessToken,t=>{if(t)snapshot=[operatorSession.actorId,operatorSession.canPoints]},{flush:'sync'});setOperatorSession({access_token:'points-token',expires_in:900,user});assert.deepEqual(snapshot,[31,true]);assert.equal(operatorLandingPage(),'/pages/operator/points-policies');stop()
 assert.equal(serviceLoginReturn([{route:'pages/operator/points-policies'},{}],'admin',{canPoints:true}),true);assert.equal(serviceLoginReturn([{route:'pages/operator/points-policies'},{}],'admin',{canDiagnoseManage:true}),false);clearOperatorSession();assert.equal(operatorSession.canPoints,false)
})
