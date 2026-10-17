import test from 'node:test'
import assert from 'node:assert/strict'
import {TASK_BUCKETS,validGrantPage,validServiceContext,validWorkbench,createServiceWorkspaceApi,initialWorkspaceState,createWorkspaceFlow} from '../src/services/service-workspace.js'
const at=Date.parse('2026-10-15T08:00:00Z'),hash='a'.repeat(64),key='00000000-0000-4000-8000-000000000001'
const record=()=>({archive_id:901,archive_type:1,recorded_date:'2026-10-15',title:'保养',notes:'记录',mileage:1000,fields:{service_items:['换油']},files:[{file_id:601,available:true}]})
const page=items=>({items,total:items.length,page:1,page_size:20})
const grants=()=>({order_id:1,service_active:true,records:page([{record:record(),source_version:hash,grant_revision:0,grant_state:'NONE'}])})
const context=()=>({order_id:1,order_status:'IN_SERVICE',vehicle:{brand:null,series:null,year:null,config_name:null},project:null,arrival_mileage:1200,archives:page([{archive_id:901,state:'ACTIVE',record:record()}])})
const bench=()=>({counts:Object.fromEntries(TASK_BUCKETS.map(k=>[k,k==='SERVICE'?1:0])),tasks:page([{order_id:1,order_status:'IN_SERVICE',bucket:'SERVICE',project_name:null,starts_at:null}])})
const signed=()=>({url:'https://private.invalid/object?signature=synthetic',expires_at:'2026-10-15T08:02:00.123456789Z'})
const apiOf=n=>createServiceWorkspaceApi({now:()=>at,runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:n}})})})
const failure=kind=>Object.assign(new Error(kind),{kind})
function setup(extra={}){const state=initialWorkspaceState(),api={grants:async()=>grants(),write:async()=>{},preview:async()=>signed(),...extra.api};const flow=createWorkspaceFlow({state,kind:'grants',token:()=> 'owner',order:()=>1,now:()=>at,newKey:()=>key,confirm:async()=>true,schedule:()=>1,cancel:()=>{},...extra,api});return {state,flow}}

