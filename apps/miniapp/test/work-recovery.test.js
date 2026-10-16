import test from 'node:test'
import assert from 'node:assert/strict'
import {createWorkRecovery,validWorkDraft,validWorkIntent,workIntentApplied} from '../src/services/work-recovery.js'
import {createAsyncDiagnosisFlow,initialDiagnosisState} from '../src/services/async-diagnosis.js'
import {setTechnicianSession,clearTechnicianSession,technicianSession} from '../src/services/technician-session.js'
import {useRoleIdentity} from '../src/services/role-identity.js'
import {watch} from 'vue'
const key='00000000-0000-4000-8000-000000000001'
const draft=()=>({plan:'维修文字',analysis:'原因',minutes:'20',noFaultParts:true,noParts:true,parts:[]})
const body=()=>({order_id:9,process_photos:[11],fault_part_photos:[],finish_photos:[12],no_fault_parts:true,repair_plan:'维修文字',fault_analysis:'原因',parts_used:[],no_parts:true,work_hours:20})
const intent=()=>({action:'report',id:9,body:body(),key})
const view=()=>({job:{order_id:9,order_status:'IN_SERVICE',assignment_status:'ACCEPTED'},work:{order_id:9,order_status:'IN_SERVICE',report:null}})
function setup(){const disk=new Map();let actor=1,order=9,now=1000,broken=false;const store=createWorkRecovery({actor:()=>actor,order:()=>order,now:()=>now,runtime:()=>({getStorageSync:k=>structuredClone(disk.get(k)),setStorageSync:(k,v)=>{if(broken)throw new Error('quota');disk.set(k,structuredClone(v))},removeStorageSync:k=>{if(broken)throw new Error('quota');disk.delete(k)}})});return {store,disk,actor:n=>actor=n,order:n=>order=n,time:n=>now=n,broken:n=>broken=n}}
test('drafts save only editable text fields, isolate staff and order, and expire in seven days',()=>{
 const h=setup();h.store.saveDraft(draft());assert.deepEqual(h.store.read().draft,draft());h.actor(2);assert.equal(h.store.read().draft,null);h.actor(1);h.order(10);assert.equal(h.store.read().draft,null);h.order(9);h.time(8*86400000);assert.equal(h.store.read().draft,null)
 for(const extra of [{signature:'bitmap'},{url:'https://private'},{token:'private'},{photo_files:[11]}])assert.equal(validWorkDraft({...draft(),...extra}),false)
})
test('original report and signature references survive reconstruction without editable images',()=>{
 const h=setup(),n=intent();h.store.savePending(n);n.body.repair_plan='后改';assert.equal(h.store.read().pending.body.repair_plan,'维修文字');h.time(100*86400000);assert.equal(h.store.read().pending.key,key);h.store.complete(intent());const sign={action:'sign',id:9,body:{order_id:9,signature_file_id:13},key};h.store.savePending(sign);assert.deepEqual(h.store.read().pending,sign);assert.equal(validWorkIntent({...sign,body:{...sign.body,localPath:'/private.png'}},9),false)
})
test('fresh current server facts reconcile a lost report response without another write',()=>{
 const h=setup();h.store.savePending(intent());const v=view();v.work.report={...body(),report_id:1,status:'SUBMITTED'};delete v.work.report.order_id;assert.equal(h.store.reconcile(v),true);assert.equal(h.disk.size,0)
 for(const change of [n=>n.job.assignment_status='ASSIGNED',n=>n.work.order_id=10,n=>n.job.order_status='CLOSED',n=>n.work.report.repair_plan='不同施工']){const h=setup();h.store.savePending(intent());const snapshot=structuredClone(v);change(snapshot);assert.equal(h.store.reconcile(snapshot),false);assert.ok(h.store.read().pending)}
})
test('only the original signed file id acknowledges a previously confirmed signature',()=>{
 const n={action:'sign',id:9,key,body:{order_id:9,signature_file_id:13}},v=view();v.work.order_status=v.job.order_status='PENDING_VERIFY';v.work.report={status:'SIGNED',signature_file_id:13};assert.equal(workIntentApplied(n,v),true);v.work.report.signature_file_id=14;assert.equal(workIntentApplied(n,v),false)
})
test('two pages cannot overwrite an unknown request or clear a later request',()=>{
 const h=setup(),first=intent(),second={...intent(),key:'00000000-0000-4000-8000-000000000002'};h.store.savePending(first);assert.throws(()=>h.store.savePending(second));assert.throws(()=>h.store.saveDraft(draft()));h.store.complete(first);h.store.savePending(second);assert.throws(()=>h.store.complete(first));assert.equal(h.store.read().pending.key,second.key)
})
test('page rebuild retries immutable work body/key only after authorized loading',async()=>{
 const h=setup(),calls=[];function page(){const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 'staff',scope:()=> 'tech:9',recovery:h.store,load:async()=>view(),write:async(...args)=>{calls.push(structuredClone(args));throw Object.assign(new Error('network'),{kind:'network'})},confirm:async()=>true,newKey:()=>key});return {state,flow}}const a=page();await a.flow.load();await a.flow.save('report',9,body());assert.equal(a.state.retry,true);const b=page();await b.flow.retry();assert.equal(calls.length,1);await b.flow.load();await b.flow.retry();assert.deepEqual(calls[0],calls[1]);
})
test('storage failure blocks new dispatch and definite rejection lets user replace bad files',async()=>{
 const h=setup();let calls=0;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 'staff',scope:()=> 'tech:9',recovery:h.store,load:async()=>view(),write:async()=>{calls++;throw Object.assign(new Error('rejected'),{kind:'rejected'})},confirm:async()=>true,newKey:()=>key});await flow.load();h.broken(true);await flow.save('report',9,body());assert.equal(calls,0);h.broken(false);await flow.save('report',9,body());assert.equal(calls,1);assert.equal(state.retry,false);assert.equal(h.store.read().pending,null)
})
test('technician actor id is in memory from the login response and clears with session',()=>{
 setTechnicianSession({access_token:'test',user:{id:12}});assert.equal(technicianSession.actorId,12);clearTechnicianSession();assert.equal(technicianSession.actorId,0);setTechnicianSession({access_token:'no-id'});assert.equal(technicianSession.actorId,0);clearTechnicianSession()
})
test('real login flow sets verified staff id before publishing its new token to other pages',async()=>{
 setTechnicianSession({access_token:'old',user:{id:12}});const seen=[],stop=watch(()=>technicianSession.accessToken,()=>seen.push(technicianSession.actorId),{flush:'sync'});const flow=useRoleIdentity('technician',{requestWechatLogin:async()=>({access_token:'new',user:{id:13,role:'technician'}})});await flow.tryLogin();assert.deepEqual(seen,[13]);stop();clearTechnicianSession()
})
