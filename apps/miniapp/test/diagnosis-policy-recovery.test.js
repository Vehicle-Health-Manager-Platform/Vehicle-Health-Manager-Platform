import test from 'node:test'
import assert from 'node:assert/strict'
import {createDiagnosisRecovery,validDiagnosisIntent} from '../src/services/diagnosis-recovery.js'
import {createAsyncDiagnosisFlow,initialDiagnosisState} from '../src/services/async-diagnosis.js'
const key='00000000-0000-4000-8000-000000000001',scope='diagnosis-service-policy'
const body=()=>({mode:'AVAILABLE',source_note:'配置来源',valid_from:'2026-10-16T00:00:00Z',valid_to:'2026-11-01T00:00:00Z'})
function setup(){const disk=new Map();let now=1000,actor=1,allowed=true,failRead=false,confirm=true,calls=0,reads=0;const recovery=createDiagnosisRecovery({actor:()=>actor,role:()=> 'admin',scope:()=>scope,now:()=>now,runtime:()=>({getStorageSync:k=>structuredClone(disk.get(k)),setStorageSync:(k,v)=>disk.set(k,structuredClone(v)),removeStorageSync:k=>disk.delete(k)})});function page(){const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,recovery,token:()=> 'op',allowed:()=>allowed,scope:()=>scope,newKey:()=>key,load:async()=>{reads++;if(failRead)throw new Error('network');return {items:[]}},write:async()=>{calls++;throw Object.assign(new Error('network'),{kind:'network'})},confirm:async()=>typeof confirm==='function'?confirm():confirm});return {state,flow}}return {recovery,disk,page,time:n=>now=n,actor:n=>actor=n,allowed:n=>allowed=n,fail:n=>failRead=n,confirm:n=>confirm=n,calls:()=>calls,reads:()=>reads}}
test('policy requests bind admin scope, strict fields and immutable publication revision',()=>{
 assert.ok(validDiagnosisIntent({action:'create',id:0,body:body(),key},'admin',scope));assert.equal(validDiagnosisIntent({action:'create',id:0,body:body(),key},'owner',scope),false);assert.equal(validDiagnosisIntent({action:'create',id:0,body:{...body(),can_diagnose:true},key},'admin',scope),false);assert.ok(validDiagnosisIntent({action:'status',id:3,body:{expected_revision:2,status:'PUBLISHED'},key},'admin',scope))
})
test('rebuilt policy page restores original key but an open page cannot retry beyond safe receipt window',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('create',0,body());assert.equal(h.calls(),1);const rebuilt=h.page();await rebuilt.flow.load();await rebuilt.flow.retry();assert.equal(h.calls(),2);h.time(24*3600000);await rebuilt.flow.retry();assert.equal(h.calls(),2);assert.equal(rebuilt.state.kind,'storage');assert.match(rebuilt.state.message,/窗口/);assert.equal(h.disk.size,1);h.actor(2);assert.equal(h.recovery.read().pending,null)
})
test('discard rechecks server scope and explicit review before clearing only local record',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('create',0,body());h.time(24*3600000);h.fail(true);await p.flow.discardLocal();assert.equal(h.disk.size,1);h.fail(false);h.confirm(false);await p.flow.discardLocal();assert.equal(h.disk.size,1);h.confirm(true);h.allowed(false);const before=h.reads();await p.flow.discardLocal();assert.equal(h.reads(),before);assert.equal(h.disk.size,1);h.allowed(true);await p.flow.discardLocal();assert.equal(h.disk.size,0);assert.equal(h.calls(),1);assert.equal(p.state.retry,false)
})

test('manual cleanup cannot erase a newer unresolved policy written while review dialog is open',async()=>{
 const h=setup(),p=h.page();await p.flow.load();await p.flow.save('create',0,body());h.confirm(()=>{const [k,v]=[...h.disk.entries()][0];h.disk.set(k,{...v,pending:{...v.pending,key:'00000000-0000-4000-8000-000000000002'}});return true});await p.flow.discardLocal();assert.equal(h.disk.size,1);assert.equal(p.state.kind,'storage');assert.equal([...h.disk.values()][0].pending.key,'00000000-0000-4000-8000-000000000002')
})