test('workspace contracts reject identities, broad vehicle fields and non-service records',()=>{
 assert.ok(validGrantPage(grants()));assert.ok(validServiceContext(context()));assert.ok(validWorkbench(bench()))
 for(const change of [n=>n.records.items[0].record.owner_id=99,n=>n.records.items[0].record.archive_type=3,n=>n.records.items[0].record.fields.provider_name='private',n=>n.records.items[0].source_version='invalid',n=>n.records.items.push(n.records.items[0])]){const n=grants();change(n);assert.equal(validGrantPage(n),false)}
 const c=context();c.vehicle.current_mileage=9000;assert.equal(validServiceContext(c),false)
 const b=bench();b.counts.SERVICE=2;assert.equal(validWorkbench(b),false)
})
test('API binds role, order and CAS receipt; rejects client recipients and mismatched revisions',async()=>{
 let call;const api=createServiceWorkspaceApi({runtime:()=>({request:r=>{call=r;r.success({statusCode:200,data:{code:0,data:grants()}})}})})
 await api.grants('owner',1);assert.equal(call.method,'GET');assert.equal(call.header.Authorization,'Bearer owner');await assert.rejects(api.grants('owner',2),e=>e.kind==='protocol')
 assert.throws(()=>api.context('a','owner',1));assert.throws(()=>api.write('a',1,901,{status:'ACTIVE',expected_revision:0,source_version:hash,merchant_id:1},key))
 const receipt={order_id:1,archive_id:901,grant_revision:1,grant_state:'ACTIVE'};await apiOf(receipt).write('a',1,901,{status:'ACTIVE',expected_revision:0,source_version:hash},key)
 await assert.rejects(apiOf({...receipt,grant_revision:2}).write('a',1,901,{status:'ACTIVE',expected_revision:0,source_version:hash},key),e=>e.kind==='protocol')
})
test('signatures accept fractional timestamps and reject unsafe or extended links',async()=>{
 assert.deepEqual(await apiOf(signed()).preview('owner',1,901,601),signed())
 for(const change of [{url:'http://private.invalid/x'},{url:'https://user@private.invalid/x'},{expires_at:'2026-10-15T08:10:00Z'},{expires_at:'2026-10-15T07:59:00Z'},{owner_id:99}])await assert.rejects(apiOf({...signed(),...change}).access('a','tech',1,901,601),e=>e.kind==='protocol')
})
test('late reads cannot restore data after hiding, changing order or switching role/account',async()=>{
 for(const change of ['hide','order','role','account']){let resolve,t='a',id=1,r='merchant';const {state,flow}=setup({kind:'context',token:()=>t,order:()=>id,role:()=>r,api:{context:()=>new Promise(done=>resolve=done)}});const work=flow.load();if(change==='order')id=2;if(change==='role')r='tech';if(change==='account')t='b';flow.reset();resolve(context());await work;assert.equal(state.data,null);assert.equal(state.busy,false)}
})
test('preview before consent never writes, and each reopen checks permission',async()=>{
 let writes=0,reads=0;const {state,flow}=setup({api:{write:async()=>writes++,preview:async()=>{reads++;return signed()}}});await flow.load();await flow.preview(901,601);assert.ok(state.preview);flow.closePreview();await flow.preview(901,601);assert.equal(reads,2);assert.equal(writes,0)
})
test('canceled confirmation and inactive service never grant',async()=>{
 let writes=0;const {state,flow}=setup({confirm:async()=>false,api:{write:async()=>writes++}});await flow.load();await flow.set(state.data.records.items[0],'ACTIVE');assert.equal(writes,0);state.data.service_active=false;await flow.set(state.data.records.items[0],'ACTIVE');assert.equal(writes,0)
})
test('account change while confirming prevents write',async()=>{
 let resolve,t='a',writes=0;const {state,flow}=setup({token:()=>t,confirm:()=>new Promise(done=>resolve=done),api:{write:async()=>writes++}});await flow.load();const work=flow.set(state.data.records.items[0],'ACTIVE');t='b';flow.reset();resolve(true);await work;assert.equal(writes,0);assert.equal(state.data,null)
})
test('uncertain write retries immutable source/revision/key after hide',async()=>{
 const calls=[];const {state,flow}=setup({api:{write:async(...args)=>{calls.push(structuredClone(args));if(calls.length===1)throw failure('network')}}});await flow.load();const item=state.data.records.items[0];await flow.set(item,'ACTIVE');assert.equal(state.retry,true);item.source_version='b'.repeat(64);flow.reset();await flow.load();await flow.retry();assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);assert.equal(state.retry,false)
})
test('definitive revoked/conflicting writes clear retry and private data',async()=>{
 for(const kind of ['conflict','unauthorized','forbidden','missing']){const {state,flow}=setup({api:{write:async()=>{throw failure(kind)}}});await flow.load();await flow.set(state.data.records.items[0],'ACTIVE');assert.equal(state.data,null);assert.equal(state.retry,false)}
})
test('stale expiry and late signatures cannot replace or clear current preview',async()=>{
 const timers=[];const {state,flow}=setup({schedule:fn=>{timers.push(fn);return timers.length}});await flow.load();await flow.preview(901,601);await flow.preview(901,601);timers[0]();assert.ok(state.preview);timers[1]();assert.equal(state.preview,null)
 let resolve;const other=setup({api:{preview:()=>new Promise(done=>resolve=done)}});await other.flow.load();const work=other.flow.preview(901,601);other.flow.reset();resolve(signed());await work;assert.equal(other.state.preview,null)
})
test('revoked photo or failed refresh clears displayed private context',async()=>{
 const {state,flow}=setup({api:{preview:async()=>{throw failure('missing')}}});await flow.load();await flow.preview(901,601);assert.equal(state.data,null);assert.equal(state.preview,null)
 let fail=false;const other=setup({api:{grants:async()=>{if(fail)throw failure('network');return grants()}}});await other.flow.load();await other.flow.preview(901,601);fail=true;await other.flow.load();assert.equal(other.state.data,null);assert.equal(other.state.preview,null)
})
