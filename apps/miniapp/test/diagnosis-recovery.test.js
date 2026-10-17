import test from 'node:test'
import assert from 'node:assert/strict'
import {createDiagnosisRecovery} from '../src/services/diagnosis-recovery.js'
import {createAsyncDiagnosisFlow,initialDiagnosisState} from '../src/services/async-diagnosis.js'
import {ownerSession,setOwnerSession,clearOwnerSession} from '../src/services/owner-session.js'
import {operatorSession,setOperatorSession,clearOperatorSession} from '../src/services/operator-session.js'
const key='00000000-0000-4000-8000-000000000001'
const body={expected_revision:2,message:'原始答复'}
const failure=kind=>Object.assign(new Error(kind),{kind})
function setup(){
 const disk=new Map();let actor=1,role='admin',scope='admin:9',time=1000,deny='',broken=false,deleteBroken=false,calls=[]
 const runtime=()=>({getStorageSync:k=>structuredClone(disk.get(k)),setStorageSync:(k,v)=>{if(broken)throw new Error('存储已满');disk.set(k,structuredClone(v))},removeStorageSync:k=>{if(deleteBroken)throw new Error('清理失败');disk.delete(k)}})
 const recovery=createDiagnosisRecovery({runtime,actor:()=>actor,role:()=>role,scope:()=>scope,now:()=>time})
 function page(write=async()=>{throw failure('network')}){const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,recovery,token:()=>`session-${actor}`,scope:()=>scope,load:async()=>{if(deny)throw failure(deny);return {server:true}},write:async(...args)=>{calls.push(structuredClone(args));return write(...args)},newKey:()=>key,confirm:async()=>true});return {state,flow}}
 return {disk,recovery,page,calls,setActor:v=>actor=v,setRole:v=>role=v,setScope:v=>scope=v,setTime:v=>time=v,setDeny:v=>deny=v,setBroken:v=>broken=v,setDeleteBroken:v=>deleteBroken=v}
}
test('page reconstruction restores original immutable intent only after fresh server authorization',async()=>{
 const h=setup(),a=h.page();await a.flow.load();const b={...body};await a.flow.save('reply',9,b);b.message='编辑后的文字';const second=h.page(async()=>({case_id:9}));await second.flow.retry();assert.equal(h.calls.length,1);await second.flow.load();assert.equal(second.state.retry,true);await second.flow.retry();assert.deepEqual(h.calls[0],h.calls[1]);assert.equal(second.state.retry,false);assert.equal(h.disk.size,0)
})
test('another account and another case never see or retry saved private content',async()=>{
 const h=setup(),a=h.page();await a.flow.load();await a.flow.save('reply',9,body)
 for(const change of [()=>h.setActor(2),()=>{h.setActor(1);h.setScope('admin:10')}]){change();const p=h.page();await p.flow.load();assert.equal(p.state.retry,false);assert.equal(p.flow.restoreDraft(),null);await p.flow.retry();assert.equal(h.calls.length,1)}
})
test('fresh permission denial removes inaccessible local content without reading it into page',async()=>{
 const h=setup(),a=h.page();await a.flow.load();a.flow.saveDraft({text:'未提交隐私文字',vehicle:0});await a.flow.save('reply',9,body);h.setDeny('forbidden');const p=h.page();await p.flow.load();assert.equal(p.state.data,null);assert.equal(p.state.hasDraft,false);assert.equal(p.flow.restoreDraft(),null);assert.equal(h.disk.size,0);await p.flow.retry();assert.equal(h.calls.length,1)
})
test('expired login hides recovery until reauthentication without losing an unknown committed intent',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('reply',9,body);h.setDeny('unauthorized');await p.flow.load();assert.equal(p.state.retry,false);assert.equal(p.flow.restoreDraft(),null);assert.equal(h.disk.size,1);h.setDeny('');const again=h.page();await again.flow.load();assert.equal(again.state.retry,true);await again.flow.retry();assert.deepEqual(h.calls[0],h.calls[1])
})
test('failed authorization refresh cannot reuse earlier authorization for persistent retry',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('reply',9,body);p.flow.reset();h.setDeny('network');await p.flow.load();await p.flow.retry();assert.equal(h.calls.length,1);assert.equal(h.disk.size,1);h.setDeny('');await p.flow.load();await p.flow.retry();assert.equal(h.calls.length,2)
})
test('storage failure before dispatch prevents business write and allows later recovery',async()=>{
 const h=setup(),p=h.page();await p.flow.load();h.setBroken(true);await p.flow.save('reply',9,body);assert.equal(h.calls.length,0);assert.equal(p.state.kind,'storage');h.setBroken(false);await p.flow.save('reply',9,body);assert.equal(h.calls.length,1);assert.ok(h.recovery.read().pending)
})
test('confirmed response with local cleanup failure retains key and blocks fresh write',async()=>{
 const h=setup(),p=h.page(async()=>({case_id:9}));await p.flow.load();h.setDeleteBroken(true);await p.flow.save('reply',9,body);assert.equal(p.state.retry,true);assert.match(p.state.message,/服务器已确认/);await p.flow.save('reply',9,{...body,message:'第二条'});assert.equal(h.calls.length,1);h.setDeleteBroken(false);await p.flow.retry();assert.deepEqual(h.calls[0],h.calls[1]);assert.equal(h.disk.size,0)
})
test('draft is explicit, expires after seven days, never stores consent or credentials',async()=>{
 const h=setup(),p=h.page();p.flow.saveDraft({text:'not authorized',vehicle:0});assert.equal(h.disk.size,0);await p.flow.load();p.flow.saveDraft({text:'草稿',vehicle:0});assert.deepEqual(p.flow.restoreDraft(),{text:'草稿',vehicle:0});const serialized=JSON.stringify([...h.disk.values()]);assert.ok(!serialized.includes('session-'));assert.ok(!serialized.includes('consent'));h.setTime(8*86400000);assert.equal(h.recovery.read().draft,null)
})
test('invalid local payload is rejected rather than dispatched',()=>{
 const h=setup();for(const bad of [{action:'assign',id:9,body:{expected_revision:2,operator_id:1001},key},{action:'reply',id:10,body,key},{action:'reply',id:9,body:{...body,token:'private'},key}])assert.throws(()=>h.recovery.savePending(bad));h.disk.set('autocare:diagnosis:v1:admin:1:admin:9',{token:'private'});assert.throws(()=>h.recovery.read(),/损坏/)
})
test('expired draft does not expire unresolved business intent',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('reply',9,body);h.setTime(100*86400000);assert.equal(h.recovery.read().pending.key,key)
})
test('owner creation and followup recovery bind role, consent and exact source revision',()=>{
 const h=setup();h.setRole('owner');h.setScope('diagnosis-owner');const original={action:'create',id:0,key,body:{vehicle_id:901,policy_id:7,policy_revision:3,message:'主动提问',consent:true}};h.recovery.savePending(original);original.body.message='之后编辑';assert.equal(h.recovery.read().pending.body.message,'主动提问');h.setRole('admin');assert.throws(()=>h.recovery.read(),/范围/);h.setRole('owner');h.setScope('owner:9');assert.throws(()=>h.recovery.savePending(original));h.recovery.savePending({action:'message',id:9,key,body:{expected_revision:2,policy_id:7,policy_revision:3,message:'追加',consent:true}});assert.equal(h.recovery.read().pending.body.policy_revision,3)
})
test('two open pages cannot overwrite unknown intent or clear a later request',()=>{
 const h=setup(),first={action:'reply',id:9,body,key},second={...first,key:'00000000-0000-4000-8000-000000000002'};h.recovery.savePending(first);assert.throws(()=>h.recovery.savePending(second),/待确认/);assert.throws(()=>h.recovery.saveDraft({text:'别页正文',vehicle:0}),/待确认/);h.recovery.complete(first);h.recovery.savePending(second);assert.throws(()=>h.recovery.complete(first),/已变化/);assert.equal(h.recovery.read().pending.key,second.key)
})
test('pending writes do not extend the seven day draft retention period',()=>{
 const h=setup();h.recovery.saveDraft({text:'草稿',vehicle:0});h.setTime(6*86400000);h.recovery.savePending({action:'reply',id:9,body,key});h.setTime(8*86400000);assert.equal(h.recovery.read().draft,null);assert.equal(h.recovery.read().pending.key,key)
})
test('session ids come from login response, survive phone binding, and clear on account change',()=>{
 clearOwnerSession();setOwnerSession({access_token:'a',user:{id:5,phone_bound:false}});setOwnerSession({access_token:'a',user:{phone_bound:true}});assert.equal(ownerSession.actorId,5);setOwnerSession({access_token:'b',user:{phone_bound:true}});assert.equal(ownerSession.actorId,0);clearOwnerSession();assert.equal(ownerSession.actorId,0)
 setOperatorSession({access_token:'admin',expires_in:900,user:{id:19}});assert.equal(operatorSession.actorId,19);clearOperatorSession();assert.equal(operatorSession.actorId,0)
})
