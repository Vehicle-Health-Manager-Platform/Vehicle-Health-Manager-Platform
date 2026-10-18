import test from 'node:test'
import assert from 'node:assert/strict'
import {createInputMaterialApi,createMaterialUploadFlow,materialScope} from '../src/services/input-material-upload.js'
const key='12345678-1234-4234-8234-123456789abc'
const scope={purpose:'AI',vehicleId:11},item={id:'a',kind:'audio',path:'wxfile://tmp/voice',size:6480}
const data={material_id:9,purpose:'AI',vehicle_id:11,content_type:'audio/mpeg',size_bytes:6480,duration_ms:1080,pages:0,scan_status:'CLEAN'}
function harness(options={}){const calls=[];let aborts=0;const api=createInputMaterialApi({baseUrl:'https://api.example.test',runtime:()=>({uploadFile:o=>{calls.push(o);return {abort(){aborts++}}}}),...options});return {api,calls,aborts:()=>aborts}}
test('scopes reject unset, unsafe and arbitrary purpose values',()=>{
 assert.deepEqual(materialScope('archive:11:1'),{purpose:'ARCHIVE',vehicleId:11});assert.deepEqual(materialScope('ai:11'),scope)
 for(const value of ['ai:0','ai:9007199254740992','other:11','archive:11:0','../ai:11'])assert.equal(materialScope(value),null)
})
test('native upload uses exact purpose, bearer, kind and original retry key without form fields',async()=>{
 const h=harness(),op=h.api.upload('session',scope,item,key);const request=h.calls[0]
 assert.equal(request.url,'https://api.example.test/api/input-materials/AI/11/upload');assert.equal(request.name,'file');assert.equal(request.formData,undefined)
 assert.equal(request.header.Authorization,'Bearer session');assert.equal(request.header['Idempotency-Key'],key);assert.equal(request.header['X-Material-Kind'],'audio')
 request.success({statusCode:200,data:JSON.stringify({code:0,data})});assert.equal((await op.promise).materialId,9)
})
test('cloud/sentinel/unconfigured service cannot leak multipart or fake success',async()=>{
 for(const config of [{cloudMode:true},{baseUrl:''},{baseUrl:'https://local-preview.invalid'}]){const h=harness(config);await assert.rejects(h.api.upload('session',scope,item,key).promise,/后端地址/);assert.equal(h.calls.length,0)}
})
test('remote paths, invalid kind/size and wrong vehicle are rejected before upload',async()=>{
 for(const bad of [{...item,path:'https://attacker.test/file.mp3'},{...item,size:0},{...item,size:4*1024*1024},{...item,kind:'image'}]){const h=harness();await assert.rejects(h.api.upload('session',scope,bad,key).promise);assert.equal(h.calls.length,0)}
})
test('status and malformed responses expose fixed messages only',async()=>{
 for(const status of [401,403,404,409,413,422,429,503]){const h=harness(),op=h.api.upload('session',scope,item,key);h.calls[0].success({statusCode:status,data:{message:'secret filename'}});await assert.rejects(op.promise,e=>!e.message.includes('secret'))}
 for(const changes of [{vehicle_id:12},{purpose:'ARCHIVE'},{scan_status:'PENDING'},{material_id:0},{duration_ms:60001},{content_type:'image/png'},{size_bytes:1}]){const h=harness(),op=h.api.upload('session',scope,item,key);h.calls[0].success({statusCode:200,data:{code:0,data:{...data,...changes}}});await assert.rejects(op.promise,/校验失败/)}
 const h=harness(),op=h.api.upload('session',scope,item,key);h.calls[0].success({statusCode:200,data:'<html>secret</html>'});await assert.rejects(op.promise,/响应无效/)
})
test('abort settles immediately and ignores late native success',async()=>{
 const h=harness(),op=h.api.upload('session',scope,item,key);op.abort();h.calls[0].success({statusCode:200,data:{code:0,data}});await assert.rejects(op.promise,/已取消/);assert.equal(h.aborts(),1)
})
function flowHarness(){let token='owner',current='ai:11';const state={busyId:'',message:'',results:{}},items=[item],operations=[],keys=[]
 const flow=createMaterialUploadFlow({state,items:()=>items,actor:()=>token,scope:()=>current,keyFactory:()=>key,api:{upload:(actor,scope,item,key)=>{keys.push(key);let resolve,reject;const promise=new Promise((r,j)=>{resolve=r;reject=j});const op={promise,resolve,reject,aborted:false,abort(){this.aborted=true;reject(new Error('cancelled'))}};operations.push(op);return op}}})
 return {state,flow,items,operations,keys,setActor:v=>token=v,setScope:v=>current=v}}
test('flow prevents duplicate clicks, retries same key, and never resubmits successful material',async()=>{
 const h=flowHarness(),first=h.flow.upload('a');await h.flow.upload('a');assert.equal(h.operations.length,1);h.operations[0].reject(new Error('network'));await first
 const next=h.flow.upload('a');assert.deepEqual(h.keys,[key,key]);h.operations[1].resolve({materialId:9});await next;await h.flow.upload('a');assert.equal(h.operations.length,2);assert.match(h.state.message,/尚未识别/)
})
test('context change, remove and hide/reset fence late responses',async()=>{
 for(const action of ['actor','scope','remove','reset']){const h=flowHarness(),pending=h.flow.upload('a');if(action==='actor')h.setActor('another');if(action==='scope')h.setScope('archive:11:1');if(action==='remove')h.items.length=0;if(action==='reset')h.flow.reset();h.operations[0].resolve({materialId:9});await pending;assert.deepEqual(h.state.results,{});if(action==='reset')assert.equal(h.operations[0].aborted,true)}
})
