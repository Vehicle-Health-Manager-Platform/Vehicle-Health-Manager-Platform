import test from 'node:test'
import assert from 'node:assert/strict'
import {validServicePolicy,validAdmission,validCaseDetail,validDiagnosisText,createAsyncDiagnosisApi,initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {createPublicationApi} from '../src/services/experience-publication.js'
const key='00000000-0000-4000-8000-000000000001',at='2026-10-15T08:00:00Z'
const policy=()=>({id:1,revision:2,status:'PUBLISHED',mode:'AVAILABLE',source_note:'合成受理来源',valid_from:'2026-10-01T00:00:00Z',valid_to:'2026-12-01T00:00:00Z',created_at:at,published_at:at})
const proof=()=>({month:'2026-10',timezone:'Asia/Shanghai',qualified_count:2,state:'ELIGIBLE',policy:{id:3,revision:2,status:'PUBLISHED',required_count:2,source_note:'解锁来源',valid_from:'2026-10-01T00:00:00Z',valid_to:'2026-12-01T00:00:00Z',created_at:at,published_at:at,rule_version:'R6B_DIAGNOSIS_V1'},usable_until:'2026-10-31T16:00:00Z',service_available:true,rule_version:'R6B_DIAGNOSIS_V1'})
const context=()=>({service:{state:'AVAILABLE',accepting:true,policy:policy()},eligibility:proof()})
const detail=()=>({case:{case_id:1,revision:2,status:'PROCESSING',created_at:at,updated_at:at},vehicle:{brand:null,series:null,year:null,config_name:null},messages:{items:[{message_id:1,sender:'OWNER',body:'主动提交的文字',created_at:at}],total:1,page:1,page_size:20}})
const body=()=>({vehicle_id:901,policy_id:1,policy_revision:2,message:'本次问题',consent:true})
const failure=kind=>Object.assign(new Error(kind),{kind})
const apiOf=n=>createAsyncDiagnosisApi({runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:n}})})})
function setup(extra={}){const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 'owner',scope:()=> 'case:1',load:async()=>detail(),write:async()=>({case_id:1,revision:3,status:'REPLIED'}),newKey:()=>key,confirm:async()=>true,...extra});return {state,flow}}
test('admission binds explicit service source to independent month eligibility',()=>{
 assert.ok(validServicePolicy(policy()));assert.ok(validAdmission(context()));const offline=context();offline.service.state='LEAVE_MESSAGE';offline.service.policy.mode='LEAVE_MESSAGE';assert.ok(validAdmission(offline))
 for(const edit of [n=>n.service.policy=null,n=>n.service.state='LEAVE_MESSAGE',n=>n.service.state='PAUSED',n=>n.service.accepting=false,n=>n.eligibility.service_available=false,n=>n.service.owner_id=1]){const n=context();edit(n);assert.equal(validAdmission(n),false)}
})
test('case projection rejects identities, archives and raw sender account fields',()=>{
 assert.ok(validCaseDetail(detail()));for(const edit of [n=>n.vehicle.vin='private',n=>n.case.owner_id=1,n=>n.messages.items[0].sender_id=1001,n=>n.messages.items[0].sender='ADMIN',n=>n.messages.items.push(n.messages.items[0])]){const n=detail();edit(n);assert.equal(validCaseDetail(n),false)}
})
test('text supports newline and Unicode character limits but rejects controls',()=>{
 assert.ok(validDiagnosisText('第一行\n第二行'));assert.ok(validDiagnosisText('🚗'.repeat(2000)));assert.equal(validDiagnosisText('🚗'.repeat(2001)),false);for(const n of ['',' leading','x\u0000','x\u200b'])assert.equal(validDiagnosisText(n),false)
})
test('owner creation sends exact consent/body/key and validates bound receipt',async()=>{
 let call;const api=createAsyncDiagnosisApi({runtime:()=>({request:r=>{call=r;r.success({statusCode:200,data:{code:0,data:{case_id:1,revision:1,status:'WAITING'}}})}})});await api.write('t','owner','create',0,body(),key);assert.ok(call.url.endsWith('/api/owner/diagnosis/cases'));assert.equal(call.method,'POST');assert.deepEqual(call.data,body());assert.equal(call.header['Idempotency-Key'],key)
 assert.throws(()=>api.write('t','owner','create',0,{...body(),operator_id:1001},key));assert.throws(()=>api.write('t','owner','reply',1,{expected_revision:1,message:'不允许'},key));await assert.rejects(apiOf({case_id:2,revision:2,status:'PROCESSING'}).write('t','admin','assign',1,{expected_revision:1,operator_id:1001},key),e=>e.kind==='protocol')
})
test('policy and case reads are role-bound and response cannot cross requested case',async()=>{
 await apiOf(detail()).detail('t','owner',1);await assert.rejects(apiOf(detail()).detail('t','owner',2),e=>e.kind==='protocol');assert.throws(()=>apiOf(detail()).list('t','owner',1,true));assert.throws(()=>apiOf(detail()).detail('t','merchant',1));assert.throws(()=>apiOf(detail()).detail('', 'owner',1))
})
test('new independent operator capabilities are accepted without accepting arbitrary grants',async()=>{
 const n={access_token:'synthetic',token_type:'Bearer',expires_in:900,user:{id:1,role:'operator',can_review:false,can_onboard:false,can_catalog:false,can_coupon:false,can_invite:false,can_diagnose:true,can_diagnose_manage:false}}
 const api=value=>createPublicationApi({baseUrl:'https://invalid',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:value}})})});await api(n).login('operator','synthetic-password','123456');await assert.rejects(api({...n,user:{...n.user,can_read_all:true}}).login('operator','synthetic-password','123456'))
})
test('late private reads after account/scope changes or hide never refill data',async()=>{
 for(const change of ['account','scope','hide']){let t='a',s='case:1',resolve;const {state,flow}=setup({token:()=>t,scope:()=>s,load:()=>new Promise(done=>resolve=done)});const work=flow.load();if(change==='account')t='b';if(change==='scope')s='case:2';flow.reset();resolve(detail());await work;assert.equal(state.data,null);assert.equal(state.busy,false)}
})
test('switch or revoked capability during confirmation prevents the write',async()=>{
 for(const mode of ['account','permission']){let resolve,t='a',allowed=true,calls=0;const {flow}=setup({token:()=>t,allowed:()=>allowed,confirm:()=>new Promise(done=>resolve=done),write:async()=>calls++});const work=flow.save('reply',1,{expected_revision:2,message:'本次答复'});if(mode==='account')t='b';else allowed=false;flow.reset();resolve(true);await work;assert.equal(calls,0)}
})
test('unresolved writes retry immutable revision/body/key after page hide',async()=>{
 const calls=[];const {state,flow}=setup({write:async(...args)=>{calls.push(structuredClone(args));if(calls.length===1)throw failure('network');return {case_id:1}}});const b={expected_revision:2,message:'原答复'};await flow.save('reply',1,b);b.message='编辑后的正文';assert.equal(state.retry,true);assert.equal(state.data,null);flow.reset();await flow.load();await flow.retry();assert.deepEqual(calls[0],calls[1]);assert.equal(state.retry,false)
})
test('definitive source or scope rejection clears body and pending action',async()=>{
 for(const kind of ['conflict','forbidden','unauthorized','missing']){const {state,flow}=setup({write:async()=>{throw failure(kind)}});await flow.load();await flow.save('reply',1,{expected_revision:2,message:'原答复'});assert.equal(state.data,null);assert.equal(state.retry,false)}
})
test('duplicate tap and rejected confirmation do not produce extra messages',async()=>{
 let resolve,calls=0;const {flow}=setup({write:()=>{calls++;return new Promise(done=>resolve=done)}});const work=flow.save('reply',1,{expected_revision:2,message:'答复'});await Promise.resolve();await Promise.resolve();await flow.save('reply',1,{expected_revision:2,message:'重复'});assert.equal(calls,1);resolve({case_id:1});await work;const other=setup({confirm:async()=>false,write:async()=>calls++});await other.flow.save('close',1,{expected_revision:2});assert.equal(calls,1)
})
test('failed refresh clears previously displayed question and reply',async()=>{
 let fail=false;const {state,flow}=setup({load:async()=>{if(fail)throw failure('network');return detail()}});await flow.load();assert.ok(state.data);fail=true;await flow.load();assert.equal(state.data,null)
})
